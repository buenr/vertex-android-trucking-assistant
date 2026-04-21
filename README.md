# Vertex Android Trucking Assistant

A hands-free, voice-activated "CB Radio" style AI assistant designed specifically for truck drivers. It leverages **Vertex AI (Enterprise)** over WebSockets using **`gemini-live-2.5-flash-native-audio`** (GA stable) model to provide real-time, bi-directional voice interaction natively on Android devices (like as Samsung Galaxy Tab Active 5).

## App Logic & Architecture

The application is built completely natively in Kotlin using Jetpack Compose and a suite of background handlers for WebSocket management and raw audio processing. 

### Core Components (`.kt` files)

*   **`GeminiViewModel.kt`**: The central state manager of the application. It acts as the bridge between the UI (`MainActivity`) and the underlying services (`AudioRecorder`, `AudioPlayer`, and `GeminiWebSocketClient`). It maintains the `GeminiUiState`, handling connection logic, logging, state transitions (e.g., IDLE, LISTENING, THINKING, SPEAKING), and reacting to model interruptions.
*   **`MainActivity.kt`**: The frontend UI built using Jetpack Compose. It displays the connection state, conversation transcripts (User Text vs. Gemini Text), and an interactive logging console for debugging the real-time connection. 

### Network Layer

*   **`network/GeminiWebSocketClient.kt`**: The workhorse of application. It opens a persistent OkHttp WebSocket connection to Vertex AI `LlmBidiService/BidiGenerateContent` endpoint. Upon connecting, it sends a configuration payload configuring `gemini-live-2.5-flash-native-audio` (GA stable) model for `AUDIO` responsiveness and provides system instructions (prompting it to act as a Swift Transportation trucking copilot). It handles routing inbound messages (dispatching audio to playback, parsing text transcripts, handling server tool calls).
*   **`network/GeminiModels.kt`**: Contains the data classes and enums mapping to the Gemini WebSocket JSON protocol (such as `FunctionDeclaration`, `ToolCall`, and `GeminiState`).

### Audio Pipeline

*   **`audio/AudioRecorder.kt`**: Uses Android's `AudioRecord` API to capture microphone input. It captures raw PCM 16-bit audio in Mono at `16,000 Hz` (defined in `AudioConfig.kt`). The audio chunks are passed to the `GeminiWebSocketClient` as Base64 encoded strings in real-time.
*   **`audio/AudioPlayer.kt`**: Responsible for playing the raw PCM audio responses streamed back from Gemini. It leverages Android `AudioTrack` handling `24,000 Hz` Mono PCM 16-bit streams. It supports "flushing" the audio buffer immediately if the user interrupts the model.
*   **`audio/AudioConfig.kt`**: Central configuration object unifying the sample rates, channel configurations, and encoding formats required by both the device hardware and the Gemini Live API.

### Agentic Tools

*   **`tools/TruckingTools.kt`**: Defines the "skills" the Gemini Copilot has access to. It implements deterministic local tools (simulating backend API calls) to provide real-time trucking logistics data:
    *   `getDriverProfile`: Driver contextual info (location, truck/trailer equipment).
    *   `getLoadStatus`: Active load IDs, stop ETAs, and transit risks.
    *   `getHoursOfServiceClocks`: HOS hours and remaining drive time.
    *   `getTrafficAndWeather`: Immediate traffic and weather data for the next hour.
    *   `getDispatchInbox`: Critical dispatch exception messages.
    *   `getCompanyFAQs`: SOP documentation (Pet policies, breakdown procedures, etc).
    *   `getPaycheckInfo`: Settled pay and mileage totals.
    *   `findNearestSwiftTerminal`: Nearby Swift yards and internal amenities.
    *   `checkSafetyScore`: Driver telemetry ranking and recent safety events.
    *   `getFuelNetworkRouting`: Approved in-network fuel routing logic.
    *   `getContacts`: Swift Transportation departments and personnel contact info.
    *   `getNextLoadDetails`: Details on the next scheduled load (pre-dispatch).
    *   `closeApp`: Gracefully closes the application when the driver explicitly requests to exit.

### Tool Call Logging

The app logs all tool/function calls for analytics and metrics tracking. This helps understand driver usage patterns and identify frequently used features.

*   **`tools/ToolCallLogger.kt`**: Singleton logger that batches tool call metrics in memory and periodically flushes to disk.

**Logged metrics per call:**
- Timestamp (ISO 8601)
- Function name
- Driver ID
- Arguments passed (if any)
- Response time (milliseconds)
- Success status

**Log file location:**  
`/storage/emulated/0/Android/data/trucker.geminilive/files/tool_calls_log.jsonl`

**Log format:** JSON Lines (one JSON object per line)
```json
{"timestamp":"2026-04-21T14:30:45.123-0700","functionName":"getLoadStatus","driverId":"284145","arguments":null,"responseTimeMs":12,"success":true}
```

**Retrieving logs:**
```bash
adb pull /storage/emulated/0/Android/data/trucker.geminilive/files/tool_calls_log.jsonl
```

**Behavior:**
- Batches writes in memory (auto-flushes every 10 calls)
- Flushes on app pause, destroy, or network timeout
- File grows indefinitely (no rotation)

### Bluetooth Headset Support

The app supports Bluetooth headsets commonly used by truck drivers (e.g., BlueParrott, Blue Tiger). When a Bluetooth headset is detected, the app automatically establishes an SCO (Synchronous Connection-Oriented) audio connection for hands-free operation. This is managed by:

*   **`audio/BluetoothScoManager.kt`**: Handles Bluetooth SCO connection lifecycle, automatically connecting to paired headsets when a session starts and disconnecting when the session ends. This ensures crystal-clear audio input/output through the driver's headset.

### What Can You Ask? (Example Prompts)

With the tools implemented above, the driver can naturally ask the copilot questions such as:
*   **"What am I hauling right now and when is my next stop?"** *(Triggers `getLoadStatus`)*
*   **"Where is the nearest Swift terminal with a maintenance shop?"** *(Triggers `findNearestSwiftTerminal`)*
*   **"How much drive time do I have left on my clock today?"** *(Triggers `getHoursOfServiceClocks`)*
*   **"How does my safety score look? Did that hard brake yesterday affect my bonus?"** *(Triggers `checkSafetyScore`)*
*   **"Where should I get fuel next on this route?"** *(Triggers `getFuelNetworkRouting`)*
*   **"What's the weather and traffic looking like for the next hour?"** *(Triggers `getTrafficAndWeather`)*
*   **"Read my unread messages from dispatch."** *(Triggers `getDispatchInbox`)*
*   **"What is the company policy on having a dog in the truck?"** *(Triggers `getCompanyFAQs`)*
*   **"How do I reach my driver leader or payroll?"** *(Triggers `getContacts`)*
*   **"What's my next load after this one?"** *(Triggers `getNextLoadDetails`)*
*   **"Close the app"** or **"Exit"** *(Triggers `closeApp` - gracefully ends the session and closes the application)*

## Setup Instructions

1. Download your Vertex AI service account JSON key file.
2. Rename the file to `[project_name].json` and place it in the `app/src/main/assets/` directory. (Note: `*.json` files in this directory are ignored by Git to prevent accidental uploads).
3. Sync Gradle and build the project.
