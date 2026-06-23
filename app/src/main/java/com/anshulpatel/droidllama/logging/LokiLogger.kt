package com.anshulpatel.droidllama.logging

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

object LokiLogger {
    private var lokiUrl: String? = null
    private var currentLogLevel = LogLevel.INFO
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    fun configure(url: String?, level: LogLevel) {
        lokiUrl = if (url.isNullOrBlank()) null else url
        currentLogLevel = level
    }

    fun log(level: LogLevel, tag: String, message: String, traceId: String? = null) {
        if (level.ordinal < currentLogLevel.ordinal) return

        val timestamp = System.currentTimeMillis()
        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val logLine = "[$formattedDate] [$level] [TraceID: ${traceId ?: "N/A"}] $tag: $message"
        
        // Always log to logcat
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, logLine)
            LogLevel.INFO -> Log.i(tag, logLine)
            LogLevel.WARN -> Log.w(tag, logLine)
            LogLevel.ERROR -> Log.e(tag, logLine)
        }

        if (lokiUrl != null) {
            logQueue.add(LogEntry(timestamp, level, tag, message, traceId))
        }
    }

    init {
        scope.launch {
            while (isActive) {
                if (lokiUrl != null && logQueue.isNotEmpty()) {
                    pushToLoki()
                    // If queue is still large, push again sooner
                    if (logQueue.size > 100) {
                        delay(200.milliseconds)
                        continue
                    }
                }
                delay(2000.milliseconds) // Batch push every 2 seconds normally
            }
        }
    }

    private suspend fun pushToLoki() {
        val urlStr = lokiUrl ?: return
        val entriesToPush = mutableListOf<LogEntry>()
        
        // Peek and poll up to 50 entries
        while (logQueue.isNotEmpty() && entriesToPush.size < 50) {
            logQueue.poll()?.let { entriesToPush.add(it) }
        }

        if (entriesToPush.isEmpty()) return

        try {
            // Group by level and tag for better Loki labels
            val streams = entriesToPush.groupBy { it.level to it.tag }.map { (key, entries) ->
                val (level, tag) = key
                LokiStream(
                    stream = mapOf(
                        "app" to "ollama-gateway",
                        "level" to level.name,
                        "tag" to tag,
                        "device" to android.os.Build.MODEL
                    ),
                    values = entries.map { entry ->
                        listOf((entry.timestamp * 1_000_000).toString(), entry.format())
                    }
                )
            }

            val payload = json.encodeToString(LokiPushRequest(streams))
            
            // Ensure URL is correctly formatted
            val baseUrl = if (!urlStr.startsWith("http")) "http://$urlStr" else urlStr
            val fullUrl = try {
                if (baseUrl.contains("/loki/api/v1/push")) {
                    URL(baseUrl)
                } else {
                    URL(baseUrl.replace(Regex("/$"), "") + "/loki/api/v1/push")
                }
            } catch (_: Exception) {
                Log.e("LokiLogger", "Invalid Loki URL: $baseUrl. Disabling Loki logging.")
                lokiUrl = null
                return
            }
            
            withContext(Dispatchers.IO) {
                val conn = fullUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                
                conn.outputStream.use { it.write(payload.toByteArray()) }
                
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    Log.e("LokiLogger", "Failed to push to Loki: $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.e("LokiLogger", "Error pushing to Loki: ${e.message}")
            // Re-add to queue if it's a connection error, to try again later
            // But limit to avoid memory bloat
            if (logQueue.size < 1000) {
                logQueue.addAll(entriesToPush)
            }
        }
    }
}

private data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val traceId: String?
) {
    fun format() = "[$level] [TraceID: ${traceId ?: "N/A"}] $tag: $message"
}

@OptIn(InternalSerializationApi::class)
@Serializable
private data class LokiPushRequest(val streams: List<LokiStream>)

@OptIn(InternalSerializationApi::class)
@Serializable
private data class LokiStream(val stream: Map<String, String>, val values: List<List<String>>)
