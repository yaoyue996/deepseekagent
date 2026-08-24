package com.deepseekv2.agent.data.api

import com.deepseekv2.agent.data.model.ApiToolCall
import com.deepseekv2.agent.data.model.ChatRequest
import com.deepseekv2.agent.data.model.DEEPSEEK_OFFICIAL_URL
import com.deepseekv2.agent.data.prefs.ProviderProfile
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 流式事件 */
sealed interface StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallFragment(
        val index: Int,
        val callId: String?,
        val toolName: String?,
        val argsDelta: String
    ) : StreamEvent
}

class DeepSeekClient {

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + path

    /** 流式对话（SSE） */
    fun streamChat(provider: ProviderProfile, request: ChatRequest): Flow<StreamEvent> = flow {
        val body = Gson().toJson(request).toRequestBody(jsonMedia)
        val httpRequest = Request.Builder()
            .url(endpoint(provider.baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = try {
                    response.body?.string()?.take(600)
                } catch (_: Exception) {
                    ""
                }
                throw IOException("HTTP ${response.code}: $errBody")
            }
            val source = response.body?.source() ?: throw IOException("空响应")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                for (event in parseChunk(payload)) emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 测试服务商连通性，成功返回模型数量描述 */
    fun testConnection(profile: ProviderProfile): String {
        val url = endpoint(
            profile.baseUrl.ifBlank { DEEPSEEK_OFFICIAL_URL },
            "/models"
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${profile.apiKey}")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val text = try {
                response.body?.string()?.take(2000)
            } catch (_: Exception) {
                ""
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} $text")
            return try {
                val obj = JsonParser.parseString(text).asJsonObject
                val count = obj.getAsJsonArray("data")?.size() ?: 0
                "连接成功 · 服务端返回 $count 个模型"
            } catch (_: Exception) {
                "连接成功 (HTTP ${response.code})"
            }
        }
    }

    private fun parseChunk(payload: String): List<StreamEvent> {
        val events = ArrayList<StreamEvent>(2)
        val obj = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (_: Exception) {
            return events
        }
        val choices = obj.getAsJsonArray("choices") ?: return events
        if (choices.size() == 0) return events
        val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return events

        delta.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.let {
            val t = it.asString
            if (t.isNotEmpty()) events.add(StreamEvent.ReasoningDelta(t))
        }
        delta.get("content")?.takeIf { it.isJsonPrimitive }?.let {
            val t = it.asString
            if (t.isNotEmpty()) events.add(StreamEvent.ContentDelta(t))
        }
        delta.getAsJsonArray("tool_calls")?.forEach { element ->
            val tc = element.asJsonObject
            val fn = tc.getAsJsonObject("function")
            events.add(
                StreamEvent.ToolCallFragment(
                    index = tc.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    callId = tc.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
                    toolName = fn?.get("name")?.takeIf { it.isJsonPrimitive }?.asString,
                    argsDelta = fn?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                )
            )
        }
        return events
    }

    companion object {
        fun toApiToolCalls(accumulated: Collection<AccumulatedToolCall>): List<ApiToolCall> =
            accumulated.filter { it.name != null }.map {
                ApiToolCall(
                    id = it.callId ?: "call_${it.index}",
                    function = com.deepseekv2.agent.data.model.FunctionCall(
                        name = it.name!!,
                        arguments = it.arguments.ifBlank { "{}" }
                    )
                )
            }
    }
}

/** 流式工具调用累积器 */
data class AccumulatedToolCall(
    val index: Int,
    var callId: String? = null,
    var name: String? = null,
    var arguments: String = ""
)
