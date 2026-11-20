package com.klischa.llmnotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Загрузка GGUF-моделей напрямую с huggingface.co.
 * Поддерживает докачку (.part + HTTP Range + автосброс при HTTP 416), отмену и проверку свободного места.
 */
class ModelDownloader(private val context: Context) {

    data class Progress(
        val modelId: String,
        val bytes: Long = 0L,
        val total: Long = 0L,
        val done: Boolean = false,
        val error: String? = null
    ) {
        val percent: Float
            get() = if (total <= 0L) 0f else (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun targetFile(model: ModelInfo): File = File(modelsDir(), model.fileName)

    fun partFile(model: ModelInfo): File = File(modelsDir(), model.fileName + ".part")

    fun isInstalled(model: ModelInfo): Boolean {
        val f = targetFile(model)
        return f.exists() && f.length() > 0L
    }

    fun installedSize(model: ModelInfo): Long = targetFile(model).length()

    fun delete(model: ModelInfo): Boolean {
        partFile(model).delete()
        return targetFile(model).delete()
    }

    fun availableBytes(): Long = context.filesDir.usableSpace

    fun canAfford(model: ModelInfo): Boolean {
        val need = (model.sizeBytes - partFile(model).length()).coerceAtLeast(0L)
        return availableBytes() > need + 150L * 1024L * 1024L
    }

    private fun openConnection(urlStr: String, rangeStart: Long): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LLMNotes-Android/1.4.4")
            if (rangeStart > 0L) {
                setRequestProperty("Range", "bytes=$rangeStart-")
            }
        }
    }

    /**
     * Качает модель. Бросает CancellationException при отмене корутины,
     * .part сохраняется — следующий запуск продолжит с места обрыва.
     * Все вызовы `onProgress` выполняются безопасно на Dispatchers.Main.
     */
    suspend fun download(
        model: ModelInfo,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        suspend fun notifyProgress(p: Progress) {
            withContext(Dispatchers.Main) {
                onProgress(p)
            }
        }

        val target = targetFile(model)
        val part = partFile(model)

        if (target.exists() && target.length() > 0L) {
            notifyProgress(Progress(model.id, target.length(), target.length(), done = true))
            return@withContext target
        }

        if (!canAfford(model)) {
            val msg = "Недостаточно места: нужно ~${ModelCatalog.formatSize(model.sizeBytes)}, " +
                "свободно ${ModelCatalog.formatSize(availableBytes())}"
            notifyProgress(Progress(model.id, error = msg))
            throw IllegalStateException(msg)
        }

        var downloaded = if (part.exists()) part.length() else 0L
        var total = model.sizeBytes

        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(model.url, downloaded)
            var code = conn.responseCode

            // Если .part устарел или больше/равен файлу на сервере (HTTP 416 Range Not Satisfiable) — сбрасываем и качаем заново
            if (code == 416 && downloaded > 0L) {
                conn.disconnect()
                part.delete()
                downloaded = 0L
                conn = openConnection(model.url, 0L)
                code = conn.responseCode
            }

            if (code !in 200..299) {
                val msg = "HTTP $code — не удалось начать загрузку"
                notifyProgress(Progress(model.id, downloaded, total, error = msg))
                throw IllegalStateException(msg)
            }

            if (code == 206) {
                val contentLength = conn.contentLengthLong
                if (contentLength > 0L) total = downloaded + contentLength
            } else {
                // Сервер не поддержал Range — качаем заново
                downloaded = 0L
                conn.contentLengthLong.takeIf { it > 0L }?.let { total = it }
                part.delete()
            }

            notifyProgress(Progress(model.id, downloaded, total))

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(downloaded)
                    val buf = ByteArray(64 * 1024)
                    var lastNotify = System.currentTimeMillis()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        raf.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 200L) {
                            lastNotify = now
                            notifyProgress(Progress(model.id, downloaded, total))
                        }
                    }
                }
            }

            if (total > 0L && downloaded < total) {
                val msg = "Загрузка прервана: ${ModelCatalog.formatSize(downloaded)} из ${ModelCatalog.formatSize(total)}"
                notifyProgress(Progress(model.id, downloaded, total, error = msg))
                throw IllegalStateException(msg)
            }

            if (part.length() <= 0L) {
                notifyProgress(Progress(model.id, 0, total, error = "Файл пустой"))
                throw IllegalStateException("Файл пустой")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }

            notifyProgress(Progress(model.id, target.length(), target.length(), done = true))
            target
        } finally {
            conn?.disconnect()
        }
    }
}
