package com.klischa.llmnotes

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.klischa.llmnotes.browser.AgentBrowserEngine
import com.klischa.llmnotes.browser.BrowserSearchEngine
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AgentConfirmationMode(val title: String, val subtitle: String) {
    CONFIRM_DESTRUCTIVE(
        "Подтверждать опасные",
        "Чтение и поиск — сразу; изменения, Termux, опасные браузерные действия и MCP без readOnlyHint — по кнопке"
    ),
    CONFIRM_ALL(
        "Подтверждать все изменения",
        "Любое изменение файлов, команда Termux, клик, ввод текста или JS в браузере — только после подтверждения"
    ),
    AUTO(
        "Автоматически",
        "Операции файлов, браузера и Termux — без пауз; MCP-вызовы без readOnlyHint всё равно требуют отдельного подтверждения"
    )
}

data class AgentToolCall(
    val tool: String,
    val args: JSONObject,
    val rawJson: String
)

data class ToolCallExtraction(
    val calls: List<AgentToolCall>,
    val hadMalformedAttempt: Boolean = false,
    val malformedReason: String? = null
)

data class AgentToolResult(
    val tool: String,
    val ok: Boolean,
    val summary: String,
    val output: String,
    val imageDataUrl: String? = null
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
        """(?:<tool_call>|<\|tool_call\|>|\[TOOL_CALL\])\s*(.*?)\s*(?:</tool_call>|<\|/tool_call\|>|\[/TOOL_CALL\])""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val FENCED_TOOL_CALL_REGEX = Regex(
        """```tool_call\s*(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val THINK_BLOCK_REGEX = Regex(
        """<(?:think|thought|reasoning)>\s*.*?\s*</(?:think|thought|reasoning)>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val OPEN_TOOL_TAG_REGEX = Regex(
        """(?:<tool_call>|<\|tool_call\|>|\[TOOL_CALL\]|```tool_call\b)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val STRAY_TOOL_TAG_REGEX = Regex(
        """</?tool_call>|<\|/?tool_call\|>|\[/?TOOL_CALL\]""",
        setOf(RegexOption.IGNORE_CASE)
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
    fun buildAgentSystemPrompt(
        termuxStatus: TermuxStatus,
        searchEngine: BrowserSearchEngine = BrowserSearchEngine.DUCKDUCKGO
    ): String {
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
[РЕЖИМ АГЕНТА — ФАЙЛЫ ТЕЛЕФОНА, ВСТРОЕННЫЙ БРАУЗЕР И TERMUX]
У тебя есть реальные инструменты для работы с файлами во внутренней памяти смартфона ($rootPath, псевдоним /sdcard), встроенный интерактивный браузер (Chromium WebView с поддержкой JS, поисковик по умолчанию: ${searchEngine.displayName}) и запуск команд через Termux ($termuxNote).

Чтобы вызвать инструмент, выведи блок строго в таком формате (один или несколько блоков подряд):
<tool_call>
{"tool": "имя_инструмента", "args": { ... }}
</tool_call>

Доступные инструменты (файлы и Termux):
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

Доступные инструменты (встроенный интерактивный браузер):
11. browser_search — поиск в интернете (DuckDuckGo / Google / Яндекс):
   args: {"query": "поисковый запрос", "engine": "${searchEngine.id}", "max_results": 8}
12. browser_open — открыть веб-страницу в браузере (рендерит JS и сразу возвращает текст и пронумерованные элементы [1..N]):
   args: {"url": "https://example.com", "wait_ms": 1400}
13. browser_read — перечитать текст и список интерактивных элементов [1..N] текущей открытой страницы:
   args: {"max_chars": 8000, "include_elements": true}
14. browser_click — нажать на ссылку/кнопку по element_id (из browser_open/browser_read), CSS-селектору или тексту:
   args: {"element_id": 5} (или {"text": "Подробнее"} или {"selector": "button.next"})
15. browser_type — ввести текст в поле ввода и опционально нажать Enter:
   args: {"element_id": 2, "text": "запрос", "press_enter": true, "clear": true}
16. browser_scroll — прокрутить страницу ("down", "up", "top", "bottom"):
   args: {"direction": "down", "amount": 700}
17. browser_back — навигация по истории браузера ("back", "forward", "reload"):
   args: {"action": "back"}
18. browser_screenshot — снять скриншот открытой веб-страницы (Vision-модель увидит изображение; опционально сохранить в Download):
   args: {"save_to_downloads": false}
19. browser_eval_js — выполнить JavaScript в контексте текущей открытой страницы:
   args: {"script": "return document.title;"}

Правила работы агента:
- Если пользователь просит найти информацию в интернете, открыть сайт, проверить новости/цены/документацию или выполнить действие на сайте — СРАЗУ используй инструменты browser_search / browser_open / browser_click / browser_type / browser_read, а не отвечай «у меня нет доступа в интернет».
- После browser_open и browser_click сразу возвращаются текст и пронумерованные элементы [1..N] — используй их element_id для точных кликов и ввода.
- Если сайт требует вход в аккаунт или капчу, сообщи пользователю, что он может нажать «🌐 Браузер» в приложении, пройти проверку вручную, и затем продолжить.
- Если пользователь просит создать, прочитать, изменить, найти или удалить файл, либо выполнить команду — СРАЗУ вызывай нужный инструмент через <tool_call>.
- Перед edit_file сначала прочитай файл через read_file, чтобы old_text совпал точно.
- После получения <tool_result> проанализируй результат и либо сделай следующий шаг, либо дай пользователю понятный итоговый ответ на русском языке (со ссылками на источники, если использовался браузер).
- Если пользователь отклонил операцию ([Отклонено пользователем]), уважай его решение и не повторяй то же действие без новой просьбы.
        """.trimIndent()
    }

    /**
     * Извлекает все блоки `<tool_call>{...}</tool_call>` из ответа модели (совместимый фасад).
     */
    fun extractToolCalls(text: String): List<AgentToolCall> = extractToolCallsDetailed(text).calls

    /**
     * Устойчивый парсер вызовов инструментов агента:
     * - Стандартные `<tool_call>...</tool_call>`, `<|tool_call|>...<|/tool_call|>`, `[TOOL_CALL]...[/TOOL_CALL]`, ```tool_call```
     * - Вызовы без открывающего тега `{"tool": ...}</tool_call>` (частый артефакт Qwen3 / OpenCode / vLLM)
     * - Незакрытые `<tool_call>{...}` (если модель забыла `</tool_call>` или оборвалась на EOS)
     * - Авто-починка неэкранированных переносов строк внутри JSON-строк и висячих запятых
     * - Поддержка `arguments`/`args` в виде JSON-строки и плоских аргументов без обёртки `args`
     * - Диагностика сломанной попытки вызова (`hadMalformedAttempt`), чтобы агент мог сам повторить команду вместо остановки.
     */
    fun extractToolCallsDetailed(text: String): ToolCallExtraction {
        // 1. Сначала ищем вне блоков <think>...</think> (чтобы упоминания примеров в рассуждениях не мешали)
        val outsideThink = stripThinkBlocksForToolSearch(text)
        val outsideResult = extractFromSegment(outsideThink)
        if (outsideResult.calls.isNotEmpty()) {
            return outsideResult
        }

        // 2. Если вне <think> ничего не нашли, но модель случайно поместила рабочий <tool_call>...</tool_call> внутрь <think>
        if (text != outsideThink && (text.contains("<tool_call>", ignoreCase = true) || text.contains("</tool_call>", ignoreCase = true))) {
            val fullResult = extractFromSegment(text)
            if (fullResult.calls.isNotEmpty()) {
                return fullResult
            }
            if (fullResult.hadMalformedAttempt) {
                return fullResult
            }
        }

        return outsideResult
    }

    private fun stripThinkBlocksForToolSearch(text: String): String {
        var working = THINK_BLOCK_REGEX.replace(text, "\n")
        // Если остался одиночный </think> (шаблон модели префиллил <think>), берём часть ПОСЛЕ последнего </think>
        val closeIdx = working.lastIndexOf("</think>", ignoreCase = true)
        if (closeIdx != -1) {
            working = working.substring(closeIdx + "</think>".length)
        }
        return working
    }

    private fun extractFromSegment(segment: String): ToolCallExtraction {
        val result = mutableListOf<AgentToolCall>()
        var malformed = false
        var malformedReason: String? = null
        var remainder = segment

        // Проход 1: Полные пары <tool_call>...</tool_call> и ```tool_call ... ```
        fun collectMatches(regex: Regex) {
            for (match in regex.findAll(remainder)) {
                val rawInner = cleanFencedPayload(match.groupValues[1])
                if (rawInner.isEmpty()) continue
                val parsed = parseSingleToolJson(rawInner)
                if (parsed != null) {
                    result.add(parsed)
                } else {
                    malformed = true
                    malformedReason = "невалидный JSON внутри <tool_call>"
                    Log.w(TAG, "Не удалось распарсить <tool_call>: raw=${rawInner.take(240)}")
                }
            }
            remainder = regex.replace(remainder, "\n")
        }

        collectMatches(TOOL_CALL_REGEX)
        collectMatches(FENCED_TOOL_CALL_REGEX)

        // Проход 2: Вызовы без открывающего `<tool_call>` вида `{"tool": "...", "args": {...}}\n</tool_call>`
        var orphanGuard = 0
        while (orphanGuard++ < 12) {
            val closeIdx = remainder.indexOf("</tool_call>", ignoreCase = true)
            if (closeIdx == -1) break
            val before = remainder.substring(0, closeIdx).trimEnd()
            val after = remainder.substring(closeIdx + "</tool_call>".length)
            val jsonStart = ThoughtParser.findTrailingJsonObjectStart(before)
            if (jsonStart != -1) {
                val candidate = before.substring(jsonStart).trim()
                val parsed = parseSingleToolJson(candidate)
                if (parsed != null) {
                    result.add(parsed)
                } else {
                    malformed = true
                    malformedReason = "обнаружен </tool_call> с некорректным JSON"
                }
                remainder = before.substring(0, jsonStart) + "\n" + after
            } else {
                // Проверяем, нет ли хотя бы незавершённого JSON перед </tool_call>
                val openBrace = before.lastIndexOf('{')
                if (openBrace != -1 && (before.substring(openBrace).contains("\"tool\"") || before.substring(openBrace).contains("\"name\""))) {
                    val repaired = parseSingleToolJson(before.substring(openBrace))
                    if (repaired != null) {
                        result.add(repaired)
                    } else {
                        malformed = true
                        malformedReason = "обнаружен </tool_call> с незавершённым JSON"
                    }
                } else if (result.isEmpty()) {
                    malformed = true
                    malformedReason = "обнаружен висячий тег </tool_call> без JSON-вызова"
                }
                remainder = before + "\n" + after
            }
        }

        // Проход 3: Незакрытый открывающий `<tool_call>` в хвосте ответа
        val openMatch = OPEN_TOOL_TAG_REGEX.find(remainder)
        if (openMatch != null) {
            val tail = cleanFencedPayload(remainder.substring(openMatch.range.last + 1))
            val parsed = if (tail.isNotBlank()) parseSingleToolJson(tail) else null
            if (parsed != null) {
                result.add(parsed)
            } else {
                malformed = true
                malformedReason = "блок <tool_call> не был закрыт или оборвался на середине JSON"
            }
        }

        return ToolCallExtraction(
            calls = result,
            hadMalformedAttempt = malformed && result.isEmpty(),
            malformedReason = if (malformed && result.isEmpty()) malformedReason else null
        )
    }

    private fun cleanFencedPayload(raw: String): String {
        return raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```tool_call")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseSingleToolJson(rawInner: String): AgentToolCall? {
        val candidate = extractFirstJsonObject(rawInner) ?: rawInner.trim()
        if (!candidate.startsWith("{")) return null

        val obj = tryParseJsonObject(candidate) ?: return null
        val fnObj = obj.optJSONObject("function")
        val rawToolName = obj.optString("tool")
            .ifBlank { obj.optString("name") }
            .ifBlank { fnObj?.optString("name").orEmpty() }
            .trim()
            .lowercase(Locale.US)
        if (rawToolName.isBlank()) return null

        val toolName = when (rawToolName) {
            "bash", "shell", "termux", "terminal", "exec" -> "run_termux"
            "web_search", "search_web", "google_search", "search_internet", "internet_search" -> "browser_search"
            "open_url", "fetch_page", "visit_url", "browser_navigate", "navigate", "browse" -> "browser_open"
            "browser_get_page", "get_page", "read_page", "browser_snapshot" -> "browser_read"
            "click", "click_element" -> "browser_click"
            "type", "type_text", "fill_input", "browser_input", "input_text" -> "browser_type"
            "scroll", "scroll_page" -> "browser_scroll"
            "go_back", "browser_forward", "browser_reload", "browser_history" -> "browser_back"
            "screenshot", "take_screenshot" -> "browser_screenshot"
            "eval_js", "run_js", "execute_js" -> "browser_eval_js"
            else -> rawToolName
        }

        val rawArgsAny = obj.opt("args")
            ?: obj.opt("arguments")
            ?: obj.opt("parameters")
            ?: fnObj?.opt("arguments")
            ?: fnObj?.opt("parameters")

        val argsObj: JSONObject = when (rawArgsAny) {
            is JSONObject -> rawArgsAny
            is String -> tryParseJsonObject(rawArgsAny.trim()) ?: JSONObject()
            else -> {
                // Поддержка плоского JSON вида {"tool": "list_dir", "path": "/sdcard"} (частый формат малых GGUF-моделей)
                val flat = JSONObject()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k !in setOf("tool", "name", "type", "id", "function", "args", "arguments", "parameters")) {
                        flat.put(k, obj.get(k))
                    }
                }
                flat
            }
        }

        if (rawToolName == "browser_forward" && !argsObj.has("action")) {
            argsObj.put("action", "forward")
        } else if (rawToolName == "browser_reload" && !argsObj.has("action")) {
            argsObj.put("action", "reload")
        } else if (rawToolName == "google_search" && !argsObj.has("engine")) {
            argsObj.put("engine", "google")
        }

        return AgentToolCall(tool = toolName, args = argsObj, rawJson = candidate)
    }

    private fun tryParseJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        try {
            return JSONObject(trimmed)
        } catch (_: Exception) {}

        val repaired = repairJson(trimmed)
        try {
            return JSONObject(repaired)
        } catch (_: Exception) {}

        // Попытка закрыть 1–2 недостающие `}` в конце (если модель оборвалась в самом конце JSON)
        var padded = repaired
        repeat(2) {
            padded += "}"
            try {
                return JSONObject(padded)
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Чинит частые огрехи JSON от LLM: сырые переносы строк/табы внутри строковых литералов и висячие запятые.
     */
    private fun repairJson(raw: String): String {
        val sb = StringBuilder(raw.length + 32)
        var inString = false
        var escaped = false
        for (i in raw.indices) {
            val c = raw[i]
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> {
                        sb.append(c)
                        escaped = true
                    }
                    '"' -> {
                        sb.append(c)
                        inString = false
                    }
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            } else {
                if (c == '"') {
                    inString = true
                }
                sb.append(c)
            }
        }
        // Убираем висячие запятые перед } или ]
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    /**
     * Удаляет сырые блоки `<tool_call>...</tool_call>`, обрывки без открывающего/закрывающего тега
     * и любые висячие теги `</tool_call>` из видимого текста, НЕ затрагивая `<think>...</think>`.
     */
    fun stripToolCallBlocks(text: String): String {
        var cleaned = TOOL_CALL_REGEX.replace(text, "")
        cleaned = FENCED_TOOL_CALL_REGEX.replace(cleaned, "")
        // Убираем блоки `{"tool": ...}</tool_call>` без открывающего тега
        var guard = 0
        while (guard++ < 12) {
            val closeIdx = cleaned.indexOf("</tool_call>", ignoreCase = true)
            if (closeIdx == -1) break
            val before = cleaned.substring(0, closeIdx).trimEnd()
            val after = cleaned.substring(closeIdx + "</tool_call>".length)
            val jsonStart = ThoughtParser.findTrailingJsonObjectStart(before)
            cleaned = if (jsonStart != -1 && (before.substring(jsonStart).contains("\"tool\"") || before.substring(jsonStart).contains("\"name\""))) {
                before.substring(0, jsonStart).trimEnd() + "\n" + after.trimStart()
            } else {
                before + after
            }
        }
        val unclosed = OPEN_TOOL_TAG_REGEX.find(cleaned)
        if (unclosed != null) {
            cleaned = cleaned.substring(0, unclosed.range.first)
        }
        cleaned = STRAY_TOOL_TAG_REGEX.replace(cleaned, "")
        return cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /**
     * Формирует чистую реплику ассистента для истории следующего шага агента:
     * полностью удаляет `<think>...</think>` (которые ломают DeepSeek-R1 / Qwen3 в многошаговой истории)
     * и оставляет канонические блоки `<tool_call>...</tool_call>`.
     */
    fun buildAssistantTurnForHistory(stepRaw: String, calls: List<AgentToolCall>): String {
        val visibleText = ThoughtParser.parse(stepRaw, isStreaming = false).answerText
        val canonicalCalls = calls.joinToString("\n") { call ->
            val obj = JSONObject().apply {
                put("tool", call.tool)
                put("args", call.args)
            }
            "<tool_call>\n$obj\n</tool_call>"
        }
        return listOf(visibleText, canonicalCalls)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { canonicalCalls }
    }

    /**
     * Читаемое представление параметров вызова инструмента для разворачиваемой карточки действий агента.
     */
    fun formatArgsForDisplay(call: AgentToolCall): String {
        val a = call.args
        return when (call.tool) {
            "list_dir" -> buildString {
                append("Путь: ${a.optString("path", "/sdcard").ifBlank { "/sdcard" }}")
                if (a.optBoolean("recursive", false)) append("\nРекурсивно: да")
            }
            "read_file" -> buildString {
                append("Файл: ${a.optString("path", "")}")
                val offset = a.optInt("offset", 1)
                val limit = a.optInt("limit", 250)
                append("\nСтроки: с $offset (лимит $limit)")
            }
            "search_files" -> buildString {
                append("Папка: ${a.optString("path", "/sdcard").ifBlank { "/sdcard" }}")
                append("\nЗапрос: ${a.optString("query", "")}")
                if (a.optBoolean("content_search", false)) append("\nПоиск по содержимому: да")
            }
            "file_info", "mkdir" -> "Путь: ${a.optString("path", "")}"
            "write_file" -> {
                val p = a.optString("path", "")
                val content = a.optString("content", "")
                val sizeStr = UniversalFileParser.formatSize(content.toByteArray(Charsets.UTF_8).size.toLong())
                buildString {
                    append("Файл: $p ($sizeStr)\n")
                    append(content.take(1500))
                    if (content.length > 1500) append("\n… (ещё ${content.length - 1500} симв.)")
                }
            }
            "edit_file" -> {
                val p = a.optString("path", "")
                val oldText = a.optString("old_text", "")
                val newText = a.optString("new_text", "")
                buildString {
                    append("Файл: $p\n")
                    append("--- Было:\n${oldText.take(700)}${if (oldText.length > 700) "…" else ""}\n")
                    append("+++ Стало:\n${newText.take(700)}${if (newText.length > 700) "…" else ""}")
                }
            }
            "move_file" -> "Откуда: ${a.optString("source", "")}\nКуда: ${a.optString("destination", "")}"
            "delete_file" -> buildString {
                append("Путь: ${a.optString("path", "")}")
                if (a.optBoolean("recursive", false)) append("\nРекурсивно: да")
            }
            "run_termux" -> {
                val cmd = a.optString("command", "")
                val workdir = a.optString("workdir", storageRootCanonical()).ifBlank { storageRootCanonical() }
                "Каталог: $workdir\n$ $cmd"
            }
            "browser_search" -> buildString {
                append("Запрос: ${a.optString("query", "")}")
                val eng = a.optString("engine", "").ifBlank { AgentBrowserEngine.state.value.searchEngine.displayName }
                append("\nПоисковик: $eng")
            }
            "browser_open" -> "URL: ${a.optString("url", "")}"
            "browser_read" -> "Текущий URL: ${AgentBrowserEngine.state.value.currentUrl.ifBlank { "(не открыт)" }}"
            "browser_click" -> buildString {
                if (a.has("element_id")) append("Элемент: [${a.optInt("element_id", -1)}]")
                val t = a.optString("text", "")
                val s = a.optString("selector", "")
                if (t.isNotBlank()) append("${if (isNotEmpty()) "\n" else ""}Текст: $t")
                if (s.isNotBlank()) append("${if (isNotEmpty()) "\n" else ""}Селектор: $s")
            }
            "browser_type" -> buildString {
                if (a.has("element_id")) append("Элемент: [${a.optInt("element_id", -1)}]\n")
                append("Текст: ${a.optString("text", "").take(200)}")
                if (a.optBoolean("press_enter", false)) append("\nОтправка: Enter")
            }
            "browser_scroll" -> "Направление: ${a.optString("direction", "down")} (${a.optInt("amount", 700)} px)"
            "browser_back" -> "Действие: ${a.optString("action", "back")}"
            "browser_screenshot" -> "Сохранить в Download: ${if (a.optBoolean("save_to_downloads", false)) "да" else "нет"}"
            "browser_eval_js" -> "JS:\n${a.optString("script", "").take(1000)}"
            else -> try {
                a.toString(2).take(1200)
            } catch (_: Exception) {
                call.rawJson.take(1200)
            }
        }
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
        // MCP has its explicit policy: declared read-only tools run directly; every other tool asks first,
        // including when the general-purpose agent policy is AUTO.
        if (call.tool == "mcp_call") {
            if (mode == AgentConfirmationMode.CONFIRM_ALL) return true
            val serverId = call.args.optString("server_id")
            val toolName = call.args.optString("tool")
            return !McpManager.isReadOnly(serverId, toolName)
        }
        if (mode == AgentConfirmationMode.AUTO) return false

        val isReadOnly = call.tool in setOf(
            "list_dir", "read_file", "search_files", "file_info",
            "browser_search", "browser_open", "browser_read", "browser_scroll", "browser_back", "browser_screenshot"
        )
        if (isReadOnly) return false

        if (mode == AgentConfirmationMode.CONFIRM_ALL) return true

        // Режим CONFIRM_DESTRUCTIVE (по умолчанию):
        return when (call.tool) {
            "delete_file", "edit_file", "move_file", "run_termux" -> true
            "browser_eval_js", "browser_type" -> true
            "browser_click" -> {
                val elemId = if (call.args.has("element_id")) call.args.optInt("element_id", -1).takeIf { it > 0 } else null
                val sel = call.args.optString("selector", "").ifBlank { null }
                val txt = call.args.optString("text", "").ifBlank { null }
                AgentBrowserEngine.analyzeClickSensitivity(elemId, sel, txt).first
            }
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
     * Строит данные для интерактивной карточки подтверждения в чате (с полным предпросмотром для разворачивания).
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
                val preview = content.take(2000) + if (content.length > 2000) "\n… (ещё ${content.length - 2000} симв.)" else ""
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
                    append("- ${oldText.take(800)}${if (oldText.length > 800) "…" else ""}\n")
                    append("+ ${newText.take(800)}${if (newText.length > 800) "…" else ""}")
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
            "mcp_call" -> {
                val serverId = call.args.optString("server_id", "")
                val toolName = call.args.optString("tool", "")
                val args = call.args.optJSONObject("arguments") ?: JSONObject()
                PendingToolApproval(
                    call = call,
                    title = "Вызов MCP-инструмента",
                    riskLabel = if (McpManager.isReadOnly(serverId, toolName)) "🔎 MCP: чтение" else "⚠️ MCP: изменение данных — требуется подтверждение",
                    targetDisplay = "$serverId / $toolName",
                    detailPreview = args.toString(2).take(1800),
                    isHighRisk = !McpManager.isReadOnly(serverId, toolName)
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
            "browser_click" -> {
                val elemId = if (call.args.has("element_id")) call.args.optInt("element_id", -1).takeIf { it > 0 } else null
                val sel = call.args.optString("selector", "").ifBlank { null }
                val txt = call.args.optString("text", "").ifBlank { null }
                val (sensitive, desc) = AgentBrowserEngine.analyzeClickSensitivity(elemId, sel, txt)
                val pageUrl = AgentBrowserEngine.state.value.currentUrl.ifBlank { "(страница не определена)" }
                PendingToolApproval(
                    call = call,
                    title = "Клик в браузере",
                    riskLabel = if (sensitive) "⚠️ Потенциально важное действие на сайте" else "🌐 Нажатие элемента в браузере",
                    targetDisplay = desc,
                    detailPreview = "Сайт: $pageUrl\nВы можете открыть «🌐 Браузер», чтобы проверить страницу визуально.",
                    isHighRisk = sensitive
                )
            }
            "browser_type" -> {
                val elemId = if (call.args.has("element_id")) call.args.optInt("element_id", -1).takeIf { it > 0 } else null
                val text = call.args.optString("text", "")
                val enter = call.args.optBoolean("press_enter", false)
                val pageUrl = AgentBrowserEngine.state.value.currentUrl.ifBlank { "(страница не определена)" }
                val elemInfo = elemId?.let { AgentBrowserEngine.getElementInfo(it) }
                val targetName = elemInfo?.let { "[${it.id}] ${it.label.ifBlank { it.tag }}" }
                    ?: call.args.optString("selector", "").ifBlank { "активное поле ввода" }
                PendingToolApproval(
                    call = call,
                    title = "Ввод текста в браузере",
                    riskLabel = if (enter) "⌨️ Ввод текста и отправка (Enter)" else "⌨️ Заполнение поля на сайте",
                    targetDisplay = "$targetName ← \"${text.take(120)}\"",
                    detailPreview = "Сайт: $pageUrl\nПолный текст:\n${text.take(1000)}",
                    isHighRisk = enter
                )
            }
            "browser_eval_js" -> {
                val script = call.args.optString("script", "")
                val pageUrl = AgentBrowserEngine.state.value.currentUrl.ifBlank { "(страница не определена)" }
                PendingToolApproval(
                    call = call,
                    title = "JavaScript в браузере",
                    riskLabel = "⚡ Выполнение JS-кода на странице",
                    targetDisplay = pageUrl,
                    detailPreview = script.take(1500),
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
                "mcp_call" -> execMcpCall(context, call.args)
                "browser_search" -> execBrowserSearch(context, call.args)
                "browser_open" -> execBrowserOpen(context, call.args)
                "browser_read" -> execBrowserRead(context, call.args)
                "browser_click" -> execBrowserClick(context, call.args)
                "browser_type" -> execBrowserType(context, call.args)
                "browser_scroll" -> execBrowserScroll(context, call.args)
                "browser_back" -> execBrowserBack(context, call.args)
                "browser_screenshot" -> execBrowserScreenshot(context, call.args)
                "browser_eval_js" -> execBrowserEvalJs(context, call.args)
                else -> AgentToolResult(
                    tool = call.tool,
                    ok = false,
                    summary = "Неизвестный инструмент '${call.tool}'",
                    output = "Ошибка: инструмент '${call.tool}' не существует. Доступные: list_dir, read_file, search_files, file_info, mkdir, write_file, edit_file, move_file, delete_file, run_termux, browser_search, browser_open, browser_read, browser_click, browser_type, browser_scroll, browser_back, browser_screenshot, browser_eval_js, mcp_call."
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

    private suspend fun execMcpCall(context: Context, args: JSONObject): AgentToolResult {
        val serverId = args.optString("server_id", "").trim()
        val toolName = args.optString("tool", "").trim()
        if (serverId.isBlank() || toolName.isBlank()) {
            return AgentToolResult("mcp_call", false, "MCP: не заданы сервер или инструмент", "Нужны аргументы server_id, tool и arguments.")
        }
        val call = McpManager.callTool(context, serverId, toolName, args.optJSONObject("arguments") ?: JSONObject())
        return AgentToolResult("mcp_call", call.ok, "MCP ${if (call.ok) "выполнено" else "ошибка"}: $toolName", call.text)
    }

    // =========================================================================
    // Инструменты встроенного браузера агента (WebView + Multi-Search)
    // =========================================================================

    private suspend fun execBrowserSearch(context: Context, args: JSONObject): AgentToolResult {
        val query = args.optString("query", "").ifBlank { args.optString("q", "") }.trim()
        val engine = args.optString("engine", "").ifBlank { null }
        val maxResults = args.optInt("max_results", 8)
        val res = AgentBrowserEngine.search(context, query, engine, maxResults)
        return AgentToolResult("browser_search", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserOpen(context: Context, args: JSONObject): AgentToolResult {
        val url = args.optString("url", "").ifBlank { args.optString("href", "") }.trim()
        val waitMs = args.optLong("wait_ms", 1400L)
        val res = AgentBrowserEngine.openUrl(context, url, waitMs)
        return AgentToolResult("browser_open", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserRead(context: Context, args: JSONObject): AgentToolResult {
        val maxChars = args.optInt("max_chars", 8000)
        val includeElements = args.optBoolean("include_elements", true)
        val res = AgentBrowserEngine.readPage(context, maxChars, includeElements)
        return AgentToolResult("browser_read", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserClick(context: Context, args: JSONObject): AgentToolResult {
        val elemId = if (args.has("element_id")) {
            args.optInt("element_id", -1).takeIf { it > 0 }
        } else if (args.has("id")) {
            args.optInt("id", -1).takeIf { it > 0 }
        } else null
        val selector = args.optString("selector", "").ifBlank { null }
        val text = args.optString("text", "").ifBlank { null }
        val res = AgentBrowserEngine.clickElement(context, elemId, selector, text)
        return AgentToolResult("browser_click", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserType(context: Context, args: JSONObject): AgentToolResult {
        val elemId = if (args.has("element_id")) {
            args.optInt("element_id", -1).takeIf { it > 0 }
        } else if (args.has("id")) {
            args.optInt("id", -1).takeIf { it > 0 }
        } else null
        val selector = args.optString("selector", "").ifBlank { null }
        val text = args.optString("text", "").ifBlank { args.optString("value", "") }
        val pressEnter = args.optBoolean("press_enter", false)
        val clear = args.optBoolean("clear", true)
        val res = AgentBrowserEngine.typeText(context, elemId, selector, text, pressEnter, clear)
        return AgentToolResult("browser_type", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserScroll(context: Context, args: JSONObject): AgentToolResult {
        val direction = args.optString("direction", "down")
        val amount = args.optInt("amount", 700)
        val res = AgentBrowserEngine.scrollPage(context, direction, amount)
        return AgentToolResult("browser_scroll", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserBack(context: Context, args: JSONObject): AgentToolResult {
        val action = args.optString("action", "back")
        val res = AgentBrowserEngine.navigateHistory(context, action)
        return AgentToolResult("browser_back", res.ok, res.summary, res.output)
    }

    private suspend fun execBrowserScreenshot(context: Context, args: JSONObject): AgentToolResult {
        val save = args.optBoolean("save_to_downloads", false)
        val res = AgentBrowserEngine.takeScreenshot(context, save)
        return AgentToolResult(
            tool = "browser_screenshot",
            ok = res.ok,
            summary = res.summary,
            output = res.output,
            imageDataUrl = res.screenshotDataUrl
        )
    }

    private suspend fun execBrowserEvalJs(context: Context, args: JSONObject): AgentToolResult {
        val script = args.optString("script", "").ifBlank { args.optString("code", "") }
        val res = AgentBrowserEngine.evalJs(context, script)
        return AgentToolResult("browser_eval_js", res.ok, res.summary, res.output)
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
