package com.klischa.llmnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.klischa.llmnotes.McpManager
import com.klischa.llmnotes.McpServer
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun McpSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(McpManager.listServers(context)) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var workdir by remember { mutableStateOf("/storage/emulated/0") }
    var transport by remember { mutableStateOf("http") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("🔌 Подключение MCP", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Удалённые MCP по HTTPS/Streamable HTTP или локальные stdio-серверы через Termux. Bearer-токен шифруется Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = transport == "http", onClick = { transport = "http" }, label = { Text("HTTP") })
                    FilterChip(selected = transport == "stdio", onClick = { transport = "stdio" }, label = { Text("Termux stdio") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Имя сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (transport == "http") {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("MCP URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(token, { token = it }, label = { Text("Bearer-токен (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                } else {
                    OutlinedTextField(command, { command = it }, label = { Text("Команда Termux (например: node ~/mcp/server.js)") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3)
                    OutlinedTextField(workdir, { workdir = it }, label = { Text("Рабочая папка Termux") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Termux должен быть установлен и иметь разрешение RUN_COMMAND. Команда запускается для каждого MCP-запроса; сервер должен поддерживать JSON-RPC stdio.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Button(
                    onClick = {
                        val valid = name.isNotBlank() && if (transport == "http") endpoint.startsWith("https://") || endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost") else command.isNotBlank()
                        if (!valid) { status = "Проверьте имя и URL/команду сервера."; return@Button }
                        val server = McpServer(UUID.randomUUID().toString().take(8), name.trim(), transport, endpoint.trim(), command.trim(), workdir.trim())
                        McpManager.saveServer(context, server, token)
                        servers = McpManager.listServers(context)
                        name = ""; endpoint = ""; token = ""; command = ""
                        status = "Сервер сохранён. Нажмите «Проверить» для запроса tools/list."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text("Добавить сервер") }

                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("Сохранённые серверы (${servers.size})", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(servers, key = { it.id }) { server ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(server.name, style = MaterialTheme.typography.bodyMedium)
                                Text(if (server.transport == "http") server.endpoint else "Termux • ${server.command}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        busy = true
                                        status = "Подключение и обнаружение инструментов…"
                                        scope.launch {
                                            val tools = McpManager.listTools(context).filter { it.server.id == server.id }
                                            status = if (tools.isEmpty()) "Инструменты не найдены — проверьте подключение и логи сервера." else "✅ ${tools.size} инструментов: ${tools.joinToString { it.name }}"
                                            busy = false
                                        }
                                    }, enabled = !busy) { Text("Проверить", fontSize = 12.sp) }
                                    TextButton(onClick = {
                                        McpManager.deleteServer(context, server.id)
                                        servers = McpManager.listServers(context)
                                        status = "Сервер удалён"
                                    }) { Text("Удалить", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                Text("Инструменты MCP доступны агенту. Read-only вызовы выполняются сразу; инструменты без readOnlyHint запрашивают подтверждение.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Готово") }
                }
            }
        }
    }
}
