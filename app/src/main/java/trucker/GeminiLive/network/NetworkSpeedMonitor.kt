package trucker.geminilive.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Monitors network speed using passive TrafficStats monitoring.
 * Calculates download/upload speeds by tracking byte deltas over time.
 */
class NetworkSpeedMonitor(private val application: Application) {

    companion object {
        const val MINIMUM_SPEED_KBPS = 100f // Minimum acceptable speed in kbps
        const val POLLING_INTERVAL_MS = 2000L // Check every 2 seconds
        const val ZERO_SPEED_POLL_COUNT = 3 // Close after 3 consecutive zero-speed polls
        private const val TAG = "NetworkSpeed"
    }

    data class NetworkStatus(
        val downloadSpeedKbps: Float = 0f,
        val uploadSpeedKbps: Float = 0f,
        val networkType: NetworkType = NetworkType.UNKNOWN,
        val isSpeedSufficient: Boolean = false,
        val isZeroSpeed: Boolean = false,
        val zeroSpeedDurationMs: Long = 0
    )

    enum class NetworkType {
        LTE,
        WIFI,
        UNKNOWN
    }

    private val _networkStatus = MutableStateFlow(NetworkStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // Zero-speed timeout detection - extraBufferCapacity ensures events aren't dropped
    private val _zeroSpeedTimeout = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val zeroSpeedTimeout: SharedFlow<Unit> = _zeroSpeedTimeout.asSharedFlow()

    private var monitoringJob: Job? = null
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastCheckTime: Long = 0
    private var consecutiveZeroSpeedCount = 0
    private var isFirstMeasurement = true
    @Volatile
    private var isPlaybackActive = false  // Pause speed checks during audio playback
    private var playbackEndedAt: Long = 0
    private val POST_PLAYBACK_GRACE_MS = 8000L // 8 second grace period (600ms silence + 7.4s for user to respond)

    fun startMonitoring() {
        if (monitoringJob != null) return

        // Initialize baseline
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastCheckTime = System.currentTimeMillis()
        isFirstMeasurement = true

        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)
                updateNetworkStatus()
            }
        }

        Log.d(TAG, "Network speed monitoring started")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        consecutiveZeroSpeedCount = 0
        isPlaybackActive = false
        Log.d(TAG, "Network speed monitoring stopped")
    }

    /**
     * Call this when audio playback starts/stops.
     * During playback, low speed detection is paused since no network traffic is expected.
     * After playback ends, a grace period allows the user time to respond before counting resumes.
     */
    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        if (active) {
            Log.d(TAG, "Playback started - pausing low-speed detection")
            // Reset counter when playback starts to avoid stale counts
            consecutiveZeroSpeedCount = 0
            playbackEndedAt = 0
        } else {
            playbackEndedAt = System.currentTimeMillis()
            Log.d(TAG, "Playback ended - ${POST_PLAYBACK_GRACE_MS}ms grace period before counting resumes")
        }
    }

    private fun isInGracePeriod(): Boolean {
        if (playbackEndedAt == 0L) return false
        val elapsed = System.currentTimeMillis() - playbackEndedAt
        return elapsed < POST_PLAYBACK_GRACE_MS
    }

    private fun updateNetworkStatus() {
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()

        val timeDeltaMs = currentTime - lastCheckTime
        if (timeDeltaMs <= 0) return

        // Calculate speeds in kbps (kilobits per second)
        val rxDeltaBytes = currentRxBytes - lastRxBytes
        val txDeltaBytes = currentTxBytes - lastTxBytes

        val downloadSpeedKbps = (rxDeltaBytes * 8f) / (timeDeltaMs / 1000f) / 1000f
        val uploadSpeedKbps = (txDeltaBytes * 8f) / (timeDeltaMs / 1000f) / 1000f

        // Check connectivity
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        Log.d(TAG, "Speed: ${downloadSpeedKbps.toInt()} kbps down, ${uploadSpeedKbps.toInt()} kbps up, hasInternet=$hasInternet")

        // Skip first measurement (will always be 0 or misleading)
        if (isFirstMeasurement) {
            isFirstMeasurement = false
            lastRxBytes = currentRxBytes
            lastTxBytes = currentTxBytes
            lastCheckTime = currentTime
            _networkStatus.value = NetworkStatus(
                downloadSpeedKbps = 0f,
                uploadSpeedKbps = 0f,
                networkType = detectNetworkType(),
                isSpeedSufficient = false,
                isZeroSpeed = false
            )
            Log.d(TAG, "First measurement skipped, calibrating...")
            return
        }

        val networkType = detectNetworkType()
        val isSpeedSufficient = downloadSpeedKbps >= MINIMUM_SPEED_KBPS
        val isZeroSpeed = downloadSpeedKbps <= 0f

        // CRITICAL: Treat "insufficient speed" the same as "zero speed" for shutdown timer
        // Any speed below MINIMUM_SPEED_KBPS increments the failure counter
        // BUT: Skip this during playback and grace period when no network traffic is expected
        val isSpeedTooLow = !isSpeedSufficient && !isPlaybackActive && !isInGracePeriod()

        // Handle low-speed detection (3 consecutive polls below threshold)
        val (isTimeout, zeroSpeedDurationMs) = handleZeroSpeedDetection(isSpeedTooLow)

        _networkStatus.value = NetworkStatus(
            downloadSpeedKbps = downloadSpeedKbps,
            uploadSpeedKbps = uploadSpeedKbps,
            networkType = networkType,
            isSpeedSufficient = isSpeedSufficient,
            isZeroSpeed = isZeroSpeed,
            zeroSpeedDurationMs = zeroSpeedDurationMs
        )

        // Emit timeout event after 3 consecutive low-speed polls
        if (isTimeout) {
            Log.e(TAG, "Shutting down: Speed remained below ${MINIMUM_SPEED_KBPS}kbps for too long.")
            _zeroSpeedTimeout.tryEmit(Unit)
        }

        // Update baseline for next calculation
        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastCheckTime = currentTime

        Log.v(TAG, "Speed: ${downloadSpeedKbps.toInt()} kbps, Type: $networkType, Sufficient: $isSpeedSufficient, LowSpeed: $isSpeedTooLow, Count: $consecutiveZeroSpeedCount")
    }

    private fun handleZeroSpeedDetection(isZeroSpeed: Boolean): Pair<Boolean, Long> {
        return when {
            isZeroSpeed -> {
                consecutiveZeroSpeedCount++
                Log.d(TAG, "Zero speed poll #$consecutiveZeroSpeedCount / $ZERO_SPEED_POLL_COUNT")

                val isTimeout = consecutiveZeroSpeedCount >= ZERO_SPEED_POLL_COUNT
                if (isTimeout) {
                    Log.d(TAG, "Zero speed timeout reached after $ZERO_SPEED_POLL_COUNT consecutive polls")
                }

                // Return (isTimeout, count for UI display)
                Pair(isTimeout, consecutiveZeroSpeedCount * POLLING_INTERVAL_MS)
            }
            else -> {
                // Speed is non-zero, reset counter
                if (consecutiveZeroSpeedCount > 0) {
                    Log.d(TAG, "Zero speed reset - speed restored (was at count: $consecutiveZeroSpeedCount)")
                    consecutiveZeroSpeedCount = 0
                }
                Pair(false, 0)
            }
        }
    }

    private fun detectNetworkType(): NetworkType {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkType.UNKNOWN

        val network = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.LTE
            else -> NetworkType.UNKNOWN
        }
    }

    /**
     * One-time check at startup to ensure the connection is capable of >100kbps.
     * Returns true if the speed is sufficient, false otherwise.
     */
    suspend fun isConnectionHealthy(): Boolean = withContext(Dispatchers.IO) {
        val uid = android.os.Process.myUid()
        var startRx = TrafficStats.getUidRxBytes(uid)
        var startTime = android.os.SystemClock.elapsedRealtime()

        // Fallback to total device traffic if UID stats not available
        if (startRx == TrafficStats.UNSUPPORTED.toLong() || startRx == 0L) {
            startRx = TrafficStats.getTotalRxBytes()
        }

        return@withContext try {
            // Download a tiny file to force an actual data transfer
            val url = java.net.URL("https://www.google.com/generate_204")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            val bytesRead = connection.inputStream.use { it.readBytes().size }

            val endTime = android.os.SystemClock.elapsedRealtime()
            var endRx = TrafficStats.getUidRxBytes(uid)
            if (endRx == TrafficStats.UNSUPPORTED.toLong() || endRx == startRx) {
                endRx = TrafficStats.getTotalRxBytes()
            }

            val deltaSec = (endTime - startTime) / 1000f
            val bytesReceived = (endRx - startRx).coerceAtLeast(bytesRead.toLong())

            // Convert to kbps
            val speedKbps = (bytesReceived * 8f) / deltaSec / 1000f

            Log.d(TAG, "Pre-flight check: HTTP $responseCode, ${speedKbps.toInt()} kbps (${bytesReceived} bytes in ${deltaSec}s)")

            // Pass if we got any successful response and reasonable speed, or just good speed
            (responseCode in 200..299 && speedKbps >= MINIMUM_SPEED_KBPS) || speedKbps >= MINIMUM_SPEED_KBPS
        } catch (e: Exception) {
            Log.e(TAG, "Pre-flight network check failed: ${e.javaClass.simpleName}: ${e.message}")
            // If we can't even make the request, connection is definitely bad
            false
        }
    }
}
