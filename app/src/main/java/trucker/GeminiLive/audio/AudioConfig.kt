package trucker.geminilive.audio

import android.media.AudioFormat

object AudioConfig {
    const val INPUT_SAMPLE_RATE = 16000
    // If 24000 fails, consider using 48000 and resampling, 
    // but 24000 is usually fine if the AudioTrack/Oboe handles it.
    const val OUTPUT_SAMPLE_RATE = 24000 
    
    // Hardware configuration for Galaxy Active 5 optimization
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    const val HARDWARE_INPUT_CHANNEL_COUNT = 2
    
    // Target configuration for Gemini
    const val TARGET_INPUT_CHANNEL_COUNT = 1
    
    // CHANGE: Some tablets behave better with STEREO config even if the data is Mono
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2 

    const val TARGET_INPUT_CHUNK_MS = 20

    const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=$INPUT_SAMPLE_RATE"

    const val TARGET_INPUT_CHUNK_BYTES =
        (INPUT_SAMPLE_RATE * TARGET_INPUT_CHANNEL_COUNT * BYTES_PER_SAMPLE * TARGET_INPUT_CHUNK_MS) / 1000
}




