# Network Connection Issues Bugfix Design

## Overview

This design addresses six critical network connectivity bugs in the GeminiLive Android app that affect startup reliability, session stability, and error handling. The bugs range from missing progress indicators during connection delays to premature session termination and inadequate error messaging. Each bug condition is formalized below with specific fix implementations that preserve existing functionality while improving user experience and reliability.

## Glossary

- **Bug_Condition (C)**: The condition that triggers each bug - specific network states, timing scenarios, or error conditions
- **Property (P)**: The desired behavior when each bug condition is addressed - proper feedback, graceful handling, or informative errors
- **Preservation**: Existing audio playback, Bluetooth SCO routing, tool execution, and voice command handling that must remain unchanged
- **GeminiWebSocketClient**: The WebSocket client in `GeminiWebSocketClient.kt` that manages the bidirectional streaming connection to Gemini API
- **NetworkSpeedMonitor**: The monitoring class in `NetworkSpeedMonitor.kt` that tracks network speed and triggers low-speed timeouts
- **GeminiViewModel**: The main ViewModel in `GeminiViewModel.kt` that orchestrates session lifecycle and network monitoring
- **WebSocket read timeout**: The OkHttp read timeout setting (currently 0 = infinite) that determines how long to wait for server responses
- **Grace period**: The time window after audio playback ends before low-speed detection resumes

## Bug Details

### Bug 1: Long Delays at Startup/Reconnection

#### Bug Condition

The bug manifests when the app starts or reconnects and the WebSocket connection takes longer than expected to establish. The user sees only "Connecting..." with no indication of progress or whether the connection is actually working.

**Formal Specification:**
```
FUNCTION isBugCondition1(input)
  INPUT: input of type ConnectionEvent
  OUTPUT: boolean
  
  RETURN input.eventType IN ['STARTUP', 'RECONNECTION']
         AND input.connectionState IN ['CONNECTING', 'WAITING_FOR_SETUP', 'WAITING_FOR_READY']
         AND input.timeInCurrentState > EXPECTED_THRESHOLD_MS
         AND NOT hasVisibleProgressIndicator(input)
END FUNCTION
```

#### Examples

- User opens app on LTE with 2 bars, sees "Connecting..." for 15+ seconds with no feedback
- Connection drops and reconnects, user sees "Setup sent, waiting..." indefinitely
- Authentication succeeds but WebSocket setup takes 10+ seconds with no status update

### Bug 2: Premature Session Termination

#### Bug Condition

The bug manifests when network speed drops below 100 kbps for 6 consecutive polls (12 seconds total), causing the app to immediately terminate without attempting reconnection or providing user warnings.

**Formal Specification:**
```
FUNCTION isBugCondition2(input)
  INPUT: input of type NetworkSpeedEvent
  OUTPUT: boolean
  
  RETURN input.speedKbps < MINIMUM_SPEED_KBPS
         AND input.consecutiveLowSpeedCount >= ZERO_SPEED_POLL_COUNT
         AND NOT isPlaybackActive(input)
         AND NOT isInGracePeriod(input)
         AND NOT hasAttemptedReconnection(input)
END FUNCTION
```

#### Examples

- User on LTE with fluctuating signal experiences 12 seconds of low speed, app closes immediately
- Network temporarily congested (not unavailable) triggers shutdown without recovery attempt
- User has stable but slow connection (50 kbps) that could recover, but app terminates anyway

### Bug 3: Generic Error Messages

#### Bug Condition

The bug manifests when any WebSocket connection failure occurs, resulting in a generic error message that doesn't distinguish between authentication failures, network unavailability, or server errors.

**Formal Specification:**
```
FUNCTION isBugCondition3(input)
  INPUT: input of type WebSocketErrorEvent
  OUTPUT: boolean
  
  RETURN input.errorType IN ['CONNECTION_FAILURE', 'TIMEOUT', 'AUTH_ERROR', 'SERVER_ERROR']
         AND input.displayedMessage == genericErrorMessage
         AND NOT hasErrorClassification(input)
         AND NOT hasUserGuidance(input)
END FUNCTION
```

#### Examples

- Authentication token expired → User sees "WebSocket failure" instead of "Session expired, please restart"
- No network connectivity → User sees "Connection Error" instead of "No internet connection"
- Server returns 503 → User sees generic error instead of "Service temporarily unavailable"

### Bug 4: Network Transition Handling

#### Bug Condition

The bug manifests when the device transitions between network types (WiFi ↔ LTE) during an active session, causing connection drops without automatic reconnection.

**Formal Specification:**
```
FUNCTION isBugCondition4(input)
  INPUT: input of type NetworkTransitionEvent
  OUTPUT: boolean
  
  RETURN input.previousNetworkType IN ['WIFI', 'LTE']
         AND input.newNetworkType IN ['WIFI', 'LTE']
         AND input.previousNetworkType != input.newNetworkType
         AND input.sessionState == 'ACTIVE'
         AND NOT hasGracefulHandoff(input)
         AND NOT hasAutomaticReconnection(input)
END FUNCTION
```

#### Examples

- User walks from WiFi coverage to LTE, connection drops without recovery
- User switches from LTE to WiFi, WebSocket becomes stale and unresponsive
- Network handoff during AI response causes session to hang

### Bug 5: Stale Connection Detection

#### Bug Condition

The bug manifests when the WebSocket read timeout is set to 0 (infinite), allowing connections to hang indefinitely without detecting stale or hung connections.

**Formal Specification:**
```
FUNCTION isBugCondition5(input)
  INPUT: input of type WebSocketConfig
  OUTPUT: boolean
  
  RETURN input.readTimeoutMs == 0  // Infinite timeout
         AND NOT hasKeepAliveMechanism(input)
         AND NOT hasStaleDetection(input)
         AND canHangIndefinitely(input)
END FUNCTION
```

#### Examples

- Server stops responding but connection appears "open" indefinitely
- Network silently drops but client doesn't detect the failure
- User sees "Connected" status but no data flows for minutes

### Bug 6: Insufficient Grace Periods

#### Bug Condition

The bug manifests when the 8-second grace period after audio playback ends is too short for slower networks or users who need more time to respond.

**Formal Specification:**
```
FUNCTION isBugCondition6(input)
  INPUT: input of type PlaybackEndEvent
  OUTPUT: boolean
  
  RETURN input.playbackJustEnded == true
         AND input.gracePeriodMs < ADEQUATE_GRACE_MS
         AND input.networkSpeedKbps < OPTIMAL_SPEED_KBPS
         AND lowSpeedDetectionResumesTooQuickly(input)
END FUNCTION
```

#### Examples

- User on slow LTE (150 kbps) has 8 seconds to respond before speed check resumes
- Network takes 5+ seconds to stabilize after playback, but grace period already expired
- User begins speaking at 7 seconds, but speed check already triggered shutdown logic

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Mouse clicks and touch interactions on UI elements must continue to work exactly as before
- Audio playback quality and timing must remain unchanged when network is sufficient
- Bluetooth SCO connection establishment and audio routing must continue to work
- Tool execution (getDriverProfile, getLoadStatus, etc.) must produce identical results
- Voice command "close app" must continue to trigger graceful shutdown
- Microphone muting during AI speech must prevent self-interruption as before

**Scope:**
All inputs that do NOT involve the bug conditions should be completely unaffected by these fixes. This includes:
- Normal operation on stable, fast network connections
- Successful authentication and session establishment
- Audio recording and streaming during active sessions
- Tool call execution and response handling

## Hypothesized Root Cause

Based on the bug analysis, the most likely issues are:

1. **Missing Progress Indicators (Bug 1)**: The connection flow lacks intermediate status updates
   - `GeminiViewModel.start()` shows "Connecting..." but doesn't update during WebSocket handshake
   - No timeout handling for stuck connections
   - No visual feedback during authentication token fetch

2. **Aggressive Shutdown Logic (Bug 2)**: The `NetworkSpeedMonitor` terminates too quickly
   - 12 seconds (6 polls × 2 seconds) is too short for temporary network fluctuations
   - No reconnection attempt before termination
   - No user warning before closing the app

3. **Undifferentiated Error Handling (Bug 3)**: The `GeminiWebSocketClient.onFailure` handler doesn't classify errors
   - All errors route to the same `onError` callback with generic message
   - No distinction between HTTP status codes, network availability, or auth failures
   - Missing error codes from WebSocket close events

4. **No Network Transition Handling (Bug 4)**: Missing `ConnectivityManager.NetworkCallback`
   - No listener for network capability changes
   - WebSocket not reconnected when network changes
   - No detection of network handoff scenarios

5. **Infinite Read Timeout (Bug 5)**: OkHttp configured with `readTimeout(0, TimeUnit.SECONDS)`
   - Allows indefinite hangs without detection
   - No ping/pong keepalive mechanism
   - No application-level heartbeat

6. **Fixed Grace Period (Bug 6)**: 8-second grace period doesn't adapt to network conditions
   - Same duration for fast WiFi and slow LTE
   - Doesn't account for round-trip latency variations
   - Doesn't consider user interaction timing patterns

## Correctness Properties

Property 1: Bug Condition - Startup Progress Indicators

_For any_ connection attempt where the WebSocket handshake takes longer than 3 seconds, the fixed system SHALL display progressive status updates ("Connecting...", "Authenticating...", "Establishing session...") and SHALL timeout with an informative error after 30 seconds.

**Validates: Requirements 2.1**

Property 2: Bug Condition - Graceful Low-Speed Handling

_For any_ network speed drop below 100 kbps for 12+ seconds, the fixed system SHALL attempt at least one reconnection before terminating, SHALL display a warning to the user before closing, and SHALL extend the grace period if the user is actively interacting.

**Validates: Requirements 2.2**

Property 3: Bug Condition - Classified Error Messages

_For any_ WebSocket connection failure, the fixed system SHALL classify the error as authentication, network, or server-related, and SHALL display a specific error message with actionable guidance (e.g., "Check your internet connection" vs "Session expired, restart the app").

**Validates: Requirements 2.3**

Property 4: Bug Condition - Network Transition Recovery

_For any_ network type transition (WiFi ↔ LTE) during an active session, the fixed system SHALL detect the transition via NetworkCallback, SHALL attempt to reconnect the WebSocket within 5 seconds, and SHALL preserve session state during the handoff.

**Validates: Requirements 2.4**

Property 5: Bug Condition - Stale Connection Detection

_For any_ WebSocket connection that becomes unresponsive, the fixed system SHALL detect the stale state within 60 seconds using read timeout or keepalive mechanism, and SHALL attempt reconnection or notify the user.

**Validates: Requirements 2.5**

Property 6: Bug Condition - Adaptive Grace Periods

_For any_ playback end event on a slow network (< 200 kbps), the fixed system SHALL extend the grace period proportionally to network latency, with a minimum of 10 seconds and maximum of 20 seconds.

**Validates: Requirements 2.6**

Property 7: Preservation - Stable Network Behavior

_For any_ input where network speed is sufficient (> 100 kbps) and stable, the fixed system SHALL produce exactly the same behavior as the original system, preserving all audio playback, tool execution, and voice command functionality.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

## Fix Implementation

### Changes Required

**File**: `app/src/main/java/trucker/geminilive/network/GeminiWebSocketClient.kt`

**Specific Changes**:

1. **Add Connection Timeout and Progress Callbacks (Bug 1)**:
   - Add `onProgressUpdate: (String) -> Unit` callback parameter
   - Emit progress updates during connection phases
   - Add connection timeout handling (30 seconds max)
   - Implement connection state machine with timestamps

2. **Classify WebSocket Errors (Bug 3)**:
   - Parse HTTP status codes from `onFailure` response
   - Classify errors: AUTH_ERROR (401/403), NETWORK_ERROR (no response), SERVER_ERROR (5xx)
   - Add error code to `onError` callback signature
   - Provide user-friendly error messages for each category

3. **Fix Infinite Read Timeout (Bug 5)**:
   - Change `readTimeout(0, TimeUnit.SECONDS)` to `readTimeout(60, TimeUnit.SECONDS)`
   - Add ping/pong keepalive if supported by OkHttp
   - Implement application-level heartbeat for stale detection

**File**: `app/src/main/java/trucker/geminilive/network/NetworkSpeedMonitor.kt`

**Specific Changes**:

1. **Add Reconnection Attempt Before Termination (Bug 2)**:
   - Add `onLowSpeedWarning: (Int) -> Unit` callback for user warnings
   - Add `attemptReconnection: () -> Boolean` callback
   - Trigger warning at 4 consecutive low-speed polls (8 seconds)
   - Attempt reconnection at 5 polls before final termination
   - Increase poll count threshold from 6 to 9 (18 seconds total)

2. **Implement Adaptive Grace Period (Bug 6)**:
   - Make `POST_PLAYBACK_GRACE_MS` dynamic based on network speed
   - Calculate grace period: `baseGraceMs + (latencyFactor * 1000)`
   - Minimum 10 seconds, maximum 20 seconds
   - Consider recent network speed average in calculation

**File**: `app/src/main/java/trucker/geminilive/GeminiViewModel.kt`

**Specific Changes**:

1. **Add Network Transition Callback (Bug 4)**:
   - Register `ConnectivityManager.NetworkCallback` for network changes
   - Detect WiFi ↔ LTE transitions during active session
   - Trigger WebSocket reconnection on network change
   - Preserve session state during reconnection

2. **Handle Progress Updates (Bug 1)**:
   - Update UI status based on connection progress callbacks
   - Add timeout handling for stuck connections
   - Display specific error messages based on error classification

3. **Handle Low-Speed Warnings (Bug 2)**:
   - Display warning dialog when low-speed warning callback fires
   - Attempt reconnection before final termination
   - Provide user option to continue waiting or close

### Implementation Priority

1. **High Priority**: Bug 5 (Stale connection) - Critical for reliability
2. **High Priority**: Bug 2 (Premature termination) - Major user impact
3. **Medium Priority**: Bug 3 (Error messages) - User experience
4. **Medium Priority**: Bug 1 (Progress indicators) - User experience
5. **Medium Priority**: Bug 4 (Network transitions) - Edge case but important
6. **Low Priority**: Bug 6 (Grace periods) - Optimization

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate each bug on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate each bug BEFORE implementing the fix. Confirm or refute the root cause analysis.

**Test Plan**: Write tests that simulate each bug condition and observe failures on UNFIXED code.

**Test Cases**:

1. **Startup Delay Test**: Simulate slow WebSocket connection (> 10 seconds) and verify no progress updates appear (will fail on unfixed code)

2. **Low-Speed Termination Test**: Simulate 12 seconds of low speed and verify immediate termination without reconnection attempt (will fail on unfixed code)

3. **Error Classification Test**: Simulate various error conditions (401, 503, network unavailable) and verify all produce same generic error (will fail on unfixed code)

4. **Network Transition Test**: Simulate WiFi → LTE transition and verify connection drops without recovery (will fail on unfixed code)

5. **Stale Connection Test**: Simulate server stop responding and verify connection hangs indefinitely (will fail on unfixed code)

6. **Grace Period Test**: Simulate playback end on slow network and verify grace period expires too quickly (will fail on unfixed code)

**Expected Counterexamples**:
- No progress updates during connection delays
- Immediate termination without reconnection
- Generic "WebSocket failure" for all error types
- Connection drops on network transition
- Infinite hang on server stop
- 8-second grace period regardless of network speed

### Fix Checking

**Goal**: Verify that for all inputs where each bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition1(input) DO
  result := handleConnection_fixed(input)
  ASSERT hasProgressUpdates(result)
  ASSERT hasTimeoutHandling(result)
END FOR

FOR ALL input WHERE isBugCondition2(input) DO
  result := handleLowSpeed_fixed(input)
  ASSERT hasReconnectionAttempt(result)
  ASSERT hasUserWarning(result)
END FOR

// Similar for bugs 3-6...
```

### Preservation Checking

**Goal**: Verify that for all inputs where bug conditions do NOT hold, the fixed functions produce the same results as original functions.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition1(input) 
                AND NOT isBugCondition2(input)
                AND NOT isBugCondition3(input)
                AND NOT isBugCondition4(input)
                AND NOT isBugCondition5(input)
                AND NOT isBugCondition6(input) DO
  ASSERT handleConnection_original(input) = handleConnection_fixed(input)
  ASSERT handleLowSpeed_original(input) = handleLowSpeed_fixed(input)
  // etc.
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for non-buggy inputs

**Test Cases**:

1. **Stable Network Preservation**: Verify that on stable fast network, all audio, tool, and voice features work identically

2. **Bluetooth SCO Preservation**: Verify Bluetooth headset connection and audio routing unchanged

3. **Tool Execution Preservation**: Verify all tool calls produce identical results

4. **Voice Command Preservation**: Verify "close app" command still triggers graceful shutdown

### Unit Tests

- Test connection state machine transitions with timing
- Test error classification logic for all error types
- Test network speed threshold calculations
- Test grace period calculation based on network speed
- Test network transition detection logic

### Property-Based Tests

- Generate random connection delays and verify progress updates appear
- Generate random network speed patterns and verify appropriate handling
- Generate random error conditions and verify correct classification
- Generate random network transitions and verify reconnection attempts

### Integration Tests

- Test full startup flow with simulated slow network
- Test session recovery after network transition
- Test error handling with actual network failures
- Test grace period behavior during active conversation on slow network
