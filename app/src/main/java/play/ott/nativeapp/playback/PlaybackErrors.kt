package play.ott.nativeapp.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** User-facing error categories. Never render exception messages containing provider URLs. */
@UnstableApi
object PlaybackErrors {
    fun message(error: PlaybackException): String {
        val response = generateSequence(error as Throwable) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (response != null) return when (response) {
            401, 403 -> "Провайдер отклонил доступ. Проверьте учётные данные и подписку (HTTP $response)."
            404, 410 -> "Поток или архивная запись недоступны (HTTP $response)."
            else -> "Сервер потока вернул ошибку HTTP $response."
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения с потоком. Проверьте сеть и повторите попытку."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Файл недоступен. Откройте его заново и предоставьте доступ."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Устройство не смогло декодировать поток. Попробуйте другой вариант качества или аудиодорожку."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Сервер вернул неподдерживаемый или повреждённый медиапоток."
            else -> "Не удалось воспроизвести поток (код ${error.errorCode})."
        }
    }
}
