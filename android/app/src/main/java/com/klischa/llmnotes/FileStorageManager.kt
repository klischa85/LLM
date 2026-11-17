package com.klischa.llmnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class ExtractedCodeBlock(
    val language: String,
    val code: String,
    val suggestedFilename: String
)

/**
 * Менеджер работы с локальными файлами во внутренней памяти устройства:
 * - Поиск и безопасное чтение текстовых файлов по путям (/sdcard/..., Download/...).
 * - Обогащение запроса пользователя содержимым найденных файлов для анализа моделью.
 * - Скачивание и сохранение сгенерированного кода (Python, Bash и др.) в папку Download.
 * - Извлечение блоков кода из Markdown-ответов модели.
 */
object FileStorageManager {
    private const val TAG = "FileStorageManager"
    private const val MAX_READ_CHARS = 25000

    private val PATH_PATTERN = Pattern.compile(
        """(?:/sdcard/|/storage/emulated/0/|/storage/[^\s"':;,]+|Download/[^\s"':;,]+|Documents/[^\s"':;,]+|Pictures/[^\s"':;,]+|DCIM/[^\s"':;,]+|[a-zA-Z0-9_\-./]+\.(?:py|txt|json|csv|md|log|kt|java|c|cpp|h|sh|html|xml|yaml|yml|sql|js|ts|conf|ini|env|png|jpg|jpeg|webp|gif|bmp|pdf|docx|xlsx|pptx|odt|epub|fb2|rtf|zip|apk|gz|mp3|wav|mp4|db|sqlite))""",
        Pattern.CASE_INSENSITIVE
    )

    private val CODE_BLOCK_PATTERN = Pattern.compile(
        """```(?:([a-zA-Z0-9_+\-]+)(?:[:=\s]([^\n\r]+))?)?[\r\n]+([\s\S]*?)```"""
    )

    /**
     * Поиск и извлечение путей к файлам из текста сообщения.
     */
    fun extractFilePathsFromText(text: String): List<String> {
        val matches = mutableListOf<String>()
        val matcher = PATH_PATTERN.matcher(text)
        while (matcher.find()) {
            val path = matcher.group().trim()
            if (path.length >= 3 && !matches.contains(path)) {
                matches.add(path)
            }
        }
        return matches
    }

    /**
     * Разрешение относительного или абсолютного пути к файлу в память телефона.
     */
    fun resolveLocalFile(rawPath: String): File? {
        val clean = rawPath.trim().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
        val direct = File(clean)
        if (direct.exists() && direct.isFile && direct.canRead()) {
            return direct
        }

        val extStorage = Environment.getExternalStorageDirectory()
        if (clean.startsWith("/sdcard/")) {
            val sub = clean.removePrefix("/sdcard/")
            val f = File(extStorage, sub)
            if (f.exists() && f.isFile && f.canRead()) return f
        }

        if (clean.startsWith("Download/", ignoreCase = true)) {
            val sub = clean.substring("Download/".length)
            val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(d, sub)
            if (f.exists() && f.isFile && f.canRead()) return f
        }

        if (clean.startsWith("Documents/", ignoreCase = true)) {
            val sub = clean.substring("Documents/".length)
            val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val f = File(d, sub)
            if (f.exists() && f.isFile && f.canRead()) return f
        }

        // Поиск в Download по короткому имени (например "script.py")
        if (!clean.contains("/") && !clean.contains("\\")) {
            val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(d, clean)
            if (f.exists() && f.isFile && f.canRead()) return f
        }

        return null
    }

    /**
     * Универсальное чтение файла любого формата (текст, PDF, Office, изображения, архивы, бинарники).
     */
    fun readTextFile(file: File, maxChars: Int = MAX_READ_CHARS): Pair<String, Boolean> {
        return try {
            val parsed = UniversalFileParser.parseFile(null, file)
            if (parsed.content.length > maxChars) {
                val truncated = parsed.content.substring(0, maxChars) +
                    "\n\n[...файл обрезан, показаны первые $maxChars символов из ${parsed.content.length}...]"
                Pair(truncated, true)
            } else {
                Pair(parsed.content, false)
            }
        } catch (e: Exception) {
            Pair("⚠️ Ошибка чтения файла '${file.name}': ${e.message}", false)
        }
    }

    /**
     * Автоматическое обогащение запроса содержимым найденных локальных файлов любых форматов.
     */
    fun enrichPromptWithLocalFiles(prompt: String): Pair<String, List<String>> {
        val (enriched, names, _) = enrichPromptWithLocalFilesAndImages(null, prompt)
        return Pair(enriched, names)
    }

    fun enrichPromptWithLocalFilesAndImages(
        context: Context?,
        prompt: String
    ): Triple<String, List<String>, List<String>> {
        val paths = extractFilePathsFromText(prompt)
        val loadedFiles = mutableListOf<String>()
        val loadedPaths = mutableSetOf<String>()
        val imageDataUrls = mutableListOf<String>()
        val fileContexts = StringBuilder()

        for (p in paths) {
            val file = resolveLocalFile(p)
            if (file != null && loadedPaths.add(file.absolutePath)) {
                val parsed = UniversalFileParser.parseFile(context, file)
                val ext = file.extension.lowercase()
                fileContexts.append("\n\n[Файл из памяти устройства: ${file.absolutePath} (${UniversalFileParser.formatSize(file.length())})]:\n")
                if (parsed.category == AttachmentCategory.TEXT) {
                    fileContexts.append("```$ext\n")
                    fileContexts.append(parsed.content)
                    fileContexts.append("\n```\n")
                } else {
                    fileContexts.append(parsed.content)
                    fileContexts.append("\n")
                }
                if (parsed.imageBase64 != null) {
                    imageDataUrls.add("data:image/jpeg;base64,${parsed.imageBase64}")
                }
                loadedFiles.add(file.name)
            }
        }

        return if (loadedFiles.isNotEmpty()) {
            val enriched = fileContexts.toString().trim() + "\n\n" + prompt
            Triple(enriched, loadedFiles, imageDataUrls)
        } else {
            Triple(prompt, emptyList(), emptyList())
        }
    }

    /**
     * Извлечение всех блоков кода из markdown-сообщения.
     */
    fun extractCodeBlocks(markdown: String): List<ExtractedCodeBlock> {
        val blocks = mutableListOf<ExtractedCodeBlock>()
        val matcher = CODE_BLOCK_PATTERN.matcher(markdown)
        var index = 1
        while (matcher.find()) {
            val lang = matcher.group(1)?.trim()?.lowercase() ?: "code"
            val rawFilename = matcher.group(2)?.trim()
            val code = matcher.group(3) ?: ""
            if (code.isNotBlank()) {
                val suggestedName = when {
                    !rawFilename.isNullOrBlank() && rawFilename.contains(".") -> rawFilename
                    else -> generateDefaultFilename(lang, index++)
                }
                blocks.add(ExtractedCodeBlock(lang, code.trimEnd(), suggestedName))
            }
        }
        return blocks
    }

    private fun generateDefaultFilename(lang: String, index: Int): String {
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val ext = when (lang) {
            "python", "py" -> "py"
            "bash", "sh", "shell" -> "sh"
            "json" -> "json"
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "c" -> "c"
            "cpp", "c++" -> "cpp"
            "html" -> "html"
            "css" -> "css"
            "javascript", "js" -> "js"
            "typescript", "ts" -> "ts"
            "markdown", "md" -> "md"
            "sql" -> "sql"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            else -> "txt"
        }
        return if (ext == "py") "script_$time.py" else "code_${index}_$time.$ext"
    }

    /**
     * Сохранение (скачивание) сгенерированного кода в память смартфона (/sdcard/Download).
     */
    fun saveCodeToDownloads(
        context: Context,
        code: String,
        suggestedName: String? = null,
        language: String? = null
    ): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val rawName = if (!suggestedName.isNullOrBlank() && suggestedName.contains(".")) {
            suggestedName
        } else {
            generateDefaultFilename(language ?: "python", 1)
        }
        // Защита от path traversal и недопустимых символов в имени файла
        val safeName = File(rawName).name
            .replace(Regex("""[^a-zA-Z0-9._\-]"""), "_")
            .trimStart('.')
            .ifBlank { generateDefaultFilename(language ?: "python", 1) }

        val targetFile = File(downloadsDir, safeName)
        targetFile.writeText(code, Charsets.UTF_8)

        // Оповещаем систему о новом файле, чтобы он сразу появился в Downloads
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                null
            ) { path, uri ->
                Log.i(TAG, "Файл успешно просканирован медиа-сканером: $path, $uri")
            }
        } catch (_: Exception) {}

        return targetFile
    }

    /**
     * Копирование текста в системный буфер обмена.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "LLM Code") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
