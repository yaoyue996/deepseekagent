package com.deepseekv2.agent.data.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

const val DEEPSEEK_OFFICIAL_URL = "https://api.deepseek.com"

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val supportsVision: Boolean = false
)

object ModelCatalog {
    val BUILTIN_MODELS = listOf(
        ModelInfo(
            id = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro",
            description = "旗舰模型 · 最强推理与智能体能力",
            supportsVision = true
        ),
        ModelInfo(
            id = "deepseek-v4-flash",
            displayName = "DeepSeek V4 Flash",
            description = "极速响应 · 高性价比",
            supportsVision = false
        ),
        ModelInfo(
            id = "deepseek-v4-flash-vision-exp",
            displayName = "DeepSeek V4 Flash Vision (Exp)",
            description = "多模态视觉理解实验版",
            supportsVision = true
        )
    )

    fun find(modelId: String): ModelInfo? = BUILTIN_MODELS.firstOrNull { it.id == modelId }

    fun displayName(modelId: String): String = find(modelId)?.displayName ?: modelId

    fun supportsVision(modelId: String): Boolean =
        find(modelId)?.supportsVision ?: modelId.contains("vision", ignoreCase = true)

    fun custom(id: String): ModelInfo = ModelInfo(id, id, "自定义模型")
}

enum class Role(val value: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");

    companion object {
        fun from(v: String) = entries.firstOrNull { it.value == v } ?: ASSISTANT
    }
}

// ---------- API wire format (OpenAI / DeepSeek compatible) ----------

data class ApiMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any?,
    @SerializedName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    @SerializedName("name") val name: String? = null
) {
    companion object {
        fun text(role: Role, content: String?) = ApiMessage(role.value, content)

        /** 多模态：文本 + 图片（base64 data URI） */
        fun vision(textContent: String, imageDataUris: List<String>): ApiMessage {
            val parts = ArrayList<Map<String, Any?>>()
            if (textContent.isNotBlank()) {
                parts.add(mapOf("type" to "text", "text" to textContent))
            }
            for (uri in imageDataUris) {
                parts.add(mapOf("type" to "image_url", "image_url" to mapOf("url" to uri)))
            }
            return ApiMessage(Role.USER.value, parts)
        }

        fun toolResult(callId: String, toolName: String, output: String) =
            ApiMessage(Role.TOOL.value, output, toolCallId = callId, name = toolName)
    }
}

data class FunctionCall(
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String
)

data class ApiToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionCall
)

data class FunctionSpec(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: JsonObject
)

data class ToolSpec(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionSpec
)

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ApiMessage>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("temperature") val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("tools") val tools: List<ToolSpec>? = null
)
