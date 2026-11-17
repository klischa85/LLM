package com.klischa.llmnotes

import org.json.JSONArray
import org.json.JSONObject

data class AgentStepLog(
    val index: Int,
    val tool: String,
    val status: String, // "ok", "error", "denied"
    val summary: String,
    val argsPreview: String = "",
    val output: String = "",
    val durationMs: Long = 0L
)

data class ParsedMessage(
    val thinkingText: String? = null,
    val answerText: String = "",
    val isStillThinking: Boolean = false,
    val agentStepsText: String? = null,
    val agentSteps: List<AgentStepLog> = emptyList(),
    val isPreparingToolCall: Boolean = false
)

object ThoughtParser {
    private val AGENT_STEPS_JSON_REGEX = Regex(
        """<agent_steps_json>\s*(.*?)\s*</agent_steps_json>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val AGENT_STEPS_LEGACY_REGEX = Regex(
        """<agent_steps>\s*(.*?)\s*</agent_steps>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val COMPLETE_THINK_REGEX = Regex(
        """<(?:think|thought|reasoning)>\s*(.*?)\s*</(?:think|thought|reasoning)>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val CLOSE_THINK_TAG_REGEX = Regex(
        """</(?:think|thought|reasoning)>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val OPEN_THINK_TAG_REGEX = Regex(
        """<(?:think|thought|reasoning)>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val COMPLETE_TOOL_CALL_REGEX = Regex(
        """(?:<tool_call>|<\|tool_call\|>|\[TOOL_CALL\])\s*.*?\s*(?:</tool_call>|<\|/tool_call\|>|\[/TOOL_CALL\])""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val FENCED_TOOL_CALL_REGEX = Regex(
        """```tool_call\s*.*?```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val OPEN_TOOL_TAG_REGEX = Regex(
        """(?:<tool_call>|<\|tool_call\|>|\[TOOL_CALL\]|```tool_call\b)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val STRAY_TAGS_REGEX = Regex(
        """</?(?:think|thought|reasoning|tool_call)>|<\|/?tool_call\|>|\[/?TOOL_CALL\]""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val TRAILING_PARTIAL_TAG_REGEX = Regex(
        """</?(?:t(?:h(?:i(?:n(?:k)?)?|o(?:u(?:g(?:h(?:t)?)?)?)?)|o(?:o(?:l(?:_(?:c(?:a(?:l(?:l)?)?)?)?)?)?)?)|r(?:e(?:a(?:s(?:o(?:n(?:i(?:n(?:g)?)?)?)?)?)?)?)?)$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    val STOP_MARKERS = listOf(
        "<|",
        "<｜",
        "<|im_end|>",
        "<|im_end|",
        "<|im_start|>",
        "<|endoftext|>",
        "<|end_of_text|>",
        "<|eot_id|>",
        "<end_of_turn>",
        "</s>",
        "<｜end of sentence｜>",
        "<｜Assistant｜>",
        "<｜User｜>",
        "[|im_end|]"
    )

    /**
     * Сериализует структурированные шаги агента для сохранения прямо внутри сообщения чата.
     */
    fun encodeAgentSteps(steps: List<AgentStepLog>): String {
        if (steps.isEmpty()) return ""
        val arr = JSONArray()
        for (step in steps) {
            arr.put(JSONObject().apply {
                put("i", step.index)
                put("t", step.tool)
                put("s", step.status)
                put("m", step.summary)
                if (step.argsPreview.isNotBlank()) put("a", step.argsPreview)
                if (step.output.isNotBlank()) put("o", step.output)
                if (step.durationMs > 0L) put("d", step.durationMs)
            })
        }
        val safeJson = arr.toString().replace("</agent_steps_json>", "<\\/agent_steps_json>")
        return "<agent_steps_json>\n$safeJson\n</agent_steps_json>\n\n"
    }

    private fun decodeAgentStepsJson(rawJson: String): List<AgentStepLog> {
        return try {
            val arr = JSONArray(rawJson.trim())
            val out = ArrayList<AgentStepLog>(arr.length())
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                out.add(
                    AgentStepLog(
                        index = obj.optInt("i", idx + 1),
                        tool = obj.optString("t", "tool"),
                        status = obj.optString("s", "ok"),
                        summary = obj.optString("m", ""),
                        argsPreview = obj.optString("a", ""),
                        output = obj.optString("o", ""),
                        durationMs = obj.optLong("d", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Обрезает строку при первом обнаружении любого служебного стоп-токена диалога
     * и очищает висячие префиксы тегов (<, |, ｜) и шаблонные заполнители (|name|).
     */
    fun cleanStopTokens(raw: String): String {
        var text = raw.replace("|name|", "LLM")
        for (marker in STOP_MARKERS) {
            val idx = text.indexOf(marker)
            if (idx != -1) {
                text = text.substring(0, idx)
            }
        }
        text = text.trimEnd()
        while (text.endsWith("<|") || text.endsWith("<｜") || text.endsWith("<")) {
            text = when {
                text.endsWith("<|") -> text.dropLast(2).trimEnd()
                text.endsWith("<｜") -> text.dropLast(2).trimEnd()
                else -> text.dropLast(1).trimEnd()
            }
        }
        return text
    }

    /**
     * Удаляет блоки вида `{"tool": ...}\n</tool_call>` (когда модель или сервер пропустили открывающий `<tool_call>`).
     */
    private fun stripOrphanClosingToolCalls(text: String): String {
        var working = text
        var safety = 0
        while (safety++ < 16) {
            val closeIdx = working.indexOf("</tool_call>", ignoreCase = true)
            if (closeIdx == -1) break
            val before = working.substring(0, closeIdx).trimEnd()
            val after = working.substring(closeIdx + "</tool_call>".length)
            val jsonStart = findTrailingJsonObjectStart(before)
            working = if (jsonStart != -1) {
                val candidate = before.substring(jsonStart)
                if (candidate.contains("\"tool\"") || candidate.contains("\"name\"")) {
                    before.substring(0, jsonStart).trimEnd() + "\n" + after.trimStart()
                } else {
                    before + after
                }
            } else {
                before + after
            }
        }
        return working
    }

    /**
     * Ищет начало сбалансированного JSON-объекта `{...}`, заканчивающегося в самом конце `text`
     * (используется для распознавания вызовов без открывающего `<tool_call>`).
     */
    internal fun findTrailingJsonObjectStart(text: String): Int {
        val trimmed = text.trimEnd().removeSuffix("```").trimEnd()
        if (!trimmed.endsWith("}")) return -1
        var depth = 0
        var inString = false
        var i = trimmed.length - 1
        while (i >= 0) {
            val c = trimmed[i]
            if (c == '"') {
                // Проверяем количество обратных слешей перед кавычкой
                var bs = 0
                var k = i - 1
                while (k >= 0 && trimmed[k] == '\\') {
                    bs++
                    k--
                }
                if (bs % 2 == 0) {
                    inString = !inString
                }
            } else if (!inString) {
                if (c == '}') depth++
                else if (c == '{') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i--
        }
        return -1
    }

    /**
     * Убирает любые остаточные служебные теги `<think>`, `</think>`, `<tool_call>`, `</tool_call>`,
     * чтобы они никогда не попадали в видимый текст чата или блок рассуждений.
     */
    fun stripResidualControlTags(text: String, isStreaming: Boolean = false): String {
        var cleaned = COMPLETE_TOOL_CALL_REGEX.replace(text, "")
        cleaned = FENCED_TOOL_CALL_REGEX.replace(cleaned, "")
        cleaned = stripOrphanClosingToolCalls(cleaned)
        val unclosedTool = OPEN_TOOL_TAG_REGEX.find(cleaned)
        if (unclosedTool != null) {
            cleaned = cleaned.substring(0, unclosedTool.range.first)
        }
        cleaned = STRAY_TAGS_REGEX.replace(cleaned, "")
        if (isStreaming) {
            cleaned = TRAILING_PARTIAL_TAG_REGEX.replace(cleaned, "")
        }
        return cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /**
     * Разделяет шаги агента (<agent_steps_json> / <agent_steps>), рассуждения модели (<think>...</think>),
     * скрывает сырые/битые блоки <tool_call> и висячие теги `</think>` / `</tool_call>`,
     * возвращая идеально чистый ответ для UI.
     */
    fun parse(rawText: String, isStreaming: Boolean = false): ParsedMessage {
        var working = rawText

        // 0. Извлекаем структурированные шаги агента ДО cleanStopTokens, чтобы вывод команд с `<|` не ломал JSON
        val structuredSteps = mutableListOf<AgentStepLog>()
        for (match in AGENT_STEPS_JSON_REGEX.findAll(working)) {
            structuredSteps.addAll(decodeAgentStepsJson(match.groupValues[1]))
        }
        if (structuredSteps.isNotEmpty()) {
            working = AGENT_STEPS_JSON_REGEX.replace(working, "")
        }

        // Обратная совместимость со старым текстовым форматом <agent_steps>...</agent_steps>
        val legacyLines = AGENT_STEPS_LEGACY_REGEX.findAll(working)
            .flatMap { it.groupValues[1].lines().asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (legacyLines.isNotEmpty()) {
            working = AGENT_STEPS_LEGACY_REGEX.replace(working, "")
        }

        val finalSteps: List<AgentStepLog> = if (structuredSteps.isNotEmpty()) {
            structuredSteps
        } else {
            legacyLines.mapIndexed { idx, line ->
                val status = when {
                    line.startsWith("❌") -> "error"
                    line.startsWith("⛔") -> "denied"
                    else -> "ok"
                }
                val cleanSummary = line
                    .removePrefix("✅").removePrefix("❌").removePrefix("⛔").trim()
                AgentStepLog(
                    index = idx + 1,
                    tool = "agent",
                    status = status,
                    summary = cleanSummary
                )
            }
        }
        val legacyStepsText = if (finalSteps.isNotEmpty()) {
            finalSteps.joinToString("\n") { step ->
                val icon = when (step.status) {
                    "ok" -> "✅"
                    "denied" -> "⛔"
                    else -> "❌"
                }
                "$icon ${step.summary}"
            }
        } else null

        // 1. Очищаем стоп-токены LLM на оставшейся части (ответе модели)
        working = cleanStopTokens(working).trimStart()

        // 2. Извлекаем ВСЕ блоки рассуждений (<think>...</think>, вложенные, множественные, с префиллом без <think> и незакрытые)
        val thoughts = mutableListOf<String>()
        var isStillThinking = false

        // 2a. Все парные блоки <think>...</think> (также <thought>, <reasoning>)
        for (match in COMPLETE_THINK_REGEX.findAll(working)) {
            val body = match.groupValues[1].trim()
            if (body.isNotBlank()) thoughts.add(body)
        }
        working = COMPLETE_THINK_REGEX.replace(working, "\n").trimStart()

        // 2b. Оставшиеся закрывающие теги </think> (например, когда шаблон модели уже подставил <think> в промпт
        //     или провайдер сгенерировал лишний </think>)
        var closeGuard = 0
        while (closeGuard++ < 8) {
            val closeMatch = CLOSE_THINK_TAG_REGEX.find(working) ?: break
            val before = working.substring(0, closeMatch.range.first).trim()
            val after = working.substring(closeMatch.range.last + 1).trimStart()
            if (before.isNotBlank()) {
                thoughts.add(before)
            }
            working = after
        }

        // 2c. Незакрытый открывающий тег <think> (идет процесс рассуждения или генерация оборвалась внутри <think>)
        val openThinkMatch = OPEN_THINK_TAG_REGEX.find(working)
        if (openThinkMatch != null) {
            val beforeOpen = working.substring(0, openThinkMatch.range.first).trimEnd()
            val insideOpen = working.substring(openThinkMatch.range.last + 1).trim()
            if (insideOpen.isNotBlank()) {
                thoughts.add(insideOpen)
            }
            isStillThinking = isStreaming
            working = beforeOpen
        }

        // 3. Скрываем завершенные и формирующиеся блоки <tool_call> из видимого ответа
        var isPreparingTool = false
        working = COMPLETE_TOOL_CALL_REGEX.replace(working, "")
        working = FENCED_TOOL_CALL_REGEX.replace(working, "")
        working = stripOrphanClosingToolCalls(working)

        val openToolMatch = OPEN_TOOL_TAG_REGEX.find(working)
        if (openToolMatch != null) {
            isPreparingTool = isStreaming
            working = working.substring(0, openToolMatch.range.first).trimEnd()
        }

        // 4. Финальная зачистка любых остаточных тегов </think> / </tool_call> в обоих полях
        val cleanedThinking = thoughts
            .map { stripResidualControlTags(cleanStopTokens(it), isStreaming = isStreaming && isStillThinking) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }

        val cleanedAnswer = stripResidualControlTags(cleanStopTokens(working), isStreaming = isStreaming)

        return ParsedMessage(
            thinkingText = cleanedThinking,
            answerText = cleanedAnswer,
            isStillThinking = isStillThinking,
            agentStepsText = legacyStepsText,
            agentSteps = finalSteps,
            isPreparingToolCall = isPreparingTool
        )
    }
}
