@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.klischa.llmnotes.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.klischa.llmnotes.AgentSkill
import com.klischa.llmnotes.LLMViewModel
import com.klischa.llmnotes.UiState

/**
 * Диалог управления скиллами (Agent Skills / SKILL.md):
 * - Вкладка «Мои»: встроенные, скачанные и пользовательские скиллы с переключателями вкл/выкл
 * - Вкладка «Получить»: загрузка SKILL.md по URL / GitHub / skills.sh, каталог GitHub и импорт .md файла
 * - Вкладка «Создать»: конструктор и редактор собственных скиллов
 */
@Composable
fun SkillsDialog(
    uiState: UiState,
    viewModel: LLMViewModel,
    onImportSkillFileClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0 = Мои, 1 = Получить, 2 = Создать/Редактировать
    var urlInput by remember { mutableStateOf("") }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }

    // Поля конструктора/редактора скилла
    var editingSkillId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftSlug by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    var draftInstructions by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Шапка
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🧩 Скиллы (Skills / SKILL.md)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Активно: ${uiState.activeSkillsCount} из ${uiState.skills.size} • Вызов в чате: /имя-скилла",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Вкладки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("🧩 Мои (${uiState.skills.size})", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("⬇️ Получить", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = {
                            if (selectedTab != 2) {
                                editingSkillId = null
                                draftName = ""
                                draftSlug = ""
                                draftDesc = ""
                                draftInstructions = ""
                            }
                            selectedTab = 2
                        },
                        label = { Text(if (editingSkillId != null) "✎ Правка" else "➕ Создать", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (uiState.skillStatusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = if (uiState.skillStatusMessage.startsWith("⚠️")) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.skillStatusMessage,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Контент выбранной вкладки
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        0 -> MySkillsTab(
                            skills = uiState.skills,
                            expandedSkillId = expandedSkillId,
                            onToggleExpand = { id ->
                                expandedSkillId = if (expandedSkillId == id) null else id
                            },
                            onToggleEnabled = { id, enabled ->
                                viewModel.toggleSkillEnabled(id, enabled)
                            },
                            onEdit = { skill ->
                                editingSkillId = skill.id
                                draftName = skill.name
                                draftSlug = skill.slug
                                draftDesc = skill.description
                                draftInstructions = skill.instructions
                                selectedTab = 2
                            },
                            onDelete = { id ->
                                viewModel.deleteSkill(id)
                            }
                        )

                        1 -> GetSkillsTab(
                            uiState = uiState,
                            urlInput = urlInput,
                            onUrlInputChange = { urlInput = it },
                            onInstallUrl = { targetUrl ->
                                viewModel.installSkillFromUrl(targetUrl) { ok, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    if (ok && targetUrl == urlInput) {
                                        urlInput = ""
                                    }
                                }
                            },
                            onImportFileClick = onImportSkillFileClick,
                            onRefreshCatalog = { viewModel.refreshOnlineSkillsCatalog() }
                        )

                        2 -> CreateSkillTab(
                            isEditing = editingSkillId != null,
                            name = draftName,
                            slug = draftSlug,
                            description = draftDesc,
                            instructions = draftInstructions,
                            onNameChange = { draftName = it },
                            onSlugChange = { draftSlug = it },
                            onDescriptionChange = { draftDesc = it },
                            onInstructionsChange = { draftInstructions = it },
                            onSave = {
                                viewModel.saveCustomSkill(
                                    existingId = editingSkillId,
                                    name = draftName,
                                    slug = draftSlug,
                                    description = draftDesc,
                                    instructions = draftInstructions
                                )
                                Toast.makeText(context, "✅ Скилл сохранён", Toast.LENGTH_SHORT).show()
                                editingSkillId = null
                                draftName = ""
                                draftSlug = ""
                                draftDesc = ""
                                draftInstructions = ""
                                selectedTab = 0
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

@Composable
private fun MySkillsTab(
    skills: List<AgentSkill>,
    expandedSkillId: String?,
    onToggleExpand: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onEdit: (AgentSkill) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "💡 Включённые скиллы автоматически подключаются к системному промпту чата и агента. Выключенный скилл можно вызвать разово командой /slug в начале сообщения.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                modifier = Modifier.padding(8.dp)
            )
        }

        skills.forEach { skill ->
            val isExpanded = expandedSkillId == skill.id
            val sourceLabel = when (skill.source) {
                "builtin" -> "Встроенный"
                "url" -> "GitHub / URL"
                "file" -> "Файл .md"
                else -> "Свой"
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleExpand(skill.id) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = sourceLabel,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "/${skill.slug}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (skill.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (isExpanded) 6 else 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { onToggleEnabled(skill.id, it) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isExpanded) "Скрыть инструкции ▲" else "Посмотреть инструкции ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onToggleExpand(skill.id) }
                                .padding(vertical = 2.dp)
                        )

                        if (skill.source != "builtin") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { onEdit(skill) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Редактировать",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDelete(skill.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Text(
                                text = skill.instructions.take(2000),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GetSkillsTab(
    uiState: UiState,
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onInstallUrl: (String) -> Unit,
    onImportFileClick: () -> Unit,
    onRefreshCatalog: () -> Unit
) {
    val installedUrls = remember(uiState.skills) {
        uiState.skills.mapNotNull { it.sourceUrl }.toSet()
    }
    val installedSlugs = remember(uiState.skills) {
        uiState.skills.map { it.slug.lowercase() }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Загрузка по URL / GitHub / skills.sh
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "🔗 Скачать SKILL.md по ссылке или с GitHub",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlInputChange,
                    placeholder = {
                        Text(
                            "https://github.com/user/repo/.../SKILL.md или owner/repo/skill",
                            fontSize = 11.sp
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onInstallUrl(urlInput) },
                        enabled = !uiState.isFetchingSkill && urlInput.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isFetchingSkill) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Загрузка...", fontSize = 12.sp)
                        } else {
                            Text("⬇️ Получить скилл", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onImportFileClick,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📂 Из .md", fontSize = 12.sp)
                    }
                }
                Text(
                    text = "Поддерживаются ссылки GitHub (blob/tree/raw), skills.sh и любые прямые .md/.txt файлы.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Онлайн-каталог GitHub (anthropics/skills)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌐 Каталог SKILL.md (${uiState.remoteSkillsCatalog.size})",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
            TextButton(
                onClick = onRefreshCatalog,
                enabled = !uiState.isFetchingSkillCatalog
            ) {
                Text(
                    text = if (uiState.isFetchingSkillCatalog) "Обновление..." else "🔄 Обновить",
                    fontSize = 11.sp
                )
            }
        }

        uiState.remoteSkillsCatalog.forEach { entry ->
            val alreadyInstalled = entry.rawUrl in installedUrls || entry.slug.lowercase() in installedSlugs
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${entry.repoLabel} • /${entry.slug}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (alreadyInstalled) {
                        OutlinedButton(
                            onClick = { onInstallUrl(entry.rawUrl) },
                            enabled = !uiState.isFetchingSkill,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("✅ Обновить", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { onInstallUrl(entry.rawUrl) },
                            enabled = !uiState.isFetchingSkill,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("⬇️ Получить", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateSkillTab(
    isEditing: Boolean,
    name: String,
    slug: String,
    description: String,
    instructions: String,
    onNameChange: (String) -> Unit,
    onSlugChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (isEditing) "Редактирование скилла" else "Создать собственный скилл",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Название скилла *") },
            placeholder = { Text("Например: Android Kotlin Эксперт") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = slug,
            onValueChange = onSlugChange,
            label = { Text("Команда (/slug, опционально)") },
            placeholder = { Text("kotlin-expert") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Краткое описание") },
            placeholder = { Text("Для чего нужен этот скилл") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = instructions,
            onValueChange = onInstructionsChange,
            label = { Text("Инструкции скилла (Markdown) *") },
            placeholder = { Text("Опишите правила, формат ответа, шаги работы агента...") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp, max = 260.dp),
            shape = RoundedCornerShape(10.dp)
        )
        Button(
            onClick = onSave,
            enabled = name.isNotBlank() && instructions.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(if (isEditing) "💾 Сохранить изменения" else "✅ Создать и включить скилл")
        }
    }
}
