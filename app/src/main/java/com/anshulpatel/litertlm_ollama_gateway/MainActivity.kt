package com.anshulpatel.litertlm_ollama_gateway

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anshulpatel.litertlm_ollama_gateway.inference.LiteRTLMManager
import com.anshulpatel.litertlm_ollama_gateway.logging.LogLevel
import com.anshulpatel.litertlm_ollama_gateway.service.KtorServerService
import com.anshulpatel.litertlm_ollama_gateway.ui.theme.LiteRTLOllamaGatewayTheme
import java.io.File
import java.io.FileOutputStream
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
        var activeBackend by remember { mutableStateOf(LiteRTLMManager.activeBackend) }
        val deviceIp = remember { getLocalIpAddress() ?: "Unknown" }
        var modelPath by remember { mutableStateOf(getSavedModelPath() ?: "No model selected") }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let {
                val path = copyFileToInternalStorage(it, "selected_model.litertlm")
                if (path != null) {
                    saveModelPath(path)
                    modelPath = path
                }
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                isServerRunning = KtorServerService.isRunning
                activeBackend = LiteRTLMManager.activeBackend
                kotlinx.coroutines.delay(1.seconds)
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Ollama Gateway", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Server IP: $deviceIp:${KtorServerService.PORT}")
            Text(text = "Status: ${if (isServerRunning) "Running" else "Stopped"}")
            if (isServerRunning) {
                Text(
                    text = "Inference Backend: ${activeBackend.name}",
                    color = if (activeBackend == LiteRTLMManager.BackendType.GPU) 
                        MaterialTheme.colorScheme.primary 
                    else if (activeBackend == LiteRTLMManager.BackendType.CPU) 
                        MaterialTheme.colorScheme.secondary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Model Settings:", style = MaterialTheme.typography.titleMedium)
            Text(text = "Path: $modelPath", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select .litertlm Model")
            }

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

    private fun getSavedModelPath(): String? {
        return getSharedPreferences("gateway_prefs", MODE_PRIVATE).getString("model_path", null)
    }

    private fun saveModelPath(path: String) {
        getSharedPreferences("gateway_prefs", MODE_PRIVATE).edit().putString("model_path", path).apply()
    }

    private fun copyFileToInternalStorage(uri: android.net.Uri, fileName: String): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val file = File(filesDir, fileName)
            inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
