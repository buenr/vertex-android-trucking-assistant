package trucker.geminilive

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import trucker.geminilive.audio.AudioPlayer
import trucker.geminilive.audio.AudioRecorder
import trucker.geminilive.audio.SoundManager
import trucker.geminilive.network.GeminiState
import trucker.geminilive.network.GeminiWebSocketClient
import trucker.geminilive.network.VertexAuth

class GeminiViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = mutableStateOf(GeminiUiState())
    val uiState: State<GeminiUiState> = _uiState

    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer()
    private val soundManager = SoundManager()
    private var geminiClient: GeminiWebSocketClient
    private var interruptionJob: Job? = null
    private var toolTimerJob: Job? = null
    private var pendingState: GeminiState? = null
    private var isRecording = false

    init {
        val projectId = VertexAuth.getProjectId(application)
        geminiClient = GeminiWebSocketClient(
            projectId = projectId,
            onStatusUpdate = { status ->
                updateUi { it.copy(status = status) }
                addLog(status)
            },
            onStateChanged = { state ->
                // If the 3-second processing timer is running, just queue the state change
                if (toolTimerJob != null) {
                    pendingState = state
                } else {
                    // Handle state transitions with synchronization for WORKING state
                    when (state) {
                        GeminiState.WORKING -> {
                            pendingState = state
                            updateUi { it.copy(aiState = state) }
                            // Start 3-second synchronized delay
                            audioPlayer.startBuffering()
                            soundManager.startWorkingLoop()

                            toolTimerJob?.cancel()
                            toolTimerJob = viewModelScope.launch {
                                delay(3000)
                                // 3 seconds are up: release buffer and stop chime
                                soundManager.stopLoop()
                                audioPlayer.stopBufferingAndPlay()
                                toolTimerJob = null

                                // Apply the most recent state that arrived while we were waiting
                                pendingState?.let { lastState ->
                                    updateUi { it.copy(aiState = lastState) }
                                }
                            }
                        }
                        GeminiState.THINKING -> {
                            pendingState = state
                            updateUi { it.copy(aiState = state) }
                            soundManager.startThinkingLoop()
                        }
                        else -> {

                            pendingState = state
                            updateUi {
                                it.copy(
                                    aiState = state,
                                    currentTool = if (state != GeminiState.WORKING) "" else it.currentTool
                                )
                            }
                            soundManager.stopLoop()
                        }
                    }
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
        viewModelScope.launch {
            try {
                val token = VertexAuth.getAccessToken(getApplication())
                geminiClient.connect(token)
            } catch (e: Exception) {
                updateUi { it.copy(status = "Failed", lastError = e.message ?: "Unknown error") }
                addLog("Connection failed: ${e.message}")
            }
        }
    }

    private fun stop() {
        interruptionJob?.cancel()
        interruptionJob = null
        toolTimerJob?.cancel()
        toolTimerJob = null
        pendingState = null
        stopRecorder()
        audioPlayer.stop()
        soundManager.stopLoop()
        geminiClient.disconnect()
        
        updateUi { it.copy(isConnected = false, status = "Disconnected") }
        addLog("Session stopped")
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        soundManager.release()
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




