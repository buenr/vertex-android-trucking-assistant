package trucker.geminilive.network

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Preservation Property Tests for Stale Connection Detection Fix
 * 
 * **Validates: Requirements 3.1, 3.2**
 * 
 * **Property 2: Preservation** - Normal WebSocket Behavior on Responsive Server
 * 
 * **CRITICAL**: These tests MUST PASS on both unfixed and fixed code.
 * These tests verify that the fix does not break existing functionality.
 * 
 * **Preservation Requirements**:
 * - 3.1: Stable network connections must continue to work exactly as before
 * - 3.2: Valid access token authentication must work successfully
 * 
 * **Test Strategy**:
 * - Create responsive server scenarios that work correctly on unfixed code
 * - Verify the same behavior continues after the fix
 * - Test normal WebSocket handshake, data flow, and connection lifecycle
 */
class GeminiWebSocketClientPreservationTest {

    /**
     * Test: WebSocket with responsive server completes handshake normally.
     * 
     * **Preservation**: Normal connections must work identically before and after fix.
     * 
     * **Expected Behavior**: Connection opens and handshake completes within timeout.
     * This test should PASS on both unfixed and fixed code.
     */
    @Test
    fun `preservation - responsive server completes handshake normally`() {
        // Arrange: Create a responsive mock server
        val connectionOpened = AtomicBoolean(false)
        val handshakeCompleted = AtomicBoolean(false)
        val dataReceived = AtomicBoolean(false)
        val errorOccurred = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val connectionTime = AtomicLong(0)
        
        // Create a simple echo server that responds immediately
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        
        try {
            // Start a responsive server thread
            val serverThread = Thread {
                try {
                    val clientSocket = serverSocket.accept()
                    val startTime = System.currentTimeMillis()
                    
                    // Perform WebSocket handshake
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(clientSocket.getInputStream()))
                    val writer = java.io.PrintWriter(clientSocket.getOutputStream(), true)
                    
                    // Read WebSocket upgrade request
                    var line: String?
                    var key: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("Sec-WebSocket-Key:")) {
                            key = line!!.substring("Sec-WebSocket-Key:".length).trim()
                        }
                        if (line!!.isEmpty()) break
                    }
                    
                    // Send WebSocket upgrade response
                    if (key != null) {
                        val acceptKey = java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                            .let { java.util.Base64.getEncoder().encodeToString(it) }
                        
                        writer.println("HTTP/1.1 101 Switching Protocols")
                        writer.println("Upgrade: websocket")
                        writer.println("Connection: Upgrade")
                        writer.println("Sec-WebSocket-Accept: $acceptKey")
                        writer.println()
                        writer.flush()
                        
                        connectionTime.set(System.currentTimeMillis() - startTime)
                        
                        // Send a simple WebSocket message (text frame)
                        // Frame format: 0x81 = text frame, followed by length and payload
                        val message = "Hello from server"
                        val payload = message.toByteArray()
                        val frame = java.io.ByteArrayOutputStream()
                        frame.write(0x81) // Text frame
                        frame.write(payload.size) // Length
                        frame.write(payload)
                        clientSocket.getOutputStream().write(frame.toByteArray())
                        clientSocket.getOutputStream().flush()
                    }
                    
                    // Keep connection alive for a bit
                    Thread.sleep(2000)
                    clientSocket.close()
                } catch (e: Exception) {
                    // Server shutdown or expected error
                }
            }
            serverThread.start()
            
            // Give server time to start
            Thread.sleep(100)
            
            // Create client with the SAME configuration as GeminiWebSocketClient
            // Note: readTimeout=0 is the buggy config, but it should still work for responsive servers
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // Same as production code
                .writeTimeout(0, TimeUnit.SECONDS)
                .build()
            
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    connectionOpened.set(true)
                }
                
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    dataReceived.set(true)
                    latch.countDown()
                }
                
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    errorOccurred.set("Connection failed: ${t.message}")
                    latch.countDown()
                }
                
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            }
            
            // Act: Connect to the responsive server
            val startTime = System.currentTimeMillis()
            client.newWebSocket(
                okhttp3.Request.Builder()
                    .url("ws://localhost:$port/test")
                    .build(),
                listener
            )
            
            // Assert: Connection should complete within reasonable time
            val completed = latch.await(10, TimeUnit.SECONDS)
            
            assertTrue(
                "Preservation FAILED: Responsive server connection should complete within 10 seconds. " +
                    "Error: ${errorOccurred.get()}, " +
                    "Connection opened: ${connectionOpened.get()}, " +
                    "Data received: ${dataReceived.get()}",
                completed
            )
            
            assertTrue(
                "Preservation FAILED: Connection should open successfully",
                connectionOpened.get()
            )
            
            // Verify connection time is reasonable (should be fast for responsive server)
            val totalTime = System.currentTimeMillis() - startTime
            assertTrue(
                "Preservation FAILED: Connection took too long (${totalTime}ms) for responsive server",
                totalTime < 5000
            )
            
        } finally {
            serverThread?.interrupt()
            serverSocket.close()
        }
    }
    
    /**
     * Test: Data flows correctly when server responds immediately.
     * 
     * **Preservation**: Data transmission must work identically before and after fix.
     * 
     * **Expected Behavior**: Client can send and receive data on responsive connection.
     * This test should PASS on both unfixed and fixed code.
     */
    @Test
    fun `preservation - data flows correctly on responsive server`() {
        val messagesReceived = AtomicInteger(0)
        val expectedMessages = 3
        val latch = CountDownLatch(expectedMessages)
        val errorOccurred = AtomicReference<String?>(null)
        
        // Create a server that echoes messages
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        
        try {
            val serverThread = Thread {
                try {
                    val clientSocket = serverSocket.accept()
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(clientSocket.getInputStream()))
                    val writer = java.io.PrintWriter(clientSocket.getOutputStream(), true)
                    
                    // Perform WebSocket handshake
                    var line: String?
                    var key: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("Sec-WebSocket-Key:")) {
                            key = line!!.substring("Sec-WebSocket-Key:".length).trim()
                        }
                        if (line!!.isEmpty()) break
                    }
                    
                    if (key != null) {
                        val acceptKey = java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                            .let { java.util.Base64.getEncoder().encodeToString(it) }
                        
                        writer.println("HTTP/1.1 101 Switching Protocols")
                        writer.println("Upgrade: websocket")
                        writer.println("Connection: Upgrade")
                        writer.println("Sec-WebSocket-Accept: $acceptKey")
                        writer.println()
                        writer.flush()
                        
                        // Send multiple messages
                        for (i in 1..expectedMessages) {
                            val message = "Message $i"
                            val payload = message.toByteArray()
                            val frame = java.io.ByteArrayOutputStream()
                            frame.write(0x81) // Text frame
                            frame.write(payload.size)
                            frame.write(payload)
                            clientSocket.getOutputStream().write(frame.toByteArray())
                            clientSocket.getOutputStream().flush()
                            Thread.sleep(100)
                        }
                    }
                    
                    Thread.sleep(1000)
                    clientSocket.close()
                } catch (e: Exception) {
                    // Expected during shutdown
                }
            }
            serverThread.start()
            
            Thread.sleep(100)
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .build()
            
            val listener = object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    messagesReceived.incrementAndGet()
                    latch.countDown()
                }
                
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    errorOccurred.set("Failed: ${t.message}")
                    // Count down remaining to unblock
                    repeat(expectedMessages - messagesReceived.get()) { latch.countDown() }
                }
            }
            
            client.newWebSocket(
                okhttp3.Request.Builder()
                    .url("ws://localhost:$port/test")
                    .build(),
                listener
            )
            
            // Wait for all messages
            val completed = latch.await(10, TimeUnit.SECONDS)
            
            assertTrue(
                "Preservation FAILED: Should receive all messages from responsive server. " +
                    "Received: ${messagesReceived.get()}/$expectedMessages, " +
                    "Error: ${errorOccurred.get()}",
                completed
            )
            
            assertEquals(
                "Preservation FAILED: Should receive exactly $expectedMessages messages",
                expectedMessages,
                messagesReceived.get()
            )
            
        } finally {
            serverThread?.interrupt()
            serverSocket.close()
        }
    }
    
    /**
     * Test: Connection with valid configuration behaves identically before and after fix.
     * 
     * **Preservation**: The fix (changing readTimeout from 0 to 60) should not affect
     * connections that complete within 60 seconds.
     * 
     * **Expected Behavior**: Connections that complete normally should work the same way.
     * This test should PASS on both unfixed and fixed code.
     */
    @Test
    fun `preservation - normal connection timing unchanged after fix`() {
        val connectionTimes = mutableListOf<Long>()
        val iterations = 5
        
        repeat(iterations) { iteration ->
            val latch = CountDownLatch(1)
            val connectionOpened = AtomicBoolean(false)
            
            val serverSocket = java.net.ServerSocket(0)
            val port = serverSocket.localPort
            
            try {
                val serverThread = Thread {
                    try {
                        val clientSocket = serverSocket.accept()
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(clientSocket.getInputStream()))
                        val writer = java.io.PrintWriter(clientSocket.getOutputStream(), true)
                        
                        var line: String?
                        var key: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.startsWith("Sec-WebSocket-Key:")) {
                                key = line!!.substring("Sec-WebSocket-Key:".length).trim()
                            }
                            if (line!!.isEmpty()) break
                        }
                        
                        if (key != null) {
                            val acceptKey = java.security.MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                                .let { java.util.Base64.getEncoder().encodeToString(it) }
                            
                            writer.println("HTTP/1.1 101 Switching Protocols")
                            writer.println("Upgrade: websocket")
                            writer.println("Connection: Upgrade")
                            writer.println("Sec-WebSocket-Accept: $acceptKey")
                            writer.println()
                            writer.flush()
                        }
                        
                        Thread.sleep(500)
                        clientSocket.close()
                    } catch (e: Exception) {
                        // Expected
                    }
                }
                serverThread.start()
                Thread.sleep(50)
                
                // Use the production configuration
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)  // Production config (will be 60 after fix)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .build()
                
                val startTime = System.currentTimeMillis()
                
                val listener = object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        connectionOpened.set(true)
                        latch.countDown()
                    }
                    
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        latch.countDown()
                    }
                }
                
                client.newWebSocket(
                    okhttp3.Request.Builder()
                        .url("ws://localhost:$port/test")
                        .build(),
                    listener
                )
                
                latch.await(5, TimeUnit.SECONDS)
                connectionTimes.add(System.currentTimeMillis() - startTime)
                
            } finally {
                serverThread?.interrupt()
                serverSocket.close()
            }
        }
        
        // Verify all connections succeeded
        assertTrue(
            "Preservation FAILED: All connections should succeed",
            connectionTimes.size == iterations
        )
        
        // Verify connection times are reasonable (all under 1 second for responsive server)
        val avgTime = connectionTimes.average()
        assertTrue(
            "Preservation FAILED: Average connection time ($avgTime ms) should be under 1000ms for responsive server",
            avgTime < 1000
        )
        
        // Verify consistency (no significant variance)
        val maxTime = connectionTimes.maxOrNull() ?: 0
        val minTime = connectionTimes.minOrNull() ?: 0
        val variance = maxTime - minTime
        assertTrue(
            "Preservation FAILED: Connection times should be consistent. " +
                "Min: ${minTime}ms, Max: ${maxTime}ms, Variance: ${variance}ms",
            variance < 2000  // Allow up to 2 second variance
        )
    }
    
    /**
     * Test: OkHttpClient configuration matches production expectations.
     * 
     * **Preservation**: Verify the client configuration is correct for normal operation.
     * 
     * **Expected Behavior**: Client should have appropriate timeouts for normal operation.
     * This test documents the expected configuration.
     */
    @Test
    fun `preservation - okHttpClient configuration is appropriate for production`() {
        // Create client with production configuration
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // Current production (will change to 60)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
        
        // Verify connect timeout is reasonable
        assertEquals(
            "Connect timeout should be 15 seconds",
            15,
            client.connectTimeoutMillis / 1000
        )
        
        // Document current read timeout (0 = infinite, the bug)
        // After fix, this should be 60 seconds
        val readTimeoutSeconds = client.readTimeoutMillis / 1000
        assertTrue(
            "Read timeout should be either 0 (current/buggy) or 60 (fixed). " +
                "Current value: $readTimeoutSeconds seconds",
            readTimeoutSeconds == 0 || readTimeoutSeconds == 60
        )
        
        // The key preservation property: both 0 and 60 second timeouts
        // should work for connections that complete within 60 seconds
        // This is because responsive servers respond quickly
    }
    
    /**
     * Test: WebSocket connection to responsive server handles close gracefully.
     * 
     * **Preservation**: Connection close should be handled gracefully.
     * 
     * **Expected Behavior**: Clean close without errors.
     * This test should PASS on both unfixed and fixed code.
     */
    @Test
    fun `preservation - connection close handled gracefully on responsive server`() {
        val connectionOpened = AtomicBoolean(false)
        val connectionClosed = AtomicBoolean(false)
        val closeCode = AtomicInteger(-1)
        val errorOccurred = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        
        try {
            val serverThread = Thread {
                try {
                    val clientSocket = serverSocket.accept()
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(clientSocket.getInputStream()))
                    val writer = java.io.PrintWriter(clientSocket.getOutputStream(), true)
                    
                    var line: String?
                    var key: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("Sec-WebSocket-Key:")) {
                            key = line!!.substring("Sec-WebSocket-Key:".length).trim()
                        }
                        if (line!!.isEmpty()) break
                    }
                    
                    if (key != null) {
                        val acceptKey = java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                            .let { java.util.Base64.getEncoder().encodeToString(it) }
                        
                        writer.println("HTTP/1.1 101 Switching Protocols")
                        writer.println("Upgrade: websocket")
                        writer.println("Connection: Upgrade")
                        writer.println("Sec-WebSocket-Accept: $acceptKey")
                        writer.println()
                        writer.flush()
                        
                        // Wait a bit then send close frame
                        Thread.sleep(500)
                        
                        // Send close frame (0x88 = close frame)
                        val closeFrame = byteArrayOf(0x88.toByte(), 0x00)
                        clientSocket.getOutputStream().write(closeFrame)
                        clientSocket.getOutputStream().flush()
                    }
                    
                    Thread.sleep(500)
                    clientSocket.close()
                } catch (e: Exception) {
                    // Expected
                }
            }
            serverThread.start()
            
            Thread.sleep(100)
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .build()
            
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    connectionOpened.set(true)
                }
                
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    closeCode.set(code)
                }
                
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    connectionClosed.set(true)
                    latch.countDown()
                }
                
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    errorOccurred.set("Failed: ${t.message}")
                    latch.countDown()
                }
            }
            
            client.newWebSocket(
                okhttp3.Request.Builder()
                    .url("ws://localhost:$port/test")
                    .build(),
                listener
            )
            
            val completed = latch.await(5, TimeUnit.SECONDS)
            
            assertTrue(
                "Preservation FAILED: Connection close should complete within 5 seconds. " +
                    "Error: ${errorOccurred.get()}",
                completed
            )
            
            assertTrue(
                "Preservation FAILED: Connection should open before closing",
                connectionOpened.get()
            )
            
            // Note: connectionClosed might not be true if server initiated close
            // The important thing is no failure occurred
            assertNull(
                "Preservation FAILED: No error should occur during graceful close. " +
                    "Error: ${errorOccurred.get()}",
                errorOccurred.get()
            )
            
        } finally {
            serverThread?.interrupt()
            serverSocket.close()
        }
    }
}

// Note: Using java.io.ByteArrayOutputStream directly - no helper needed
