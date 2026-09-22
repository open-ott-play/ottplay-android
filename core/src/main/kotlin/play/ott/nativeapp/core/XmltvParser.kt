package play.ott.nativeapp.core

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import play.ott.core.GuideProgrammeRules
import play.ott.core.GuideTime
import play.ott.core.GuideTimeFormat

/** Parses XMLTV off the UI thread. All times are absolute UTC epoch milliseconds. */
object XmltvParser {
    private const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024

    fun parse(bytes: ByteArray): List<Programme> {
        if (bytes.size > MAX_EPG_BYTES) throw ProviderException("EPG download exceeds the supported size limit")
        val handler = EpgHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            // Android and desktop XML providers expose different feature sets. Entity resolution is
            // also denied by the handler, and the mandatory lexical handler rejects every DOCTYPE.
            listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://javax.xml.XMLConstants/feature/secure-processing" to true,
            ).forEach { (feature, value) -> try { factory.setFeature(feature, value) } catch (_: Exception) { /* Provider-specific. */ } }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            // Fail closed when a provider cannot install this DTD barrier.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            val compressed = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            val source = ByteArrayInputStream(bytes)
            val stream = if (compressed) GZIPInputStream(source) else source
            // SAX consumes the bounded decompression stream; never allocate the expanded XML document.
            BoundedInputStream(stream, MAX_EXPANDED_BYTES).use { reader.parse(InputSource(it)) }
        } catch (error: Exception) {
            if (error is ProviderException) throw error
            throw ProviderException("EPG is malformed XML or uses unsupported document declarations")
        }
        val rows = handler.programmes
        return GuideProgrammeRules.androidOrder(rows.size, { rows[it].channelId }, { rows[it].startMillis }, { rows[it].endMillis }).map(rows::get)
    }

    fun parseTimestamp(value: String): Long? = GuideTime.parse(value, GuideTimeFormat.ANDROID)

    private class BoundedInputStream(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var count = 0
        private fun counted(read: Int) {
            if (read > 0) {
                if (count > limit - read) throw ProviderException("Expanded EPG exceeds the supported size limit")
                count += read
            }
        }
        override fun read(): Int = `in`.read().also { if (it != -1) counted(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also(::counted)
        override fun skip(length: Long): Long {
            // XML parsers normally read; still count skipped bytes against the same bound.
            val skipped = `in`.skip(minOf(length, Int.MAX_VALUE.toLong()))
            counted(skipped.toInt())
            return skipped
        }
    }

    private class EpgHandler : DefaultHandler2() {
        val programmes = mutableListOf<Programme>()
        private var channelId = ""
        private var start: Long? = null
        private var end: Long? = null
        private var title = ""
        private var description = ""
        private var inProgramme = false
        private var field: String? = null
        private val text = StringBuilder()
        private var depth = 0

        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD is disabled") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are disabled") }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            if (++depth > 64) throw SAXException("XML nesting limit exceeded")
            val element = localName?.takeIf(String::isNotBlank) ?: qName.orEmpty()
            when (element) {
                "programme" -> {
                    if (inProgramme) throw SAXException("Nested programme")
                    inProgramme = true
                    channelId = attributes.getValue("channel").orEmpty().trim()
                    start = parseTimestamp(attributes.getValue("start").orEmpty())
                    end = parseTimestamp(attributes.getValue("stop").orEmpty())
                    title = ""; description = ""; field = null
                }
                "title", "desc" -> if (inProgramme) { field = element; text.setLength(0) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (field != null) {
                if (text.length + length > 65_536) throw SAXException("XML text limit exceeded")
                text.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val element = localName?.takeIf(String::isNotBlank) ?: qName.orEmpty()
            if (element == field) {
                if (element == "title" && title.isBlank()) title = text.toString().trim()
                if (element == "desc" && description.isBlank()) description = text.toString().trim()
                field = null
            }
            if (element == "programme") {
                val from = start; val to = end
                if (GuideProgrammeRules.validAndroid(channelId, from, to)) {
                    if (programmes.size >= 500_000) throw SAXException("Programme count limit exceeded")
                    programmes += Programme(channelId, title.ifBlank { "Untitled programme" }, from!!, to!!, description)
                }
                inProgramme = false
            }
            depth--
        }
    }
}
