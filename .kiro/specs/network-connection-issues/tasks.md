# Implementation Plan

## Bug 5: Stale Connection Detection (High Priority)

- [x] 1. Write bug condition exploration test for stale connection
  - **Property 1: Bug Condition** - Infinite Read Timeout Allows Indefinite Hangs
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing case: readTimeout=0 with unresponsive server
  - Test that WebSocket with readTimeout=0 hangs indefinitely when server stops responding
  - Simulate server that accepts connection but never sends data
  - Verify connection remains "open" for > 60 seconds without detection
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (connection hangs indefinitely, proving the bug exists)
  - Document counterexamples found (e.g., "Connection with readTimeout=0 hangs for 5+ minutes without detection")
  - _Requirements: 2.5_
  - _Bug_Condition: isBugCondition5(input) where input.readTimeoutMs == 0_

- [x] 2. Write preservation property tests for stable connections (Bug 5)
  - **Property 2: Preservation** - Normal WebSocket Behavior on Responsive Server
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: WebSocket with responsive server completes handshake normally on unfixed code
  - Observe: Data flows correctly when server responds on unfixed code
  - Write property-based test: for all responsive server scenarios, connection behaves identically before and after fix
  - Verify test passes on UNFIXED code
  - _Requirements: 3.1, 3.2_
  - _Preservation: Stable network connections must continue to work exactly as before_

- [-] 3. Fix for stale connection detection

  - [x] 3.1 Implement read timeout fix in GeminiWebSocketClient.kt
    - Change `readTimeout(0, TimeUnit.SECONDS)` to `readTimeout(60, TimeUnit.SECONDS)`
    - Add connection state tracking with timestamps
    - Implement onConnectionStale callback for stale detection
    - _Bug_Condition: isBugCondition5(input) where input.readTimeoutMs == 0 AND NOT hasKeepAliveMechanism(input)_
    - _Expected_Behavior: Detect stale state within 60 seconds using read timeout, attempt reconnection or notify user_
    - _Preservation: Normal connections on responsive servers must work identically_
    - _Requirements: 2.5_

  - [ ] 3.2 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Stale Connection Detection
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 encodes the expected behavior
    - When this test passes, it confirms the expected behavior is satisfied
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms stale connections are detected within 60 seconds)
    - _Requirements: 2.5_

  - [ ] 3.3 Verify preservation tests still pass
    - **Property 2: Preservation** - Normal WebSocket Behavior
    - **IMPORTANT**: Re-run the SAME tests from task 2 - do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all tests still pass after fix (no regressions)

## Bug 2: Premature Session Termination (High Priority)

- [x] 4. Write bug condition exploration test for premature termination
  - **Property 1: Bug Condition** - Immediate Termination Without Recovery
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing case: 6 consecutive low-speed polls without reconnection
  - Test that NetworkSpeedMonitor terminates immediately after 6 low-speed polls (12 seconds)
  - Verify no reconnection attempt is made before termination
  - Verify no user warning is displayed before termination
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (immediate termination without recovery, proving the bug exists)
  - Document counterexamples found (e.g., "Session terminated after 12 seconds of low speed with no reconnection attempt")
  - _Requirements: 2.2_
  - _Bug_Condition: isBugCondition2(input) where input.consecutiveLowSpeedCount >= 6 AND NOT hasAttemptedReconnection(input)_

- [x] 5. Write preservation property tests for sufficient network speed (Bug 2)
  - **Property 2: Preservation** - Stable Session on Sufficient Network
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Session continues normally when speed > 100 kbps on unfixed code
  - Observe: No termination triggered when network is stable on unfixed code
  - Write property-based test: for all inputs where speed >= 100 kbps, session continues without termination
  - Verify test passes on UNFIXED code
  - _Requirements: 3.1_
  - _Preservation: Stable network connections must maintain session without interruption_

- [-] 6. Fix for premature session termination

  - [x] 6.1 Implement reconnection and warning in NetworkSpeedMonitor.kt
    - Add `onLowSpeedWarning: (Int) -> Unit` callback for user warnings
    - Add `attemptReconnection: () -> Boolean` callback
    - Trigger warning at 4 consecutive low-speed polls (8 seconds)
    - Attempt reconnection at 5 polls before final termination
    - Increase poll count threshold from 6 to 9 (18 seconds total)
    - _Bug_Condition: isBugCondition2(input) where input.speedKbps < 100 AND input.consecutiveLowSpeedCount >= 6_
    - _Expected_Behavior: Attempt reconnection before terminating, display warning to user, extend grace period if user is interacting_
    - _Preservation: Sessions on stable network must continue without interruption_
    - _Requirements: 2.2_

  - [x] 6.2 Implement warning dialog in GeminiViewModel.kt
    - Display warning dialog when low-speed warning callback fires
    - Provide user option to continue waiting or close
    - Handle reconnection attempt result
    - _Requirements: 2.2_

  - [ ] 6.3 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Graceful Low-Speed Handling
    - **IMPORTANT**: Re-run the SAME test from task 4 - do NOT write a new test
    - The test from task 4 encodes the expected behavior
    - When this test passes, it confirms the expected behavior is satisfied
    - Run bug condition exploration test from step 4
    - **EXPECTED OUTCOME**: Test PASSES (confirms reconnection is attempted and warning is shown)
    - _Requirements: 2.2_

  - [ ] 6.4 Verify preservation tests still pass
    - **Property 2: Preservation** - Stable Session Behavior
    - **IMPORTANT**: Re-run the SAME tests from task 5 - do NOT write new tests
    - Run preservation property tests from step 5
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all tests still pass after fix (no regressions)

## Final Checkpoint

- [ ] 7. Checkpoint - Ensure all tests pass
  - Run all bug condition exploration tests - all should PASS
  - Run all preservation property tests - all should PASS
  - Run existing unit tests - all should PASS
  - Verify no regressions in audio playback, Bluetooth SCO, tool execution, or voice commands
  - Ask the user if questions arise
