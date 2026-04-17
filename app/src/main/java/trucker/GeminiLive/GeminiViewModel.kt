package trucker.GeminiLive

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var interruptionJob: Job? = null
    private var isRecording = false

    init {
        geminiClient = GeminiWebSocketClient(
            apiKey = trucker.GeminiLive.BuildConfig.GEMINI_API_KEY,
            onStatusUpdate = { status ->
                updateUi { it.copy(status = status) }
                addLog(status)
            },
            onStateChanged = { state ->
                updateUi { 
                    it.copy(
                        aiState = state,
                        currentTool = if (state != GeminiState.WORKING) "" else it.currentTool
                    ) 
                }
            },
            onReady = {
                updateUi { it.copy(status = "Connected & Listening") }
                addLog("Ready — starting mic")
                startRecorder()
            },
            onAudioReceived = { audioData ->
                audioPlayer.play(audioData)
                if (interruptionJob != null) {
                    viewModelScope.launch {
                        interruptionJob?.cancel()
                        interruptionJob = null
                    }
                }
            },
            onInterrupted = {
                viewModelScope.launch {
                    interruptionJob?.cancel()
                    interruptionJob = launch {
                        delay(200)
                        audioPlayer.flush()
                        addLog("Interruption confirmed")
                        interruptionJob = null
                    }
                    addLog("Interrupted — waiting grace period")
                    startRecorder()
                }
            },
            onTurnComplete = {
                viewModelScope.launch {
                    interruptionJob?.cancel()
                    interruptionJob = null
                    addLog("Turn complete — resuming mic")
                    startRecorder()
                }
            },
            onToolCallStarted = { toolName ->
                updateUi { it.copy(currentTool = toolName) }
                addLog("TOOL CALL: $toolName")
            },
            onError = { error ->
                addLog("ERROR: $error")
                updateUi { it.copy(lastError = error, status = "Error") }
                stop()
            }
        )
    }

    private fun updateUi(reducer: (GeminiUiState) -> GeminiUiState) {
        viewModelScope.launch {
            _uiState.value = reducer(_uiState.value)
        }
    }

    private fun addLog(message: String) {
        viewModelScope.launch {
            val timestamped = "[${System.currentTimeMillis() % 100000}] $message"
            _uiState.value = _uiState.value.copy(
                log = (_uiState.value.log + timestamped).takeLast(100)
            )
        }
    }

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

    fun toggleConnection() {
        if (uiState.value.isConnected) {
            stop()
        } else {
            start()
        }
    }

    private fun start() {
        // Immediate reset for a clean start
        _uiState.value = _uiState.value.copy(
            isConnected = true,
            status = "Connecting...",
            aiState = GeminiState.IDLE,
            currentTool = "",
            log = emptyList(),
            lastError = "",
            userText = "",
            geminiText = ""
        )
        addLog("Starting session...")
        try {
            geminiClient.connect()
        } catch (e: Exception) {
            updateUi { it.copy(status = "Failed", lastError = e.message ?: "Unknown error") }
            addLog("Connection failed: ${e.message}")
        }
    }

    private fun stop() {
        interruptionJob?.cancel()
        interruptionJob = null
        isRecording = false
        audioRecorder.stop()
        audioPlayer.stop()
        geminiClient.disconnect()
        
        updateUi { it.copy(isConnected = false, status = "Disconnected") }
        addLog("Session stopped")
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
    val currentTool: String = "",
    val lastError: String = "",
    val log: List<String> = emptyList()
)
