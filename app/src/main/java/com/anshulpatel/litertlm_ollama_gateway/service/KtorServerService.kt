package com.anshulpatel.litertlm_ollama_gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anshulpatel.litertlm_ollama_gateway.domain.model.*
import com.anshulpatel.litertlm_ollama_gateway.inference.LiteRTLMManager
import com.anshulpatel.litertlm_ollama_gateway.logging.LogLevel
import com.anshulpatel.litertlm_ollama_gateway.logging.LokiLogger
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.event.Level
import java.io.File
import java.util.*

class KtorServerService : Service() {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "ktor_server_channel"
        const val NOTIFICATION_ID = 1
        const val PORT = 11434
        
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lokiUrl = intent?.getStringExtra("LOKI_URL")
        val logLevel = intent?.getStringExtra("LOG_LEVEL") ?: "INFO"
        
        LokiLogger.configure(lokiUrl, LogLevel.valueOf(logLevel))
        LokiLogger.log(LogLevel.INFO, "KtorServerService", "Starting server service...")

        val savedModelPath = getSharedPreferences("gateway_prefs", MODE_PRIVATE).getString("model_path", null)
        val maxTokens = getSharedPreferences("gateway_prefs", MODE_PRIVATE).getString("max_tokens", "2048")?.toIntOrNull() ?: 2048
        
        if (savedModelPath != null) {
            LokiLogger.log(LogLevel.INFO, "KtorServerService", "Initializing LiteRT-LM with: $savedModelPath, context: $maxTokens")
            CoroutineScope(Dispatchers.IO).launch {
                LiteRTLMManager.initialize(savedModelPath, maxTokens)
            }
        } else {
            LokiLogger.log(LogLevel.WARN, "KtorServerService", "No model path found. Inference will fail.")
        }

        startForegroundService()
        acquireWakeLock()
        startKtorServer()
        
        isRunning = true
        return START_STICKY
    }

    private fun extractInferenceOptions(options: Map<String, JsonElement>?): LiteRTLMManager.InferenceOptions {
        val prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE)
        val defaultThinking = prefs.getBoolean("enable_thinking", false)
        val defaultTemp = prefs.getString("default_temp", "0.7")?.toDoubleOrNull() ?: 0.7

        if (options == null) return LiteRTLMManager.InferenceOptions(
            temperature = defaultTemp,
            thinking = defaultThinking
        )

        return LiteRTLMManager.InferenceOptions(
            temperature = (options["temperature"] as? JsonPrimitive)?.doubleOrNull ?: defaultTemp,
            topK = (options["top_k"] as? JsonPrimitive)?.intOrNull,
            topP = (options["top_p"] as? JsonPrimitive)?.doubleOrNull,
            maxTokens = (options["num_predict"] as? JsonPrimitive)?.intOrNull,
            thinking = (options["thinking"] as? JsonPrimitive)?.booleanOrNull ?: defaultThinking
        )
    }

    private fun estimateTokens(text: String): Int {
        // Simple heuristic: 1 token ~= 4 chars for English
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun startForegroundService() {
        val notification = createNotification("Ollama Gateway is running on port $PORT")
        
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OllamaGateway::WakeLock").apply {
            acquire()
        }
    }

    private fun startKtorServer() {
        if (server != null) return

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                })
            }
            install(CallId) {
                generate { UUID.randomUUID().toString() }
                replyToHeader("X-Trace-Id")
            }
            install(CallLogging) {
                level = Level.INFO
                callIdMdc("traceId")
                logger = org.slf4j.LoggerFactory.getLogger("KtorServer")
            }
            
            // Manual metrics implementation to avoid JMX crash on Android
            
            intercept(ApplicationCallPipeline.Monitoring) {
                try {
                    proceed()
                } finally {
                    val method = call.request.httpMethod.value
                    val path = call.request.path()
                    val status = call.response.status()?.value?.toString() ?: "500"
                    prometheusRegistry.counter("http_requests_per_route", 
                        "method", method, 
                        "path", path, 
                        "status", status
                    ).increment()
                }
            }

            routing {
                get("/health") {
                    call.respond(mapOf("status" to "up"))
                }

                get("/metrics") {
                    call.respondText(prometheusRegistry.scrape())
                }

                route("/api") {
                    post("/generate") {
                        val request = call.receive<GenerateRequest>()
                        val traceId = call.callId
                        
                        val options = extractInferenceOptions(request.options)
                        
                        // Log Request with detailed parameters
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", 
                            "Generate Request: model=${request.model}, prompt=\"${request.prompt}\", " +
                            "options=[temp=${options.temperature}, topK=${options.topK}, topP=${options.topP}, " +
                            "maxTokens=${options.maxTokens}, thinking=${options.thinking}]", 
                            traceId)
                        
                        val startTime = System.currentTimeMillis()
                        val generatedText = LiteRTLMManager.generateResponse(request.prompt, options)
                        val duration = System.currentTimeMillis() - startTime
                        
                        val promptTokens = estimateTokens(request.prompt)
                        val responseTokens = estimateTokens(generatedText)
                        val totalTokens = promptTokens + responseTokens
                        
                        // Log Response with token usage
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", 
                            "Generate Response: model=${request.model}, response=\"$generatedText\", " +
                            "prompt_tokens=$promptTokens, response_tokens=$responseTokens, total_tokens=$totalTokens, " +
                            "duration_ms=$duration", 
                            traceId)
                        
                        val response = GenerateResponse(
                            model = request.model,
                            createdAt = java.time.Instant.now().toString(),
                            response = generatedText,
                            done = true,
                            totalDuration = duration * 1_000_000,
                            promptEvalCount = promptTokens,
                            evalCount = responseTokens
                        )
                        call.respond(response)
                    }

                    get("/tags") {
                        val traceId = call.callId
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", "List models request", traceId)
                        
                        val prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE)
                        val modelPath = prefs.getString("model_path", null)
                        
                        val models = if (modelPath != null) {
                            val file = File(modelPath)
                            if (file.exists()) {
                                listOf(
                                    ModelInfo(
                                        name = file.name,
                                        model = file.name,
                                        modifiedAt = java.time.Instant.ofEpochMilli(file.lastModified()).toString(),
                                        size = file.length(),
                                        digest = "sha256:" + file.name.hashCode().toString(),
                                        details = ModelDetails(
                                            format = "litertlm",
                                            family = "gemma",
                                            parameterSize = "2B",
                                            quantizationLevel = "INT4"
                                        )
                                    )
                                )
                            } else emptyList()
                        } else emptyList()
                        
                        call.respond(ModelsResponse(models = models))
                    }

                    post("/show") {
                        val request = call.receive<ShowRequest>()
                        val traceId = call.callId
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", "Show model details for: ${request.model}", traceId)
                        
                        val prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE)
                        val modelPath = prefs.getString("model_path", null)
                        val fileName = if (modelPath != null) File(modelPath).name else request.model

                        val response = ShowResponse(
                            modelfile = "# LiteRT-LM Modelfile\nFROM $fileName",
                            details = ModelDetails(
                                format = "litertlm",
                                family = "gemma",
                                parameterSize = "2B",
                                quantizationLevel = "INT4"
                            )
                        )
                        call.respond(response)
                    }

                    post("/chat") {
                        val request = call.receive<ChatRequest>()
                        val traceId = call.callId

                        val options = extractInferenceOptions(request.options)
                        val lastMessage = request.messages.lastOrNull()?.content ?: ""
                        
                        // Log Chat Request
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", 
                            "Chat Request: model=${request.model}, last_message=\"$lastMessage\", " +
                            "messages_count=${request.messages.size}, " +
                            "options=[temp=${options.temperature}, topK=${options.topK}, topP=${options.topP}, " +
                            "maxTokens=${options.maxTokens}, thinking=${options.thinking}]", 
                            traceId)

                        val startTime = System.currentTimeMillis()
                        val generatedText = LiteRTLMManager.generateResponse(lastMessage, options)
                        val duration = System.currentTimeMillis() - startTime

                        val promptTokens = estimateTokens(lastMessage)
                        val responseTokens = estimateTokens(generatedText)
                        val totalTokens = promptTokens + responseTokens

                        // Log Chat Response
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", 
                            "Chat Response: model=${request.model}, message=\"$generatedText\", " +
                            "prompt_tokens=$promptTokens, response_tokens=$responseTokens, total_tokens=$totalTokens, " +
                            "duration_ms=$duration", 
                            traceId)

                        val response = ChatResponse(
                            model = request.model,
                            createdAt = java.time.Instant.now().toString(),
                            message = ChatMessage(
                                role = "assistant",
                                content = generatedText
                            ),
                            done = true,
                            totalDuration = duration * 1_000_000,
                            promptEvalCount = promptTokens,
                            evalCount = responseTokens
                        )
                        call.respond(response)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ollama Gateway")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ollama Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        LokiLogger.log(LogLevel.INFO, "KtorServerService", "Stopping server service...")
        LiteRTLMManager.close()
        server?.stop(1000, 5000)
        wakeLock?.release()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
