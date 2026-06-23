# DroidLlama for Android

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

## Known Shortcomings

This project has specific limitations regarding tool calling and multi-turn conversations when used through proxy layers like n8n or standard OpenAI-compatible clients.

### 1. Externally Passed Tools (Function Calling)

There are specific limitations regarding the handling of externally passed tool definitions when proxied through certain environments.

*   **The Intermediary Protocol Drop**: If you are connecting n8n to LiteRT-LM via the Ollama Chat Model node or the OpenAI Chat Model node, n8n expects the underlying model runtime to natively accept a top-level `tools` JSON array. Ollama may strip out these parameters because it does not recognize the custom LiteRT-LM backend as a native tool-calling engine.
*   **Native Format Mismatches**: Standard OpenAI-compatible JSON schemas differ from LiteRT-LM's requirement for platform-level compilation (e.g., Kotlin `@Tool` annotations) baked into the `.litertlm` execution graph.
*   **Execution Model**: LiteRT-LM is an on-device framework. Tools must reside on the native system hosting the model binary, and the execution loop expects to intercept function calls at the hardware/binary boundary.

### 2. Multi-turn Conversations and Chat History

Currently, this gateway **does not support persistent chat history or multi-turn conversations**. Each request is treated as a fresh, isolated inference call. This is due to several architectural factors:

*   **Stateless Inference**: LiteRT-LM is designed for on-device app development where a persistent `Conversation` object is maintained in local memory. Our current Ktor/Ollama proxy layer initializes a brand-new LiteRT-LM inference session for every HTTP POST request, wiping out any context from previous turns.
*   **Context Window Constraints**: LiteRT-LM models (like Gemma 2B) are optimized for mobile and have strict, smaller context window ceilings. Passing a full history array may easily overflow these limits, causing the model to throw memory errors or drop tokens.
*   **Chat Template Formatting**: LiteRT-LM requires strict formatting tags (e.g., `<start_of_turn>user`, `<start_of_turn>model`) to distinguish between roles. If a proxy passes an array of messages without explicit formatting into the specific special tokens expected by the `.litertlm` file, the model will fail to understand the conversation structure.

## License
MIT
