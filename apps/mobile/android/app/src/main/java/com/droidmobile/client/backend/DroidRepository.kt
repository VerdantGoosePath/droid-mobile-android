package com.droidmobile.client.backend

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin data layer, independent of Compose, with one presentation-neutral RPC
 * interface. Replacing the TypeScript SDK later only replaces BackendTransport
 * and the mapping functions here, never any Composable.
 */
data class DroidState(
    val bridgeReady: Boolean = false,
    val status: String = "disconnected",
    val connectedUrl: String = "",
    val sessions: List<JSONObject> = emptyList(),
    val activeSession: String? = null,
    val messages: List<JSONObject> = emptyList(),
    val sections: Map<String, Any?> = emptyMap(),
    val streaming: Boolean = false,
    val streamText: String = "",
    val terminalOutput: Map<String, String> = emptyMap(),
    val terminalStatus: String = "idle",
    val error: String? = null,
    val permissionRequest: JSONObject? = null,
    val questionRequest: JSONObject? = null
)

class DroidRepository(
    private val transport: BackendTransport,
    val secrets: SecretStore,
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sequence = AtomicLong()
    private val pending = mutableMapOf<String, CompletableDeferred<Any?>>()
    private val mutable = MutableStateFlow(DroidState())
    val state = mutable.asStateFlow()
    private var apiKey: String? = null

    init { transport.setReceiver(::onMessage) }

    private fun onMessage(raw: String) {
        try {
            val msg = JSONObject(raw)
            if (msg.has("id")) {
                val deferred = pending.remove(msg.getString("id")) ?: return
                if (msg.optBoolean("ok")) deferred.complete(msg.opt("result"))
                else deferred.completeExceptionally(IllegalStateException(msg.optString("error", "Backend error")))
                return
            }
            val event = msg.optString("event")
            val data = msg.opt("data")
            when (event) {
                "host.ready" -> mutable.value = mutable.value.copy(bridgeReady = true)
                "connection.status" -> {
                    val status = if (data is JSONObject) data.optString("state", data.toString()) else "$data"
                    mutable.value = mutable.value.copy(status = status)
                    if (status == "ready") scope.launch { refreshSessions() }
                }
                "session.stream" -> {
                    val update = (data as? JSONObject)?.optJSONObject("update")
                    val text = update?.optString("text", "") ?: ""
                    if (text.isNotEmpty()) mutable.value = mutable.value.copy(streamText = mutable.value.streamText + text)
                }
                "session.finished" -> {
                    mutable.value = mutable.value.copy(streaming = false, streamText = "")
                    scope.launch { refreshMessages() }
                }
                "session.failed" -> mutable.value = mutable.value.copy(
                    streaming = false, error = (data as? JSONObject)?.optString("message", "Turn failed")
                )
                "terminal.status" -> mutable.value = mutable.value.copy(terminalStatus = data.toString())
                "terminal.event" -> {
                    val eventData = data as? JSONObject
                    val id = eventData?.optString("terminalId").orEmpty()
                    if (id.isNotBlank()) {
                        val text = when (eventData?.optString("type")) {
                            "data" -> eventData.optString("data")
                            "exit" -> "\n[Shell exited: ${eventData.optString("exitCode")}]\n"
                            else -> ""
                        }
                        if (text.isNotBlank()) {
                            // Present readable terminal output without raw protocol JSON.
                            // An ANSI/xterm renderer is still required for full TTY parity.
                            val plain = text.replace(Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
                            val outputs = mutable.value.terminalOutput.toMutableMap()
                            outputs[id] = ((outputs[id] ?: "") + plain).takeLast(120_000)
                            mutable.value = mutable.value.copy(terminalOutput = outputs)
                        }
                    }
                }
                "permission.request" -> mutable.value = mutable.value.copy(permissionRequest = data as? JSONObject)
                "question.request" -> mutable.value = mutable.value.copy(questionRequest = data as? JSONObject)
                else -> if (event.isNotBlank()) {
                    val all = mutable.value.sections.toMutableMap()
                    all["event.$event"] = data
                    mutable.value = mutable.value.copy(sections = all)
                }
            }
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = "Bridge message could not be decoded") }
    }

    suspend fun request(op: String, args: JSONObject = JSONObject()): Any? {
        val id = sequence.incrementAndGet().toString()
        val deferred = CompletableDeferred<Any?>()
        pending[id] = deferred
        transport.send(JSONObject().put("id", id).put("op", op).put("args", args).toString())
        return try { withTimeout(45_000L) { deferred.await() } } finally { pending.remove(id) }
    }
    private fun JSONArray.toObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
    fun updateError(error: String?) { mutable.value = mutable.value.copy(error = error) }

    suspend fun connect(address: String, key: String) {
        require(address.startsWith("wss://")) { "An encrypted wss:// relay URL is required" }
        require(key.isNotBlank()) { "Enter a Factory API key" }
        apiKey = key
        secrets.save(key)
        mutable.value = mutable.value.copy(status = "connecting", connectedUrl = address, error = null)
        try { request("connect", JSONObject().put("url", address).put("apiKey", key)) }
        catch (e: Exception) { mutable.value = mutable.value.copy(status = "error", error = e.message) }
    }
    suspend fun disconnect() {
        runCatching { request("disconnect") }
        mutable.value = DroidState(bridgeReady = mutable.value.bridgeReady)
    }
    suspend fun refreshSessions() {
        try {
            val result = request("sessions.list")
            mutable.value = mutable.value.copy(sessions = (result as? JSONArray)?.toObjects() ?: emptyList(), error = null)
        } catch (e: Exception) { updateError(e.message) }
    }
    suspend fun openSession(id: String) {
        try {
            request("sessions.open", JSONObject().put("id", id))
            mutable.value = mutable.value.copy(activeSession = id, messages = emptyList())
            refreshMessages()
        } catch (e: Exception) { updateError(e.message) }
    }
    suspend fun refreshMessages() {
        val id = mutable.value.activeSession ?: return
        try {
            val result = request("sessions.messages", JSONObject().put("id", id).put("options", JSONObject().put("limit", 100)))
            val obj = result as? JSONObject
            val messages = obj?.optJSONArray("messages")?.toObjects()
                ?: (result as? JSONArray)?.toObjects() ?: emptyList()
            mutable.value = mutable.value.copy(messages = messages)
        } catch (e: Exception) { updateError(e.message) }
    }
    suspend fun send(text: String) {
        val id = mutable.value.activeSession ?: return
        if (text.isBlank()) return
        mutable.value = mutable.value.copy(streaming = true, streamText = "", error = null)
        try {
            request("sessions.send", JSONObject().put("id", id).put("prompt", text))
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(streaming = false, error = e.message)
        }
    }
    suspend fun fetchSection(section: String, operation: String, args: JSONObject = JSONObject()) {
        try {
            val data = request(operation, args)
            val sections = mutable.value.sections.toMutableMap()
            sections[section] = data
            mutable.value = mutable.value.copy(sections = sections, error = null)
        } catch (e: Exception) { updateError(e.message) }
    }
    suspend fun invoke(operation: String, args: JSONObject = JSONObject()) {
        try { request(operation, args) } catch (e: Exception) { updateError(e.message) }
    }
    suspend fun respondPermission(answer: JSONObject) {
        val id = mutable.value.permissionRequest?.optString("id") ?: return
        request("permission.answer", JSONObject().put("id", id).put("answer", answer))
        mutable.value = mutable.value.copy(permissionRequest = null)
    }
    suspend fun respondQuestion(answer: JSONObject) {
        val id = mutable.value.questionRequest?.optString("id") ?: return
        request("question.answer", JSONObject().put("id", id).put("answer", answer))
        mutable.value = mutable.value.copy(questionRequest = null)
    }

    /** Android-only REST discovery, to avoid CORS inside the isolated SDK host. */
    suspend fun discoverComputers(region: String = "global", suppliedKey: String? = null): List<JSONObject> = withContext(Dispatchers.IO) {
        val token = suppliedKey?.takeIf { it.isNotBlank() } ?: apiKey ?: secrets.read()
            ?: throw IllegalArgumentException("API key required")
        val host = if (region == "eu") "https://api.eu.factory.ai" else "https://api.factory.ai"
        val client = (URL("$host/api/v0/computers").openConnection() as HttpURLConnection)
        try {
            client.connectTimeout = 10_000
            client.readTimeout = 10_000
            client.setRequestProperty("Authorization", "Bearer $token")
            client.setRequestProperty("Accept", "application/json")
            if (client.responseCode != 200) throw IllegalStateException("Could not list Factory computers (${client.responseCode})")
            val response = JSONObject(client.inputStream.bufferedReader().use { it.readText() })
            val list = response.optJSONArray("computers") ?: JSONArray()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("relayClientUrl").startsWith("wss://relay.") }
        } finally { client.disconnect() }
    }
    fun close() {
        scope.launch { runCatching { disconnect() } }
        transport.close()
    }
}
