package com.klischa.llmnotes.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klischa.llmnotes.ModelCatalog
import com.klischa.llmnotes.ModelDownloader
import com.klischa.llmnotes.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun deviceRamGb(context: Context): Int = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    (mi.totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
} catch (e: Exception) {
    4
}

/**
 * Каталог моделей: скачивание GGUF прямо из приложения, без файловых менеджеров.
 * После успешной загрузки файл сразу передаётся в onModelReady(path).
 */
@Composable
fun ModelStoreDialog(
    onDismiss: () -> Unit,
    onModelReady: (String) -> Unit
) {
    val context = LocalContext.current
    val downloader = remember { ModelDownloader(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val ramGb = remember { deviceRamGb(context) }

    var installed by remember {
        mutableStateOf(ModelCatalog.models.associate { it.id to downloader.isInstalled(it) })
    }
    var freeBytes by remember { mutableStateOf(downloader.availableBytes()) }
    var progressMap by remember { mutableStateOf<Map<String, ModelDownloader.Progress>>(emptyMap()) }
    var activeJob by remember { mutableStateOf<Job?``>() }
    var activeId by remember { mutableStateOf<String?``>() }

    fun close() {
        activeJob?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = {
            Column {
                Text("Каталог моделей", fontWeight = FontWeight.Bold)
                Text(
                    text = "Свободно ${ModelCatalog.formatSize(freeBytes)} • ОЗУ $ramGb ГБ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ModelCatalog.models) { model ->
                    ModelCard(
                        model = model,
                        ramGb = ramGb,
                        isInstalled = installed[model.id] == true,
                        progress = progressMap[model.id],
                        isBusy = activeId == model.id,
                        onDownload = {
                            if (activeId != null) return@ModelCard
                            activeId = model.id
                            progressMap = progressMap + (
                                model.id to ModelDownloader.Progress(model.id, 0L, model.sizeBytes)
                            )
                            activeJob = scope.launch {
                                try {
                                    val file = downloader.download(model) { p ->
                                        progressMap = progressMap + (model.id to p)
                                    }
                                    installed = installed + (model.id to true)
                                    freeBytes = downloader.availableBytes()
                                    progressMap = progressMap - model.id
                                    activeId = null
                                    activeJob = null
                                    onModelReady(file.absolutePath)
                                    onDismiss()
                                } catch (c: CancellationException) {
                                    progressMap = progressMap + (
                                        model.id to ModelDownloader.Progress(model.id, error = "Загрузка отменена")
                                    )
                                    activeId = null
                                    activeJob = null
                                } catch (e: Exception) {
                                    progressMap = progressMap + (
                                        model.id to ModelDownloader.Progress(
                                            model.id,
                                            error = e.message ?: "Ошибка сети"
                                        )
                                    )
                                    activeId = null
                                    activeJob = null
                                }
                            }
                        },
                        onCancel = { activeJob?.cancel() },
                        onUse = {
                            onModelReady(downloader.targetFile(model).absolutePath)
                            onDismiss()
                        },
                        onDelete = {
                            downloader.delete(model)
                            installed = installed + (model.id to false)
                            freeBytes = downloader.availableBytes()
                            progressMap = progressMap - model.id
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { close() }) { Text("Закрыть") }
        }
    )
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    ramGb: Int,
    isInstalled: Boolean,
    progress: ModelDownloader.Progress?,
    isBusy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit
) {
    val compatible = model.minRamGb <= ramGb
    val downloading = progress != null && !progress.done && progress.error == null

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (model.recommended) {
                    Text(
                        text = "рекомендуется",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "${model.params} • ${model.quant} • ${model.sizeText} • ${model.license}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(text = model.description, style = MaterialTheme.typography.bodySmall)

            if (!compatible) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Требует ${model.minRamGb} ГБ ОЗУ, у устройства $ramGb ГБ — может не загрузиться",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (progress?.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progress.error!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (downloading) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.percent)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${(progress.percent * 100).toInt()}% • " +
                            "${ModelCatalog.formatSize(progress.bytes)} / ${ModelCatalog.formatSize(progress.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel) { Text("Отмена", fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    downloading -> { /* кнопки уже показаны в строке прогресса */ }
                    isInstalled -> {
                        Button(onClick = onUse) { Text("Загрузить", fontSize = 12.sp) }
                        TextButton(onClick = onDelete) { Text("Удалить", fontSize = 12.sp) }
                    }
                    else -> {
                        Button(onClick = onDownload, enabled = !isBusy) {
                            Text(
                                text = if (progress?.error != null) "Повторить" else "Скачать",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
