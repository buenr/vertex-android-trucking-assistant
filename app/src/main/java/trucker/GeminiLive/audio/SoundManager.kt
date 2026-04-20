package trucker.geminilive.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.*
import kotlin.random.Random

class SoundManager {
    private val lock = Any()
    private var toneGenerator: ToneGenerator? = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
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
                    // Start with a guaranteed long beep to simulate immediate API call
                    synchronized(lock) {
                        toneGenerator?.startTone(tones[0], Random.nextInt(3000, 6001))
                    }
                    delay(100) // Brief pause before the random loop
                    
                    while (isActive) {
                        val tone = tones[Random.nextInt(tones.size)]
                        
                        // Randomly choose between short beeps (30-60ms) and long beeps (3-6s)
                        val isLongBeep = Random.nextInt(2) == 0 // 50% chance for long beep
                        val duration = if (isLongBeep) {
                            Random.nextInt(3000, 6001) // 3-6 second beeps for API latency simulation
                        } else {
                            Random.nextInt(30, 61) // Existing short beeps
                        }
                        
                        synchronized(lock) {
                            toneGenerator?.startTone(tone, duration)
                        }
                        
                        // Adjust delay based on beep type
                        val nextDelay = if (isLongBeep) {
                            Random.nextLong(200, 800) // Shorter delay after long beeps to allow more beeps
                        } else {
                            Random.nextLong(40, 181) // Existing short delays
                        }
                        delay(nextDelay)
                        
                        // Keep existing occasional pause logic
                        if (Random.nextInt(10) == 0) {
                            delay(Random.nextLong(150, 301))
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

    fun playErrorChime() {
        Log.d("SoundManager", "playErrorChime()")
        scope.launch {
            try {
                synchronized(lock) {
                    // Distinct descending tones to signal disconnection/error
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
                }
                delay(250)
                synchronized(lock) {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 150)
                }
                delay(200)
                synchronized(lock) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                }
            } catch (e: Exception) {
                Log.e("SoundManager", "Error in playErrorChime", e)
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




