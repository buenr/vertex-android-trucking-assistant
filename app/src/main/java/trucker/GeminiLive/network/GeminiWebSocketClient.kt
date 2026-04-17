package trucker.GeminiLive.network

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import trucker.GeminiLive.audio.AudioConfig
import trucker.GeminiLive.tools.TruckingTools
import java.util.concurrent.TimeUnit

class GeminiWebSocketClient(
    private val apiKey: String,
    private val onStatusUpdate: (String) -> Unit,
    private val onStateChanged: (GeminiState) -> Unit,
    private val onReady: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onToolCallStarted: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private var webSocket: WebSocket? = null
    private var isReady = false
    private var isModelSpeaking = false
    private val json = Json { ignoreUnknownKeys = true }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            Log.d("GeminiWS", "WebSocket Connected")
            onStatusUpdate("WebSocket open, sending config...")
            sendSetup()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            Log.d("GeminiWS", "Received text message: ${text.take(100)}...")
            onStatusUpdate("Recv text: ${text.take(100)}")
            handleRawMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            Log.d("GeminiWS", "Received binary message: ${bytes.size} bytes")
            onStatusUpdate("Recv binary: ${bytes.size} bytes")
            try {
                handleRawMessage(bytes.utf8())
            } catch (e: Exception) {
                onStatusUpdate("Binary parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            val errorMsg = "WebSocket failure: ${t.message}"
            Log.e("GeminiWS", errorMsg, t)
            isReady = false
            onError(errorMsg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            Log.d("GeminiWS", "WebSocket Closing: $reason (code: $code)")
            onStatusUpdate("Server closing connection ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != this@GeminiWebSocketClient.webSocket) return
            Log.d("GeminiWS", "WebSocket Closed: $reason (code: $code)")
            isReady = false
            if (code != 1000) {
                onError("Connection closed unexpectedly ($code)")
            }
        }
    }

    fun connect() {
        disconnect()
        isModelSpeaking = false
        isReady = false
        try {
            val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            val request = Request.Builder().url(url).build()
            webSocket = sharedClient.newWebSocket(request, wsListener)
        } catch (e: Exception) {
            onError("Connection Error: ${e.message}")
        }
    }

    private fun sendSetup() {
        val setupJson = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/gemini-3.1-flash-live-preview")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") {
                        add("AUDIO")
                    }
                    putJsonObject("speechConfig") {
                        put("languageCode", "en-US")
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Aoede")
                            }
                        }
                    }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put(
                                "text",
                                "You are a Swift Transportation trucking copilot (AI Assistant). Use concise, operational language familiar to truck drivers. Tool selection guidance: use getDriverProfile for profile/location/equipment/compliance snapshot requests; use getLoadStatus for active-load timeline, stop status, ETA, and load-specific risks; use getHoursOfServiceClocks for HOS clock and break timing; use getTrafficAndWeather for immediate (1 hour) road conditions, traffic, and weather ahead; use getDispatchInbox for dispatch messages and open exceptions requiring action; use getCompanyFAQs for general company policy/procedure FAQs; use getPaycheckInfo for paycheck, settlement, CPM, gross/net, and miles-related compensation questions; use findNearestSwiftTerminal to check for nearby Swift yards and amenities; use checkSafetyScore to review driving telematics, harsh braking, and bonus standing; use getFuelNetworkRouting to find the next approved in-network fuel stop; use getNextLoadDetails for details on the next scheduled load, pickup/delivery windows, and pre-dispatch information. If the driver asks for data or actions outside available tools/data, unmistakably state that the request is out-of-scope of available data and provide the closest supported alternative without fabricating details. ONLY AND ALWAYS RESPOND IN ENGLISH. "
                            )
                        }
                    }
                }
                putJsonObject("realtimeInputConfig") {
                    putJsonObject("automaticActivityDetection") {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", 20)
                        put("silenceDurationMs", 100)
                    }
                }
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            TruckingTools.declaration.functionDeclarations.forEach { decl ->
                                addJsonObject {
                                    put("name", decl.name)
                                    put("description", decl.description)
                                    decl.parameters?.let { params ->
                                        put("parameters", json.encodeToJsonElement(params))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val text = setupJson.toString()
        Log.d("GeminiWS", "Sending setup")
        onStatusUpdate("Setup sent, waiting...")
        webSocket?.send(text)
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isReady || isModelSpeaking) return 
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val audioMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", base64Audio)
                    put("mimeType", AudioConfig.INPUT_AUDIO_MIME_TYPE)
                }
            }
        }
        webSocket?.send(audioMessage.toString())
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        val endMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("audioStreamEnd", true)
            }
        }
        webSocket?.send(endMessage.toString())
        onStateChanged(GeminiState.THINKING)
    }

    fun sendText(text: String) {
        if (!isReady) return
        val textMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("text", text)
            }
        }
        webSocket?.send(textMessage.toString())
        onStateChanged(GeminiState.THINKING)
    }

    private fun handleRawMessage(text: String) {
        try {
            val element = json.parseToJsonElement(text).jsonObject

            if (element.containsKey("setupComplete") || element.containsKey("setup_complete")) {
                isReady = true
                onStatusUpdate("Ready")
                onReady()
            }

            val errorEl = element["error"] ?: element["Error"]
            if (errorEl != null) {
                val errorObj = errorEl.jsonObject
                val msg = errorObj["message"]?.jsonPrimitive?.content ?: errorObj.toString()
                Log.e("GeminiWS", "Server Error: $msg")
                isReady = false
                onError(msg)
                return
            }

            val scEl = element["serverContent"] ?: element["server_content"]
            if (scEl != null) {
                handleServerContent(scEl.jsonObject)
            }

            val tcEl = element["toolCall"] ?: element["tool_call"]
            if (tcEl != null) {
                val tcObj = tcEl.jsonObject
                val fcEl = tcObj["functionCalls"] ?: tcObj["function_calls"]
                if (fcEl != null) {
                    val calls = fcEl.jsonArray.map { callEl ->
                        val callObj = callEl.jsonObject
                        val name = callObj["name"]!!.jsonPrimitive.content
                        onToolCallStarted(name)
                        FunctionCall(
                            name = name,
                            id = callObj["id"]!!.jsonPrimitive.content,
                            args = (callObj["args"] as? JsonObject)?.mapValues { it.value }
                        )
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
            isModelSpeaking = true
            onStateChanged(GeminiState.SPEAKING)
            val turnObj = contentObj[modelTurnKey]!!.jsonObject
            val partsKey = if (turnObj.containsKey("parts")) "parts" else null
            if (partsKey != null) {
                val parts = turnObj[partsKey]!!.jsonArray
                for (partEl in parts) {
                    val partObj = partEl.jsonObject
                    val inlineKey = if (partObj.containsKey("inlineData")) "inlineData" else "inline_data"
                    if (partObj.containsKey(inlineKey)) {
                        val inlineObj = partObj[inlineKey]!!.jsonObject
                        val audioBytes = Base64.decode(inlineObj["data"]!!.jsonPrimitive.content, Base64.DEFAULT)
                        onAudioReceived(audioBytes)
                    }
                }
            }
        }

        val interruptedEl = contentObj["interrupted"]
        if (interruptedEl?.jsonPrimitive?.booleanOrNull == true) {
            isModelSpeaking = false
            onStateChanged(GeminiState.IDLE)
            onInterrupted()
        }

        val turnCompleteEl = contentObj["turnComplete"]
        if (turnCompleteEl?.jsonPrimitive?.booleanOrNull == true) {
            isModelSpeaking = false
            onStateChanged(GeminiState.IDLE)
            onTurnComplete()
        }
    }

    private fun handleToolCall(toolCall: ToolCall) {
        onStateChanged(GeminiState.WORKING)
        val responseMessage = buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    toolCall.functionCalls.forEach { call ->
                        val result = TruckingTools.handleToolCall(call.name, call.args)
                        addJsonObject {
                            put("name", call.name)
                            put("id", call.id)
                            put("response", result)
                        }
                    }
                }
            }
        }
        webSocket?.send(responseMessage.toString())
    }

    fun disconnect() {
        isReady = false
        isModelSpeaking = false
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }
}
