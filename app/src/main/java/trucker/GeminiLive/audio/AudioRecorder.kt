package trucker.geminilive.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    
    private val isRecording = AtomicBoolean(false)
    private val minBufferSize = AudioRecord.getMinBufferSize(
        AudioConfig.INPUT_SAMPLE_RATE,
        AudioConfig.CHANNEL_CONFIG_IN,
        AudioConfig.AUDIO_FORMAT
    )

    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(onAudioData: (ByteArray) -> Unit) {
        synchronized(lock) {
            if (isRecording.get()) return

            try {
                val sessionAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AudioConfig.INPUT_SAMPLE_RATE,
                    AudioConfig.CHANNEL_CONFIG_IN,
                    AudioConfig.AUDIO_FORMAT,
                    minBufferSize
                ).apply {
                    if (state != AudioRecord.STATE_INITIALIZED) {
                        Log.e("AudioRecorder", "AudioRecord initialization failed")
                        return
                    }
                    
                    // Hardware Noise Suppression
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(audioSessionId).apply {
                            enabled = true
                        }
                    }
                    
                    // Hardware Echo Cancellation
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(audioSessionId).apply {
                            enabled = true
                        }
                    }

                    startRecording()
                }
                
                audioRecord = sessionAudioRecord
                isRecording.set(true)

                Thread {
                    val readBuffer = ByteArray(minBufferSize)
                    val targetChunkSize = AudioConfig.TARGET_INPUT_CHUNK_BYTES
                    val staging = ByteArray(targetChunkSize * 8)
                    var stagedBytes = 0

                    while (isRecording.get()) {
                        val currentRecord = synchronized(lock) { audioRecord }
                        if (currentRecord == null || !isRecording.get()) break

                        val bytesRead = currentRecord.read(readBuffer, 0, readBuffer.size)
                        if (bytesRead > 0 && isRecording.get()) {
                            
                            // Optimization for Galaxy Active 5: Down-mix Stereo to Mono
                            val processedData = if (AudioConfig.HARDWARE_INPUT_CHANNEL_COUNT == 2 && 
                                AudioConfig.TARGET_INPUT_CHANNEL_COUNT == 1) {
                                downMixStereoToMono(readBuffer, bytesRead)
                            } else {
                                readBuffer.copyOf(bytesRead)
                            }

                            var offset = 0
                            val dataSize = processedData.size
                            while (offset < dataSize) {
                                val writable = minOf(staging.size - stagedBytes, dataSize - offset)
                                System.arraycopy(processedData, offset, staging, stagedBytes, writable)
                                stagedBytes += writable
                                offset += writable

                                while (stagedBytes >= targetChunkSize && isRecording.get()) {
                                    val chunk = ByteArray(targetChunkSize)
                                    System.arraycopy(staging, 0, chunk, 0, targetChunkSize)
                                    val remaining = stagedBytes - targetChunkSize
                                    if (remaining > 0) {
                                        System.arraycopy(staging, targetChunkSize, staging, 0, remaining)
                                    }
                                    stagedBytes = remaining
                                    onAudioData(chunk)
                                }

                                if (stagedBytes == staging.size) {
                                    onAudioData(staging.copyOf(stagedBytes))
                                    stagedBytes = 0
                                }
                            }
                        }
                    }

                    if (stagedBytes > 0) {
                        val tail = staging.copyOf(stagedBytes)
                        onAudioData(tail)
                    }
                }.start()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error starting AudioRecorder", e)
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
            
            val mono = ((left.toInt() + right.toInt()) / 2).toShort()
            
            monoData[monoIdx++] = (mono.toInt() and 0xFF).toByte()
            monoData[monoIdx++] = ((mono.toInt() shr 8) and 0xFF).toByte()
        }
        return monoData
    }

    fun stop() {
        synchronized(lock) {
            isRecording.set(false)
            try {
                noiseSuppressor?.release()
                echoCanceler?.release()
                noiseSuppressor = null
                echoCanceler = null

                audioRecord?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        stop()
                        release()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Error stopping AudioRecorder", e)
            } finally {
                audioRecord = null
            }
        }
    }
}




