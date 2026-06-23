# LiteRT-LM Ollama Gateway for Android

A high-performance, long-running REST API server for Android that implements the Ollama API specification. This gateway allows Android devices to act as local LLM providers on a network.

## Features

- **Ollama API Compatibility**: Implements `/api/generate`, `/api/chat`, `/api/tags`, and `/api/show` endpoints.
- **Persistent Server**: Runs as an Android Foreground Service (`specialUse`) to ensure 24/7 availability even with the screen off.
- **Network Discovery**: Automatically binds to all local network interfaces (0.0.0.0) for easy access via device IP.
- **Observability**:
    - **Prometheus Metrics**: Real-time server performance data available at `/metrics`.
    - **Health Check**: Simple status monitoring at `/health`.
    - **Loki Logging**: Centralized logging support with automatic batching and Trace ID propagation.
- **Material 3 UI**: Modern Jetpack Compose interface with support for Dark Mode and Dynamic Color (Material You).

## Tech Stack

- **Language**: Idiomatic Kotlin
- **Server**: Ktor (CIO Engine)
- **UI**: Jetpack Compose (Material 3)
- **Metrics**: Micrometer Prometheus
- **Serialization**: Kotlinx Serialization
- **Logging**: Custom Coroutine-based Loki Logger

## Getting Started

1. **Build and Install**: Clone the repo and install the APK on your Android device.
2. **Configure Loki**: (Optional) Enter your Loki server URL in the UI settings.
3. **Start Server**: Click the "Start Server" button. The server will run on port `11434`.
4. **Access API**: From any device on the same network, use your preferred Ollama client or `curl`:
   ```bash
   curl http://<device-ip>:11434/api/tags
   ```

## Known Shortcomings: Externally Passed Tools

While this project provides a high-performance bridge for LiteRT models, there are specific limitations regarding the handling of externally passed tool definitions (function calling) when proxied through certain environments like n8n or OpenAI-compatible clients.

### 1. The Intermediary Protocol Drop
If you are connecting n8n to LiteRT-LM via the Ollama Chat Model node or the OpenAI Chat Model node (pointed to the gateway's port), n8n expects the underlying model runtime to natively accept a top-level `tools` JSON array.

While Ollama natively supports tool parameters for its own packaged models (like Llama 3.1 or Qwen 2.5), it acts purely as a generic text/token bypass when you proxy an external backend like LiteRT-LM through it. Consequently, Ollama may strip out the `tools` parameters because it does not recognize the custom LiteRT-LM backend as a native tool-calling engine, resulting in only raw text being transmitted.

### 2. Native Format Mismatches
*   **n8n/Standard Format:** Sends standard OpenAI-compatible JSON schemas (`{"type": "function", "function": {...}}`).
*   **LiteRT-LM Format:** Requires specific platform-level compilation (e.g., Python `@register_tool` or Kotlin `@Tool` annotations) baked into its specific `.litertlm` execution graph.

LiteRT-LM does not dynamically parse standard raw OpenAI tools JSON arrays out-of-the-box unless wrapped in a dedicated API server logic that specifically maps those parameters back to the graph's internal expectations.

### 3. Client-Side vs. Server-Side Execution Model
LiteRT-LM is an on-device client framework rather than a typical cloud-style API.
*   **Cloud Models:** The server reads the tools, asks the model to output JSON, and sends that JSON back to the client to execute.
*   **LiteRT-LM:** The tools must reside on the native system hosting the LiteRT model binary. The LiteRT execution loop expects to intercept the function call internally at the hardware/binary boundary, rather than receiving abstract instructions via a networked OpenAI-compatible proxy layer.

## License
MIT
