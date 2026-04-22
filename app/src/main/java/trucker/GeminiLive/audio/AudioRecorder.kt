package trucker.geminilive.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioRecorder {
    @Volatile
    private var isRecording = false
    private val minBufferSize = AudioRecord.getMinBufferSize(
        AudioConfig.INPUT_SAMPLE_RATE,
        AudioConfig.CHANNEL_CONFIG_IN,
        AudioConfig.AUDIO_FORMAT
    )

    private val lock = Any()
    private var recordingThread: Thread? = null

    // Software mute to prevent model from hearing its own output
    @Volatile
    private var isModelSpeaking = false

    // Batch audio chunks to reduce network overhead on LTE
    // Accumulate 100ms of audio (5x 20ms chunks) before sending
    private val BATCH_SIZE_MS = 100
    private val batchBuffer = mutableListOf<ByteArray>()
    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null
    private var lastSendTime = 0L
    
    // Voice Activity Detection (VAD) for poor network conditions
    // Saves 100% bandwidth during silence by not transmitting silent frames
    @Volatile
    private var vadEnabled = false
    private val VAD_ENERGY_THRESHOLD = 500  // RMS energy threshold for speech detection
    private val VAD_HANGOVER_MS = 300       // Continue sending for 300ms after speech ends
    private var isCurrentlySpeaking = false
    private var lastSpeechTimestamp = 0L

    @SuppressLint("MissingPermission")
    fun start(onAudioData: (ByteArray) -> Unit) {
        Log.d("AudioRecorder", "start() called")
        synchronized(lock) {
            if (isRecording) {
                Log.d("AudioRecorder", "start() skipped: already recording")
                return
            }

            try {
                isRecording = true
                onAudioDataCallback = onAudioData
                lastSendTime = System.currentTimeMillis()
                recordingThread = Thread {
                    var recorder: AudioRecord? = null
                    var ns: NoiseSuppressor? = null
                    var aec: AcousticEchoCanceler? = null

                    try {
                        Log.d("AudioRecorder", "Initializing AudioRecord...")
                        Log.d("AudioRecorder", "Creating AudioRecord with VOICE_COMMUNICATION source")
                        recorder = AudioRecord(
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            AudioConfig.INPUT_SAMPLE_RATE,
                            AudioConfig.CHANNEL_CONFIG_IN,
                            AudioConfig.AUDIO_FORMAT,
                            minBufferSize
                        )

                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            Log.e("AudioRecorder", "AudioRecord initialization failed")
                            return@Thread
                        }
                        
                        // Hardware Echo Cancellation only - NoiseSuppressor often conflicts with Samsung's hardware-optimized AEC
                        if (AcousticEchoCanceler.isAvailable()) {
                            Log.d("AudioRecorder", "Enabling AcousticEchoCanceler")
                            aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                                enabled = true
                            }
                        }

                        Log.d("AudioRecorder", "Starting recording hardware...")
                        recorder.startRecording()
                        
                        val readBuffer = ByteArray(minBufferSize)
                        val targetChunkSize = AudioConfig.TARGET_INPUT_CHUNK_BYTES
                        val staging = ByteArray(targetChunkSize * 8)
                        var stagedBytes = 0
                        Log.d("AudioRecorder", "Recording thread loop starting")

                        while (isRecording) {
                            val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                            if (bytesRead > 0 && isRecording) {
                                // Skip audio processing when model is speaking to prevent self-interruption
                                if (isModelSpeaking) {
                                    continue
                                }
                                
                                // Down-mix stereo to mono preserving phase alignment
                                val processedData = downMixStereoToMono(readBuffer, bytesRead)

                                var offset = 0
                                val dataSize = processedData.size
                                while (offset < dataSize && isRecording) {
                                    val writable = minOf(staging.size - stagedBytes, dataSize - offset)
                                    System.arraycopy(processedData, offset, staging, stagedBytes, writable)
                                    stagedBytes += writable
                                    offset += writable

                                    while (stagedBytes >= targetChunkSize && isRecording) {
                                        val chunk = staging.copyOfRange(0, targetChunkSize)
                                        val remaining = stagedBytes - targetChunkSize
                                        if (remaining > 0) {
                                            System.arraycopy(staging, targetChunkSize, staging, 0, remaining)
                                        }
                                        stagedBytes = remaining
                                        
                                        // Apply VAD: skip silent frames when VAD is enabled on poor connections
                                        if (vadEnabled) {
                                            val rms = calculateRmsEnergy(chunk)
                                            val now = System.currentTimeMillis()
                                            
                                            if (rms > VAD_ENERGY_THRESHOLD) {
                                                // Speech detected
                                                isCurrentlySpeaking = true
                                                lastSpeechTimestamp = now
                                                addToBatch(chunk, onAudioData)
                                            } else if (isCurrentlySpeaking) {
                                                // Silence but within hangover period - continue sending
                                                if (now - lastSpeechTimestamp < VAD_HANGOVER_MS) {
                                                    addToBatch(chunk, onAudioData)
                                                } else {
                                                    // Hangover expired, stop sending
                                                    isCurrentlySpeaking = false
                                                    // Flush any pending batch before going silent
                                                    flushBatch(onAudioData)
                                                }
                                            }
                                            // If not speaking and past hangover, skip this frame entirely
                                        } else {
                                            // Normal operation: batch all chunks
                                            addToBatch(chunk, onAudioData)
                                        }
                                    }

                                    if (stagedBytes == staging.size) {
                                        Log.w("AudioRecorder", "Staging buffer full, forced flush")
                                        onAudioData(staging.copyOf(stagedBytes))
                                        stagedBytes = 0
                                    }
                                }
                            } else if (bytesRead < 0) {
                                Log.e("AudioRecorder", "AudioRecord.read error: $bytesRead")
                                break
                            }
                        }

                        if (stagedBytes > 0 && isRecording) {
                            addToBatch(staging.copyOf(stagedBytes), onAudioData)
                        }
                        // Flush any remaining batched audio
                        flushBatch(onAudioData)
                    } catch (e: Exception) {
                        Log.e("AudioRecorder", "Error in recording thread", e)
                    } finally {
                        Log.d("AudioRecorder", "Cleaning up hardware resources")
                        try {
                            ns?.release()
                            aec?.release()
                            if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
                                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    recorder.stop()
                                }
                                recorder.release()
                            }
                        } catch (e: Exception) {
                            Log.e("AudioRecorder", "Error during resource release", e)
                        }
                        Log.d("AudioRecorder", "Recording thread exiting")
                    }
                }.apply {
                    name = "AudioRecorderThread"
                    start()
                }
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error starting AudioRecorder", e)
                isRecording = false
            }
        }
    }

    /**
     * Calculates RMS (Root Mean Square) energy of a 16-bit PCM audio frame.
     * Higher values indicate louder audio. Used for VAD to detect speech vs silence.
     */
    private fun calculateRmsEnergy(pcmData: ByteArray): Double {
        var sumSquares = 0.0
        var sampleCount = 0
        
        // Process as 16-bit little-endian samples
        for (i in 0 until pcmData.size step 2) {
            if (i + 1 >= pcmData.size) break
            
            val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleCount++
        }
        
        return if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0
    }

    /**
     * Enables or disables Voice Activity Detection.
     * When enabled on poor connections, only speech frames are transmitted.
     */
    fun setVadEnabled(enabled: Boolean) {
        if (vadEnabled != enabled) {
            vadEnabled = enabled
            isCurrentlySpeaking = false  // Reset state on toggle
            Log.d("AudioRecorder", "VAD ${if (enabled) "enabled" else "disabled"} (threshold: $VAD_ENERGY_THRESHOLD, hangover: ${VAD_HANGOVER_MS}ms)")
        }
    }

    /**
     * Returns whether VAD is currently active.
     */
    fun isVadEnabled(): Boolean = vadEnabled

    /**
     * Returns whether VAD has currently detected active speech.
     */
    fun isCurrentlySpeaking(): Boolean = isCurrentlySpeaking

    /**
     * Down-mixes stereo 16-bit PCM to mono by averaging the left and right channels.
     */
    private fun downMixStereoToMono(stereoData: ByteArray, length: Int): ByteArray {
        val monoData = ByteArray(length / 2)
        var monoIdx = 0
        // Step by 4 bytes: 2 bytes for Left, 2 bytes for Right
        for (i in 0 until length step 4) {
            if (i + 3 >= length) break
            
            // PCM_16BIT is little-endian
            val left = ((stereoData[i + 1].toInt() shl 8) or (stereoData[i].toInt() and 0xFF)).toShort()
            val right = ((stereoData[i + 3].toInt() shl 8) or (stereoData[i + 2].toInt() and 0xFF)).toShort()
            
            // To preserve phase, we just take one channel or sum them carefully.
            // Averaging is usually safer for phase.
            val mono = ((left.toInt() + right.toInt()) / 2).toShort()
            
            monoData[monoIdx++] = (mono.toInt() and 0xFF).toByte()
            monoData[monoIdx++] = ((mono.toInt() shr 8) and 0xFF).toByte()
        }
        return monoData
    }

    fun mute() {
        if (!isModelSpeaking) {
            Log.d("AudioRecorder", "Muting microphone (model speaking)")
            isModelSpeaking = true
            // Flush batched audio immediately when muting
            synchronized(lock) {
                onAudioDataCallback?.let { flushBatch(it) }
            }
        }
    }

    fun unmute() {
        if (isModelSpeaking) {
            Log.d("AudioRecorder", "Unmuting microphone")
            isModelSpeaking = false
            lastSendTime = System.currentTimeMillis()
        }
    }

    /**
     * Adds a chunk to the batch buffer and sends when threshold is reached.
     * Reduces network writes from 50/sec (20ms chunks) to 10/sec (100ms batches).
     */
    private fun addToBatch(chunk: ByteArray, onAudioData: (ByteArray) -> Unit) {
        batchBuffer.add(chunk)

        // Send batch when we accumulate 100ms of audio or 100ms has elapsed
        val elapsedMs = System.currentTimeMillis() - lastSendTime
        val batchDurationMs = batchBuffer.size * AudioConfig.TARGET_INPUT_CHUNK_MS

        if (batchDurationMs >= BATCH_SIZE_MS || elapsedMs >= BATCH_SIZE_MS) {
            flushBatch(onAudioData)
        }
    }

    /**
     * Flushes batched audio by concatenating chunks and sending.
     */
    private fun flushBatch(onAudioData: (ByteArray) -> Unit) {
        if (batchBuffer.isEmpty()) return

        // Concatenate all chunks
        val totalSize = batchBuffer.sumOf { it.size }
        val batched = ByteArray(totalSize)
        var offset = 0
        for (chunk in batchBuffer) {
            System.arraycopy(chunk, 0, batched, offset, chunk.size)
            offset += chunk.size
        }
        batchBuffer.clear()
        lastSendTime = System.currentTimeMillis()

        Log.v("AudioRecorder", "Sending batched audio: ${batched.size} bytes (${totalSize / AudioConfig.TARGET_INPUT_CHUNK_BYTES} chunks)")
        onAudioData(batched)
    }

    fun stop() {
        Log.d("AudioRecorder", "stop() called")
        val threadToJoin: Thread?
        synchronized(lock) {
            isRecording = false
            threadToJoin = recordingThread
            recordingThread = null
        }
        
        try {
            threadToJoin?.join(500) // Wait up to 500ms for thread to exit
            if (threadToJoin?.isAlive == true) {
                Log.w("AudioRecorder", "Recording thread did not exit in time")
            }
        } catch (e: InterruptedException) {
            Log.e("AudioRecorder", "Interrupted while waiting for recording thread", e)
            Thread.currentThread().interrupt()
        }
    }
}
