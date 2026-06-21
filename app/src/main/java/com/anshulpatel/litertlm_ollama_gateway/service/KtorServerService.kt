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
import org.slf4j.event.Level
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

        startForegroundService()
        acquireWakeLock()
        startKtorServer()
        
        isRunning = true
        return START_STICKY
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
                json()
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
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", "Generate request for model: ${request.model}", traceId)
                        
                        val response = GenerateResponse(
                            model = request.model,
                            createdAt = "2024-05-01T12:00:00.000000Z",
                            response = "This is a dummy response from Ollama Gateway on Android.",
                            done = true
                        )
                        call.respond(response)
                    }

                    get("/tags") {
                        val traceId = call.callId
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", "List models request", traceId)
                        
                        val response = ModelsResponse(
                            models = listOf(
                                ModelInfo(
                                    name = "llama3:latest",
                                    model = "llama3:latest",
                                    modifiedAt = "2024-04-20T10:30:00Z",
                                    size = 4700000000,
                                    digest = "c6eb396dbd5992bbe3f5cdb947e8bbc0ee413d7c17e2beaae69f5d569cf982eb",
                                    details = ModelDetails(
                                        format = "gguf",
                                        family = "llama",
                                        parameter_size = "8B",
                                        quantization_level = "Q4_0"
                                    )
                                )
                            )
                        )
                        call.respond(response)
                    }

                    post("/show") {
                        val request = call.receive<ShowRequest>()
                        val traceId = call.callId
                        LokiLogger.log(LogLevel.INFO, "OllamaAPI", "Show model details for: ${request.model}", traceId)
                        
                        val response = ShowResponse(
                            modelfile = "# Dummy Modelfile\nFROM ${request.model}",
                            details = ModelDetails(
                                format = "gguf",
                                family = "llama",
                                parameter_size = "8B",
                                quantization_level = "Q4_0"
                            )
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
        server?.stop(1000, 5000)
        wakeLock?.release()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
