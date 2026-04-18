package trucker.geminilive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AudioPlayer {
    @Volatile
    private var audioTrack: AudioTrack? = null
    // Increase buffer size to 20x min to handle burst data and avoid underruns during processing
    private val bufferSize = AudioTrack.getMinBufferSize(
        AudioConfig.OUTPUT_SAMPLE_RATE,
        AudioConfig.CHANNEL_CONFIG_OUT,
        AudioConfig.AUDIO_FORMAT
    ) * 20

    // Pre-roll buffer: accumulate 200ms of audio before starting playback to prevent underrun
    // 24000Hz * 2 channels * 2 bytes/sample * 0.2s = 19200 bytes
    private val PRE_ROLL_BYTES = (AudioConfig.OUTPUT_SAMPLE_RATE * 2 * 2 * 200) / 1000

    private val lock = Any()
    private var isBuffering = false
    private var totalFramesWritten: Long = 0
    private var preRollAccumulated: Int = 0
    private var isPreRollComplete = false
    private val preRollQueue = mutableListOf<ByteArray>()
    private val bufferQueue = mutableListOf<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Shared audio session ID for echo cancellation
    val audioSessionId: Int
        get() = synchronized(lock) {
            audioTrack?.audioSessionId ?: AudioTrack.ERROR
        }
    
    // Channel to offload AudioTrack.write from the network thread. 
    // Using Any to support both audio data (ByteArray) and completion markers.
    private val audioChannel = Channel<Any>(Channel.UNLIMITED)
    var onPlaybackComplete: (() -> Unit)? = null

    init {
        // Dedicated playback loop to avoid blocking WebSocket thread
        scope.launch(Dispatchers.Default) {
            for (msg in audioChannel) {
                try {
                    when (msg) {
                        is ByteArray -> processAndPlay(msg)
                        is SignalCompletionMarker -> {
                            waitForPlaybackToFinish()
                            onPlaybackComplete?.invoke()
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e("AudioPlayer", "Error in playback loop", e)
                    }
                }
            }
            // Signal when the channel is closed
            onPlaybackComplete?.invoke()
        }
    }

    private object SignalCompletionMarker

    private suspend fun waitForPlaybackToFinish() {
        val track = synchronized(lock) { audioTrack } ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return
        
        val target = totalFramesWritten
        Log.d("AudioPlayer", "Waiting for playback to finish. Target frame: $target")
        
        while (true) {
            val head = try { 
                // Convert uint32 to long
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL 
            } catch (e: Exception) { 
                break 
            }
            
            if (head >= target) break
            delay(40) // Check every 40ms
        }
        Log.d("AudioPlayer", "Playback finished at head: $target")
    }

    private fun processAndPlay(audioData: ByteArray) {
        if (!ensureInitialized()) return

        val track = synchronized(lock) { audioTrack }
        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
            try {
                // Convert to stereo just-in-time if needed
                val dataToPlay = monoToStereo(audioData)

                synchronized(lock) {
                    // Handle pre-roll phase: accumulate audio before starting playback
                    if (!isPreRollComplete) {
                        preRollQueue.add(dataToPlay)
                        preRollAccumulated += dataToPlay.size

                        if (preRollAccumulated >= PRE_ROLL_BYTES) {
                            isPreRollComplete = true
                            Log.d("AudioPlayer", "Pre-roll complete: $preRollAccumulated bytes buffered, starting playback")
                            track.play()

                            // Flush accumulated pre-roll data
                            for (chunk in preRollQueue) {
                                writeToTrack(track, chunk)
                            }
                            preRollQueue.clear()
                        }
                        return
                    }
                }

                // Normal playback after pre-roll
                writeToTrack(track, dataToPlay)
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error writing to AudioTrack", e)
            }
        }
    }

    private fun writeToTrack(track: AudioTrack, data: ByteArray) {
        val result = track.write(data, 0, data.size)
        if (result > 0) {
            // 4 bytes per frame (Stereo 16-bit)
            totalFramesWritten += result / 4
        } else if (result < 0) {
            Log.e("AudioPlayer", "AudioTrack.write error: $result")
        }
    }

    fun startBuffering() {
        synchronized(lock) {
            isBuffering = true
            bufferQueue.clear()
            preRollAccumulated = 0
            isPreRollComplete = false
            preRollQueue.clear()
        }
    }

    fun stopBufferingAndPlay() {
        val backlog = synchronized(lock) {
            isBuffering = false
            val list = bufferQueue.toList()
            bufferQueue.clear()
            list
        }
        
        backlog.forEach { data ->
            audioChannel.trySend(data)
        }
    }

    private fun ensureInitialized(): Boolean {
        val currentTrack = audioTrack
        if (currentTrack != null && currentTrack.state == AudioTrack.STATE_INITIALIZED) {
            return true
        }
        
        synchronized(lock) {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                return true
            }
            try {
                // Request Communication Mode to trigger hardware AEC optimizations on Samsung devices
                val audioManager = android.content.Context.AUDIO_SERVICE.let {
                    // This is a bit of a workaround since we don't have direct Context access here.
                    // Assuming this AudioPlayer is managed by something that could inject context if needed,
                    // but for now, we rely on the attributes.
                    null
                }
                
                Log.d("AudioPlayer", "Initializing AudioTrack...")
                val newTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioConfig.AUDIO_FORMAT)
                            .setSampleRate(AudioConfig.OUTPUT_SAMPLE_RATE)
                            .setChannelMask(AudioConfig.CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                Log.d("AudioPlayer", "AudioTrack initialized, setting gain to 0.75f, pre-roll: $PRE_ROLL_BYTES bytes")
                newTrack.setVolume(0.75f)
                // Don't call play() yet - wait for pre-roll buffer to fill
                audioTrack = newTrack
                totalFramesWritten = 0
                preRollAccumulated = 0
                isPreRollComplete = false
                preRollQueue.clear()
                return true
            } catch (e: Exception) {
                Log.e("AudioPlayer", "AudioTrack initialization failed", e)
                return false
            }
        }
    }

    private fun monoToStereo(monoData: ByteArray): ByteArray {
        if (AudioConfig.CHANNEL_CONFIG_OUT != AudioFormat.CHANNEL_OUT_STEREO) return monoData
        
        val stereoData = ByteArray(monoData.size * 2)
        for (i in 0 until monoData.size step 2) {
            if (i + 1 >= monoData.size) break
            // Left
            stereoData[i * 2] = monoData[i]
            stereoData[i * 2 + 1] = monoData[i + 1]
            // Right
            stereoData[i * 2 + 2] = monoData[i]
            stereoData[i * 2 + 3] = monoData[i + 1]
        }
        return stereoData
    }

    fun play(audioData: ByteArray) {
        if (isBuffering) {
            synchronized(lock) {
                if (isBuffering) {
                    bufferQueue.add(audioData)
                    return
                }
            }
        }
        audioChannel.trySend(audioData)
    }

    /**
     * Queues a marker to signal when all currently queued audio has finished playing.
     * This will eventually trigger the [onPlaybackComplete] callback.
     */
    fun requestCompletionSignal() {
        audioChannel.trySend(SignalCompletionMarker)
    }

    /**
     * Initializes the AudioTrack if not already initialized and returns the audio session ID.
     * This ensures the audio session is created and can be shared with AudioRecorder for echo cancellation.
     */
    fun initializeAndGetSessionId(): Int {
        ensureInitialized()
        return audioSessionId
    }

    fun flush() {
        Log.d("AudioPlayer", "flush() called")
        // Clear the pending audio channel
        while (audioChannel.tryReceive().isSuccess) { /* drain */ }

        synchronized(lock) {
            try {
                audioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        // To stop playback immediately, use pause(), then flush().
                        // stop() waits for the buffer to drain, which we don't want.
                        if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                            Log.d("AudioPlayer", "  Pausing for flush")
                            pause()
                        }
                        Log.d("AudioPlayer", "  Flushing and restarting")
                        flush()
                        // Reset pre-roll after flush
                        preRollAccumulated = 0
                        isPreRollComplete = false
                        preRollQueue.clear()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error flushing AudioTrack", e)
            }
        }
    }

    fun stop() {
        Log.d("AudioPlayer", "stop() called")
        while (audioChannel.tryReceive().isSuccess) { /* drain */ }
        
        synchronized(lock) {
            try {
                audioTrack?.apply {
                    Log.d("AudioPlayer", "Stopping and releasing AudioTrack")
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        try {
                            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                                stop()
                            }
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Error calling stop()", e)
                        }
                        release()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error stopping AudioTrack", e)
            } finally {
                audioTrack = null
                preRollAccumulated = 0
                isPreRollComplete = false
                preRollQueue.clear()
            }
        }
    }

    fun release() {
        Log.d("AudioPlayer", "release() called")
        stop()
        scope.cancel()
    }
}
