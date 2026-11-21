package com.openchatcut.android.ai

import com.openchatcut.android.util.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ToolCall(val name: String, val arguments: JSONObject)
data class AssistantReply(val text: String, val toolCalls: List<ToolCall>, val raw: JSONObject)

/**
 * Native Ollama HTTP client. Talks only to the user-configured local endpoint using
 * Ollama's own /api/chat and /api/tags endpoints. There is no cloud provider, no API key,
 * and no canned/fallback response: transport or model failure always surfaces as an error.
 */
class OllamaClient(
    var baseUrl: String = "http://127.0.0.1:11434",
    var model: String = "qwen2.5:7b",
    var requestTimeoutMs: Int = 180_000,
) {

    fun normalizedBase(): String = baseUrl.trim().trimEnd('/')

    /** Real GET /api/tags — the model list comes from the running Ollama daemon. */
    fun listModels(): List<String> {
        val connection = open("/api/tags", "GET")
        try {
            connection.connect()
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) {
                throw RuntimeException("Ollama /api/tags HTTP $code: ${body.take(500)}")
            }
            val json = Json.parseObject(body)
            val models = json.optJSONArray("models") ?: JSONArray()
            return (0 until models.length()).mapNotNull { i ->
                models.optJSONObject(i)?.let { Json.optString(it, "name").ifEmpty { Json.optString(it, "model") } }
            }.filter { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Real POST /api/chat. Streams NDJSON deltas when onDelta is supplied; the final
     * assistant message and any native tool_calls are returned.
     */
    fun chat(
        messages: JSONArray,
        tools: JSONArray? = null,
        onDelta: ((String) -> Unit)? = null,
    ): AssistantReply {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", onDelta != null)
            put("options", JSONObject().apply {
                put("temperature", 0.1)
                put("num_ctx", 8192)
            })
            if (tools != null && tools.length() > 0) put("tools", tools)
        }
        val connection = open("/api/chat", "POST")
        try {
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            connection.connect()
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) {
                throw RuntimeException("Ollama /api/chat HTTP $code: ${body.take(800)}")
            }

            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var lastRaw = JSONObject()

            if (onDelta == null) {
                lastRaw = Json.parseObject(body)
                val message = lastRaw.optJSONObject("message") ?: JSONObject()
                text.append(Json.optString(message, "content"))
                parseToolCalls(message, calls)
            } else {
                // NDJSON streaming: one JSON object per line, final one has done=true.
                body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val chunk = try { Json.parseObject(line) } catch (_: Exception) { return@forEach }
                    lastRaw = chunk
                    val message = chunk.optJSONObject("message") ?: JSONObject()
                    val delta = Json.optString(message, "content")
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onDelta.invoke(delta)
                    }
                    parseToolCalls(message, calls)
                }
            }
            return AssistantReply(text.toString(), calls, lastRaw)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseToolCalls(message: JSONObject, out: MutableList<ToolCall>) {
        val array = message.optJSONArray("tool_calls") ?: return
        for (i in 0 until array.length()) {
            val call = array.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            val name = Json.optString(fn, "name")
            if (name.isEmpty()) continue
            val args = when (val raw = fn.opt("arguments")) {
                is JSONObject -> raw
                is String -> try { Json.parseObject(raw) } catch (_: Exception) { JSONObject() }
                else -> JSONObject()
            }
            out += ToolCall(name, args)
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = URL(normalizedBase() + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = requestTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val reader = BufferedReader(InputStreamReader(stream ?: return "", StandardCharsets.UTF_8))
        return reader.use { it.readText() }
    }

    /** Human-readable connectivity failure used by Settings → Test connection. */
    fun describeConnectivity(): String = try {
        val models = listModels()
        if (models.isEmpty()) "Connected to ${normalizedBase()}, but no models are installed."
        else "Connected to ${normalizedBase()}. Models: ${models.joinToString(", ")}"
    } catch (e: Throwable) {
        "Cannot reach ${normalizedBase()}: ${e.message ?: e::class.java.simpleName}"
    }
}
