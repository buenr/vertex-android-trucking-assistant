package trucker.geminilive.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.*
import kotlin.random.Random

class SoundManager {
    private val lock = Any()
    private var toneGenerator: ToneGenerator? = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    private var loopingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun playStartupBeep() {
        Log.d("SoundManager", "playStartupBeep()")
        scope.launch {
            try {
                synchronized(lock) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                }
                delay(150)
                synchronized(lock) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                }
            } catch (e: Exception) {
                Log.e("SoundManager", "Error in playStartupBeep", e)
            }
        }
    }

    fun startThinkingLoop() {
        Log.d("SoundManager", "startThinkingLoop()")
        stopLoop()
        synchronized(lock) {
            loopingJob = scope.launch {
                try {
                    while (isActive) {
                        synchronized(lock) {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                        }
                        delay(2000)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e("SoundManager", "Error in thinking loop", e)
                    }
                } finally {
                    Log.d("SoundManager", "Thinking loop stopped")
                }
            }
        }
    }

    fun startWorkingLoop() {
        Log.d("SoundManager", "startWorkingLoop()")
        stopLoop()
        synchronized(lock) {
            loopingJob = scope.launch {
                val tones = intArrayOf(
                    ToneGenerator.TONE_CDMA_PIP,
                    ToneGenerator.TONE_PROP_BEEP2,
                    ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE
                )
                try {
                    while (isActive) {
                        val tone = tones[Random.nextInt(tones.size)]
                        val duration = Random.nextInt(30, 60)
                        synchronized(lock) {
                            toneGenerator?.startTone(tone, duration)
                        }
                        val nextDelay = Random.nextLong(40, 180)
                        delay(nextDelay)
                        if (Random.nextInt(10) == 0) {
                            delay(Random.nextLong(150, 300))
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e("SoundManager", "Error in working loop", e)
                    }
                } finally {
                    Log.d("SoundManager", "Working loop stopped")
                }
            }
        }
    }

    fun stopLoop() {
        synchronized(lock) {
            if (loopingJob != null) {
                Log.v("SoundManager", "stopLoop()")
                loopingJob?.cancel()
                loopingJob = null
            }
        }
    }

    fun release() {
        Log.d("SoundManager", "release()")
        stopLoop()
        scope.cancel()
        synchronized(lock) {
            toneGenerator?.release()
            toneGenerator = null
        }
    }
}




