package trucker.GeminiLive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val bufferSize = AudioTrack.getMinBufferSize(
        AudioConfig.OUTPUT_SAMPLE_RATE,
        AudioConfig.CHANNEL_CONFIG_OUT,
        AudioConfig.AUDIO_FORMAT
    )

    private val lock = Any()

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
