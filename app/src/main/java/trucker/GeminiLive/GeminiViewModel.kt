package trucker.GeminiLive

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import trucker.GeminiLive.audio.AudioPlayer
import trucker.GeminiLive.audio.AudioRecorder
import trucker.GeminiLive.network.GeminiState
import trucker.GeminiLive.network.GeminiWebSocketClient

class GeminiViewModel : ViewModel() {
    private val _uiState = mutableStateOf(GeminiUiState())
    val uiState: State<GeminiUiState> = _uiState

    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer()
    private lateinit var geminiClient: GeminiWebSocketClient

    private fun addLog(message: String) {
        val timestamped = "[${System.currentTimeMillis() % 100000}] $message"
        _uiState.value = _uiState.value.copy(
            log = _uiState.value.log + timestamped
        )
    }

    private var isRecording = false

    private fun startRecorder() {
        if (isRecording) return
        isRecording = true
        audioRecorder.start { audioData ->
            geminiClient.sendAudio(audioData)
        }
    }

    private fun stopRecorder() {
        if (!isRecording) return
        isRecording = false
        audioRecorder.stop()
        geminiClient.sendAudioStreamEnd()
    }

    init {
        geminiClient = GeminiWebSocketClient(
            apiKey = trucker.GeminiLive.BuildConfig.GEMINI_API_KEY,
            onStatusUpdate = { status ->
                _uiState.value = _uiState.value.copy(status = status)
                addLog(status)
            },
            onStateChanged = { state ->
                _uiState.value = _uiState.value.copy(aiState = state)
            },
            onReady = {
                _uiState.value = _uiState.value.copy(status = "Connected & Listening")
                addLog("Ready — audio recorder starting")
                startRecorder()
            },
            onAudioReceived = { audioData ->
                audioPlayer.play(audioData)
            },
            onInterrupted = {
                audioPlayer.flush()
                addLog("Model interrupted — resuming mic")
                startRecorder()
            },
            onTurnComplete = {
                addLog("Turn complete — resuming mic")
                startRecorder()
            },
            onUserTextReceived = { text ->
                _uiState.value = _uiState.value.copy(userText = text)
                stopRecorder()
            },
            onGeminiTextReceived = { text ->
                _uiState.value = _uiState.value.copy(geminiText = text)
            },
            onError = { error ->
                addLog("ERROR: $error")
                _uiState.value = _uiState.value.copy(lastError = error, status = "Error")
                stop()
            }
        )
    }

    fun toggleConnection() {
        if (uiState.value.isConnected) {
            stop()
        } else {
            start()
        }
    }

    private fun start() {
        _uiState.value = _uiState.value.copy(
            isConnected = true,
            status = "Connecting...",
            log = emptyList(),
            lastError = "",
            userText = "",
            geminiText = ""
        )
        addLog("Starting connection...")
        try {
            geminiClient.connect()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(status = "Failed", lastError = e.message ?: "Unknown error")
            addLog("Connection failed: ${e.message}")
        }
    }

    private fun stop() {
        isRecording = false
        audioRecorder.stop()
        audioPlayer.stop()
        geminiClient.disconnect()
        addLog("Disconnected")
        _uiState.value = _uiState.value.copy(isConnected = false, status = "Disconnected")
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

data class GeminiUiState(
    val isConnected: Boolean = false,
    val aiState: GeminiState = GeminiState.IDLE,
    val status: String = "Disconnected",
    val userText: String = "",
    val geminiText: String = "",
    val lastError: String = "",
    val log: List<String> = emptyList()
)
