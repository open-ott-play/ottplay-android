package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import play.ott.nativeapp.BuildConfig

/** Offline copy: reading the policy never opens a provider or requires an installed browser. */
@Composable
internal fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 180.dp.toPx() }
    val readerFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var readerFocused by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth(.94f).fillMaxHeight(.92f).testTag("privacy-policy"),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Политика конфиденциальности", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("OTT-play Native for Android · 13 сентября 2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isTv) Text("↑ ↓ — читать · → — закрыть · Назад — вернуться", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("privacy-policy-text")
                        .focusRequester(readerFocus)
                        .onPreviewKeyEvent { event ->
                            if (!isTv || event.type != KeyEventType.KeyDown) false
                            else when (event.key) {
                                Key.DirectionDown -> {
                                    if (scroll.value < scroll.maxValue) scope.launch { scroll.scrollTo((scroll.value + step.toInt()).coerceAtMost(scroll.maxValue)) }
                                    else closeFocus.requestFocus()
                                    true
                                }
                                Key.DirectionUp -> {
                                    scope.launch { scroll.scrollTo((scroll.value - step.toInt()).coerceAtLeast(0)) }
                                    true
                                }
                                Key.DirectionRight -> { closeFocus.requestFocus(); true }
                                else -> false
                            }
                        }
                        .onFocusChanged { readerFocused = it.isFocused }
                        .border(BorderStroke(2.dp, if (readerFocused) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(8.dp))
                        .focusable(isTv).verticalScroll(scroll).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    privacyPolicySections().forEach { section ->
                        Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(section.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ActionButton("Закрыть", onDismiss, Modifier.fillMaxWidth().testTag("privacy-policy-close")
                    .focusRequester(closeFocus).focusProperties { up = readerFocus; left = readerFocus }, selected = true)
            }
        }
        LaunchedEffect(isTv) {
            if (isTv) { withFrameNanos { }; readerFocus.requestFocus() }
        }
    }
}

internal data class PrivacyPolicySection(val title: String, val body: String)

/** Publisher details match the existing public policy of the OTT-play project. */
internal data class PrivacyPublisher(
    val name: String = "alvit",
    val email: String = "alvit.work@gmail.com",
)

internal fun privacyPolicySections(
    allowInsecureHttp: Boolean = BuildConfig.ALLOW_INSECURE_HTTP,
    publisher: PrivacyPublisher = PrivacyPublisher(),
): List<PrivacyPolicySection> = listOf(
    PrivacyPolicySection("Разработчик и обратная связь",
        "Издатель OTT-play Native for Android — ${publisher.name}, проект open-ott-play. По вопросам конфиденциальности, поддержки и удаления данных обращайтесь: ${publisher.email}. Перед отправкой материалов в поддержку удалите пароли, токены, файлы настроек и другую конфиденциальную информацию."),
    PrivacyPolicySection("Для чего нужно приложение",
        "OTT-play Native for Android воспроизводит ваши плейлисты и источники IPTV. Приложение не продаёт подписки, не создаёт собственные пользовательские аккаунты и не предоставляет коммерческий каталог. Встроенное демо — синтетический ролик, который работает без сети. Используйте только контент и учётные данные, к которым у вас есть законный доступ."),
    PrivacyPolicySection("Какие данные вы добавляете",
        "Вы можете указать название и адрес источника, логин и пароль провайдера, зарегистрированный у него MAC-адрес, адрес телепрограммы и HTTP-заголовки. MAC-адрес вводится вами; приложение не считывает аппаратный MAC-адрес устройства. При выборе файла системный диалог предоставляет доступ к выбранному плейлисту или файлу настроек."),
    PrivacyPolicySection("Кому отправляются запросы",
        "Для загрузки каталога и воспроизведения приложение соединяется с указанным провайдером и адресами из его каталога или вашего плейлиста. Серверы могут получать необходимые логины, пароли, токены, заголовки, введённый MAC-адрес и сведения о запрошенном контенте. Как при любом сетевом соединении, получатель видит IP-адрес соединения. Логотипы и обложки загружаются по адресам в каталоге. У этих серверов могут быть собственные правила хранения и использования данных."),
    PrivacyPolicySection("Защита сетевых соединений",
        if (allowInsecureHttp) "Эта версия поддерживает как HTTPS, так и незашифрованные HTTP-источники. HTTP не защищает передаваемые пароли, токены, адреса и данные просмотра от участников сети. Используйте HTTPS, особенно для источников с учётными данными. Шифрование данных на устройстве не защищает HTTP-трафик."
        else "Для сетевых источников, видеопотоков, телепрограммы, изображений и серверов лицензий эта версия допускает только HTTPS. Незашифрованные HTTP-адреса и переходы с HTTPS на HTTP блокируются. Шифрование соединения защищает данные в пути, но сервер, которому они адресованы, получает необходимые данные в открытом виде."),
    PrivacyPolicySection("Телепрограмма и фоновые запросы",
        "Приложение загружает XMLTV-телепрограмму с адресов источника или плейлиста. После добавления источников Android также планирует обновление телепрограммы примерно каждые шесть часов в сети без тарификации; обновления возможны, когда экран приложения закрыт. Android может откладывать запуск. Удаление источника прекращает его последующие обновления. На телефоне включённое фоновое воспроизведение продолжает обращаться к серверу текущего потока, пока вы не остановите воспроизведение."),
    PrivacyPolicySection("Защищённое видео и DRM",
        "Если выбранный контент требует DRM, Android и проигрыватель могут обращаться к серверу лицензий, заданному источником, и к серверу подготовки DRM устройства. Эти серверы получают запросы, необходимые для проверки права воспроизведения и работы DRM. Заголовки сервера лицензий отделены от заголовков видеопотока; они не передаются серверу подготовки устройства. Приложение не выдаёт права на просмотр и не извлекает ключи DRM."),
    PrivacyPolicySection("Хранение на устройстве",
        "Настройки источников и сохранённые записи каталога, включая адреса потоков и телепрограммы, шифруются с помощью AES-GCM и ключа Android Keystore. Телепрограмма, избранное и позиции продолжения просмотра хранятся локально в закрытом хранилище приложения. Приложение исключает свои данные из автоматического облачного резервного копирования Android и переноса на другое устройство."),
    PrivacyPolicySection("Экспорт и импорт настроек",
        "Экспорт создаётся только по вашему действию в месте, выбранном через системный диалог. Это незашифрованный JSON-файл: он содержит адреса, пароли и другие учётные данные источников, избранное и позиции просмотра. Не передавайте его посторонним. Если вы выбираете облачное хранилище, сохранённый файл обрабатывает выбранный вами сервис по своим правилам. Шифрование локального хранилища приложения не защищает экспортированный файл."),
    PrivacyPolicySection("Удаление данных",
        "В разделе «Источники» можно удалить источник вместе с его сохранёнными учётными данными, каталогом и телепрограммой. Локальные идентификаторы избранного и позиции просмотра могут сохраняться до очистки данных приложения. Чтобы удалить все локальные данные, очистите хранилище приложения в настройках Android или удалите приложение. Экспортированные файлы нужно удалять отдельно. Эти действия не удаляют аккаунт или данные у провайдера: для этого обратитесь непосредственно к нему."),
    PrivacyPolicySection("Реклама и аналитика",
        "В приложении нет рекламы и рекламных или аналитических SDK. Приложение не отправляет разработчику отдельную телеметрию, историю просмотра или отчёты о сбоях. Системная диагностика Android и Google Play зависит от настроек устройства и правил этих сервисов. Сообщения об ошибках приложения не предназначены для раскрытия паролей и токенов."),
    PrivacyPolicySection("Android и ваши обращения",
        "Для воспроизведения используются сетевой доступ, медиаслужба и блокировка сна. Название и обложка текущего контента могут отображаться в системных медиакнопках и на экране блокировки согласно настройкам Android. Для обычного просмотра не нужны разрешения на геолокацию, камеру, микрофон, контакты или всю медиатеку. Письма, которые вы сами отправляете в поддержку, не удаляются при очистке приложения: по их удалению обращайтесь к издателю. Для данных стороннего сервиса действуют его правила и возможности удаления."),
)
