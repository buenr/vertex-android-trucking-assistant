package trucker.geminilive.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BidiGenerateContentClientMessage(
    val setup: BidiGenerateContentSetup? = null,
    @SerialName("realtimeInput") val realtimeInput: BidiGenerateContentRealtimeInput? = null,
    @SerialName("toolResponse") val toolResponse: ToolResponse? = null
)

@Serializable
data class BidiGenerateContentSetup(
    val model: String,
    @SerialName("generationConfig") val generationConfig: GenerationConfig? = null,
    @SerialName("realtimeInputConfig") val realtimeInputConfig: RealtimeInputConfig? = null,
    @SerialName("systemInstruction") val systemInstruction: Content? = null,
    val tools: List<Tool>? = null
)

@Serializable
data class GenerationConfig(
    @SerialName("responseModalities") val responseModalities: List<String>? = null,
    @SerialName("speechConfig") val speechConfig: SpeechConfig? = null
)

@Serializable
data class SpeechConfig(
    @SerialName("languageCode") val languageCode: String? = null,
    @SerialName("voiceConfig") val voiceConfig: VoiceConfig? = null
)

@Serializable
data class RealtimeInputConfig(
    @SerialName("automaticActivityDetection") val automaticActivityDetection: AutomaticActivityDetection? = null
)

@Serializable
data class AutomaticActivityDetection(
    val disabled: Boolean? = null,
    @SerialName("startOfSpeechSensitivity") val startOfSpeechSensitivity: String? = null,
    @SerialName("endOfSpeechSensitivity") val endOfSpeechSensitivity: String? = null,
    @SerialName("prefixPaddingMs") val prefixPaddingMs: Int? = null,
    @SerialName("silenceDurationMs") val silenceDurationMs: Int? = null
)

@Serializable
data class VoiceConfig(
    @SerialName("prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
)

@Serializable
data class PrebuiltVoiceConfig(
    @SerialName("voiceName") val voiceName: String? = null
)

@Serializable
data class Tool(
    @SerialName("functionDeclarations") val functionDeclarations: List<FunctionDeclaration>
)

@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Schema? = null
)

@Serializable
data class Schema(
    val type: String,
    val properties: Map<String, Schema>? = null,
    val required: List<String>? = null,
    val items: Schema? = null,
    val description: String? = null
)

@Serializable
data class ToolResponse(
    @SerialName("functionResponses") val functionResponses: List<FunctionResponse>
)

@Serializable
data class FunctionResponse(
    val name: String,
    val id: String,
    val response: JsonElement
)

@Serializable
data class BidiGenerateContentRealtimeInput(
    val text: String? = null,
    val audio: Blob? = null,
    val video: Blob? = null
)

@Serializable
data class Blob(
    val data: String,
    @SerialName("mimeType") val mimeType: String
)

@Serializable
data class Content(
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    @SerialName("inlineData") val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val data: String,
    @SerialName("mimeType") val mimeType: String
)

@Serializable
data class BidiGenerateContentServerMessage(
    @SerialName("serverContent") val serverContent: ServerContent? = null,
    @SerialName("toolCall") val toolCall: ToolCall? = null,
    @SerialName("setupComplete") val setupComplete: SetupComplete? = null,
    val error: ServerError? = null
)

@Serializable
class SetupComplete

@Serializable
data class ServerError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

@Serializable
data class ServerContent(
    @SerialName("modelTurn") val modelTurn: Content? = null,
    @SerialName("turnComplete") val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null
)

@Serializable
data class ToolCall(
    @SerialName("functionCalls") val functionCalls: List<FunctionCall>
)

@Serializable
data class FunctionCall(
    val name: String,
    val id: String,
    val args: Map<String, JsonElement>? = null
)

enum class GeminiState(val label: String) {
    IDLE("Ready"),
    LISTENING("Listening..."),
    THINKING("Thinking..."),
    WORKING("Checking Data..."), // Used during Tool Calls
    SPEAKING("Speaking...")
}




