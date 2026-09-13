package play.ott.nativeapp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class ItemMediaSourceFactoryTest {
    @Test fun `preparing another item cannot replace headers used by an older source`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val received = executor.submit<List<String>> {
                List(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        var authorization = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", true)) authorization = line.substringAfter(':').trim()
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray())
                            flush()
                        }
                        authorization
                    }
                }
            }
            try {
                val url = "http://127.0.0.1:${server.localPort}/stream"
                val factory = ItemMediaSourceFactory(context, client)
                val first = factory.httpFactoryFor(PlaybackItems.build("a", "A", url, mapOf("Authorization" to "Bearer A")))
                val second = factory.httpFactoryFor(PlaybackItems.build("b", "B", url, mapOf("Authorization" to "Bearer B")))
                // Open A after constructing B; a global mutable HTTP factory sends B twice.
                listOf(first, second).forEach { sourceFactory ->
                    val source = sourceFactory.createDataSource()
                    try {
                        assertEquals(1L, source.open(DataSpec(Uri.parse(url))))
                        assertEquals(1, source.read(ByteArray(1), 0, 1))
                    } finally {
                        source.close()
                    }
                }
                assertEquals(listOf("Bearer A", "Bearer B"), received.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
