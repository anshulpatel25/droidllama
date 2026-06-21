package com.anshulpatel.litertlm_ollama_gateway.domain.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class GenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = true,
    val format: String? = null,
    val options: Map<String, String>? = null,
    val system: String? = null,
    val template: String? = null,
    val context: List<Int>? = null,
    val raw: Boolean? = null,
    @SerialName("keep_alive") val keepAlive: String? = null
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class GenerateResponse(
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val response: String,
    val done: Boolean,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("load_duration") val loadDuration: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    val context: List<Int>? = null
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ModelsResponse(
    val models: List<ModelInfo>
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ModelInfo(
    val name: String,
    val model: String,
    @SerialName("modified_at") val modifiedAt: String,
    val size: Long,
    val digest: String,
    val details: ModelDetails
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ModelDetails(
    @SerialName("parent_model") val parentModel: String = "",
    val format: String,
    val family: String,
    val families: List<String>? = null,
    @SerialName("parameter_size") val parameterSize: String,
    @SerialName("quantization_level") val quantizationLevel: String
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ShowRequest(
    val model: String,
    val verbose: Boolean = false
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ShowResponse(
    val modelfile: String,
    val parameters: String? = null,
    val template: String? = null,
    val details: ModelDetails
)
