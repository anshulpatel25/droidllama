package com.anshulpatel.litertlm_ollama_gateway.inference

import com.anshulpatel.litertlm_ollama_gateway.logging.LogLevel
import com.anshulpatel.litertlm_ollama_gateway.logging.LokiLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

object LiteRTLMManager {
    private var engine: Engine? = null
    private var currentModelPath: String? = null

    private const val TAG = "LiteRTLMManager"

    enum class BackendType {
        GPU, CPU, NONE
    }

    var activeBackend: BackendType = BackendType.NONE
        private set

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) return@withContext

        engine?.close()
        
        try {
            // Try GPU first
            LokiLogger.log(LogLevel.INFO, TAG, "Initializing LiteRT-LM engine with GPU backend...")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU()
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath
            activeBackend = BackendType.GPU
            LokiLogger.log(LogLevel.INFO, TAG, "LiteRT-LM engine initialized with GPU.")
        } catch (e: Exception) {
            LokiLogger.log(LogLevel.WARN, TAG, "Failed to initialize GPU backend: ${e.localizedMessage}. Falling back to CPU...")
            e.printStackTrace()
            // Fallback to CPU if GPU initialization fails
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
                currentModelPath = modelPath
                activeBackend = BackendType.CPU
                LokiLogger.log(LogLevel.INFO, TAG, "LiteRT-LM engine initialized with CPU fallback.")
            } catch (e2: Exception) {
                LokiLogger.log(LogLevel.ERROR, TAG, "Failed to initialize CPU backend: ${e2.localizedMessage}")
                e2.printStackTrace()
                engine = null
                currentModelPath = null
                activeBackend = BackendType.NONE
            }
        }
    }

    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: return@withContext "Engine not initialized"
        
        // Gemma prompt template
        val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        
        return@withContext try {
            currentEngine.createConversation().use { conversation ->
                val response = conversation.sendMessage(formattedPrompt)
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
        } catch (e: Exception) {
            "Error: ${e.localizedMessage}"
        }
    }

    suspend fun generateResponseAsync(prompt: String, onToken: (String, Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: run {
            onToken("Engine not initialized", true)
            return@withContext
        }

        val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        
        try {
            currentEngine.createConversation().use { conversation ->
                conversation.sendMessageAsync(formattedPrompt).collect { message ->
                    val token = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    onToken(token, false)
                }
                onToken("", true)
            }
        } catch (e: Exception) {
            onToken("Error: ${e.localizedMessage}", true)
        }
    }

    fun close() {
        engine?.close()
        engine = null
        currentModelPath = null
        activeBackend = BackendType.NONE
    }
}
