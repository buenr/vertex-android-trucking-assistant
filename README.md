# Vertex Android Trucking Assistant

A hands-free, voice-activated "CB Radio" style AI assistant designed specifically for truck drivers. It leverages **Vertex AI (Enterprise)** over WebSockets using the **`gemini-live-2.5-flash-preview-native-audio-09-2025`** model to provide real-time, bi-directional voice interaction natively on Android devices (like the Samsung Galaxy Tab Active 5).

## App Logic & Architecture

The application is built completely natively in Kotlin using Jetpack Compose and a suite of background handlers for WebSocket management and raw audio processing. 

### Core Components (`.kt` files)

*   **`GeminiViewModel.kt`**: The central state manager of the application. It acts as the bridge between the UI (`MainActivity`) and the underlying services (`AudioRecorder`, `AudioPlayer`, and `GeminiWebSocketClient`). It maintains the `GeminiUiState`, handling connection logic, logging, state transitions (e.g., IDLE, LISTENING, THINKING, SPEAKING), and reacting to model interruptions.
*   **`MainActivity.kt`**: The frontend UI built using Jetpack Compose. It displays the connection state, conversation transcripts (User Text vs. Gemini Text), and an interactive logging console for debugging the real-time connection. 

### Network Layer

*   **`network/GeminiWebSocketClient.kt`**: The workhorse of the application. It opens a persistent OkHttp WebSocket connection to the Vertex AI `LlmBidiService/BidiGenerateContent` endpoint. Upon connecting, it sends a configuration payload configuring the `gemini-live-2.5-flash-preview-native-audio-09-2025` model for `AUDIO` responsiveness and provides the system instructions (prompting it to act as a Swift Transportation trucking copilot). It handles routing inbound messages (dispatching audio to playback, parsing text transcripts, handling server tool calls).
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

### What Can You Ask? (Example Prompts)

With the tools implemented above, the driver can naturally ask the copilot questions such as:
*   **"What am I hauling right now and when is my next stop?"** *(Triggers `getLoadStatus`)*
*   **"Where is the nearest Swift terminal with a shower and a maintenance shop?"** *(Triggers `findNearestSwiftTerminal`)*
*   **"How much drive time do I have left on my clock today?"** *(Triggers `getHoursOfServiceClocks`)*
*   **"How does my safety score look? Did that hard brake yesterday affect my bonus?"** *(Triggers `checkSafetyScore`)*
*   **"Where should I get fuel next on this route?"** *(Triggers `getFuelNetworkRouting`)*
*   **"What's the weather and traffic looking like for the next hour?"** *(Triggers `getTrafficAndWeather`)*
*   **"Read my unread messages from dispatch."** *(Triggers `getDispatchInbox`)*
*   **"What is the company policy on having a dog in the truck?"** *(Triggers `getCompanyFAQs`)*
*   **"How do I reach my driver leader or payroll?"** *(Triggers `getContacts`)*
*   **"What's my next load after this one?"** *(Triggers `getNextLoadDetails`)*

## Setup Instructions

1. Download your Vertex AI service account JSON key file.
2. Rename the file to `[project_name].json` and place it in the `app/src/main/assets/` directory. (Note: `*.json` files in this directory are ignored by Git to prevent accidental uploads).
3. Sync Gradle and build the project.
