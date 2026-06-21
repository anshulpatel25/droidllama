package com.anshulpatel.litertlm_ollama_gateway

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anshulpatel.litertlm_ollama_gateway.logging.LogLevel
import com.anshulpatel.litertlm_ollama_gateway.service.KtorServerService
import com.anshulpatel.litertlm_ollama_gateway.ui.theme.LiteRTLOllamaGatewayTheme
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiteRTLOllamaGatewayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        var lokiUrl by remember { mutableStateOf("") }
        var selectedLogLevel by remember { mutableStateOf(LogLevel.INFO) }
        var isServerRunning by remember { mutableStateOf(KtorServerService.isRunning) }
        val deviceIp = remember { getLocalIpAddress() ?: "Unknown" }

        LaunchedEffect(Unit) {
            while (true) {
                isServerRunning = KtorServerService.isRunning
                kotlinx.coroutines.delay(1.seconds)
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Ollama Gateway", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Server IP: $deviceIp:${KtorServerService.PORT}")
            Text(text = "Status: ${if (isServerRunning) "Running" else "Stopped"}")
            
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = lokiUrl,
                onValueChange = { lokiUrl = it },
                label = { Text("Loki URL") },
                placeholder = { Text("http://192.168.x.x:3100") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "Log Level:")
            Row {
                LogLevel.entries.forEach { level ->
                    Row(modifier = Modifier.padding(end = 8.dp)) {
                        RadioButton(
                            selected = selectedLogLevel == level,
                            onClick = { selectedLogLevel = level }
                        )
                        Text(text = level.name, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (isServerRunning) {
                        stopServer()
                    } else {
                        startServer(lokiUrl, selectedLogLevel)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isServerRunning) "Stop Server" else "Start Server")
            }
        }
    }

    private fun startServer(lokiUrl: String, level: LogLevel) {
        val intent = Intent(this, KtorServerService::class.java).apply {
            putExtra("LOKI_URL", lokiUrl)
            putExtra("LOG_LEVEL", level.name)
        }
        startForegroundService(intent)
    }

    private fun stopServer() {
        val intent = Intent(this, KtorServerService::class.java)
        stopService(intent)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
