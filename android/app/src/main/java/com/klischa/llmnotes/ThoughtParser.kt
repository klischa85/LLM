package com.klischa.llmnotes

data class ParsedMessage(
    val thinkingText: String? = null,
    val answerText: String = "",
    val isStillThinking: Boolean = false,
    val agentStepsText: String? = null,
    val isPreparingToolCall: Boolean = false
)

object ThoughtParser {
    private val AGENT_STEPS_REGEX = Regex(
        """<agent_steps>\s*(.*?)\s*</agent_steps>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val COMPLETE_TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*.*?\s*</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
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
     * Обрезает строку при первом обнаружении любого служебного стоп-токена диалога
     * и очищает висячие префиксы тегов (<, |, ｜) и шаблонные заполнители (|name|)
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
        // Устраняем висячие служебные префиксы тегов в самом конце текста (не повреждая Markdown-таблицы)
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
     * Разделяет шаги агента (<agent_steps>), рассуждения модели (<think>...</think>),
     * скрывает сырые блоки <tool_call> во время стриминга и возвращает чистый ответ.
     */
    fun parse(rawText: String, isStreaming: Boolean = false): ParsedMessage {
        var working = cleanStopTokens(rawText).trimStart()

        // 0. Извлекаем блок выполненных шагов агента (<agent_steps>...</agent_steps>)
        val stepsMatches = AGENT_STEPS_REGEX.findAll(working).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        val agentSteps = if (stepsMatches.isNotEmpty()) stepsMatches.joinToString("\n") else null
        if (agentSteps != null) {
            working = AGENT_STEPS_REGEX.replace(working, "").trimStart()
        }

        // Проверяем, не формирует ли модель прямо сейчас блок <tool_call>
        var isPreparingTool = false
        working = COMPLETE_TOOL_CALL_REGEX.replace(working, "").trim()
        val openToolIdx = working.indexOf("<tool_call>", ignoreCase = true)
        if (openToolIdx != -1) {
            isPreparingTool = isStreaming
            working = working.substring(0, openToolIdx).trimEnd()
        }

        // Случай 1: Присутствует закрывающий тег </think> (модель завершила рассуждения и выдала ответ)
        if (working.contains("</think>")) {
            val parts = working.split("</think>", limit = 2)
            val thought = cleanStopTokens(parts[0].replace("<think>", "").trim())
            val answer = cleanStopTokens(parts[1].trim())
            return ParsedMessage(
                thinkingText = thought.ifBlank { null },
                answerText = answer,
                isStillThinking = false,
                agentStepsText = agentSteps,
                isPreparingToolCall = isPreparingTool
            )
        }

        // Случай 2: Текст начинается с <think>, но закрывающего тега еще нет (идет процесс размышления)
        if (working.startsWith("<think>")) {
            val thought = cleanStopTokens(working.removePrefix("<think>").trim())
            return ParsedMessage(
                thinkingText = thought.ifBlank { null },
                answerText = "",
                isStillThinking = isStreaming,
                agentStepsText = agentSteps,
                isPreparingToolCall = isPreparingTool
            )
        }

        // Случай 3: Обычный ответ без рассуждений
        return ParsedMessage(
            thinkingText = null,
            answerText = working,
            isStillThinking = false,
            agentStepsText = agentSteps,
            isPreparingToolCall = isPreparingTool
        )
    }
}
