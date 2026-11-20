package com.klischa.llmnotes

import android.content.Context
import android.util.Base64
import android.util.Log
import com.klischa.llmnotes.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** MCP configuration and client for remote Streamable HTTP and Termux stdio servers. */
data class McpServer(
    val id: String,
    val name: String,
    val transport: String, // "http" or "stdio"
    val endpoint: String = "",
    val command: String = "",
    val workdir: String = "/storage/emulated/0",
    val enabled: Boolean = true
)

data class McpTool(
    val server: McpServer,
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean = false
)

data class McpCallResult(val ok: Boolean, val text: String)

object McpManager {
    private const val PREFS = "mcp_servers_v1"
    private const val LIST_KEY = "server_list"
    private const val TOOLS_KEY = "cached_tools"
    private const val TAG = "McpManager"
    private const val PROTOCOL_VERSION = "2025-03-26"
    @Volatile private var cachedTools: List<McpTool> = emptyList()

    fun getCachedTools(context: Context): List<McpTool> {
        if (cachedTools.isNotEmpty()) return cachedTools
        val servers = listServers(context).associateBy { it.id }
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOOLS_KEY, "[]") ?: "[]"
        val loaded = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val server = servers[obj.optString("serverId")] ?: return@mapNotNull null
                McpTool(server, obj.optString("name"), obj.optString("description"), obj.optJSONObject("schema") ?: JSONObject().put("type", "object"), obj.optBoolean("readOnly"))
            }
        } catch (_: Exception) { emptyList() }
        cachedTools = loaded
        return loaded
    }

    private fun persistTools(context: Context, tools: List<McpTool>) {
        val arr = JSONArray()
        tools.forEach { t -> arr.put(JSONObject().put("serverId", t.server.id).put("name", t.name).put("description", t.description).put("schema", t.inputSchema).put("readOnly", t.readOnly)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOOLS_KEY, arr.toString()).apply()
    }

    fun isReadOnly(serverId: String, toolName: String): Boolean =
        cachedTools.firstOrNull { it.server.id == serverId && it.name == toolName }?.readOnly == true

    fun promptForTools(tools: List<McpTool>): String {
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\n[MCP TOOLS — Model Context Protocol]\n")
            append("Вызов MCP-инструмента: <tool_call>{\"tool\":\"mcp_call\",\"args\":{\"server_id\":\"ID\",\"tool\":\"TOOL_NAME\",\"arguments\":{...}}}</tool_call>.\n")
            append("Подключённые инструменты (read-only пометка сервера не отменяет подтверждений в приложении):\n")
            tools.forEach { t ->
                append("- server_id=${t.server.id}; server=${t.server.name}; tool=${t.name}; read_only=${t.readOnly}; description=${t.description.take(400)}; schema=${t.inputSchema.toString().take(900)}\n")
            }
        }
    }

    fun listServers(context: Context): List<McpServer> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LIST_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                McpServer(
                    id = o.optString("id"), name = o.optString("name"), transport = o.optString("transport"),
                    endpoint = o.optString("endpoint"), command = o.optString("command"),
                    workdir = o.optString("workdir", "/storage/emulated/0"), enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveServer(context: Context, server: McpServer, token: String = "") {
        val app = context.applicationContext
        val previousTools = getCachedTools(app)
        val all = listServers(app).filterNot { it.id == server.id } + server
        val arr = JSONArray()
        all.forEach { s -> arr.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("transport", s.transport)
            put("endpoint", s.endpoint); put("command", s.command); put("workdir", s.workdir); put("enabled", s.enabled)
        }) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LIST_KEY, arr.toString()).apply()
        cachedTools = previousTools.filterNot { it.server.id == server.id }
        persistTools(app, cachedTools)
        SecureKeyStore.saveSecret(app, tokenKey(server.id), token)
    }

    fun deleteServer(context: Context, id: String) {
        val app = context.applicationContext
        val previousTools = getCachedTools(app)
        val arr = JSONArray()
        listServers(app).filterNot { it.id == id }.forEach { s -> arr.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("transport", s.transport); put("endpoint", s.endpoint)
            put("command", s.command); put("workdir", s.workdir); put("enabled", s.enabled)
        }) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LIST_KEY, arr.toString()).apply()
        cachedTools = previousTools.filterNot { it.server.id == id }
        persistTools(app, cachedTools)
        SecureKeyStore.saveSecret(app, tokenKey(id), "")
    }

    private fun tokenKey(id: String) = "mcp_token_$id"
    private fun token(context: Context, id: String) = SecureKeyStore.getSecret(context, tokenKey(id))

    suspend fun listTools(context: Context): List<McpTool> = withContext(Dispatchers.IO) {
        val found = mutableListOf<McpTool>()
        for (server in listServers(context).filter { it.enabled }) {
            try {
                val result = if (server.transport == "stdio") callStdio(context, server, JSONObject().put("method", "tools/list").put("params", JSONObject()))
                else callHttp(server, token(context, server.id), JSONObject().put("method", "tools/list").put("params", JSONObject()))
                val arr = result.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    found += McpTool(
                        server, obj.optString("name"), obj.optString("description"),
                        obj.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                        obj.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MCP tools/list failed for ${server.name}: ${e.message}")
            }
        }
        cachedTools = found
        persistTools(context, found)
        found
    }

    suspend fun callTool(context: Context, serverId: String, toolName: String, arguments: JSONObject): McpCallResult = withContext(Dispatchers.IO) {
        val server = listServers(context).firstOrNull { it.id == serverId && it.enabled }
            ?: return@withContext McpCallResult(false, "MCP-сервер недоступен или отключён")
        try {
            val req = JSONObject().put("method", "tools/call").put("params", JSONObject().put("name", toolName).put("arguments", arguments))
            val result = if (server.transport == "stdio") callStdio(context, server, req) else callHttp(server, token(context, server.id), req)
            val data = result.optJSONObject("result") ?: JSONObject()
            val content = data.optJSONArray("content") ?: JSONArray()
            val text = buildString {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    if (item.optString("type") == "text") append(item.optString("text")).append('\n')
                }
                if (data.optBoolean("isError")) insert(0, "MCP tool returned an error: ")
            }.trim().ifBlank { data.toString() }
            McpCallResult(!data.optBoolean("isError"), text.take(18_000))
        } catch (e: Exception) {
            McpCallResult(false, "MCP call failed: ${e.message}")
        }
    }

    private fun request(method: String, params: JSONObject = JSONObject(), id: Int = 1) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    private fun callHttp(server: McpServer, token: String, operation: JSONObject): JSONObject {
        val endpoint = server.endpoint.trim()
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost")) {
            "MCP endpoint must use HTTPS (or loopback HTTP)"
        }
        val session = initializeHttp(endpoint, token)
        val req = URL(endpoint).openConnection() as HttpURLConnection
        req.requestMethod = "POST"; req.connectTimeout = 12_000; req.readTimeout = 45_000; req.doOutput = true
        req.setRequestProperty("Content-Type", "application/json")
        req.setRequestProperty("Accept", "application/json, text/event-stream")
        req.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
        if (session.isNotBlank()) req.setRequestProperty("Mcp-Session-Id", session)
        if (token.isNotBlank()) req.setRequestProperty("Authorization", "Bearer $token")
        req.outputStream.use { it.write(request(operation.getString("method"), operation.optJSONObject("params") ?: JSONObject(), 3).toString().toByteArray()) }
        return parseResponse(req)
    }

    private fun initializeHttp(endpoint: String, token: String): String {
        val req = URL(endpoint).openConnection() as HttpURLConnection
        req.requestMethod = "POST"; req.connectTimeout = 12_000; req.readTimeout = 20_000; req.doOutput = true
        req.setRequestProperty("Content-Type", "application/json")
        req.setRequestProperty("Accept", "application/json, text/event-stream")
        if (token.isNotBlank()) req.setRequestProperty("Authorization", "Bearer $token")
        req.outputStream.use {
            it.write(request("initialize", JSONObject().put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "LLM Notes").put("version", "1.4.5"))).toString().toByteArray())
        }
        val response = parseResponse(req)
        val session = req.getHeaderField("Mcp-Session-Id").orEmpty()
        val notify = URL(endpoint).openConnection() as HttpURLConnection
        notify.requestMethod = "POST"; notify.connectTimeout = 10_000; notify.readTimeout = 10_000; notify.doOutput = true
        notify.setRequestProperty("Content-Type", "application/json"); notify.setRequestProperty("Accept", "application/json, text/event-stream")
        notify.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
        if (session.isNotBlank()) notify.setRequestProperty("Mcp-Session-Id", session)
        if (token.isNotBlank()) notify.setRequestProperty("Authorization", "Bearer $token")
        notify.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString().toByteArray()) }
        try { notify.inputStream.close() } catch (_: Exception) {}
        if (response.has("error")) error(response.getJSONObject("error").optString("message", "MCP initialize failed"))
        return session
    }

    private fun parseResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("MCP HTTP $code: ${body.take(500)}")
        val jsonText = if (body.trimStart().startsWith("data:" ) || body.contains("\ndata:")) {
            body.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull().orEmpty()
        } else body
        return JSONObject(jsonText)
    }

    private suspend fun callStdio(context: Context, server: McpServer, operation: JSONObject): JSONObject {
        val (method, params) = operation.getString("method") to (operation.optJSONObject("params") ?: JSONObject())
        val init = request("initialize", JSONObject().put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "LLM Notes").put("version", "1.4.5")), 1)
        val notify = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        val actual = request(method, params, 2)
        val stdin = listOf(init, notify, actual).joinToString("\n") { it.toString() } + "\n"
        val encoded = Base64.encodeToString(stdin.toByteArray(), Base64.NO_WRAP)
        // The configured command is user-owned in Termux; JSON input is base64 encoded before piping to stdin.
        val shell = "printf '%s' '$encoded' | base64 -d | ${server.command}"
        val result = TermuxBridge.runCommand(context, shell, server.workdir, 90)
        if (!result.success) error(result.errorMessage ?: result.stderr.ifBlank { "Termux MCP command failed (${result.exitCode})" })
        val lines = result.stdout.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) null else try { JSONObject(trimmed) } catch (_: Exception) { null }
        }.toList()
        val response = lines.firstOrNull { it.optInt("id", -1) == 2 } ?: lines.lastOrNull { it.has("result") || it.has("error") }
            ?: error("MCP stdio server returned no JSON-RPC response")
        if (response.has("error")) error(response.getJSONObject("error").optString("message", "MCP error"))
        return response
    }

    fun newId() = UUID.randomUUID().toString()
}
