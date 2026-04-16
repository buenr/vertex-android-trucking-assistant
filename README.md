# Gemini Android Trucking Assistant

A hands-free, voice-activated "CB Radio" style AI assistant designed specifically for truck drivers. It leverages the **Gemini 3.1 Flash Live API** over WebSockets to provide real-time, bi-directional voice interaction natively on Android devices (like the Samsung Galaxy Tab Active 5).

## App Logic & Architecture

The application is built completely natively in Kotlin using Jetpack Compose and a suite of background handlers for WebSocket management and raw audio processing. 

### Core Components (`.kt` files)

*   **`GeminiViewModel.kt`**: The central state manager of the application. It acts as the bridge between the UI (`MainActivity`) and the underlying services (`AudioRecorder`, `AudioPlayer`, and `GeminiWebSocketClient`). It maintains the `GeminiUiState`, handling connection logic, logging, state transitions (e.g., IDLE, LISTENING, THINKING, SPEAKING), and reacting to model interruptions.
*   **`MainActivity.kt`**: The frontend UI built using Jetpack Compose. It displays the connection state, conversation transcripts (User Text vs. Gemini Text), and an interactive logging console for debugging the real-time connection. 

### Network Layer

*   **`network/GeminiWebSocketClient.kt`**: The workhorse of the application. It opens a persistant OkHttp WebSocket connection to the Google Generative Language `BidiGenerateContent` endpoint. Upon connecting, it sends a configuration payload configuring it for `AUDIO` responsiveness and provides the system instructions (prompting it to act as a Swift Transportation trucking copilot). It handles routing inbound messages (dispatching audio to playback, parsing text transcripts, handling server tool calls).
*   **`network/GeminiModels.kt`**: Contains the data classes and enums mapping to the Gemini WebSocket JSON protocol (such as `FunctionDeclaration`, `ToolCall`, and `GeminiState`).

### Audio Pipeline

*   **`audio/AudioRecorder.kt`**: Uses Android's `AudioRecord` API to capture microphone input. It captures raw PCM 16-bit audio in Mono at `16,000 Hz` (defined in `AudioConfig.kt`). The audio chunks are passed to the `GeminiWebSocketClient` as Base64 encoded strings in real-time.
*   **`audio/AudioPlayer.kt`**: Responsible for playing the raw PCM audio responses streamed back from Gemini. It leverages Android `AudioTrack` handling `24,000 Hz` Mono PCM 16-bit streams. It supports "flushing" the audio buffer immediately if the user interrupts the model.
*   **`audio/AudioConfig.kt`**: Central configuration object unifying the sample rates, channel configurations, and encoding formats required by both the device hardware and the Gemini Live API.

### Agentic Tools

*   **`tools/TruckingTools.kt`**: Defines the "skills" the Gemini Copilot has access to. It implements deterministic local tools (simulating backend API calls) to provide real-time trucking logistics data:
    *   `getDriverProfile`: Driver contextual info (location, truck/trailer equipment).
    *   `getLoadStatus`: Active load IDs, stop ETAs, and transit risks.
    *   `getComplianceAlerts`: HOS hours and DVIR requirements.
    *   `getRouteRisks`: Weather and traffic data along the current route.
    *   `getDispatchInbox`: Critical dispatch exception messages.
    *   `getCompanyFAQs`: SOP documentation (Pet policies, breakdown procedures, etc).
    *   `getPaycheckInfo`: Settled pay and mileage totals.
    *   `findNearestSwiftTerminal`: Nearby Swift yards and internal amenities.
    *   `checkInGaugeSafetyScore`: Driver telemetry ranking and recent safety events.
    *   `getFuelNetworkRouting`: Approved in-network fuel routing logic.

### What Can You Ask? (Example Prompts)

With the tools implemented above, the driver can naturally ask the copilot questions such as:
*   **"What am I hauling right now and when is my next stop?"** *(Triggers `getLoadStatus`)*
*   **"Where is the nearest Swift terminal with a shower and a maintenance shop?"** *(Triggers `findNearestSwiftTerminal`)*
*   **"How much drive time do I have left on my clock today?"** *(Triggers `getComplianceAlerts`)*
*   **"How does my InGauge safety score look? Did that hard brake yesterday affect my bonus?"** *(Triggers `checkInGaugeSafetyScore`)*
*   **"Where should I get fuel next on this route?"** *(Triggers `getFuelNetworkRouting`)*
*   **"What's the weather and traffic looking like for the next 4 hours?"** *(Triggers `getRouteRisks`)*
*   **"Read my unread messages from dispatch."** *(Triggers `getDispatchInbox`)*
*   **"What is the company policy on having a dog in the truck?"** *(Triggers `getCompanyFAQs`)*

## Setup Instructions

1. Ensure you have the `secrets.properties` file in your project root folder (next to `local.properties`).
2. Add your Gemini API key:
   ```properties
   GEMINI_API_KEY=...
   ```
3. Sync Gradle and build the project.
