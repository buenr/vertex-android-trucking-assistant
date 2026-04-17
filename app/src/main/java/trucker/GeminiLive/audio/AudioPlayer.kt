package trucker.geminilive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    // Increase buffer size to 4x min to handle burst data during synchronization
    private val bufferSize = AudioTrack.getMinBufferSize(
        AudioConfig.OUTPUT_SAMPLE_RATE,
        AudioConfig.CHANNEL_CONFIG_OUT,
        AudioConfig.AUDIO_FORMAT
    ) * 4

    private val lock = Any()
    private var isBuffering = false
    private val bufferQueue = mutableListOf<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startBuffering() {
        synchronized(lock) {
            isBuffering = true
            bufferQueue.clear()
        }
    }

    fun stopBufferingAndPlay() {
        // Offload the backlog drainage to a background thread to avoid blocking the caller
        scope.launch {
            val backlog = synchronized(lock) {
                isBuffering = false
                val list = bufferQueue.toList()
                bufferQueue.clear()
                list
            }
            
            if (!ensureInitialized()) return@launch
            backlog.forEach { data ->
                try {
                    audioTrack?.write(data, 0, data.size)
                } catch (e: Exception) {
                    Log.e("AudioPlayer", "Error writing buffered data", e)
                }
            }
        }
    }

    private fun ensureInitialized(): Boolean {
        synchronized(lock) {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                return true
            }
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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

                audioTrack?.play()
                return true
            } catch (e: Exception) {
                Log.e("AudioPlayer", "AudioTrack initialization failed", e)
                return false
            }
        }
    }

    fun play(audioData: ByteArray) {
        synchronized(lock) {
            if (isBuffering) {
                bufferQueue.add(audioData)
                return
            }
            if (!ensureInitialized()) return
            try {
                audioTrack?.write(audioData, 0, audioData.size)
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error writing to AudioTrack", e)
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            try {
                audioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        // To flush, we stop, then flush, then play again
                        if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                            stop()
                        }
                        flush()
                        play()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error flushing AudioTrack", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                audioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        try {
                            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                                stop()
                            }
                        } catch (e: Exception) {}
                        release()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error stopping AudioTrack", e)
            } finally {
                audioTrack = null
            }
        }
    }
}




