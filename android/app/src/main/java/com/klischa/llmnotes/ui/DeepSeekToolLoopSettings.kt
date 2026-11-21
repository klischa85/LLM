package com.klischa.llmnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klischa.llmnotes.AgentSkill
import com.klischa.llmnotes.AgentTools
import com.klischa.llmnotes.SkillManager
import com.klischa.llmnotes.TermuxStatus
import com.klischa.llmnotes.browser.BrowserSearchEngine
import com.klischa.llmnotes.web.DeepSeekWebController

/**
 * Собирает системный префикс для tool-loop в веб-чате DeepSeek:
 * инструкции агента (если включены скиллы — плюс их блок).
 *
 * Возвращает пустую строку, если скиллы выключены и агент не нужен —
 * в этом случае цикл работает как обычный чат с инструментами.
 */
fun buildDeepSeekSystemPrefix(
    skillsEnabled: Boolean,
    skills: List<AgentSkill>,
    userPrompt: String,
    termuxStatus: TermuxStatus,
    searchEngine: BrowserSearchEngine
): String {
    if (!skillsEnabled) return ""
    val resolved = SkillManager.resolveSkillsForPrompt(
        allSkills = skills,
        userPrompt = userPrompt,
        isLocalCompact = false
    )
    val agentBlock = AgentTools.buildAgentSystemPrompt(termuxStatus, searchEngine)
    return buildString {
        if (resolved.systemBlock.isNotBlank()) {
            append(resolved.systemBlock.trim())
            append("\n\n")
        }
        append(agentBlock.trim())
    }
}

/**
 * Настройки tool-loop для веб-чата DeepSeek: тумблер, чек-листы инструментов
 * и скиллов, предупреждение о приватности.
 */
@Composable
fun DeepSeekToolLoopSettings(
    enabled: Boolean,
    enabledTools: Set<String>,
    skillsEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onToggleSkills: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showPrivacyWarning by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!enabled) {
                        showPrivacyWarning = true
                    } else {
                        onToggleEnabled(false)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🔧 Разрешить инструменты в веб-чате",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Агент сможет читать файлы, искать и запускать команды",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                if (it) showPrivacyWarning = true else onToggleEnabled(false)
            })
        }

        if (enabled) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (expanded) "▾ Свернуть настройки"
                    else "▸ Инструменты (${enabledTools.size} из ${DeepSeekWebController.ALL_TOOLS.size})",
                    fontSize = 11.sp
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    DeepSeekWebController.ALL_TOOLS.forEach { tool ->
                        val checked = tool in enabledTools
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleTool(tool, !checked) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleTool(tool, it) },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = tool, fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSkills(!skillsEnabled) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skillsEnabled,
                            onCheckedChange = { onToggleSkills(it) },
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(text = "Применять скиллы", fontSize = 11.sp)
                            Text(
                                text = "Добавит активные скиллы в каждый запрос",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            DeepSeekWebController.DEFAULT_SAFE_TOOLS.forEach { tool ->
                                onToggleTool(tool, tool !in enabledTools)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Безопасный набор", fontSize = 10.sp)
                    }
                }
            }
        }
    }

    if (showPrivacyWarning) {
        AlertDialog(
            onDismissRequest = { showPrivacyWarning = false },
            title = { Text("Включить инструменты?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Запросы к DeepSeek будут содержать результаты чтения файлов, " +
                            "поиска и команд Termux — они уйдут на серверы DeepSeek.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Расход квоты DeepSeek вырастет: каждая итерация цикла — " +
                            "это отдельное сообщение в их чате.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrivacyWarning = false
                    onToggleEnabled(true)
                }) { Text("Включить") }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyWarning = false }) { Text("Отмена") }
            },
            shape = RoundedCornerShape(14.dp)
        )
    }
}
