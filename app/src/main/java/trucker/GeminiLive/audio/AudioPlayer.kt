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

    init {
        synchronized(lock) {
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
            } catch (e: Exception) {
                Log.e("AudioPlayer", "AudioTrack initialization failed", e)
            }
        }
    }

    fun play(audioData: ByteArray) {
        synchronized(lock) {
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
                    if (state == AudioTrack.STATE_INITIALIZED && playState == AudioTrack.PLAYSTATE_PLAYING) {
                        stop()
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
                        stop()
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
