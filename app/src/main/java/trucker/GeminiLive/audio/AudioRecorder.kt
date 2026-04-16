package trucker.GeminiLive.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
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
                audioRecord = AudioRecord(
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
                    startRecording()
                }

                isRecording.set(true)

                Thread {
                    val readBuffer = ByteArray(minBufferSize)
                    val targetChunkSize = AudioConfig.TARGET_INPUT_CHUNK_BYTES
                    val staging = ByteArray(targetChunkSize * 8)
                    var stagedBytes = 0

                    while (isRecording.get()) {
                        val currentRecord = synchronized(lock) { audioRecord }
                        if (currentRecord == null || !isRecording.get()) break

                        val read = currentRecord.read(readBuffer, 0, readBuffer.size)
                        if (read > 0 && isRecording.get()) {
                            var offset = 0
                            while (offset < read) {
                                val writable = minOf(staging.size - stagedBytes, read - offset)
                                System.arraycopy(readBuffer, offset, staging, stagedBytes, writable)
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

                                // Prevent overflow by flushing if buffer fills unexpectedly.
                                if (stagedBytes == staging.size) {
                                    onAudioData(staging.copyOf(stagedBytes))
                                    stagedBytes = 0
                                }
                            }
                        }
                    }

                    // Flush remaining bytes on stop to avoid losing tail audio.
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

    fun stop() {
        synchronized(lock) {
            isRecording.set(false)
            try {
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
