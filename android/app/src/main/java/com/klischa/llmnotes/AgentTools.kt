package com.klischa.llmnotes

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AgentConfirmationMode(val title: String, val subtitle: String) {
    CONFIRM_DESTRUCTIVE(
        "Подтверждать опасные",
        "Чтение, поиск и новые файлы — сразу; удаление, правка, перезапись и команды Termux — по кнопке"
    ),
    CONFIRM_ALL(
        "Подтверждать все изменения",
        "Любое создание, правка, удаление файла или команда Termux — только после подтверждения"
    ),
    AUTO(
        "Автоматически",
        "Выполнять все операции с файлами и команды Termux без пауз на подтверждение"
    )
}

data class AgentToolCall(
    val tool: String,
    val args: JSONObject,
    val rawJson: String
)

data class AgentToolResult(
    val tool: String,
    val ok: Boolean,
    val summary: String,
    val output: String
)

data class PendingToolApproval(
    val call: AgentToolCall,
    val title: String,
    val riskLabel: String,
    val targetDisplay: String,
    val detailPreview: String,
    val isHighRisk: Boolean
)

/**
 * Агентный движок работы с файлами на устройстве (/storage/emulated/0) и командами Termux.
 * Поддерживает 10 инструментов:
 *  - Безопасное чтение: list_dir, read_file, search_files, file_info
 *  - Создание/редактирование/удаление: mkdir, write_file, edit_file, move_file, delete_file
 *  - Командная строка Android: run_termux (через официальный Termux RUN_COMMAND Intent)
 */
object AgentTools {
    private const val TAG = "AgentTools"
    private const val MAX_OUTPUT_CHARS = 18_000
    private const val MAX_SEARCH_VISITED = 2_500
    private const val MAX_SEARCH_MATCHES = 80

    private val PROTECTED_SUBPATHS = listOf(
        "/Android/data",
        "/Android/obb",
        "/Android/media"
    )

    private val PROTECTED_TOP_DIRS_FROM_RECURSIVE_DELETE = setOf(
        "DCIM", "Pictures", "Download", "Documents", "Music", "Movies", "Android", "Alarms", "Notifications", "Ringtones"
    )

    private val TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*(.*?)\s*</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun storageRoot(): File {
        return try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            File("/storage/emulated/0")
        }
    }

    fun storageRootCanonical(): String {
        return try {
            storageRoot().canonicalPath
        } catch (_: Exception) {
            "/storage/emulated/0"
        }
    }

    /**
     * Формирует системные инструкции агентного режима (универсальны для всех облачных API и локальных GGUF).
     */
    fun buildAgentSystemPrompt(termuxStatus: TermuxStatus): String {
        val rootPath = storageRootCanonical()
        val termuxNote = when {
            termuxStatus.isInstalled && termuxStatus.hasRunPermission ->
                "Termux установлен и готов к выполнению bash/python/git-команд через инструмент run_termux."
            termuxStatus.isInstalled ->
                "Termux установлен, но разрешение RUN_COMMAND ещё не выдано (при вызове run_termux вернётся подсказка пользователю)."
            else ->
                "Termux не обнаружен на устройстве; для файлов используй встроенные файловые инструменты."
        }

        return """
[РЕЖИМ АГЕНТА — ДОСТУП К ФАЙЛАМ ТЕЛЕФОНА И TERMUX]
У тебя есть реальные инструменты для работы с файлами во внутренней памяти смартфона ($rootPath, псевдоним /sdcard) и запуска команд через Termux ($termuxNote).

Чтобы вызвать инструмент, выведи блок строго в таком формате (один или несколько блоков подряд):
<tool_call>
{"tool": "имя_инструмента", "args": { ... }}
</tool_call>

Доступные инструменты:
1. list_dir — список файлов и папок:
   args: {"path": "/sdcard/Download", "recursive": false, "max_entries": 100}
2. read_file — чтение файла (текст с нумерацией строк, а также PDF/DOCX/XLSX/ZIP/изображения):
   args: {"path": "/sdcard/Documents/note.txt", "offset": 1, "limit": 200}
3. search_files — поиск файлов по имени и (опционально) по содержимому:
   args: {"path": "/sdcard/Documents", "query": "отчет", "content_search": false}
4. file_info — размер, дата изменения, тип файла:
   args: {"path": "/sdcard/Download/file.txt"}
5. mkdir — создать папку (включая вложенные):
   args: {"path": "/sdcard/Documents/MyProject"}
6. write_file — создать новый файл или перезаписать существующий:
   args: {"path": "/sdcard/Documents/hello.txt", "content": "Текст файла"}
7. edit_file — точечная замена фрагмента внутри существующего файла:
   args: {"path": "/sdcard/Documents/hello.txt", "old_text": "старый текст", "new_text": "новый текст", "replace_all": false}
8. move_file — переименовать или переместить файл/папку:
   args: {"source": "/sdcard/Download/a.txt", "destination": "/sdcard/Documents/a.txt"}
9. delete_file — удалить файл или папку:
   args: {"path": "/sdcard/Download/temp.txt", "recursive": false}
10. run_termux — выполнить команду bash/python/git/cli в Termux (возвращает exitCode, stdout, stderr):
   args: {"command": "python3 --version && ls -la", "workdir": "/storage/emulated/0", "timeout_sec": 35}

Правила работы агента:
- Если пользователь просит создать, прочитать, изменить, найти или удалить файл, либо выполнить команду — СРАЗУ вызывай нужный инструмент через <tool_call>, а не просто пиши инструкцию словами.
- Для обычных файловых операций в /sdcard предпочитай встроенные инструменты (write_file, edit_file, read_file, list_dir, delete_file, move_file, mkdir, search_files) — они работают мгновенно и без лишних зависимостей.
- Используй run_termux для запуска скриптов (python, bash, node, git, pip, pkg, grep, sed, ffmpeg и т.д.) и операций внутри домашней директории Termux.
- Перед edit_file сначала прочитай файл через read_file, чтобы old_text совпал точно.
- После получения <tool_result> проанализируй результат и либо сделай следующий шаг, либо дай пользователю понятный итоговый ответ на русском языке.
- Если пользователь отклонил операцию ([Отклонено пользователем]), уважай его решение и не повторяй то же действие без новой просьбы.
        """.trimIndent()
    }

    /**
     * Извлекает все блоки `<tool_call>{...}</tool_call>` из ответа модели.
     */
    fun extractToolCalls(text: String): List<AgentToolCall> {
        val result = mutableListOf<AgentToolCall>()
        for (match in TOOL_CALL_REGEX.findAll(text)) {
            val rawInner = match.groupValues[1]
                .trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (rawInner.isEmpty()) continue
            val jsonCandidate = extractFirstJsonObject(rawInner) ?: rawInner
            try {
                val obj = JSONObject(jsonCandidate)
                val toolName = obj.optString("tool").ifBlank { obj.optString("name") }.trim().lowercase(Locale.US)
                if (toolName.isNotBlank()) {
                    val argsObj = obj.optJSONObject("args")
                        ?: obj.optJSONObject("arguments")
                        ?: obj.optJSONObject("parameters")
                        ?: JSONObject()
                    result.add(AgentToolCall(tool = toolName, args = argsObj, rawJson = jsonCandidate))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось распарсить <tool_call>: ${e.message} | raw=$rawInner")
            }
        }
        return result
    }

    /**
     * Удаляет сырые блоки `<tool_call>...</tool_call>` из видимого текста сообщения (для красивого отображения в чате).
     */
    fun stripToolCallBlocks(text: String): String {
        return TOOL_CALL_REGEX.replace(text, "").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Определяет, требуется ли подтверждение пользователя перед выполнением данного инструмента.
     */
    fun needsConfirmation(call: AgentToolCall, mode: AgentConfirmationMode): Boolean {
        if (mode == AgentConfirmationMode.AUTO) return false

        val isReadOnly = call.tool in setOf("list_dir", "read_file", "search_files", "file_info")
        if (isReadOnly) return false

        if (mode == AgentConfirmationMode.CONFIRM_ALL) return true

        // Режим CONFIRM_DESTRUCTIVE (по умолчанию):
        return when (call.tool) {
            "delete_file", "edit_file", "move_file", "run_termux" -> true
            "write_file" -> {
                // Создание нового файла — сразу; перезапись существующего — с подтверждением!
                val pathStr = call.args.optString("path", "")
                val resolved = resolveSafeSdcardPath(pathStr)
                resolved != null && resolved.exists()
            }
            "mkdir" -> false
            else -> true
        }
    }

    /**
     * Строит данные для интерактивной карточки подтверждения в чате.
     */
    fun buildApprovalPrompt(call: AgentToolCall): PendingToolApproval {
        return when (call.tool) {
            "delete_file" -> {
                val p = call.args.optString("path", "")
                val rec = call.args.optBoolean("recursive", false)
                val target = resolveSafeSdcardPath(p)
                val desc = if (target != null && target.isDirectory) {
                    "Будет удалена папка${if (rec) " вместе со всем содержимым" else ""}"
                } else {
                    val sizeNote = target?.takeIf { it.exists() }?.let { " (${UniversalFileParser.formatSize(it.length())})" } ?: ""
                    "Файл будет безвозвратно удалён$sizeNote"
                }
                PendingToolApproval(
                    call = call,
                    title = "Удаление файла",
                    riskLabel = "⚠️ Опасная операция",
                    targetDisplay = target?.absolutePath ?: p,
                    detailPreview = desc,
                    isHighRisk = true
                )
            }
            "write_file" -> {
                val p = call.args.optString("path", "")
                val content = call.args.optString("content", "")
                val target = resolveSafeSdcardPath(p)
                val exists = target != null && target.exists()
                val sizeOld = if (exists) UniversalFileParser.formatSize(target!!.length()) else ""
                val sizeNew = UniversalFileParser.formatSize(content.toByteArray(Charsets.UTF_8).size.toLong())
                val preview = content.take(280) + if (content.length > 280) "\n…" else ""
                PendingToolApproval(
                    call = call,
                    title = if (exists) "Перезапись файла" else "Создание файла",
                    riskLabel = if (exists) "⚡ Существующий файл будет перезаписан ($sizeOld → $sizeNew)" else "📝 Новый файл ($sizeNew)",
                    targetDisplay = target?.absolutePath ?: p,
                    detailPreview = preview,
                    isHighRisk = exists
                )
            }
            "edit_file" -> {
                val p = call.args.optString("path", "")
                val oldText = call.args.optString("old_text", "")
                val newText = call.args.optString("new_text", "")
                val target = resolveSafeSdcardPath(p)
                val preview = buildString {
                    append("- ${oldText.take(140)}${if (oldText.length > 140) "…" else ""}\n")
                    append("+ ${newText.take(140)}${if (newText.length > 140) "…" else ""}")
                }
                PendingToolApproval(
                    call = call,
                    title = "Редактирование файла",
                    riskLabel = "✏️ Изменение содержимого файла",
                    targetDisplay = target?.absolutePath ?: p,
                    detailPreview = preview,
                    isHighRisk = false
                )
            }
            "move_file" -> {
                val src = call.args.optString("source", "")
                val dst = call.args.optString("destination", "")
                PendingToolApproval(
                    call = call,
                    title = "Перемещение / переименование",
                    riskLabel = "📦 Перенос файла",
                    targetDisplay = "$src  →  $dst",
                    detailPreview = "Исходный файл будет перемещён по новому пути",
                    isHighRisk = false
                )
            }
            "mkdir" -> {
                val p = call.args.optString("path", "")
                PendingToolApproval(
                    call = call,
                    title = "Создание папки",
                    riskLabel = "📁 Новая директория",
                    targetDisplay = resolveSafeSdcardPath(p)?.absolutePath ?: p,
                    detailPreview = "Будет создана новая директория",
                    isHighRisk = false
                )
            }
            "run_termux" -> {
                val cmd = call.args.optString("command", "")
                val workdir = call.args.optString("workdir", storageRootCanonical()).ifBlank { storageRootCanonical() }
                PendingToolApproval(
                    call = call,
                    title = "Команда Termux",
                    riskLabel = "💻 Выполнение shell-команды (каталог: $workdir)",
                    targetDisplay = cmd,
                    detailPreview = "Команда выполнится в фоновом окружении Termux (RUN_COMMAND)",
                    isHighRisk = true
                )
            }
            else -> PendingToolApproval(
                call = call,
                title = "Действие агента: ${call.tool}",
                riskLabel = "⚙️ Инструмент",
                targetDisplay = call.rawJson.take(160),
                detailPreview = "",
                isHighRisk = true
            )
        }
    }

    /**
     * Выполняет один инструмент агента и возвращает структурированный результат.
     */
    suspend fun execute(context: Context, call: AgentToolCall): AgentToolResult {
        return try {
            when (call.tool) {
                "list_dir" -> execListDir(call.args)
                "read_file" -> execReadFile(context, call.args)
                "search_files" -> execSearchFiles(call.args)
                "file_info" -> execFileInfo(call.args)
                "mkdir" -> execMkdir(call.args)
                "write_file" -> execWriteFile(context, call.args)
                "edit_file" -> execEditFile(context, call.args)
                "move_file" -> execMoveFile(context, call.args)
                "delete_file" -> execDeleteFile(context, call.args)
                "run_termux" -> execRunTermux(context, call.args)
                else -> AgentToolResult(
                    tool = call.tool,
                    ok = false,
                    summary = "Неизвестный инструмент '${call.tool}'",
                    output = "Ошибка: инструмент '${call.tool}' не существует. Доступные: list_dir, read_file, search_files, file_info, mkdir, write_file, edit_file, move_file, delete_file, run_termux."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выполнения ${call.tool}: ${e.message}", e)
            AgentToolResult(
                tool = call.tool,
                ok = false,
                summary = "Ошибка ${call.tool}: ${e.message}",
                output = "Ошибка при выполнении ${call.tool}: ${e.message}"
            )
        }
    }

    // =========================================================================
    // Безопасное разрешение путей внутри /storage/emulated/0
    // =========================================================================

    fun resolveSafeSdcardPath(rawPath: String?): File? {
        val rootCanonical = storageRootCanonical()
        val trimmed = rawPath?.trim().orEmpty()
        val candidate = when {
            trimmed.isEmpty() || trimmed == "." || trimmed == "/" || trimmed == "/sdcard" || trimmed == "/sdcard/" ->
                File(rootCanonical)
            trimmed.startsWith("/sdcard/") ->
                File(rootCanonical, trimmed.removePrefix("/sdcard/"))
            trimmed.startsWith("~/") ->
                File(rootCanonical, trimmed.removePrefix("~/"))
            trimmed.startsWith(rootCanonical) ->
                File(trimmed)
            trimmed.startsWith("/") ->
                File(trimmed)
            else ->
                File(rootCanonical, trimmed)
        }

        val canonical = try {
            candidate.canonicalFile
        } catch (_: Exception) {
            return null
        }

        val canonicalStr = canonical.path
        if (canonicalStr != rootCanonical && !canonicalStr.startsWith("$rootCanonical/")) {
            return null
        }
        return canonical
    }

    private fun isProtectedForWrite(file: File): String? {
        val root = storageRootCanonical()
        val path = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (path == root) {
            return "Нельзя изменять или удалять корневой каталог $root."
        }
        for (sub in PROTECTED_SUBPATHS) {
            val blocked = "$root$sub"
            if (path == blocked || path.startsWith("$blocked/")) {
                return "Доступ на изменение системного каталога '$sub' заблокирован в целях безопасности."
            }
        }
        return null
    }

    private fun checkStorageWriteReady(): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return "Нет разрешения «Доступ ко всем файлам» (MANAGE_EXTERNAL_STORAGE). Выдайте его приложению LLM в настройках Android."
        }
        return null
    }

    // =========================================================================
    // Реализация 10 инструментов
    // =========================================================================

    private fun execListDir(args: JSONObject): AgentToolResult {
        val pathArg = args.optString("path", "")
        val recursive = args.optBoolean("recursive", false)
        val maxEntries = args.optInt("max_entries", 100).coerceIn(1, 300)

        val dir = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("list_dir", false, "Запрещённый путь: $pathArg", "Путь должен находиться внутри ${storageRootCanonical()}.")
        if (!dir.exists()) {
            return AgentToolResult("list_dir", false, "Папка не найдена", "Каталог '${dir.absolutePath}' не существует.")
        }
        if (!dir.isDirectory) {
            return AgentToolResult("list_dir", false, "Это не папка", "'${dir.absolutePath}' является файлом, а не папкой.")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = mutableListOf<String>()
        var truncated = false

        fun walk(current: File, relPrefix: String, depth: Int) {
            if (lines.size >= maxEntries) {
                truncated = true
                return
            }
            val children = current.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
                ?: return
            for (child in children) {
                if (lines.size >= maxEntries) {
                    truncated = true
                    return
                }
                val displayRel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
                val dateStr = sdf.format(Date(child.lastModified()))
                if (child.isDirectory) {
                    lines.add("[DIR]  $displayRel/  ($dateStr)")
                    if (recursive && depth < 4 && child.name != "Android") {
                        walk(child, displayRel, depth + 1)
                    }
                } else {
                    lines.add("[FILE] $displayRel  (${UniversalFileParser.formatSize(child.length())}, $dateStr)")
                }
            }
        }

        walk(dir, "", 0)

        val body = buildString {
            append("Содержимое ${dir.absolutePath} (элементов: ${lines.size}${if (truncated) ", показаны первые $maxEntries" else ""}):\n")
            if (lines.isEmpty()) append("(папка пуста)")
            else append(lines.joinToString("\n"))
        }.take(MAX_OUTPUT_CHARS)

        return AgentToolResult("list_dir", true, "📂 ${dir.name.ifBlank { "/sdcard" }} (${lines.size} шт.)", body)
    }

    private fun execReadFile(context: Context, args: JSONObject): AgentToolResult {
        val pathArg = args.optString("path", "")
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 250).coerceIn(1, 2000)

        val file = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("read_file", false, "Запрещённый путь: $pathArg", "Путь должен находиться внутри ${storageRootCanonical()}.")
        if (!file.exists()) {
            return AgentToolResult("read_file", false, "Файл не найден", "Файл '${file.absolutePath}' не существует.")
        }
        if (file.isDirectory) {
            return AgentToolResult("read_file", false, "Это папка", "'${file.absolutePath}' — это каталог. Используйте list_dir.")
        }

        val parsed = UniversalFileParser.parseFile(context, file)
        val output = if (parsed.category == AttachmentCategory.TEXT) {
            val allLines = parsed.content.lines()
            val startIdx = (offset - 1).coerceAtMost(allLines.size)
            val endIdx = (startIdx + limit).coerceAtMost(allLines.size)
            val numbered = (startIdx until endIdx).joinToString("\n") { idx ->
                "${idx + 1}: ${allLines[idx]}"
            }
            buildString {
                append("Файл: ${file.absolutePath} (${UniversalFileParser.formatSize(file.length())}, всего строк: ${allLines.size})\n")
                append("Строки ${startIdx + 1}..$endIdx:\n")
                append(numbered)
                if (endIdx < allLines.size) {
                    append("\n[...ещё ${allLines.size - endIdx} строк; вызовите read_file с offset=${endIdx + 1}...]")
                }
            }.take(MAX_OUTPUT_CHARS)
        } else {
            "Файл: ${file.absolutePath} (${parsed.summaryBadge})\n\n${parsed.content}".take(MAX_OUTPUT_CHARS)
        }

        return AgentToolResult("read_file", true, "📄 Прочитан ${file.name}", output)
    }

    private fun execSearchFiles(args: JSONObject): AgentToolResult {
        val pathArg = args.optString("path", "")
        val query = args.optString("query", "").trim()
        val contentSearch = args.optBoolean("content_search", false)
        if (query.isEmpty()) {
            return AgentToolResult("search_files", false, "Пустой запрос", "Параметр 'query' обязателен для search_files.")
        }

        val rootDir = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("search_files", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return AgentToolResult("search_files", false, "Папка не найдена", "Каталог '${rootDir.absolutePath}' не найден.")
        }

        val lowerQuery = query.lowercase(Locale.US)
        val matches = mutableListOf<String>()
        var visited = 0

        val queue = ArrayDeque<File>()
        queue.add(rootDir)

        while (queue.isNotEmpty() && visited < MAX_SEARCH_VISITED && matches.size < MAX_SEARCH_MATCHES) {
            val curr = queue.removeFirst()
            val children = curr.listFiles() ?: continue
            for (child in children) {
                visited++
                if (visited >= MAX_SEARCH_VISITED || matches.size >= MAX_SEARCH_MATCHES) break
                val nameLow = child.name.lowercase(Locale.US)
                if (child.isDirectory) {
                    if (child.name == "Android" && curr.canonicalPath == storageRootCanonical()) continue
                    if (!contentSearch && nameLow.contains(lowerQuery)) {
                        matches.add("[DIR]  ${child.absolutePath}")
                    }
                    queue.add(child)
                } else {
                    if (!contentSearch) {
                        if (nameLow.contains(lowerQuery)) {
                            matches.add("[FILE] ${child.absolutePath} (${UniversalFileParser.formatSize(child.length())})")
                        }
                    } else if (child.length() in 1..(512 * 1024)) {
                        try {
                            val parsed = UniversalFileParser.parseFile(null, child)
                            if (parsed.category == AttachmentCategory.TEXT) {
                                val matchingLine = parsed.content.lineSequence()
                                    .withIndex()
                                    .firstOrNull { (_, line) -> line.lowercase(Locale.US).contains(lowerQuery) }
                                if (matchingLine != null) {
                                    matches.add("${child.absolutePath}:${matchingLine.index + 1}: ${matchingLine.value.trim().take(120)}")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        val out = buildString {
            append("Поиск '$query' в ${rootDir.absolutePath} (проверено: $visited, найдено: ${matches.size}):\n")
            if (matches.isEmpty()) append("Совпадений не найдено.")
            else append(matches.joinToString("\n"))
        }.take(MAX_OUTPUT_CHARS)

        return AgentToolResult("search_files", true, "🔍 Найдено: ${matches.size} по «$query»", out)
    }

    private fun execFileInfo(args: JSONObject): AgentToolResult {
        val pathArg = args.optString("path", "")
        val file = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("file_info", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        if (!file.exists()) {
            return AgentToolResult("file_info", true, "Файл не существует", "Путь '${file.absolutePath}' не существует.")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val info = buildString {
            append("Путь: ${file.absolutePath}\n")
            append("Тип: ${if (file.isDirectory) "Папка" else "Файл"}\n")
            if (file.isFile) {
                append("Размер: ${UniversalFileParser.formatSize(file.length())} (${file.length()} Б)\n")
            } else {
                append("Элементов внутри: ${file.listFiles()?.size ?: 0}\n")
            }
            append("Изменён: ${sdf.format(Date(file.lastModified()))}\n")
            append("Права: чтение=${file.canRead()}, запись=${file.canWrite()}")
        }
        return AgentToolResult("file_info", true, "ℹ️ ${file.name}", info)
    }

    private fun execMkdir(args: JSONObject): AgentToolResult {
        checkStorageWriteReady()?.let {
            return AgentToolResult("mkdir", false, "Нет доступа к хранилищу", it)
        }
        val pathArg = args.optString("path", "")
        val dir = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("mkdir", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        isProtectedForWrite(dir)?.let {
            return AgentToolResult("mkdir", false, "Защищённый путь", it)
        }
        if (dir.exists()) {
            return if (dir.isDirectory) {
                AgentToolResult("mkdir", true, "📁 Папка уже существует", "Папка '${dir.absolutePath}' уже существует.")
            } else {
                AgentToolResult("mkdir", false, "Конфликт имени", "По пути '${dir.absolutePath}' уже существует файл.")
            }
        }
        val created = dir.mkdirs()
        return if (created) {
            AgentToolResult("mkdir", true, "📁 Создана папка ${dir.name}", "Успешно создана папка: ${dir.absolutePath}")
        } else {
            AgentToolResult("mkdir", false, "Не удалось создать папку", "Не удалось создать '${dir.absolutePath}'. Проверьте права на память.")
        }
    }

    private fun execWriteFile(context: Context, args: JSONObject): AgentToolResult {
        checkStorageWriteReady()?.let {
            return AgentToolResult("write_file", false, "Нет доступа к хранилищу", it)
        }
        val pathArg = args.optString("path", "")
        if (!args.has("content")) {
            return AgentToolResult("write_file", false, "Отсутствует content", "Параметр 'content' обязателен для write_file.")
        }
        val content = args.optString("content", "")
        val file = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("write_file", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        isProtectedForWrite(file)?.let {
            return AgentToolResult("write_file", false, "Защищённый путь", it)
        }
        if (file.isDirectory) {
            return AgentToolResult("write_file", false, "Это папка", "'${file.absolutePath}' является папкой.")
        }

        val existed = file.exists()
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        file.writeText(content, Charsets.UTF_8)
        scanMediaFile(context, file)

        val sizeStr = UniversalFileParser.formatSize(file.length())
        val verb = if (existed) "Перезаписан" else "Создан"
        return AgentToolResult(
            "write_file",
            true,
            "📝 $verb ${file.name} ($sizeStr)",
            "$verb файл: ${file.absolutePath} ($sizeStr, строк: ${content.lines().size})"
        )
    }

    private fun execEditFile(context: Context, args: JSONObject): AgentToolResult {
        checkStorageWriteReady()?.let {
            return AgentToolResult("edit_file", false, "Нет доступа к хранилищу", it)
        }
        val pathArg = args.optString("path", "")
        val oldText = args.optString("old_text", "")
        val newText = args.optString("new_text", "")
        val replaceAll = args.optBoolean("replace_all", false)

        if (oldText.isEmpty()) {
            return AgentToolResult("edit_file", false, "Пустой old_text", "Параметр 'old_text' не может быть пустым.")
        }

        val file = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("edit_file", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        isProtectedForWrite(file)?.let {
            return AgentToolResult("edit_file", false, "Защищённый путь", it)
        }
        if (!file.exists() || !file.isFile) {
            return AgentToolResult("edit_file", false, "Файл не найден", "Файл '${file.absolutePath}' не существует.")
        }

        val original = file.readText(Charsets.UTF_8)
        val occurrences = countOccurrences(original, oldText)
        if (occurrences == 0) {
            return AgentToolResult(
                "edit_file",
                false,
                "Фрагмент не найден в ${file.name}",
                "Ошибка: указанный old_text не найден в '${file.absolutePath}'. Сначала прочитайте файл через read_file."
            )
        }
        if (occurrences > 1 && !replaceAll) {
            return AgentToolResult(
                "edit_file",
                false,
                "Найдено $occurrences совпадений",
                "Ошибка: old_text встречается $occurrences раз в '${file.absolutePath}'. Уточните контекст или передайте replace_all=true."
            )
        }

        val updated = if (replaceAll) {
            original.replace(oldText, newText)
        } else {
            original.replaceFirst(oldText, newText)
        }

        file.writeText(updated, Charsets.UTF_8)
        scanMediaFile(context, file)

        val countReplaced = if (replaceAll) occurrences else 1
        return AgentToolResult(
            "edit_file",
            true,
            "✏️ Отредактирован ${file.name} (замен: $countReplaced)",
            "Файл '${file.absolutePath}' успешно обновлён (заменено фрагментов: $countReplaced, новый размер: ${UniversalFileParser.formatSize(file.length())})."
        )
    }

    private fun execMoveFile(context: Context, args: JSONObject): AgentToolResult {
        checkStorageWriteReady()?.let {
            return AgentToolResult("move_file", false, "Нет доступа к хранилищу", it)
        }
        val srcArg = args.optString("source", "")
        val dstArg = args.optString("destination", "")
        val src = resolveSafeSdcardPath(srcArg)
            ?: return AgentToolResult("move_file", false, "Запрещённый источник", "Путь source должен быть внутри ${storageRootCanonical()}.")
        val dst = resolveSafeSdcardPath(dstArg)
            ?: return AgentToolResult("move_file", false, "Запрещённое назначение", "Путь destination должен быть внутри ${storageRootCanonical()}.")

        isProtectedForWrite(src)?.let { return AgentToolResult("move_file", false, "Защищённый путь", it) }
        isProtectedForWrite(dst)?.let { return AgentToolResult("move_file", false, "Защищённый путь", it) }

        if (!src.exists()) {
            return AgentToolResult("move_file", false, "Источник не найден", "Файл/папка '${src.absolutePath}' не существует.")
        }

        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val moved = src.renameTo(dst) || run {
            if (src.isFile) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            } else {
                false
            }
        }
        if (moved) {
            scanMediaFile(context, dst)
            return AgentToolResult("move_file", true, "📦 Перемещён ${src.name} → ${dst.name}", "Успешно перемещено: ${src.absolutePath} → ${dst.absolutePath}")
        }
        return AgentToolResult("move_file", false, "Не удалось переместить", "Не удалось переместить '${src.absolutePath}' в '${dst.absolutePath}'.")
    }

    private fun execDeleteFile(context: Context, args: JSONObject): AgentToolResult {
        checkStorageWriteReady()?.let {
            return AgentToolResult("delete_file", false, "Нет доступа к хранилищу", it)
        }
        val pathArg = args.optString("path", "")
        val recursive = args.optBoolean("recursive", false)
        val target = resolveSafeSdcardPath(pathArg)
            ?: return AgentToolResult("delete_file", false, "Запрещённый путь", "Путь должен находиться внутри ${storageRootCanonical()}.")
        isProtectedForWrite(target)?.let {
            return AgentToolResult("delete_file", false, "Защищённый путь", it)
        }
        if (!target.exists()) {
            return AgentToolResult("delete_file", false, "Файл не найден", "Путь '${target.absolutePath}' не существует.")
        }

        // Дополнительная защита от случайного удаления стандартных пользовательских корневых папок (DCIM, Download, Pictures...)
        val root = storageRootCanonical()
        if (target.isDirectory && target.parentFile?.canonicalPath == root && target.name in PROTECTED_TOP_DIRS_FROM_RECURSIVE_DELETE) {
            return AgentToolResult(
                "delete_file",
                false,
                "Защита системной папки",
                "Удаление корневой пользовательской папки '${target.absolutePath}' заблокировано. Удаляйте конкретные файлы или подпапки внутри неё."
            )
        }

        val deleted = if (target.isDirectory) {
            val children = target.listFiles()
            if (!children.isNullOrEmpty() && !recursive) {
                return AgentToolResult(
                    "delete_file",
                    false,
                    "Папка не пуста",
                    "Папка '${target.absolutePath}' содержит ${children.size} элементов. Передайте recursive=true для удаления вместе с содержимым."
                )
            }
            target.deleteRecursively()
        } else {
            target.delete()
        }

        if (deleted) {
            scanMediaFile(context, target)
            return AgentToolResult("delete_file", true, "🗑️ Удалён ${target.name}", "Успешно удалено: ${target.absolutePath}")
        }
        return AgentToolResult("delete_file", false, "Не удалось удалить", "Не удалось удалить '${target.absolutePath}'.")
    }

    private suspend fun execRunTermux(context: Context, args: JSONObject): AgentToolResult {
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) {
            return AgentToolResult("run_termux", false, "Пустая команда", "Параметр 'command' обязателен для run_termux.")
        }
        val rawWorkdir = args.optString("workdir", storageRootCanonical()).trim()
        val normalizedWorkdir = when {
            rawWorkdir.isEmpty() -> storageRootCanonical()
            rawWorkdir == "/sdcard" -> storageRootCanonical()
            rawWorkdir.startsWith("/sdcard/") -> storageRootCanonical() + rawWorkdir.removePrefix("/sdcard")
            else -> rawWorkdir
        }
        val timeoutSec = args.optInt("timeout_sec", 35).coerceIn(5, 180)

        val res = TermuxBridge.runCommand(
            context = context,
            command = command,
            workdir = normalizedWorkdir,
            timeoutSeconds = timeoutSec
        )

        val output = buildString {
            append("Команда Termux: $command\n")
            append("Каталог: $normalizedWorkdir\n")
            append("Код возврата (exitCode): ${res.exitCode}\n")
            if (!res.errorMessage.isNullOrBlank()) {
                append("Ошибка Termux: ${res.errorMessage}\n")
            }
            if (res.stdout.isNotBlank()) {
                append("\nSTDOUT:\n${res.stdout.take(12_000)}")
                if (res.stdout.length > 12_000) append("\n[...stdout обрезан...]")
            }
            if (res.stderr.isNotBlank()) {
                append("\nSTDERR:\n${res.stderr.take(6_000)}")
                if (res.stderr.length > 6_000) append("\n[...stderr обрезан...]")
            }
            if (res.stdout.isBlank() && res.stderr.isBlank() && res.errorMessage.isNullOrBlank()) {
                append("\n(команда завершилась без вывода)")
            }
        }.take(MAX_OUTPUT_CHARS)

        val shortCmd = command.lineSequence().firstOrNull()?.take(36) ?: "cmd"
        val summary = if (res.success) {
            "💻 Termux OK (exit 0): $shortCmd"
        } else {
            "💻 Termux (exit ${res.exitCode}): $shortCmd"
        }
        return AgentToolResult("run_termux", res.success, summary, output)
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = text.indexOf(sub, idx)
            if (found == -1) break
            count++
            idx = found + sub.length
        }
        return count
    }

    private fun scanMediaFile(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
        } catch (_: Exception) {}
    }
}
