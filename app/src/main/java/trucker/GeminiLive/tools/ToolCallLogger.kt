package trucker.geminilive.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Logger for tracking tool/function call metrics.
 * Batches writes in memory and flushes to external files directory.
 * 
 * Log format: JSON Lines (one JSON object per line)
 * Storage: External files directory (accessible via USB/debugging)
 */
object ToolCallLogger {
    private const val TAG = "ToolCallLogger"
    private const val LOG_FILE_NAME = "tool_calls_log.jsonl"
    private const val FLUSH_THRESHOLD = 10 // Auto-flush after N entries
    
    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private val totalCalls = AtomicLong(0)
    private val callCounts = mutableMapOf<String, AtomicLong>()
    private var logFile: File? = null
    private val json = Json { 
        encodeDefaults = true
        prettyPrint = false 
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    
    @Serializable
    data class LogEntry(
        val timestamp: String,
        val functionName: String,
        val driverId: String,
        val arguments: String? = null, // JSON string representation of args
        val responseTimeMs: Long,
        val success: Boolean
    )
    
    /**
     * Initialize the logger with application context.
     * Must be called before logging (e.g., in Application.onCreate or Activity.onCreate)
     */
    fun init(context: Context) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            logFile = File(externalDir, LOG_FILE_NAME)
            Log.i(TAG, "Log file initialized at: ${logFile?.absolutePath}")
        } else {
            Log.e(TAG, "Failed to get external files directory")
        }
    }
    
    /**
     * Log a tool call with all metrics.
     * 
     * @param functionName Name of the tool/function called
     * @param driverId Driver ID from the call context
     * @param arguments Arguments passed to the function (can be null)
     * @param responseTimeMs Time taken to execute the function in milliseconds
     * @param success Whether the call succeeded
     */
    fun logCall(
        functionName: String,
        driverId: String,
        arguments: Map<String, JsonElement>?,
        responseTimeMs: Long,
        success: Boolean = true
    ) {
        // Convert arguments to JSON string for serialization
        val argsString = arguments?.let { args ->
            try {
                JsonObject(args).toString()
            } catch (e: Exception) {
                null
            }
        }
        
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            functionName = functionName,
            driverId = driverId,
            arguments = argsString,
            responseTimeMs = responseTimeMs,
            success = success
        )
        
        buffer.add(entry)
        totalCalls.incrementAndGet()
        
        // Update per-function count
        synchronized(callCounts) {
            callCounts.getOrPut(functionName) { AtomicLong(0) }.incrementAndGet()
        }
        
        Log.v(TAG, "Logged call: $functionName (${responseTimeMs}ms), buffer size: ${buffer.size}")
        
        // Auto-flush if threshold reached
        if (buffer.size >= FLUSH_THRESHOLD) {
            flush()
        }
    }
    
    /**
     * Flush all buffered entries to the log file.
     * Safe to call multiple times - only writes new entries.
     */
    fun flush() {
        val file = logFile ?: run {
            Log.w(TAG, "Log file not initialized, skipping flush")
            return
        }
        
        if (buffer.isEmpty()) {
            return
        }
        
        try {
            val entriesToWrite = mutableListOf<LogEntry>()
            while (buffer.isNotEmpty()) {
                buffer.poll()?.let { entriesToWrite.add(it) }
            }
            
            if (entriesToWrite.isEmpty()) return
            
            // Append to file (JSON Lines format)
            file.appendText(entriesToWrite.joinToString("\n") { entry ->
                json.encodeToString(LogEntry.serializer(), entry)
            } + "\n")
            
            Log.i(TAG, "Flushed ${entriesToWrite.size} entries to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush log entries", e)
        }
    }
    
    /**
     * Get current metrics summary.
     */
    fun getMetrics(): MetricsSummary {
        val counts = synchronized(callCounts) {
            callCounts.mapValues { it.value.get() }
        }
        return MetricsSummary(
            totalCalls = totalCalls.get(),
            callsByFunction = counts,
            logFilePath = logFile?.absolutePath
        )
    }
    
    data class MetricsSummary(
        val totalCalls: Long,
        val callsByFunction: Map<String, Long>,
        val logFilePath: String?
    )
    
    /**
     * Clear the in-memory buffer (does not delete the log file).
     */
    fun clearBuffer() {
        buffer.clear()
    }
}
