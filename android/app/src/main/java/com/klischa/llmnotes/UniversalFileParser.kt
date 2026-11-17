package com.klischa.llmnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

enum class AttachmentCategory(val emoji: String, val label: String) {
    IMAGE("🖼️", "Изображение"),
    PDF("📄", "PDF"),
    DOCUMENT("📝", "Документ"),
    SPREADSHEET("📊", "Таблица"),
    PRESENTATION("📽️", "Презентация"),
    ARCHIVE("📦", "Архив"),
    AUDIO("🎵", "Аудио"),
    VIDEO("🎬", "Видео"),
    TEXT("📄", "Текст/Код"),
    BINARY("📎", "Файл")
}

/**
 * Универсальный парсер файлов любых форматов без внешних зависимостей:
 * - Изображения и скриншоты (PNG, JPG, WEBP, GIF, BMP, HEIC): безопасное сжатие (≤1280px) + Base64 JPEG для Vision API + метаданные.
 * - PDF: встроенное извлечение текста (FlateDecode + BT/ET операторы) + рендер 1-й страницы через PdfRenderer для сканов/Vision API.
 * - Microsoft Office / OpenDocument / книги: .docx, .xlsx, .pptx, .odt, .ods, .epub, .fb2, .rtf.
 * - Архивы: .zip, .jar, .apk (список содержимого + превью текстовых файлов), .gz.
 * - Текст и код: автоопределение UTF-8, UTF-16LE/BE (BOM) и Windows-1251.
 * - Аудио / Видео: метаданные через MediaMetadataRetriever (длительность, разрешение, кодек, теги).
 * - Любые другие бинарные файлы: определение формата по magic-байтам, SHA-256, извлечённые строки + hex-превью.
 */
object UniversalFileParser {
    private const val TAG = "UniversalFileParser"
    private const val MAX_TEXT_OUTPUT_CHARS = 25_000
    private const val MAX_BINARY_INSPECT_BYTES = 12 * 1024 * 1024 // 12 МБ для архивов/документов
    private const val MAX_RAW_TEXT_READ_BYTES = 512 * 1024        // 512 КБ для текстовых/бинарных файлов
    private const val MAX_IMAGE_DIM = 1280
    private const val IMAGE_JPEG_QUALITY = 82

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "ico", "tif", "tiff")
    private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "opus", "m4a", "aac", "flac", "amr", "wma", "mid")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "flv", "wmv")
    private val ARCHIVE_EXTS = setOf("zip", "jar", "apk", "aab", "cbz")
    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "log", "xml", "html", "htm", "css",
        "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "cpp", "cc", "h", "hpp",
        "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "bat", "ps1", "sql", "yaml", "yml",
        "toml", "ini", "conf", "cfg", "env", "properties", "gradle", "dockerfile", "gitignore", "svg",
        "patch", "diff", "srt", "vtt", "tex", "r", "swift", "dart", "lua", "pl"
    )

    fun parseUri(context: Context, uri: Uri, directPath: String? = null): AttachedDoc {
        var displayName = "file"
        var sizeBytes = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: displayName
                    if (sizeIdx != -1) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка чтения метаданных URI: ${e.message}")
        }

        val mimeType = context.contentResolver.getType(uri) ?: guessMimeFromName(displayName)
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)

        return try {
            when {
                mimeType.startsWith("image/") || ext in IMAGE_EXTS ->
                    parseImageFromUri(context, uri, displayName, sizeBytes, mimeType, directPath)

                mimeType == "application/pdf" || ext == "pdf" ->
                    parsePdfFromUri(context, uri, displayName, sizeBytes, directPath)

                ext == "docx" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    parseOfficeZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath,
                        kind = "docx"
                    )

                ext == "xlsx" || mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    parseOfficeZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath,
                        kind = "xlsx"
                    )

                ext == "pptx" || mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    parseOfficeZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath,
                        kind = "pptx"
                    )

                ext in setOf("odt", "ods", "odp") ->
                    parseOfficeZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath,
                        kind = "odf"
                    )

                ext == "epub" ->
                    parseOfficeZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath,
                        kind = "epub"
                    )

                ext == "fb2" -> {
                    val bytes = readBoundedStream(context.contentResolver.openInputStream(uri), MAX_BINARY_INSPECT_BYTES)
                    val actualSize = if (sizeBytes > 0) sizeBytes else bytes.size.toLong()
                    val text = extractXmlText(bytes).take(MAX_TEXT_OUTPUT_CHARS)
                    AttachedDoc(
                        name = displayName,
                        sizeBytes = actualSize,
                        content = text.ifBlank { "[FB2-документ без извлекаемого текста]" },
                        path = directPath,
                        mimeType = mimeType,
                        category = AttachmentCategory.DOCUMENT,
                        summaryBadge = "FB2 • ${formatSize(actualSize)}"
                    )
                }

                ext == "rtf" -> {
                    val bytes = readBoundedStream(context.contentResolver.openInputStream(uri), MAX_RAW_TEXT_READ_BYTES)
                    val actualSize = if (sizeBytes > 0) sizeBytes else bytes.size.toLong()
                    val text = stripRtf(decodeTextBytes(bytes)).take(MAX_TEXT_OUTPUT_CHARS)
                    AttachedDoc(
                        name = displayName,
                        sizeBytes = actualSize,
                        content = text.ifBlank { "[RTF-документ]" },
                        path = directPath,
                        mimeType = mimeType,
                        category = AttachmentCategory.DOCUMENT,
                        summaryBadge = "RTF • ${formatSize(actualSize)}"
                    )
                }

                ext in ARCHIVE_EXTS || mimeType == "application/zip" || mimeType == "application/vnd.android.package-archive" ->
                    parseGenericZipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath
                    )

                ext == "gz" || mimeType == "application/gzip" ->
                    parseGzipFromStream(
                        openStream = { context.contentResolver.openInputStream(uri) },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = directPath
                    )

                mimeType.startsWith("audio/") || ext in AUDIO_EXTS ->
                    parseMediaFromUri(context, uri, displayName, sizeBytes, mimeType, directPath, isVideo = false)

                mimeType.startsWith("video/") || ext in VIDEO_EXTS ->
                    parseMediaFromUri(context, uri, displayName, sizeBytes, mimeType, directPath, isVideo = true)

                else -> {
                    val bytes = readBoundedStream(context.contentResolver.openInputStream(uri), MAX_RAW_TEXT_READ_BYTES)
                    val actualSize = if (sizeBytes > 0) sizeBytes else bytes.size.toLong()
                    parseByContentInspection(context, uri, null, bytes, displayName, actualSize, mimeType, ext, directPath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка разбора файла '$displayName': ${e.message}", e)
            AttachedDoc(
                name = displayName,
                sizeBytes = sizeBytes,
                content = "[Файл: $displayName | Тип: $mimeType | Размер: ${formatSize(sizeBytes)} | Примечание: частичное чтение (${e.message})]",
                path = directPath,
                mimeType = mimeType,
                category = AttachmentCategory.BINARY,
                summaryBadge = "${ext.uppercase(Locale.US).ifBlank { "FILE" }} • ${formatSize(sizeBytes)}"
            )
        }
    }

    fun parseFile(context: Context?, file: File): AttachedDoc {
        val displayName = file.name
        val sizeBytes = file.length()
        val ext = file.extension.lowercase(Locale.US)
        val mimeType = guessMimeFromName(displayName)

        return try {
            when {
                mimeType.startsWith("image/") || ext in IMAGE_EXTS ->
                    parseImageFromFile(file, displayName, sizeBytes, mimeType)

                ext == "pdf" ->
                    parsePdfFromFile(file, displayName, sizeBytes)

                ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub") -> {
                    val kind = when (ext) {
                        "docx", "xlsx", "pptx", "epub" -> ext
                        else -> "odf"
                    }
                    parseOfficeZipFromStream(
                        openStream = { file.inputStream() },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = file.absolutePath,
                        kind = kind
                    )
                }

                ext == "fb2" -> {
                    val bytes = readBoundedStream(file.inputStream(), MAX_BINARY_INSPECT_BYTES)
                    val text = extractXmlText(bytes).take(MAX_TEXT_OUTPUT_CHARS)
                    AttachedDoc(
                        name = displayName,
                        sizeBytes = sizeBytes,
                        content = text.ifBlank { "[FB2-документ без извлекаемого текста]" },
                        path = file.absolutePath,
                        mimeType = mimeType,
                        category = AttachmentCategory.DOCUMENT,
                        summaryBadge = "FB2 • ${formatSize(sizeBytes)}"
                    )
                }

                ext == "rtf" -> {
                    val bytes = readBoundedStream(file.inputStream(), MAX_RAW_TEXT_READ_BYTES)
                    val text = stripRtf(decodeTextBytes(bytes)).take(MAX_TEXT_OUTPUT_CHARS)
                    AttachedDoc(
                        name = displayName,
                        sizeBytes = sizeBytes,
                        content = text.ifBlank { "[RTF-документ]" },
                        path = file.absolutePath,
                        mimeType = mimeType,
                        category = AttachmentCategory.DOCUMENT,
                        summaryBadge = "RTF • ${formatSize(sizeBytes)}"
                    )
                }

                ext in ARCHIVE_EXTS ->
                    parseGenericZipFromStream(
                        openStream = { file.inputStream() },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = file.absolutePath
                    )

                ext == "gz" ->
                    parseGzipFromStream(
                        openStream = { file.inputStream() },
                        name = displayName,
                        sizeBytes = sizeBytes,
                        mimeType = mimeType,
                        directPath = file.absolutePath
                    )

                (mimeType.startsWith("audio/") || ext in AUDIO_EXTS) && context != null ->
                    parseMediaFromFile(file, displayName, sizeBytes, mimeType, isVideo = false)

                (mimeType.startsWith("video/") || ext in VIDEO_EXTS) && context != null ->
                    parseMediaFromFile(file, displayName, sizeBytes, mimeType, isVideo = true)

                else -> {
                    val bytes = readBoundedStream(file.inputStream(), MAX_RAW_TEXT_READ_BYTES)
                    parseByContentInspection(context, null, file, bytes, displayName, sizeBytes, mimeType, ext, file.absolutePath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка разбора локального файла '${file.name}': ${e.message}", e)
            AttachedDoc(
                name = displayName,
                sizeBytes = sizeBytes,
                content = "[Файл: ${file.absolutePath} | Размер: ${formatSize(sizeBytes)} | Ошибка разбора: ${e.message}]",
                path = file.absolutePath,
                mimeType = mimeType,
                category = AttachmentCategory.BINARY,
                summaryBadge = "${ext.uppercase(Locale.US).ifBlank { "FILE" }} • ${formatSize(sizeBytes)}"
            )
        }
    }

    // =========================================================================
    // 1. ИЗОБРАЖЕНИЯ И СКРИНШОТЫ (безопасное масштабирование + Base64 JPEG)
    // =========================================================================

    private fun parseImageFromUri(
        context: Context,
        uri: Uri,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?
    ): AttachedDoc {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

        val origW = boundsOpts.outWidth.coerceAtLeast(0)
        val origH = boundsOpts.outHeight.coerceAtLeast(0)

        val sampleSize = computeInSampleSize(origW, origH, MAX_IMAGE_DIM)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }

        return buildImageAttachment(bitmap, origW, origH, name, sizeBytes, mimeType, directPath)
    }

    private fun parseImageFromFile(
        file: File,
        name: String,
        sizeBytes: Long,
        mimeType: String
    ): AttachedDoc {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val origW = boundsOpts.outWidth.coerceAtLeast(0)
        val origH = boundsOpts.outHeight.coerceAtLeast(0)

        val sampleSize = computeInSampleSize(origW, origH, MAX_IMAGE_DIM)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)

        return buildImageAttachment(bitmap, origW, origH, name, sizeBytes, mimeType, file.absolutePath)
    }

    private fun buildImageAttachment(
        decodedBitmap: Bitmap?,
        origW: Int,
        origH: Int,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?
    ): AttachedDoc {
        var base64Jpeg: String? = null
        var finalW = origW
        var finalH = origH

        if (decodedBitmap != null) {
            try {
                val scaled = scaleBitmapIfNeeded(decodedBitmap, MAX_IMAGE_DIM)
                if (finalW == 0) finalW = decodedBitmap.width
                if (finalH == 0) finalH = decodedBitmap.height

                // Рисуем на белом фоне, чтобы PNG-скриншоты с прозрачностью не становились чёрными в JPEG
                val rgbBitmap = if (scaled.hasAlpha()) {
                    Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { bg ->
                        val canvas = Canvas(bg)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(scaled, 0f, 0f, null)
                    }
                } else {
                    scaled
                }

                val baos = ByteArrayOutputStream()
                rgbBitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                base64Jpeg = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                if (rgbBitmap !== scaled) rgbBitmap.recycle()
                if (scaled !== decodedBitmap) scaled.recycle()
            } finally {
                decodedBitmap.recycle()
            }
        }

        val ext = name.substringAfterLast('.', "").uppercase(Locale.US).ifBlank { "IMG" }
        val dimsStr = if (finalW > 0 && finalH > 0) "${finalW}×${finalH} px" else "неизвестное разрешение"
        val orientStr = when {
            finalW <= 0 || finalH <= 0 -> ""
            finalH > finalW -> " (вертикальное / скриншот телефона)"
            finalW > finalH -> " (горизонтальное)"
            else -> " (квадратное)"
        }

        val textSummary = buildString {
            append("[Прикреплённое изображение: $name]\n")
            append("- Формат: $ext ($mimeType)\n")
            append("- Разрешение: $dimsStr$orientStr\n")
            if (sizeBytes > 0) append("- Размер файла: ${formatSize(sizeBytes)}\n")
            if (directPath != null) append("- Путь: $directPath\n")
            if (base64Jpeg != null) {
                append("- Визуальные данные: подготовлены для мультимодальных моделей (Vision).")
            }
        }

        val badge = buildString {
            append(ext)
            if (finalW > 0 && finalH > 0) append(" • ${finalW}×${finalH}")
            if (sizeBytes > 0) append(" • ${formatSize(sizeBytes)}")
        }

        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = textSummary,
            path = directPath,
            mimeType = mimeType,
            category = AttachmentCategory.IMAGE,
            imageBase64 = base64Jpeg,
            imageWidth = finalW,
            imageHeight = finalH,
            summaryBadge = badge
        )
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val maxSide = maxOf(width, height)
        if (maxSide > maxDim) {
            while ((maxSide / inSampleSize) > maxDim * 2) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleBitmapIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    // =========================================================================
    // 2. PDF (извлечение текста + рендер 1-й страницы через PdfRenderer для Vision)
    // =========================================================================

    private fun parsePdfFromUri(
        context: Context,
        uri: Uri,
        name: String,
        sizeBytes: Long,
        directPath: String?
    ): AttachedDoc {
        val rawBytes = readBoundedStream(context.contentResolver.openInputStream(uri), MAX_BINARY_INSPECT_BYTES)
        val actualSize = if (sizeBytes > 0) sizeBytes else rawBytes.size.toLong()
        val extractedText = extractPdfTextHeuristic(rawBytes)

        var pageCount = 0
        var firstPageBase64: String? = null
        var pageW = 0
        var pageH = 0

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    pageCount = renderer.pageCount
                    if (pageCount > 0) {
                        val page = renderer.openPage(0)
                        try {
                            val scale = minOf(1.5f, MAX_IMAGE_DIM.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                            pageW = (page.width * scale).toInt().coerceAtLeast(1)
                            pageH = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bmp)
                                canvas.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val baos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                                firstPageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            } finally {
                                bmp.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer недоступен для '$name': ${e.message}")
        }

        return buildPdfAttachment(name, actualSize, directPath, pageCount, extractedText, firstPageBase64, pageW, pageH)
    }

    private fun parsePdfFromFile(file: File, name: String, sizeBytes: Long): AttachedDoc {
        val rawBytes = readBoundedStream(file.inputStream(), MAX_BINARY_INSPECT_BYTES)
        val extractedText = extractPdfTextHeuristic(rawBytes)

        var pageCount = 0
        var firstPageBase64: String? = null
        var pageW = 0
        var pageH = 0

        try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    pageCount = renderer.pageCount
                    if (pageCount > 0) {
                        val page = renderer.openPage(0)
                        try {
                            val scale = minOf(1.5f, MAX_IMAGE_DIM.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                            pageW = (page.width * scale).toInt().coerceAtLeast(1)
                            pageH = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bmp)
                                canvas.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val baos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                                firstPageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            } finally {
                                bmp.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer недоступен для '${file.name}': ${e.message}")
        }

        return buildPdfAttachment(name, sizeBytes, file.absolutePath, pageCount, extractedText, firstPageBase64, pageW, pageH)
    }

    private fun buildPdfAttachment(
        name: String,
        sizeBytes: Long,
        directPath: String?,
        pageCount: Int,
        extractedText: String,
        firstPageBase64: String?,
        pageW: Int,
        pageH: Int
    ): AttachedDoc {
        val content = buildString {
            append("[PDF-документ: $name | Страниц: ${if (pageCount > 0) pageCount else "?"} | Размер: ${formatSize(sizeBytes)}]\n")
            if (extractedText.length >= 20) {
                append("\nИзвлечённый текст PDF:\n")
                append(extractedText.take(MAX_TEXT_OUTPUT_CHARS))
            } else {
                append("\nТекстовый слой PDF не обнаружен или зашифрован (возможно, это скан/изображение).")
                if (firstPageBase64 != null) {
                    append(" Первая страница отрендерена как изображение для мультимодальных (Vision) моделей.")
                }
            }
        }

        val badge = buildString {
            append("PDF")
            if (pageCount > 0) append(" • $pageCount стр.")
            append(" • ${formatSize(sizeBytes)}")
        }

        // Отправляем изображение 1-й страницы в Vision API, если текстовый слой скудный
        val attachImage = if (extractedText.length < 150) firstPageBase64 else null

        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = content,
            path = directPath,
            mimeType = "application/pdf",
            category = AttachmentCategory.PDF,
            imageBase64 = attachImage,
            imageWidth = pageW,
            imageHeight = pageH,
            summaryBadge = badge
        )
    }

    /**
     * Лёгкое встроенное извлечение текста из PDF (распаковка FlateDecode потоков + разбор операторов Tj/TJ).
     */
    private fun extractPdfTextHeuristic(pdfBytes: ByteArray): String {
        val sb = StringBuilder()
        val latin = String(pdfBytes, Charsets.ISO_8859_1)

        // 1. Сначала собираем несжатые блоки текста
        extractPdfOperators(latin, sb)

        // 2. Ищем и распаковываем сжатые FlateDecode потоки
        var idx = 0
        var streamsParsed = 0
        while (idx < pdfBytes.size && streamsParsed < 60 && sb.length < MAX_TEXT_OUTPUT_CHARS) {
            val streamPos = indexOfAscii(pdfBytes, "stream", idx)
            if (streamPos == -1) break
            var dataStart = streamPos + 6
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\n'.code.toByte()) dataStart++

            val endPos = indexOfAscii(pdfBytes, "endstream", dataStart)
            if (endPos == -1) break
            val chunkLen = (endPos - dataStart).coerceAtMost(2 * 1024 * 1024)
            if (chunkLen > 4) {
                val inflated = tryInflate(pdfBytes, dataStart, chunkLen)
                if (inflated != null) {
                    val textStream = String(inflated, Charsets.ISO_8859_1)
                    if (textStream.contains("Tj") || textStream.contains("TJ")) {
                        extractPdfOperators(textStream, sb)
                        streamsParsed++
                    }
                }
            }
            idx = endPos + 9
        }

        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractPdfOperators(content: String, out: StringBuilder) {
        val tjRegex = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)\s*Tj""")
        for (m in tjRegex.findAll(content)) {
            if (out.length >= MAX_TEXT_OUTPUT_CHARS) return
            val decoded = unescapePdfLiteral(m.groupValues[1])
            if (decoded.isNotBlank()) out.append(decoded).append(' ')
        }

        val tjArrayRegex = Regex("""\[(.*?)]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
        val innerLitRegex = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)""")
        for (arrMatch in tjArrayRegex.findAll(content)) {
            if (out.length >= MAX_TEXT_OUTPUT_CHARS) return
            val lineBuf = StringBuilder()
            for (lit in innerLitRegex.findAll(arrMatch.groupValues[1])) {
                lineBuf.append(unescapePdfLiteral(lit.groupValues[1]))
            }
            if (lineBuf.isNotBlank()) {
                out.append(lineBuf).append('\n')
            }
        }
    }

    private fun unescapePdfLiteral(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                i++
                when (val nxt = raw[i]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '(', ')', '\\' -> sb.append(nxt)
                    in '0'..'7' -> {
                        var oct = (nxt - '0')
                        var count = 1
                        while (count < 3 && i + 1 < raw.length && raw[i + 1] in '0'..'7') {
                            i++
                            oct = oct * 8 + (raw[i] - '0')
                            count++
                        }
                        if (oct in 32..126) sb.append(oct.toChar())
                    }
                    else -> sb.append(nxt)
                }
            } else if (ch.code in 32..126 || ch == '\n' || ch == '\t') {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun tryInflate(src: ByteArray, offset: Int, len: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(src, offset, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (!inflater.finished() && total < 512 * 1024) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            if (total > 0) out.toByteArray() else null
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun indexOfAscii(haystack: ByteArray, needle: String, startIndex: Int): Int {
        val nb = needle.toByteArray(Charsets.US_ASCII)
        val limit = haystack.size - nb.size
        outer@ for (i in startIndex..limit) {
            for (j in nb.indices) {
                if (haystack[i + j] != nb[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // =========================================================================
    // 3. OFFICE (.docx, .xlsx, .pptx), ODF (.odt/.ods), EPUB
    // =========================================================================

    private fun parseOfficeZipFromStream(
        openStream: () -> InputStream?,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?,
        kind: String
    ): AttachedDoc {
        val entries = mutableMapOf<String, ByteArray>()
        var totalRead = 0L

        openStream()?.use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && totalRead < MAX_BINARY_INSPECT_BYTES) {
                    if (!entry.isDirectory) {
                        val lower = entry.name.lowercase(Locale.US)
                        val shouldRead = when (kind) {
                            "docx" -> lower == "word/document.xml" || lower.startsWith("word/header") || lower.startsWith("word/footer")
                            "xlsx" -> lower == "xl/sharedstrings.xml" || (lower.startsWith("xl/worksheets/sheet") && lower.endsWith(".xml"))
                            "pptx" -> lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml")
                            "odf" -> lower == "content.xml"
                            "epub" -> lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                            else -> false
                        }
                        if (shouldRead) {
                            val data = readZipEntryBounded(zis, 2 * 1024 * 1024)
                            totalRead += data.size
                            entries[entry.name] = data
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val extracted = when (kind) {
            "docx" -> parseDocxEntries(entries)
            "xlsx" -> parseXlsxEntries(entries)
            "pptx" -> parsePptxEntries(entries)
            "odf" -> entries["content.xml"]?.let { extractXmlText(it) } ?: ""
            "epub" -> entries.toSortedMap().values.joinToString("\n\n") { extractXmlText(it) }
            else -> ""
        }.take(MAX_TEXT_OUTPUT_CHARS)

        val category = when (kind) {
            "xlsx" -> AttachmentCategory.SPREADSHEET
            "pptx" -> AttachmentCategory.PRESENTATION
            else -> AttachmentCategory.DOCUMENT
        }
        val extLabel = name.substringAfterLast('.', kind).uppercase(Locale.US)

        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = extracted.ifBlank { "[$extLabel-документ '$name' не содержит текстового содержимого]" },
            path = directPath,
            mimeType = mimeType,
            category = category,
            summaryBadge = "$extLabel • ${formatSize(sizeBytes)}"
        )
    }

    private fun parseDocxEntries(entries: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        val ordered = entries.keys.sortedBy { if (it.equals("word/document.xml", true)) "0" else it }
        for (key in ordered) {
            val xmlBytes = entries[key] ?: continue
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_TEXT_OUTPUT_CHARS) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "tab" -> sb.append('\t')
                            "br", "cr" -> sb.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text
                        if (!t.isNullOrEmpty()) sb.append(t)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "p" -> sb.append('\n')
                            "tc" -> sb.append(" | ")
                            "tr" -> sb.append('\n')
                        }
                    }
                }
                event = parser.next()
            }
        }
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun parseXlsxEntries(entries: Map<String, ByteArray>): String {
        // 1. Читаем sharedStrings.xml
        val sharedStrings = mutableListOf<String>()
        val ssEntry = entries.entries.firstOrNull { it.key.equals("xl/sharedStrings.xml", true) }?.value
        if (ssEntry != null) {
            try {
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(ssEntry), "UTF-8")
                var event = parser.eventType
                var inSi = false
                val current = StringBuilder()
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> if (parser.name == "si") {
                            inSi = true
                            current.setLength(0)
                        }
                        XmlPullParser.TEXT -> if (inSi) current.append(parser.text ?: "")
                        XmlPullParser.END_TAG -> if (parser.name == "si") {
                            sharedStrings.add(current.toString())
                            inSi = false
                        }
                    }
                    event = parser.next()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка разбора sharedStrings.xml: ${e.message}")
            }
        }

        // 2. Читаем листы xl/worksheets/sheet*.xml
        val sb = StringBuilder()
        val sheets = entries.entries
            .filter { it.key.lowercase(Locale.US).startsWith("xl/worksheets/sheet") }
            .sortedBy { it.key }

        for ((sheetIndex, sheetEntry) in sheets.withIndex()) {
            if (sb.length >= MAX_TEXT_OUTPUT_CHARS) break
            sb.append("### Лист ${sheetIndex + 1} (${sheetEntry.key.substringAfterLast('/')})\n")
            try {
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(sheetEntry.value), "UTF-8")
                var event = parser.eventType
                var cellType = ""
                var inVal = false
                var inInlineText = false
                val rowCells = mutableListOf<String>()
                val cellBuf = StringBuilder()

                while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_TEXT_OUTPUT_CHARS) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "row" -> rowCells.clear()
                            "c" -> {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                                cellBuf.setLength(0)
                            }
                            "v" -> inVal = true
                            "t" -> inInlineText = true
                        }
                        XmlPullParser.TEXT -> {
                            if (inVal || inInlineText) cellBuf.append(parser.text ?: "")
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "v" -> inVal = false
                            "t" -> inInlineText = false
                            "c" -> {
                                val rawVal = cellBuf.toString().trim()
                                val resolved = if (cellType == "s") {
                                    val idx = rawVal.toIntOrNull()
                                    if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawVal
                                } else {
                                    rawVal
                                }
                                if (resolved.isNotEmpty()) rowCells.add(resolved)
                            }
                            "row" -> if (rowCells.isNotEmpty()) {
                                sb.append(rowCells.joinToString(" | ")).append('\n')
                            }
                        }
                    }
                    event = parser.next()
                }
            } catch (e: Exception) {
                sb.append("[Ошибка чтения листа: ${e.message}]\n")
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun parsePptxEntries(entries: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        val slides = entries.entries.sortedBy {
            Regex("""slide(\d+)\.xml""", RegexOption.IGNORE_CASE)
                .find(it.key)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }
        for ((index, slide) in slides.withIndex()) {
            if (sb.length >= MAX_TEXT_OUTPUT_CHARS) break
            val slideText = extractXmlText(slide.value)
            if (slideText.isNotBlank()) {
                sb.append("--- Слайд ${index + 1} ---\n").append(slideText).append("\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun extractXmlText(xmlBytes: ByteArray): String {
        return try {
            val sb = StringBuilder()
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && sb.length < MAX_TEXT_OUTPUT_CHARS) {
                when (event) {
                    XmlPullParser.TEXT -> {
                        val t = parser.text?.trim()
                        if (!t.isNullOrEmpty()) sb.append(t).append(' ')
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name?.lowercase(Locale.US) ?: ""
                        if (tag in setOf("p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4", "section", "title")) {
                            sb.append('\n')
                        }
                    }
                }
                event = parser.next()
            }
            sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
        } catch (_: Exception) {
            // Запасной вариант через очистку тегов
            decodeTextBytes(xmlBytes)
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }
    }

    private fun stripRtf(rtf: String): String {
        return rtf
            .replace(Regex("""\\par\b"""), "\n")
            .replace(Regex("""\\tab\b"""), "\t")
            .replace(Regex("""\\'[0-9a-fA-F]{2}""")) { m ->
                val b = m.value.substring(2).toIntOrNull(16) ?: 32
                String(byteArrayOf(b.toByte()), Charset.forName("windows-1251"))
            }
            .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
            .replace("{", "")
            .replace("}", "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // =========================================================================
    // 4. АРХИВЫ (ZIP / APK / JAR / GZ)
    // =========================================================================

    private fun parseGenericZipFromStream(
        openStream: () -> InputStream?,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?
    ): AttachedDoc {
        val fileList = mutableListOf<String>()
        val previews = StringBuilder()
        var entryCount = 0

        openStream()?.use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && entryCount < 250) {
                    entryCount++
                    val eName = entry.name
                    val eSize = entry.size
                    if (fileList.size < 120) {
                        val sizeTag = if (eSize >= 0) " (${formatSize(eSize)})" else ""
                        fileList.add("  - $eName$sizeTag")
                    }
                    val eExt = eName.substringAfterLast('.', "").lowercase(Locale.US)
                    if (!entry.isDirectory && eExt in TEXT_EXTS && previews.length < 16_000) {
                        val data = readZipEntryBounded(zis, 64 * 1024)
                        if (data.isNotEmpty() && !looksBinary(data)) {
                            previews.append("\n--- $eName ---\n")
                            previews.append(decodeTextBytes(data).take(4000)).append('\n')
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val content = buildString {
            append("[Архив: $name | Размер: ${formatSize(sizeBytes)} | Файлов: $entryCount]\n")
            append("Содержимое архива:\n")
            append(fileList.joinToString("\n"))
            if (entryCount > fileList.size) append("\n  ...и ещё ${entryCount - fileList.size} элементов")
            if (previews.isNotBlank()) {
                append("\n\nТекстовые файлы внутри архива:")
                append(previews.toString())
            }
        }.take(MAX_TEXT_OUTPUT_CHARS)

        val ext = name.substringAfterLast('.', "ZIP").uppercase(Locale.US)
        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = content,
            path = directPath,
            mimeType = mimeType,
            category = AttachmentCategory.ARCHIVE,
            summaryBadge = "$ext • $entryCount ф. • ${formatSize(sizeBytes)}"
        )
    }

    private fun parseGzipFromStream(
        openStream: () -> InputStream?,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?
    ): AttachedDoc {
        val decompressed = openStream()?.use { raw ->
            GZIPInputStream(raw).use { gzis -> readBoundedStream(gzis, MAX_RAW_TEXT_READ_BYTES) }
        } ?: ByteArray(0)

        val content = if (!looksBinary(decompressed)) {
            decodeTextBytes(decompressed).take(MAX_TEXT_OUTPUT_CHARS)
        } else {
            buildBinaryReport(name, sizeBytes, mimeType, decompressed, directPath)
        }

        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = content,
            path = directPath,
            mimeType = mimeType,
            category = AttachmentCategory.ARCHIVE,
            summaryBadge = "GZIP • ${formatSize(sizeBytes)}"
        )
    }

    // =========================================================================
    // 5. АУДИО И ВИДЕО МЕТАДАННЫЕ
    // =========================================================================

    private fun parseMediaFromUri(
        context: Context,
        uri: Uri,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        directPath: String?,
        isVideo: Boolean
    ): AttachedDoc {
        val retriever = MediaMetadataRetriever()
        val info = try {
            retriever.setDataSource(context, uri)
            extractMediaMeta(retriever, isVideo)
        } catch (e: Exception) {
            "Метаданные недоступны (${e.message})"
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // Для видео также захватываем первый кадр для мультимодальных Vision-моделей!
        var frameBase64: String? = null
        var frameW = 0
        var frameH = 0
        if (isVideo) {
            val frameRetriever = MediaMetadataRetriever()
            try {
                frameRetriever.setDataSource(context, uri)
                val frame = frameRetriever.getFrameAtTime(0)
                if (frame != null) {
                    val scaled = scaleBitmapIfNeeded(frame, MAX_IMAGE_DIM)
                    frameW = scaled.width
                    frameH = scaled.height
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                    frameBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                }
            } catch (_: Exception) {
            } finally {
                try { frameRetriever.release() } catch (_: Exception) {}
            }
        }

        val category = if (isVideo) AttachmentCategory.VIDEO else AttachmentCategory.AUDIO
        val ext = name.substringAfterLast('.', if (isVideo) "VIDEO" else "AUDIO").uppercase(Locale.US)
        val content = buildString {
            append("[${category.label}: $name | Тип: $mimeType | Размер: ${formatSize(sizeBytes)}]\n")
            append(info)
            if (frameBase64 != null) {
                append("\n- Превью-кадр видео: подготовлен для Vision-моделей.")
            }
        }

        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = content,
            path = directPath,
            mimeType = mimeType,
            category = category,
            imageBase64 = frameBase64,
            imageWidth = frameW,
            imageHeight = frameH,
            summaryBadge = "$ext • ${formatSize(sizeBytes)}"
        )
    }

    private fun parseMediaFromFile(
        file: File,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        isVideo: Boolean
    ): AttachedDoc {
        val retriever = MediaMetadataRetriever()
        val info = try {
            retriever.setDataSource(file.absolutePath)
            extractMediaMeta(retriever, isVideo)
        } catch (e: Exception) {
            "Метаданные недоступны (${e.message})"
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val category = if (isVideo) AttachmentCategory.VIDEO else AttachmentCategory.AUDIO
        val ext = name.substringAfterLast('.', if (isVideo) "VIDEO" else "AUDIO").uppercase(Locale.US)
        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = "[${category.label}: $name | Тип: $mimeType | Размер: ${formatSize(sizeBytes)}]\n$info",
            path = file.absolutePath,
            mimeType = mimeType,
            category = category,
            summaryBadge = "$ext • ${formatSize(sizeBytes)}"
        )
    }

    private fun extractMediaMeta(retriever: MediaMetadataRetriever, isVideo: Boolean): String {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

        return buildString {
            if (durationMs != null) {
                val sec = durationMs / 1000
                append("- Длительность: ${sec / 60} мин ${sec % 60} сек\n")
            }
            if (isVideo && !w.isNullOrBlank() && !h.isNullOrBlank()) {
                append("- Разрешение видео: ${w}×${h} px\n")
            }
            if (bitrate != null && bitrate > 0) {
                append("- Битрейт: ${bitrate / 1000} кбит/с\n")
            }
            if (!title.isNullOrBlank()) append("- Название: $title\n")
            if (!artist.isNullOrBlank()) append("- Исполнитель: $artist\n")
            if (!album.isNullOrBlank()) append("- Альбом: $album\n")
        }.trim().ifBlank { "- Медиафайл без заполненных метаданных" }
    }

    // =========================================================================
    // 6. АВТООПРЕДЕЛЕНИЕ ПО МАГИЧЕСКИМ БАЙТАМ + ТЕКСТ (UTF-8 / UTF-16 / CP1251) + ЛЮБОЙ БИНАРНИК
    // =========================================================================

    private fun parseByContentInspection(
        context: Context?,
        uri: Uri?,
        file: File?,
        bytes: ByteArray,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        ext: String,
        directPath: String?
    ): AttachedDoc {
        // Проверка на изображение по magic-байтам (даже если файл без расширения!)
        if (isImageMagic(bytes)) {
            if (context != null && uri != null) {
                return parseImageFromUri(context, uri, name, sizeBytes, "image/*", directPath)
            } else if (file != null) {
                return parseImageFromFile(file, name, sizeBytes, "image/*")
            }
        }

        // Проверка на PDF по magic-байтам (%PDF-)
        if (bytes.size >= 5 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() && bytes[4] == 0x2D.toByte()
        ) {
            if (context != null && uri != null) {
                return parsePdfFromUri(context, uri, name, sizeBytes, directPath)
            } else if (file != null) {
                return parsePdfFromFile(file, name, sizeBytes)
            }
        }

        // Проверка на ZIP по magic-байтам (PK\x03\x04)
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            val streamOpener: () -> InputStream? = when {
                context != null && uri != null -> ({ context.contentResolver.openInputStream(uri) })
                file != null -> ({ file.inputStream() })
                else -> ({ ByteArrayInputStream(bytes) })
            }
            return parseGenericZipFromStream(streamOpener, name, sizeBytes, "application/zip", directPath)
        }

        // Текстовый файл (поддерживаем UTF-8, UTF-16LE/BE с BOM, Windows-1251)
        if (!looksBinary(bytes) || ext in TEXT_EXTS) {
            val decoded = decodeTextBytes(bytes)
            val truncated = if (decoded.length > MAX_TEXT_OUTPUT_CHARS) {
                decoded.substring(0, MAX_TEXT_OUTPUT_CHARS) +
                    "\n\n[...файл обрезан, показаны первые $MAX_TEXT_OUTPUT_CHARS из ${decoded.length} символов...]"
            } else {
                decoded
            }
            val label = ext.uppercase(Locale.US).ifBlank { "TXT" }
            return AttachedDoc(
                name = name,
                sizeBytes = sizeBytes,
                content = truncated,
                path = directPath,
                mimeType = if (mimeType == "application/octet-stream") "text/plain" else mimeType,
                category = AttachmentCategory.TEXT,
                summaryBadge = "$label • ${formatSize(sizeBytes)}"
            )
        }

        // Любой другой бинарный формат — формируем информативный технический отчёт (НИКОГДА не отклоняем!)
        val report = buildBinaryReport(name, sizeBytes, mimeType, bytes, directPath)
        val detectedSig = detectMagicFormat(bytes)
        val badgeLabel = ext.uppercase(Locale.US).ifBlank { detectedSig.substringBefore(' ').take(8) }
        return AttachedDoc(
            name = name,
            sizeBytes = sizeBytes,
            content = report,
            path = directPath,
            mimeType = mimeType,
            category = AttachmentCategory.BINARY,
            summaryBadge = "$badgeLabel • ${formatSize(sizeBytes)}"
        )
    }

    private fun buildBinaryReport(
        name: String,
        sizeBytes: Long,
        mimeType: String,
        sampleBytes: ByteArray,
        directPath: String?
    ): String {
        val formatDesc = detectMagicFormat(sampleBytes)
        val sha256 = try {
            MessageDigest.getInstance("SHA-256")
                .digest(sampleBytes)
                .joinToString("") { "%02x".format(it) }
                .take(16) + "…"
        } catch (_: Exception) {
            "n/a"
        }

        val hexPreview = sampleBytes.take(128).chunked(16).joinToString("\n") { chunk ->
            val hex = chunk.joinToString(" ") { "%02X".format(it) }.padEnd(47, ' ')
            val ascii = chunk.map { b ->
                val v = b.toInt() and 0xFF
                if (v in 32..126) v.toChar() else '.'
            }.joinToString("")
            "$hex  |$ascii|"
        }

        val extractedStrings = extractReadableStrings(sampleBytes, maxChars = 4000)

        return buildString {
            append("[Прикреплён файл (бинарный формат): $name]\n")
            append("- Определённый формат: $formatDesc\n")
            append("- MIME-тип: $mimeType\n")
            append("- Размер: ${formatSize(sizeBytes)}\n")
            append("- SHA-256 (превью): $sha256\n")
            if (directPath != null) append("- Путь: $directPath\n")
            append("\nHex-заголовок (первые ${minOf(sampleBytes.size, 128)} байт):\n")
            append("```\n$hexPreview\n```\n")
            if (extractedStrings.isNotBlank()) {
                append("\nНайденные читаемые строки / метаданные внутри файла:\n")
                append("```\n$extractedStrings\n```")
            }
        }
    }

    private fun extractReadableStrings(bytes: ByteArray, minLen: Int = 5, maxChars: Int = 4000): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 32..126) {
                run.append(v.toChar())
            } else {
                if (run.length >= minLen) {
                    if (sb.length + run.length + 1 > maxChars) break
                    sb.append(run).append('\n')
                }
                run.setLength(0)
            }
        }
        if (run.length >= minLen && sb.length + run.length <= maxChars) {
            sb.append(run)
        }
        return sb.toString().trim()
    }

    private fun isImageMagic(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        return (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) || // PNG
            (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) ||                   // JPEG
            (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) ||     // GIF
            (b0 == 0x42 && b1 == 0x4D) ||                                 // BMP
            (b.size >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte()) // WEBP
    }

    private fun detectMagicFormat(b: ByteArray): String {
        if (b.size < 4) return "Неизвестный бинарный файл"
        val hex4 = b.take(4).joinToString("") { "%02X".format(it) }
        val ascii16 = String(b.take(16).toByteArray(), Charsets.ISO_8859_1)
        return when {
            ascii16.startsWith("SQLite format 3") -> "SQLite 3 Database"
            ascii16.startsWith("GGUF") -> "GGUF LLM Model Weights"
            hex4 == "7F454C46" -> "ELF Executable / Shared Library (.so)"
            hex4.startsWith("4D5A") -> "Windows PE / EXE / DLL"
            hex4 == "6465780A" -> "Android DEX Bytecode"
            hex4 == "52617221" -> "RAR Archive"
            hex4 == "377ABCAF" -> "7-Zip Archive"
            hex4.startsWith("1F8B") -> "GZIP Archive"
            hex4 == "D0CF11E0" -> "Legacy MS Office (DOC/XLS/PPT OLE2)"
            ascii16.contains("ftyp") -> "MP4 / MOV / HEIC ISO Media Container"
            ascii16.startsWith("ID3") -> "MP3 Audio"
            ascii16.startsWith("OggS") -> "OGG / Opus Media"
            ascii16.startsWith("fLaC") -> "FLAC Audio"
            else -> "Бинарный файл (сигнатура 0x$hex4)"
        }
    }

    /**
     * Определяет, является ли массив байт бинарным (с учётом UTF-16 BOM, чтобы не отбрасывать UTF-16 текст!).
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // UTF-16LE / UTF-16BE BOM — это текст!
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return false
            }
        }
        val checkLen = bytes.size.coerceAtMost(2048)
        var controlCount = 0
        for (i in 0 until checkLen) {
            val v = bytes[i].toInt() and 0xFF
            if (v == 0) return true
            if (v < 32 && v != 9 && v != 10 && v != 13 && v != 12) {
                controlCount++
            }
        }
        return controlCount.toFloat() / checkLen.toFloat() > 0.15f
    }

    /**
     * Декодирует текст с автоопределением UTF-8 BOM, UTF-16LE/BE BOM, строгого UTF-8 и Windows-1251.
     */
    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // Сначала пробуем строгий UTF-8; при ошибке кодировки — Windows-1251 (для русских ANSI-файлов)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            try {
                String(bytes, Charset.forName("windows-1251"))
            } catch (_: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    private fun readBoundedStream(input: InputStream?, maxBytes: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buf = ByteArray(8192)
            var total = 0
            while (total < maxBytes) {
                val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
        }
        return out.toByteArray()
    }

    private fun readZipEntryBounded(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val n = zis.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f КБ", bytes / 1024f)
            else -> String.format(Locale.US, "%.1f МБ", bytes / (1024f * 1024f))
        }
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "gz" -> "application/gzip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "txt", "md", "log", "py", "kt", "java", "js", "ts", "sh", "sql", "yaml", "yml" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
