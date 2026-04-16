package trucker.GeminiLive.audio

import android.media.AudioFormat

object AudioConfig {
    const val INPUT_SAMPLE_RATE = 16000
    // If 24000 fails, consider using 48000 and resampling, 
    // but 24000 is usually fine if the AudioTrack/Oboe handles it.
    const val OUTPUT_SAMPLE_RATE = 24000 
    
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    
    // CHANGE: Some tablets behave better with STEREO config even if the data is Mono
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2 
    const val INPUT_CHANNEL_COUNT = 1 
    const val OUTPUT_CHANNEL_COUNT = 1

    const val TARGET_INPUT_CHUNK_MS = 20

    val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=$INPUT_SAMPLE_RATE"

    val TARGET_INPUT_CHUNK_BYTES =
        (INPUT_SAMPLE_RATE * INPUT_CHANNEL_COUNT * BYTES_PER_SAMPLE * TARGET_INPUT_CHUNK_MS) / 1000
}
