package com.anshulpatel.litertlm_ollama_gateway

import com.anshulpatel.litertlm_ollama_gateway.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testGenerateResponseSerialization() {
        val response = GenerateResponse(
            model = "test-model",
            createdAt = "2024-01-01",
            response = "hello",
            done = true
        )
        val serialized = json.encodeToString(response)
        assertTrue(serialized.contains("\"model\":\"test-model\""))
        assertTrue(serialized.contains("\"created_at\":\"2024-01-01\""))
    }

    @Test
    fun testModelsResponseSerialization() {
        val details = ModelDetails(
            format = "gguf",
            family = "llama",
            parameterSize = "7B",
            quantizationLevel = "Q4_0"
        )
        val modelInfo = ModelInfo(
            name = "llama3",
            model = "llama3",
            modifiedAt = "2024-01-01",
            size = 1000,
            digest = "abc",
            details = details
        )
        val response = ModelsResponse(models = listOf(modelInfo))
        val serialized = json.encodeToString(response)
        assertTrue(serialized.contains("\"name\":\"llama3\""))
        assertTrue(serialized.contains("\"details\":{"))
    }

    @Test
    fun testChatResponseSerialization() {
        val response = ChatResponse(
            model = "llama3",
            createdAt = "2024-05-01T12:00:00Z",
            message = ChatMessage(
                role = "assistant",
                content = "hello"
            ),
            done = true
        )
        val serialized = json.encodeToString(response)
        assertTrue(serialized.contains("\"role\":\"assistant\""))
        assertTrue(serialized.contains("\"content\":\"hello\""))
    }
}
