package play.ott.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import java.net.URI
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceEditor(
    original: SourceConfig?,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
) {
    val id = rememberSaveable(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    val localSource = original != null && !isHttpUrl(original.url)
    var name by rememberSaveable(id) { mutableStateOf(original?.name.orEmpty()) }
    var kind by rememberSaveable(id) { mutableStateOf(original?.kind ?: SourceKind.M3U) }
    var url by rememberSaveable(id) { mutableStateOf(original?.url.orEmpty()) }
    var username by rememberSaveable(id) { mutableStateOf(original?.username.orEmpty()) }
    var password by rememberSaveable(id) { mutableStateOf(original?.password.orEmpty()) }
    var mac by rememberSaveable(id) { mutableStateOf(original?.mac.orEmpty()) }
    var epgUrl by rememberSaveable(id) { mutableStateOf(original?.epgUrl.orEmpty()) }
    var headers by rememberSaveable(id) { mutableStateOf(original?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()) }
    var showPassword by rememberSaveable(id) { mutableStateOf(false) }
    var validation by rememberSaveable(id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Добавить источник" else "Изменить источник") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Плейлисты и аккаунты провайдеров в одном приложении.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, enabled = !localSource, label = { Text(option.label()) })
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название источника") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = if (localSource) (if (original?.url?.startsWith("content://") == true) "Выбранный файл M3U" else "Встроенный тест") else url,
                    onValueChange = { url = it }, readOnly = localSource,
                    label = { Text(if (kind == SourceKind.M3U) "Адрес плейлиста" else "Адрес сервера / портала") },
                    placeholder = { Text("https://…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (kind == SourceKind.XTREAM) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Скрыть" else "Показать") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == SourceKind.STALKER) {
                    OutlinedTextField(
                        value = mac, onValueChange = { mac = it }, label = { Text("MAC-адрес устройства") },
                        placeholder = { Text("00:1A:79:00:00:00") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Укажите MAC-адрес, зарегистрированный у провайдера.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = epgUrl, onValueChange = { epgUrl = it }, label = { Text("Адрес телепрограммы XMLTV (необязательно)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it }, label = { Text("HTTP-заголовки (необязательно)") },
                    placeholder = { Text("User-Agent: My player\nReferer: https://example.com/") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                Text("Один заголовок на строку: имя: значение", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        confirmButton = {
            ActionButton(text = if (original == null) "Добавить" else "Сохранить", selected = true, onClick = {
                val cleanUrl = url.trim()
                val parsedHeaders = parseHeaders(headers)
                validation = when {
                    name.isBlank() -> "Введите название источника."
                    !isHttpUrl(cleanUrl) && !(localSource && cleanUrl == original?.url) -> "Введите корректный адрес источника: http:// или https://."
                    kind == SourceKind.XTREAM && (username.isBlank() || password.isBlank()) -> "Введите логин и пароль провайдера."
                    kind == SourceKind.STALKER && !Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac.trim()) -> "Введите MAC-адрес, например 00:1A:79:00:00:00."
                    epgUrl.isNotBlank() && !isHttpUrl(epgUrl.trim()) -> "Введите корректный адрес телепрограммы или оставьте поле пустым."
                    parsedHeaders == null -> "Проверьте заголовки: по одному имя: значение на строку."
                    else -> null
                }
                if (validation == null) {
                    onSave(SourceConfig(id, name.trim(), kind, cleanUrl, username.trim(), password, mac.trim(), epgUrl.trim(), parsedHeaders.orEmpty()))
                }
            })
        },
    )
}

internal fun SourceKind.label(): String = when (this) {
    SourceKind.M3U -> "M3U"
    SourceKind.XTREAM -> "Xtream"
    SourceKind.STALKER -> "Stalker"
}

private fun isHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun parseHeaders(text: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    for (line in text.lines().filter { it.isNotBlank() }) {
        val delimiter = line.indexOf(':')
        if (delimiter <= 0) return null
        val key = line.substring(0, delimiter).trim()
        val value = line.substring(delimiter + 1).trim()
        if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(key) || value.contains('\r') || value.contains('\n')) return null
        result[key] = value
    }
    return result
}
