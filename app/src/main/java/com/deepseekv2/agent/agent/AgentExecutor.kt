package com.deepseekv2.agent.agent

import com.deepseekv2.agent.data.api.AccumulatedToolCall
import com.deepseekv2.agent.data.api.DeepSeekClient
import com.deepseekv2.agent.data.model.ApiMessage
import com.deepseekv2.agent.data.model.ChatRequest
import com.deepseekv2.agent.data.prefs.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** 智能体执行事件 */
sealed interface AgentEvent {
    data class StepStart(val step: Int) : AgentEvent
    data class ContentDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolCallsPreview(val calls: List<AccumulatedToolCall>) : AgentEvent
    data class ToolCallStarted(
        val callId: String,
        val toolName: String,
        val arguments: String
    ) : AgentEvent

    data class ToolCallFinished(
        val callId: String,
        val toolName: String,
        val output: String,
        val success: Boolean
    ) : AgentEvent

    /** 本轮结束（模型给出最终回复，无更多工具调用） */
    data class Completed(val assistantTurn: AssistantTurn) : AgentEvent
    data class Failed(val error: Throwable) : AgentEvent
}

/** 一次完整的助手回合 */
data class AssistantTurn(
    val content: String,
    val reasoning: String
)

data class RunConfig(
    val provider: ProviderProfile,
    val modelId: String,
    val systemPrompt: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val agentEnabled: Boolean,
    val webSearchEnabled: Boolean = true
)

/**
 * DeepSeek 智能体执行器：
 * 循环「对话 → 解析工具调用 → 执行工具 → 回填结果」，直到产出最终回答。
 */
class AgentExecutor(private val client: DeepSeekClient) {

    fun execute(
        config: RunConfig,
        history: List<ApiMessage>
    ): Flow<AgentEvent> = channelFlow {
        val messages = history.toMutableList()
        try {
            var step = 0
            while (step < MAX_STEPS) {
                step++
                send(AgentEvent.StepStart(step))

                val content = StringBuilder()
                val reasoning = StringBuilder()
                val tools = LinkedHashMap<Int, AccumulatedToolCall>()
                var streamFailed: Throwable? = null

                val request = ChatRequest(
                    model = config.modelId,
                    messages = messages.toList(),
                    stream = true,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens?.takeIf { it > 0 },
                    tools = if (config.agentEnabled) {
                        ToolRegistry.availableTools().filter {
                            config.webSearchEnabled || it.function.name != "http_fetch"
                        }
                    } else null
                )

                try {
                    client.streamChat(config.provider, request).collect { ev ->
                        when (ev) {
                            is com.deepseekv2.agent.data.api.StreamEvent.ContentDelta -> {
                                content.append(ev.text)
                                send(AgentEvent.ContentDelta(ev.text))
                            }
                            is com.deepseekv2.agent.data.api.StreamEvent.ReasoningDelta -> {
                                reasoning.append(ev.text)
                                send(AgentEvent.ReasoningDelta(ev.text))
                            }
                            is com.deepseekv2.agent.data.api.StreamEvent.ToolCallFragment -> {
                                val acc = tools.getOrPut(ev.index) {
                                    AccumulatedToolCall(index = ev.index)
                                }
                                ev.callId?.let { acc.callId = it }
                                ev.toolName?.let { acc.name = it }
                                acc.arguments += ev.argsDelta
                                send(AgentEvent.ToolCallsPreview(tools.values.toList()))
                            }
                        }
                    }
                } catch (e: Exception) {
                    streamFailed = e
                }

                if (tools.isEmpty()) {
                    if (content.isBlank()) {
                        send(
                            AgentEvent.Failed(streamFailed ?: IOException2("模型未返回任何内容"))
                        )
                        return@channelFlow
                    }
                    send(
                        AgentEvent.Completed(
                            AssistantTurn(content = content.toString(), reasoning = reasoning.toString())
                        )
                    )
                    return@channelFlow
                }

                // 有工具调用：回填 assistant + tool 消息，继续下一轮
                val calls = DeepSeekClient.toApiToolCalls(tools.values)
                messages.add(
                    ApiMessage(
                        role = "assistant",
                        content = content.toString().ifBlank { null },
                        toolCalls = calls.ifEmpty { null }
                    )
                )

                for (call in tools.values.filter { it.name != null }) {
                    val id = call.callId ?: "call_${call.index}"
                    val name = call.name!!
                    send(AgentEvent.ToolCallStarted(id, name, call.arguments))

                    val output: ToolOutput = withContext(Dispatchers.IO) {
                        ToolRegistry.execute(name, call.arguments)
                    }
                    messages.add(ApiMessage.toolResult(id, name, output.output))
                    send(AgentEvent.ToolCallFinished(id, name, output.output, output.success))
                }
            }
            send(AgentEvent.Failed(IllegalStateException("已达最大工具调用轮次 ($MAX_STEPS)")))
        } catch (e: Exception) {
            send(AgentEvent.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    private class IOException2(message: String) : RuntimeException(message)

    companion object {
        const val MAX_STEPS = 10
    }
}
