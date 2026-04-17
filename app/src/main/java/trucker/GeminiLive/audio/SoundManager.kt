package trucker.geminilive.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.*
import kotlin.random.Random

class SoundManager {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    private var loopingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * "Thinking" effect: A very subtle, slow rhythmic pulse.
     * Provides immediate feedback as soon as the user stops talking.
     */
    fun startThinkingLoop() {
        stopLoop()
        loopingJob = scope.launch {
            while (isActive) {
                // Extremely soft pulse
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                delay(2000)
            }
        }
    }

    /**
     * "Digital Typing/Processing" effect: Randomized tones and timing.
     * Simulates a high-speed mechanical typing or data processing sequence.
     */
    fun startWorkingLoop() {
        stopLoop()
        loopingJob = scope.launch {
            val tones = intArrayOf(
                ToneGenerator.TONE_CDMA_PIP,
                ToneGenerator.TONE_PROP_BEEP2,
                ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE
            )
            while (isActive) {
                // Pick a random tone from the set to vary the "key" sound
                val tone = tones[Random.nextInt(tones.size)]
                
                // Varied duration for slight mechanical imperfection
                val duration = Random.nextInt(30, 60)
                toneGenerator.startTone(tone, duration)
                
                // Irregular delays between "keystrokes"
                val nextDelay = Random.nextLong(40, 180)
                delay(nextDelay)
                
                // Occasionally add a longer pause between "words/chunks"
                if (Random.nextInt(10) == 0) {
                    delay(Random.nextLong(150, 300))
                }
            }
        }
    }

    fun stopLoop() {
        loopingJob?.cancel()
        loopingJob = null
    }

    fun release() {
        stopLoop()
        scope.cancel()
        toneGenerator.release()
    }
}




