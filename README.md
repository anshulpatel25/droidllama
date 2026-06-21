# LiteRT-LM Ollama Gateway for Android

A high-performance, long-running REST API server for Android that implements the Ollama API specification. This gateway allows Android devices to act as local LLM providers on a network.

## Features

- **Ollama API Compatibility**: Implements `/api/generate`, `/api/tags`, and `/api/show` endpoints.
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

## License
MIT
