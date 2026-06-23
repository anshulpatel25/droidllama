package com.anshulpatel.droidllama.inference

import com.anshulpatel.droidllama.logging.LogLevel
import com.anshulpatel.droidllama.logging.LokiLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

object DroidLlamaManager {
    private var engine: Engine? = null
    private var currentModelPath: String? = null

    private const val TAG = "DroidLlamaManager"

    data class InferenceOptions(
        val temperature: Double? = null,
        val topK: Int? = null,
        val topP: Double? = null,
        val maxTokens: Int? = null,
        val thinking: Boolean = false
    )

    enum class BackendType {
        GPU, CPU, NONE, INITIALIZING, ERROR
    }

    private val _activeBackend = MutableStateFlow(BackendType.NONE)
    val activeBackend = _activeBackend.asStateFlow()

    suspend fun initialize(modelPath: String, maxTokens: Int? = 2048) = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) return@withContext

        _activeBackend.value = BackendType.INITIALIZING
        engine?.close()
        
        try {
            // Try GPU first
            LokiLogger.log(LogLevel.INFO, TAG, "Initializing LiteRT-LM engine with GPU backend...")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = maxTokens
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath
            _activeBackend.value = BackendType.GPU
            LokiLogger.log(LogLevel.INFO, TAG, "LiteRT-LM engine initialized with GPU.")
        } catch (e: Exception) {
            LokiLogger.log(LogLevel.WARN, TAG, "Failed to initialize GPU backend: ${e.localizedMessage}. Falling back to CPU...")
            e.printStackTrace()
            // Fallback to CPU if GPU initialization fails
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxNumTokens = maxTokens
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
                currentModelPath = modelPath
                _activeBackend.value = BackendType.CPU
                LokiLogger.log(LogLevel.INFO, TAG, "LiteRT-LM engine initialized with CPU fallback.")
            } catch (e2: Exception) {
                LokiLogger.log(LogLevel.ERROR, TAG, "CRITICAL: Failed to initialize any LiteRT-LM backend: ${e2.localizedMessage}")
                e2.printStackTrace()
                engine = null
                currentModelPath = null
                _activeBackend.value = BackendType.ERROR
            }
        }
    }

    suspend fun generateResponse(prompt: String, options: InferenceOptions = InferenceOptions()): String = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: return@withContext "Engine not initialized"
        
        // Gemma prompt template
        val formattedPrompt = if (options.thinking) {
            "<start_of_turn>user\nThink carefully and then answer: $prompt<end_of_turn>\n<start_of_turn>model\n<thought>\n"
        } else {
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        }
        
        val samplerConfig = if (options.temperature != null || options.topK != null || options.topP != null) {
            SamplerConfig(
                topK = options.topK ?: 40,
                topP = options.topP ?: 0.9,
                temperature = options.temperature ?: 0.7
            )
        } else null

        val convConfig = ConversationConfig(samplerConfig = samplerConfig)

        return@withContext try {
            currentEngine.createConversation(convConfig).use { conversation ->
                val response = conversation.sendMessage(formattedPrompt)
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
        } catch (e: Exception) {
            "Error: ${e.localizedMessage}"
        }
    }

    suspend fun generateResponseAsync(prompt: String, options: InferenceOptions = InferenceOptions(), onToken: (String, Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: run {
            onToken("Engine not initialized", true)
            return@withContext
        }

        val formattedPrompt = if (options.thinking) {
            "<start_of_turn>user\nThink carefully and then answer: $prompt<end_of_turn>\n<start_of_turn>model\n<thought>\n"
        } else {
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        }
        
        val samplerConfig = if (options.temperature != null || options.topK != null || options.topP != null) {
            SamplerConfig(
                topK = options.topK ?: 40,
                topP = options.topP ?: 0.9,
                temperature = options.temperature ?: 0.7
            )
        } else null

        val convConfig = ConversationConfig(samplerConfig = samplerConfig)
        
        try {
            currentEngine.createConversation(convConfig).use { conversation ->
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
        _activeBackend.value = BackendType.NONE
    }
}
