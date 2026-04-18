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
                                        onAudioData(chunk)
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
                            onAudioData(staging.copyOf(stagedBytes))
                        }
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
        }
    }

    fun unmute() {
        if (isModelSpeaking) {
            Log.d("AudioRecorder", "Unmuting microphone")
            isModelSpeaking = false
        }
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
