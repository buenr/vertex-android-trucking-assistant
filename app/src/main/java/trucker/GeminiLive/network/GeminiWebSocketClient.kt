package trucker.GeminiLive.network

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import trucker.GeminiLive.audio.AudioConfig
import trucker.GeminiLive.tools.TruckingTools

class GeminiWebSocketClient(
    private val apiKey: String,
    private val onStatusUpdate: (String) -> Unit,
    private val onStateChanged: (GeminiState) -> Unit,
    private val onReady: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onUserTextReceived: (String) -> Unit,
    private val onGeminiTextReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isReady = false
    private var isModelSpeaking = false
    private val json = Json { ignoreUnknownKeys = true }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("GeminiWS", "WebSocket Connected")
            onStatusUpdate("WebSocket open, sending config...")
            sendSetup()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("GeminiWS", "Received text message: $text")
            onStatusUpdate("Recv text: ${text.take(300)}")
            handleRawMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d("GeminiWS", "Received binary message: ${bytes.size} bytes")
            onStatusUpdate("Recv binary: ${bytes.size} bytes")
            try {
                val text = bytes.utf8()
                handleRawMessage(text)
            } catch (e: Exception) {
                onStatusUpdate("Binary parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorMsg = "WebSocket failure: ${t.message}"
            Log.e("GeminiWS", errorMsg, t)
            isReady = false
            onError(errorMsg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("GeminiWS", "WebSocket Closing: $reason (code: $code)")
            onStatusUpdate("Server closing connection ($code): $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("GeminiWS", "WebSocket Closed: $reason (code: $code)")
            isReady = false
            if (code != 1000) {
                onError("Connection closed unexpectedly ($code): $reason")
            }
        }
    }

    fun connect() {
        try {
            val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, wsListener)
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
                    putJsonObject("thinkingConfig") {
                        put("thinkingLevel", "LOW")
                    }
                }
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put(
                                "text",
                                "You are a Swift Transportation trucking copilot (AI Assistant). Use concise, operational language familiar to truck drivers. Tool selection guidance: use getDriverProfile for profile/location/equipment/compliance snapshot requests; use getLoadStatus for active-load timeline, stop status, ETA, and load-specific risks; use getComplianceAlerts for HOS/DVIR/permit due items; use getRouteRisks for forward-looking route hazards within a requested horizon; use getDispatchInbox for dispatch messages and open exceptions requiring action; use getCompanyFAQs for general company policy/procedure FAQs; use getPaycheckInfo for paycheck, settlement, CPM, gross/net, and miles-related compensation questions. If the driver asks for data or actions outside available tools/data, unmistakably state that the request is out-of-scope of available data and provide the closest supported alternative without fabricating details. ONLY AND ALWAYS RESPOND IN ENGLISH. "
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
        Log.d("GeminiWS", "Sending setup: $text")
        onStatusUpdate("Setup sent (${text.length} chars), waiting...")
        webSocket?.send(text)
    }

    fun sendAudio(audioData: ByteArray) {
        if (isModelSpeaking) return // Drop mic input while model is speaking to avoid echo
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
        val endMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("audioStreamEnd", true)
            }
        }
        webSocket?.send(endMessage.toString())
        Log.d("GeminiWS", "Sent audioStreamEnd")
        onStateChanged(GeminiState.THINKING)
    }

    fun sendText(text: String) {
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

            // Check setupComplete in any casing
            if (element.containsKey("setupComplete") || element.containsKey("setup_complete")) {
                Log.d("GeminiWS", "Setup Complete received")
                isReady = true
                onStatusUpdate("Setup complete!")
                onReady()
            }

            // Check for error
            val errorEl = element["error"] ?: element["Error"]
            if (errorEl != null) {
                val errorObj = errorEl.jsonObject
                val msg = errorObj["message"]?.jsonPrimitive?.content ?: errorObj.toString()
                Log.e("GeminiWS", "Server Error: $msg")
                isReady = false
                onError(msg)
                return
            }

            // Check serverContent
            val scEl = element["serverContent"] ?: element["server_content"]
            if (scEl != null) {
                handleServerContent(scEl.jsonObject)
            }

            // Check toolCall
            val tcEl = element["toolCall"] ?: element["tool_call"]
            if (tcEl != null) {
                val tcObj = tcEl.jsonObject
                val fcEl = tcObj["functionCalls"] ?: tcObj["function_calls"]
                if (fcEl != null) {
                    val calls = fcEl.jsonArray.map { callEl ->
                        val callObj = callEl.jsonObject
                        FunctionCall(
                            name = callObj["name"]!!.jsonPrimitive.content,
                            id = callObj["id"]!!.jsonPrimitive.content,
                            args = (callObj["args"] as? JsonObject)?.mapValues { it.value }
                        )
                    }
                    handleToolCall(ToolCall(calls))
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiWS", "Error parsing message", e)
            onStatusUpdate("Parse error: ${e.message}")
        }
    }

    private fun handleServerContent(contentObj: JsonObject) {
        // Handle interruption — flush audio playback, mark turn ended
        val interruptedEl = contentObj["interrupted"]
        if (interruptedEl?.jsonPrimitive?.booleanOrNull == true) {
            Log.d("GeminiWS", "Model interrupted")
            isModelSpeaking = false
            onStateChanged(GeminiState.IDLE)
            onInterrupted()
        }

        // Detect turn complete — resume mic
        val turnCompleteEl = contentObj["turnComplete"]
        if (turnCompleteEl?.jsonPrimitive?.booleanOrNull == true) {
            Log.d("GeminiWS", "Turn complete")
            isModelSpeaking = false
            onStateChanged(GeminiState.IDLE)
            onTurnComplete()
        }

        val modelTurnKey = when {
            contentObj.containsKey("modelTurn") -> "modelTurn"
            contentObj.containsKey("model_turn") -> "model_turn"
            else -> null
        }
        if (modelTurnKey != null && contentObj.containsKey(modelTurnKey)) {
            isModelSpeaking = true
            onStateChanged(GeminiState.SPEAKING)
            val partsKey = when {
                contentObj[modelTurnKey]!!.jsonObject.containsKey("parts") -> "parts"
                else -> null
            }
            if (partsKey != null) {
                val parts = contentObj[modelTurnKey]!!.jsonObject[partsKey]!!.jsonArray
                for (partEl in parts) {
                    val partObj = partEl.jsonObject
                    val inlineKey = when {
                        partObj.containsKey("inlineData") -> "inlineData"
                        partObj.containsKey("inline_data") -> "inline_data"
                        else -> null
                    }
                    if (inlineKey != null) {
                        val inlineObj = partObj[inlineKey]!!.jsonObject
                        val audioBytes = Base64.decode(inlineObj["data"]!!.jsonPrimitive.content, Base64.DEFAULT)
                        onAudioReceived(audioBytes)
                    }
                }
            }
        }

        val inTransKey = when {
            contentObj.containsKey("inputTranscription") -> "inputTranscription"
            contentObj.containsKey("input_transcription") -> "input_transcription"
            else -> null
        }
        if (inTransKey != null && contentObj.containsKey(inTransKey)) {
            onStateChanged(GeminiState.LISTENING)
            val text = contentObj[inTransKey]!!.jsonObject["text"]!!.jsonPrimitive.content
            onUserTextReceived(text)
        }

        val outTransKey = when {
            contentObj.containsKey("outputTranscription") -> "outputTranscription"
            contentObj.containsKey("output_transcription") -> "output_transcription"
            else -> null
        }
        if (outTransKey != null && contentObj.containsKey(outTransKey)) {
            val text = contentObj[outTransKey]!!.jsonObject["text"]!!.jsonPrimitive.content
            onGeminiTextReceived(text)
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
        Log.d("GeminiWS", "Sent tool responses: $responseMessage")
    }

    fun disconnect() {
        isReady = false
        webSocket?.close(1000, "App closed")
        webSocket = null
    }
}
