package trucker.geminilive

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    private var isTurnCompletePending = false

    init {
        val projectId = VertexAuth.getProjectId(application)
        audioPlayer.onPlaybackComplete = {
            Log.d("GeminiVM", "Playback complete, entering silence window (600ms)")
            viewModelScope.launch {
                delay(600)
                Log.d("GeminiVM", "Silence window passed, re-enabling microphone. Pending turn complete: $isTurnCompletePending")
                if (isTurnCompletePending) {
                    isTurnCompletePending = false
                    audioRecorder.unmute()
                    startRecorder()
                } else {
                    Log.d("GeminiVM", "Turn status no longer pending, skipping recorder restart")
                    audioRecorder.unmute()
                }
            }
        }
        geminiClient = GeminiWebSocketClient(
            projectId = projectId,
            onStatusUpdate = { status ->
                updateUi { it.copy(status = status) }
                addLog(status)
            },
            onStateChanged = { state ->
                viewModelScope.launch {
                    try {
                        Log.d("GeminiVM", "State changed to: $state")
                        addLog("State -> $state")
                        
                        // If we transition to a state other than WORKING, we should immediately 
                        // stop the tool loop/timer so that UI and audio are in sync.
                        if (state != GeminiState.WORKING && toolTimerJob != null) {
                            Log.d("GeminiVM", "  $state state reached during WORKING loop - cancelling timer")
                            toolTimerJob?.cancel()
                            toolTimerJob = null
                            soundManager.stopLoop()
                            audioPlayer.stopBufferingAndPlay()
                            pendingState = state
                            updateUi { it.copy(aiState = state) }
                        } else if (toolTimerJob != null && state != GeminiState.WORKING) {
                            Log.v("GeminiVM", "  State change pending (waiting for timer)")
                            pendingState = state
                        } else {
                            // Handle state transitions with synchronization for WORKING state
                            when (state) {
                                GeminiState.WORKING -> {
                                    pendingState = state
                                    updateUi { it.copy(aiState = state) }
                                    
                                    // Only initiate buffering/loop and timer if this is the FIRST tool in a sequence
                                    if (toolTimerJob == null) {
                                        Log.d("GeminiVM", "  Starting tool timer loop")
                                        audioPlayer.startBuffering()
                                        soundManager.startWorkingLoop()

                                        toolTimerJob = launch {
                                            try {
                                                // Randomized minimum perception delay (2-6s)
                                                val minDelay = (2000..6000).random().toLong()
                                                Log.v("GeminiVM", "    Timer set for ${minDelay}ms")
                                                delay(minDelay)
                                                
                                                // NO CHEATING: If the server is still actually working after the 
                                                // minimum delay, we stay in the working loop until it finishes.
                                                while (pendingState == GeminiState.WORKING) {
                                                    delay(100)
                                                }

                                                Log.d("GeminiVM", "    Timer & Work finished, stopping loop")
                                                // Both min delay passed AND actual work is finished
                                                soundManager.stopLoop()
                                                audioPlayer.stopBufferingAndPlay()
                                                toolTimerJob = null

                                                // Apply the final state reached after all tools/delays finished
                                                pendingState?.let { lastState ->
                                                    Log.d("GeminiVM", "    Applying final state: $lastState")
                                                    updateUi { it.copy(aiState = lastState) }
                                                }
                                            } catch (e: Exception) {
                                                if (e !is CancellationException) {
                                                    Log.e("GeminiVM", "Error in toolTimerJob", e)
                                                }
                                            }
                                        }
                                    }
                                }
                                GeminiState.THINKING -> {
                                    Log.d("GeminiVM", "  Starting thinking loop")
                                    pendingState = state
                                    updateUi { it.copy(aiState = state) }
                                    soundManager.startThinkingLoop()
                                }
                                else -> {
                                    Log.d("GeminiVM", "  Clearing loops and applying state")
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
                    } catch (e: Exception) {
                        Log.e("GeminiVM", "Critical error in onStateChanged", e)
                        addLog("CRITICAL ERROR: ${e.message}")
                    }
                }
            },
            onReady = {
                updateUi { it.copy(status = "Connected & Listening") }
                addLog("Ready — starting mic")
                soundManager.playStartupBeep()
                startRecorder()
            },
            onAudioReceived = { audioData ->
                // Reset turn complete flag if new audio arrives - we're still in the turn
                if (isTurnCompletePending) {
                    Log.d("GeminiVM", "New audio arrived after turn complete, resetting pending flag")
                    isTurnCompletePending = false
                }
                // Mute microphone when model starts speaking to prevent self-interruption
                audioRecorder.mute()
                audioPlayer.play(audioData)
                viewModelScope.launch {
                    if (interruptionJob != null) {
                        Log.d("GeminiVM", "Cancelling pending interruption job as audio arrived")
                        interruptionJob?.cancel()
                        interruptionJob = null
                    }
                }
            },
            onInterrupted = {
                viewModelScope.launch {
                    interruptionJob?.cancel()
                    interruptionJob = null
                    isTurnCompletePending = false
                    audioPlayer.flush()
                    audioRecorder.unmute()
                    addLog("Interruption confirmed")
                    // Only restart recorder if it's not already running
                    if (!isRecording) {
                        startRecorder()
                    }
                }
            },
            onTurnComplete = {
                viewModelScope.launch {
                    interruptionJob?.cancel()
                    interruptionJob = null
                    isTurnCompletePending = true
                    addLog("Turn complete, waiting for playback")
                    audioPlayer.requestCompletionSignal()
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

    private fun startRecorder(force: Boolean = false) {
        if (isRecording && !force) {
            Log.d("GeminiVM", "startRecorder skipped: already recording")
            return
        }

        if (force && isRecording) {
            Log.d("GeminiVM", "startRecorder: forcing restart")
            audioRecorder.stop()
        }

        Log.d("GeminiVM", "startRecorder: starting AudioRecorder")
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
        audioPlayer.release()
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




