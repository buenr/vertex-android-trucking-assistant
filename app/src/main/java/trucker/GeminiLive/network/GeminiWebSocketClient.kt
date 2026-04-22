package trucker.geminilive.network

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import trucker.geminilive.audio.AudioConfig
import trucker.geminilive.tools.TruckingTools
import trucker.geminilive.tools.ToolCallLogger
import java.util.concurrent.TimeUnit

/**
 * Represents the current connection state of the WebSocket.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    READY,
    STALE
}

/**
 * Tracks connection state with timestamps for stale detection.
 */
data class ConnectionTracker(
    var state: ConnectionState = ConnectionState.DISCONNECTED,
    var lastActivityTimestamp: Long = 0L,
    var connectionEstablishedTimestamp: Long = 0L
) {
    fun updateActivity() {
        lastActivityTimestamp = System.currentTimeMillis()
    }
    
    fun timeSinceLastActivityMs(): Long {
        return if (lastActivityTimestamp == 0L) 0L 
               else System.currentTimeMillis() - lastActivityTimestamp
    }
    
    fun timeSinceConnectionEstablishedMs(): Long {
        return if (connectionEstablishedTimestamp == 0L) 0L
               else System.currentTimeMillis() - connectionEstablishedTimestamp
    }
}

class GeminiWebSocketClient(
    private val projectId: String,
    private val onStatusUpdate: (String) -> Unit,
    private val onStateChanged: (GeminiState) -> Unit,
    private val onReady: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onToolCallStarted: (String) -> Unit,
    private val onCloseAppRequested: () -> Unit,
    private val onError: (String) -> Unit,
    private val onConnectionStale: (() -> Unit)? = null
) {
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()

        private const val STALE_THRESHOLD_MS = 30_000L // 30 seconds (reduced from 60s)

        private const val SYSTEM_PROMPT = """You are the Swift Transportation Trucking Copilot, an AI-powered voice assistant for professional truck drivers. You operate hands-free in the cab, providing real-time information through natural voice conversation.

PERSONA:
- Professional yet approachable—like an experienced driver manager who knows the road
- CB radio-friendly communication style (concise, clear, trucker terminology)
- Knowledgeable about Swift Transportation operations, policies, and procedures

INTERACTION STYLE:
- Keep responses concise (2-3 sentences typical, expand only when detail is requested)
- Speak at a moderate pace for clarity over cab noise
- Prioritize answering the question but warn about delays or issues promptly

AVAILABLE TOOLS - Invoke these when drivers ask:
- getDriverProfile: Driver identity, location, equipment, compliance status
- getLoadStatus: Active load progress, stops, ETAs, route risks
- getHoursOfServiceClocks: HOS drive/duty/cycle time remaining
- getTrafficAndWeather: Road conditions ahead (1-hour horizon)
- getDispatchInbox: Dispatch messages and exceptions
- getCompanyFAQs: Pet Policy, Rider Policy, Breakdown procedures, Macros, Headsets
- getPaycheckInfo: Pay summary, miles, CPM rate
- findNearestSwiftTerminal: Nearby terminals with amenities
- checkSafetyScore: Safety score, ranking, recent events
- getFuelNetworkRouting: Approved fuel stops and discounts
- getContacts: Driver Leader, departments (payroll, breakdown, support)
- getNextLoadDetails: Pre-dispatch load information
- getMentorFAQs: Driver mentor program info
- getOwnerOperatorFAQs: Owner-operator program info
- closeApp: Close the app when driver explicitly requests to exit or quit

RESPONSE GUIDELINES:
1. Summarize key information in 1-2 sentences
2. Offer to expand on details if the driver wants more info

EXAMPLE GOOD RESPONSES:
- "Copy that. You've got 5 hours 15 minutes of drive time left, with your next break due in 2 hours 30 minutes. Want me to check your route conditions?"
- "10-4. Your next stop is Flagstaff Fuel in 12 miles—ETA 7:40 PM. You're running about 10 minutes behind due to winds on I-40, but you'll still make your Dallas delivery window tomorrow."

SAFETY PRIORITIES:
1. HOS Alerts - Warn immediately if drive time is critically low
2. Safety Issues - Flag hard braking, speeding, or score impacts
3. Route Risks - Report weather, traffic, or road hazards promptly

SAFETY RULES:
- Never suggest violating HOS regulations
- Remind about break times when approaching limits
- Flag safety score impacts from recent events
- Warn about weather/traffic risks with recommended actions

TONE & LANGUAGE:
- Use trucker terminology naturally (dispatch, shippers, consignees, macros)
- Be direct and helpful
- Acknowledge with "Copy that," "10-4," or "Got it"
- Keep it professional but human

ERROR HANDLING:
- If tool fails: "Sorry, I'm having trouble pulling that up. Is there something else I can help you with."
- If unclear request: "Say again? I didn't catch that." or ask for clarification

Remember: You are the driver's trusted co-pilot. Keep them informed, keep them safe, and keep it brief."""
    }

    @Volatile
    private var webSocket: WebSocket? = null
    private val isReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isModelSpeaking = java.util.concurrent.atomic.AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    private val connectionTracker = ConnectionTracker()

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("GeminiWS", "WebSocket Connected: ${response.message}")
            connectionTracker.state = ConnectionState.CONNECTED
            connectionTracker.connectionEstablishedTimestamp = System.currentTimeMillis()
            connectionTracker.updateActivity()
            onStatusUpdate("WebSocket open, sending config...")
            sendSetup(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            connectionTracker.updateActivity()
            Log.v("GeminiWS", "Received text message: $text")
            handleRawMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            connectionTracker.updateActivity()
            Log.v("GeminiWS", "Received binary message: ${bytes.size} bytes")
            try {
                handleRawMessage(bytes.utf8())
            } catch (e: Exception) {
                Log.e("GeminiWS", "Binary parse error: ${e.message}")
                onStatusUpdate("Binary parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorMsg = "WebSocket failure: ${t.message}"
            Log.e("GeminiWS", errorMsg, t)
            isReady.set(false)
            connectionTracker.state = ConnectionState.DISCONNECTED
            onError(errorMsg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("GeminiWS", "WebSocket Closing: $reason (code: $code)")
            onStatusUpdate("Server closing connection ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("GeminiWS", "WebSocket Closed: $reason (code: $code)")
            isReady.set(false)
            connectionTracker.state = ConnectionState.DISCONNECTED
            if (code != 1000) {
                onError("Connection closed unexpectedly ($code)")
            }
        }
    }

    fun connect(accessToken: String) {
        disconnect()
        isModelSpeaking.set(false)
        isReady.set(false)
        connectionTracker.state = ConnectionState.CONNECTING
        connectionTracker.updateActivity()
        try {
            val url = "wss://us-central1-aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            webSocket = sharedClient.newWebSocket(request, createWebSocketListener())
        } catch (e: Exception) {
            connectionTracker.state = ConnectionState.DISCONNECTED
            onError("Connection Error: ${e.message}")
        }
    }

    private fun sendSetup(targetWebSocket: WebSocket) {
        val currentWs = targetWebSocket
        val setupJson = buildJsonObject {
            putJsonObject("setup") {
                put("model", "projects/$projectId/locations/us-central1/publishers/google/models/gemini-live-2.5-flash-native-audio")
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", SYSTEM_PROMPT)
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") {
                        add("AUDIO")
                    }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Fenrir")
                            }
                        }
                    }
                }
                putJsonObject("realtimeInputConfig") {
                    putJsonObject("automaticActivityDetection") {
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    }
                }
                // Serialize tools from single source of truth (TruckingTools.declaration)
                val toolsJson = Json.encodeToJsonElement(TruckingTools.declaration)
                put("tools", buildJsonArray { add(toolsJson) })
            }
        }

        val text = setupJson.toString()
        Log.d("GeminiWS", "Sending setup JSON: ${text.take(500)}...")
        onStatusUpdate("Setup sent, waiting...")
        currentWs.send(text)
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isReady.get()) {
            return
        }

        val currentWs = webSocket ?: return
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val audioMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", base64Audio)
                    put("mimeType", AudioConfig.INPUT_AUDIO_MIME_TYPE)
                }
            }
        }
        currentWs.send(audioMessage.toString())
    }

    fun sendAudioStreamEnd() {
        if (!isReady.get()) return
        val currentWs = webSocket ?: return
        val endMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("audioStreamEnd", true)
            }
        }
        currentWs.send(endMessage.toString())
        onStateChanged(GeminiState.THINKING)
    }

    fun sendText(text: String) {
        if (!isReady.get()) return
        val currentWs = webSocket ?: return
        val textMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("text", text)
            }
        }
        currentWs.send(textMessage.toString())
        onStateChanged(GeminiState.THINKING)
    }

    private fun handleRawMessage(text: String) {
        try {
            val element = json.parseToJsonElement(text).jsonObject
            Log.v("GeminiWS", "Parsing message keys: ${element.keys}")

            if (element.containsKey("setupComplete") || element.containsKey("setup_complete")) {
                Log.d("GeminiWS", "Setup Complete received")
                isReady.set(true)
                connectionTracker.state = ConnectionState.READY
                connectionTracker.updateActivity()
                onStatusUpdate("Ready")
                onReady()
            }

            val errorEl = element["error"] ?: element["Error"]
            if (errorEl != null) {
                val errorObj = errorEl.jsonObject
                val msg = errorObj["message"]?.jsonPrimitive?.content ?: errorObj.toString()
                Log.e("GeminiWS", "Server Error: $msg")
                isReady.set(false)
                onError(msg)
                return
            }

        val scEl = element["serverContent"] ?: element["server_content"]
        if (scEl != null) {
            Log.v("GeminiWS", "Server Content found")
            handleServerContent(scEl.jsonObject)
        }

            val tcEl = element["toolCall"] ?: element["tool_call"]
            if (tcEl != null) {
                Log.d("GeminiWS", "Tool Call found")
                val tcObj = tcEl.jsonObject
                val fcEl = tcObj["functionCalls"] ?: tcObj["function_calls"]
                if (fcEl != null) {
                    val calls = fcEl.jsonArray.map { callEl ->
                        val callObj = callEl.jsonObject
                        val name = callObj["name"]!!.jsonPrimitive.content
                        val id = callObj["id"]!!.jsonPrimitive.content
                        val args = (callObj["args"] as? JsonObject)?.mapValues { it.value }
                        Log.d("GeminiWS", "  Function Call: $name (ID: $id, Args: $args)")
                        onToolCallStarted(name)
                        FunctionCall(name = name, id = id, args = args)
                    }
                    handleToolCall(ToolCall(calls))
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiWS", "Error parsing message", e)
        }
    }

    private fun handleServerContent(contentObj: JsonObject) {
        val modelTurnKey = if (contentObj.containsKey("modelTurn")) "modelTurn" else "model_turn"
        if (contentObj.containsKey(modelTurnKey)) {
            Log.v("GeminiWS", "Model Turn detected")
            if (!isModelSpeaking.getAndSet(true)) {
                onStateChanged(GeminiState.SPEAKING)
            }
            val turnObj = contentObj[modelTurnKey]!!.jsonObject
            val partsKey = if (turnObj.containsKey("parts")) "parts" else "parts"
            val partsEl = turnObj[partsKey]
            if (partsEl != null && partsEl is JsonArray) {
                val parts = partsEl.jsonArray
                for (partEl in parts) {
                    val partObj = partEl.jsonObject
                    val inlineKey = if (partObj.containsKey("inlineData")) "inlineData" else "inline_data"
                    val inlineEl = partObj[inlineKey]
                    if (inlineEl != null && inlineEl is JsonObject) {
                        val inlineObj = inlineEl.jsonObject
                        val dataEl = inlineObj["data"]
                        if (dataEl != null && dataEl.jsonPrimitive.content.isNotEmpty()) {
                            val data = dataEl.jsonPrimitive.content
                            try {
                                val audioBytes = Base64.decode(data, Base64.DEFAULT)
                                if (audioBytes.isNotEmpty()) {
                                    onAudioReceived(audioBytes)
                                }
                            } catch (e: Exception) {
                                Log.e("GeminiWS", "Error decoding audio data", e)
                            }
                        }
                    }
                }
            }
        }

        val interruptedEl = contentObj["interrupted"]
        if (interruptedEl?.jsonPrimitive?.booleanOrNull == true) {
            Log.d("GeminiWS", "Interrupted by user")
            isModelSpeaking.set(false)
            onStateChanged(GeminiState.IDLE)
            onInterrupted()
        }

        val turnCompleteEl = contentObj["turnComplete"] ?: contentObj["turn_complete"]
        if (turnCompleteEl?.jsonPrimitive?.booleanOrNull == true) {
            Log.d("GeminiWS", "Turn Complete")
            isModelSpeaking.set(false)
            onStateChanged(GeminiState.IDLE)
            onTurnComplete()
        }
    }

    private fun handleToolCall(toolCall: ToolCall) {
        Log.d("GeminiWS", "Handling tool call with ${toolCall.functionCalls.size} functions")
        onStateChanged(GeminiState.WORKING)
        // Send tool results immediately to Gemini to start buffering audio
        sharedClient.dispatcher.executorService.execute {
            try {
                val results = toolCall.functionCalls.map { call ->
                    Log.d("GeminiWS", "Executing tool: ${call.name}")
                    val startTime = System.currentTimeMillis()
                    val result = TruckingTools.handleToolCall(call.name, call.args)
                    val responseTimeMs = System.currentTimeMillis() - startTime
                    Log.d("GeminiWS", "Tool result for ${call.name}: $result")
                    
                    // Log the tool call metrics
                    val driverId = extractDriverId(result)
                    ToolCallLogger.logCall(
                        functionName = call.name,
                        driverId = driverId,
                        arguments = call.args,
                        responseTimeMs = responseTimeMs,
                        success = true
                    )
                    
                    // Check if this is a closeApp request
                    if (call.name == "closeApp") {
                        Log.d("GeminiWS", "closeApp tool called - triggering close callback")
                        onCloseAppRequested()
                    }
                    
                    call.name to (call.id to result)
                }

                val currentWs = webSocket
                if (currentWs != null && isReady.get()) {
                    val responseMessage = buildJsonObject {
                        putJsonObject("toolResponse") {
                            putJsonArray("functionResponses") {
                                results.forEach { (name, idAndResult) ->
                                    val (id, result) = idAndResult
                                    addJsonObject {
                                        put("name", name)
                                        put("id", id)
                                        put("response", result)
                                    }
                                }
                            }
                        }
                    }
                    currentWs.send(responseMessage.toString())
                } else {
                    Log.w("GeminiWS", "Tool results ready but WebSocket not available or not ready")
                }
            } catch (e: Exception) {
                Log.e("GeminiWS", "Error executing tools or sending response", e)
            }
        }
    }

    fun disconnect() {
        isReady.set(false)
        isModelSpeaking.set(false)
        connectionTracker.state = ConnectionState.DISCONNECTED
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }

    /**
     * Returns the current connection state.
     */
    fun getConnectionState(): ConnectionState = connectionTracker.state

    /**
     * Returns the time in milliseconds since the last activity was received.
     */
    fun getTimeSinceLastActivityMs(): Long = connectionTracker.timeSinceLastActivityMs()

    /**
     * Returns the time in milliseconds since the connection was established.
     */
    fun getTimeSinceConnectionEstablishedMs(): Long = connectionTracker.timeSinceConnectionEstablishedMs()

    /**
     * Checks if the connection is stale (no activity for STALE_THRESHOLD_MS).
     * If stale, triggers the onConnectionStale callback and returns true.
     */
    fun checkAndNotifyStaleConnection(): Boolean {
        if (connectionTracker.state == ConnectionState.READY &&
            connectionTracker.timeSinceLastActivityMs() > STALE_THRESHOLD_MS) {
            connectionTracker.state = ConnectionState.STALE
            Log.w("GeminiWS", "Connection detected as stale after ${connectionTracker.timeSinceLastActivityMs()}ms")
            onConnectionStale?.invoke()
            return true
        }
        return false
    }
    
    /**
     * Extract driver_id from tool result JSON for logging purposes.
     */
    private fun extractDriverId(result: JsonElement): String {
        return try {
            (result as? JsonObject)?.get("driver_id")?.let { 
                (it as? JsonPrimitive)?.content 
            } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}


