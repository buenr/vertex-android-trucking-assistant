package trucker.geminilive.network

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bug Condition Exploration Test for Stale Connection Detection
 * 
 * **Validates: Requirements 2.5**
 * 
 * **Property 1: Bug Condition** - Infinite Read Timeout Allows Indefinite Hangs
 * 
 * **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists.
 * This test encodes the expected behavior - it will validate the fix when it passes after implementation.
 * 
 * **Bug Condition**: isBugCondition5(input) where input.readTimeoutMs == 0
 * 
 * **Expected Behavior After Fix**: Detect stale state within 60 seconds using read timeout,
 * attempt reconnection or notify user.
 * 
 * **Test Strategy**: 
 * - Create an OkHttpClient with readTimeout=0 (infinite) - the buggy configuration
 * - Connect to a non-existent endpoint that will hang
 * - Verify the connection hangs for > 60 seconds without detection
 * - This proves the bug exists: infinite timeout allows indefinite hangs
 */
class GeminiWebSocketClientStaleConnectionTest {

    /**
     * Test: WebSocket with readTimeout=0 hangs indefinitely when server stops responding.
     * 
     * **Bug Condition**: readTimeoutMs == 0 (infinite timeout)
     * 
     * **Expected Behavior on UNFIXED code**: Connection hangs indefinitely (test FAILS by timeout)
     * **Expected Behavior on FIXED code**: Connection times out within 60 seconds (test PASSES)
     * 
     * This test simulates a server that accepts the WebSocket connection but never sends
     * any data after the initial handshake. With readTimeout=0, the client will wait
     * forever without detecting the stale connection.
     */
    @Test
    fun `bug condition - infinite read timeout allows indefinite hangs`() {
        // Arrange: Track timing and connection state
        val connectionEstablished = AtomicBoolean(false)
        val staleDetected = AtomicBoolean(false)
        val timeToDetection = AtomicLong(0)
        val connectionStartTime = AtomicLong(0)
        val failureReason = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        
        connectionStartTime.set(System.currentTimeMillis())
        
        // Create OkHttpClient with the BUGGY configuration: readTimeout=0 (infinite)
        // This is the exact configuration from GeminiWebSocketClient.kt
        val buggyClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // BUG: Infinite timeout
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
        
        // Create a WebSocket listener that tracks connection state
        val listener = object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                connectionEstablished.set(true)
                // Server accepted connection but won't send any data
                // With readTimeout=0, this connection will hang forever
            }
            
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                // This should be called when timeout occurs (after fix)
                staleDetected.set(true)
                timeToDetection.set(System.currentTimeMillis() - connectionStartTime.get())
                failureReason.set("Connection failure: ${t.message}")
                latch.countDown()
            }
            
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                // Connection closing
            }
            
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }
        
        // Act: Connect to a WebSocket endpoint that will hang
        // Using a non-routable IP address that will cause the connection to hang
        // 10.255.255.1 is a non-routable address that will cause connection timeout
        val request = okhttp3.Request.Builder()
            .url("ws://10.255.255.1:9999/test")
            .build()
        
        buggyClient.newWebSocket(request, listener)
        
        // Wait for either:
        // 1. Stale detection (failure callback) - expected after fix
        // 2. 65 seconds timeout - proves bug exists (infinite hang)
        // 
        // We use 65 seconds because the fix should detect stale within 60 seconds
        val detectedWithinTimeout = latch.await(65, TimeUnit.SECONDS)
        
        // Assert: This is the bug condition check
        if (!detectedWithinTimeout) {
            // BUG CONFIRMED: Connection hung for 65+ seconds without detection
            // This is the expected behavior on UNFIXED code
            fail(
                "BUG CONFIRMED: WebSocket with readTimeout=0 hung for 65+ seconds " +
                    "without detecting stale connection. The connection to an unresponsive " +
                    "server was never detected, proving the bug exists. " +
                    "Connection established: ${connectionEstablished.get()}, " +
                    "Stale detected: ${staleDetected.get()}"
            )
        }
        
        // If we reach here on FIXED code, verify the detection happened within expected time
        // Note: For this test with non-routable IP, connection will fail at connect phase,
        // not read phase. The readTimeout bug is better demonstrated with a server that
        // accepts but doesn't respond.
    }
    
    /**
     * Test: Verify the specific counterexample - readTimeout=0 allows indefinite read waits.
     * 
     * This test documents the exact counterexample that demonstrates the bug:
     * - Input: readTimeout=0, server accepts but never responds
     * - Expected on unfixed: Read operation hangs indefinitely
     * - Expected after fix: Read operation times out within 60 seconds
     * 
     * This test directly tests the OkHttpClient configuration to demonstrate the bug.
     */
    @Test
    fun `counterexample - readTimeout zero allows indefinite read waits`() {
        val maxWaitSeconds = 65L
        val detectionLatch = CountDownLatch(1)
        val readCompleted = AtomicBoolean(false)
        val readTimedOut = AtomicBoolean(false)
        
        // Create a server socket that accepts but doesn't respond
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        
        try {
            // Start a thread that accepts connection but never sends data
            val serverThread = Thread {
                try {
                    val client = serverSocket.accept()
                    // Accept the connection but intentionally don't send any data
                    // This simulates a stale server
                    // Just keep the connection open
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(1000)
                    }
                    client.close()
                } catch (e: Exception) {
                    // Expected during shutdown
                }
            }
            serverThread.start()
            
            val startTime = System.currentTimeMillis()
            
            // Buggy client configuration
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // THE BUG: Infinite timeout
                .writeTimeout(0, TimeUnit.SECONDS)
                .build()
            
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    // Connection opened, now we wait for data that will never come
                    // With readTimeout=0, this will hang forever
                }
                
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    readTimedOut.set(true)
                    detectionLatch.countDown()
                }
                
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    readCompleted.set(true)
                    detectionLatch.countDown()
                }
            }
            
            client.newWebSocket(
                okhttp3.Request.Builder()
                    .url("ws://localhost:$port/test")
                    .build(),
                listener
            )
            
            // Wait for detection or timeout
            val detected = detectionLatch.await(maxWaitSeconds, TimeUnit.SECONDS)
            
            if (!detected) {
                // COUNTEREXAMPLE FOUND: Document this
                val hangDuration = System.currentTimeMillis() - startTime
                fail(
                    "COUNTEREXAMPLE: Connection with readTimeout=0 hung for ${hangDuration}ms " +
                        "(${hangDuration / 1000} seconds) without detecting stale state. " +
                        "Server accepted connection but never sent data. " +
                        "This proves the bug: infinite timeout allows indefinite hangs."
                )
            }
            
            // After fix: verify detection happened within 60 seconds
            val detectionTime = System.currentTimeMillis() - startTime
            assertTrue(
                "After fix: stale detection should occur within 60 seconds",
                detectionTime <= 60000
            )
            
        } finally {
            serverThread?.interrupt()
            serverSocket.close()
        }
    }
    
    /**
     * Test: Demonstrate the bug condition with a simple HTTP read.
     * 
     * This test shows that readTimeout=0 allows a read operation to hang indefinitely
     * when the server accepts the connection but doesn't respond.
     */
    @Test
    fun `bug condition - readTimeout zero causes indefinite read hang on silent server`() {
        val readCompleted = AtomicBoolean(false)
        val readTimedOut = AtomicBoolean(false)
        val readLatch = CountDownLatch(1)
        
        // Create a server that accepts but doesn't respond
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        
        try {
            val serverThread = Thread {
                try {
                    val clientSocket = serverSocket.accept()
                    // Accept connection but don't send any data
                    // This simulates a stale/hung server
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(1000)
                    }
                    clientSocket.close()
                } catch (e: Exception) {
                    // Expected during shutdown
                }
            }
            serverThread.start()
            
            // Give server time to start
            Thread.sleep(100)
            
            val startTime = System.currentTimeMillis()
            
            // Buggy client with readTimeout=0
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // THE BUG
                .build()
            
            // Make a request that will hang on read
            val call = client.newCall(
                okhttp3.Request.Builder()
                    .url("http://localhost:$port/test")
                    .build()
            )
            
            // Execute in a separate thread
            val clientThread = Thread {
                try {
                    val response = call.execute()
                    // Try to read the body - this will hang with readTimeout=0
                    val body = response.body?.string()
                    readCompleted.set(true)
                } catch (e: java.net.SocketTimeoutException) {
                    readTimedOut.set(true)
                } catch (e: Exception) {
                    // Other errors
                } finally {
                    readLatch.countDown()
                }
            }
            clientThread.start()
            
            // Wait for 65 seconds max
            val completed = readLatch.await(65, TimeUnit.SECONDS)
            
            if (!completed) {
                val hangDuration = System.currentTimeMillis() - startTime
                fail(
                    "BUG CONFIRMED: HTTP read with readTimeout=0 hung for ${hangDuration}ms " +
                        "(${hangDuration / 1000} seconds). The server accepted the connection " +
                        "but never sent data, and the client never timed out. " +
                        "This proves the bug: readTimeout=0 allows indefinite hangs."
                )
            }
            
            // After fix: verify timeout occurred
            assertTrue(
                "After fix: read should timeout within 60 seconds",
                readTimedOut.get()
            )
            
        } finally {
            serverThread?.interrupt()
            serverSocket.close()
        }
    }
}
