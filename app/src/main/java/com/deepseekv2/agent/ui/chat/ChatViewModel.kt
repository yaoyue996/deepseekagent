package com.deepseekv2.agent.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseekv2.agent.agent.AgentEvent
import com.deepseekv2.agent.agent.AgentExecutor
import com.deepseekv2.agent.agent.RunConfig
import com.deepseekv2.agent.data.api.AccumulatedToolCall
import com.deepseekv2.agent.data.model.ApiMessage
import com.deepseekv2.agent.data.model.Role
import com.deepseekv2.agent.data.prefs.AppSettings
import com.deepseekv2.agent.data.store.Conversation
import com.deepseekv2.agent.data.store.ConversationStore
import com.deepseekv2.agent.data.store.ToolCallDisplay
import com.deepseekv2.agent.data.store.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

data class AttachedImage(val path: String)

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val streaming: Boolean = false,
    val streamedContent: String = "",
    val streamedReasoning: String = "",
    val reasoningElapsedSec: Int = 0,
    val pendingTools: List<ToolCallDisplay> = emptyList(),
    val error: String? = null
) {
    val messages: List<UiMessage> get() = conversation.messages
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = com.deepseekv2.agent.di.AppGraph.settings
    private val store = com.deepseekv2.agent.di.AppGraph.conversations
    private val executor: AgentExecutor = com.deepseekv2.agent.di.AppGraph.executor

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val _ui = MutableStateFlow(ChatUiState(conversation = store.newConversation()))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val _attachments = MutableStateFlow<List<AttachedImage>>(emptyList())
    val attachments: StateFlow<List<AttachedImage>> = _attachments.asStateFlow()

    private var job: Job? = null

    // ---------------- 会话管理 ----------------

    fun newChat() {
        job?.cancel()
        job = null
        _ui.value = ChatUiState(conversation = store.newConversation())
        clearError()
    }

    fun selectConversation(id: String) {
        job?.cancel()
        job = null
        store.load(id)?.let { conv ->
            _ui.value = ChatUiState(conversation = conv)
        }
        clearError()
    }

    fun deleteConversation(id: String) {
        store.delete(id)
        if (_ui.value.conversation.id == id) newChat()
    }

    fun historyConversations(): List<Conversation> = store.list()

    fun consumeError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun clearError() {
        if (_ui.value.error != null) consumeError()
    }

    // ---------------- 附件 ----------------

    fun addAttachment(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = copyAndDownscale(uri) ?: return@launch
                if (_attachments.value.size < 4) {
                    _attachments.value += AttachedImage(file.absolutePath)
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = "图片读取失败: ${e.message}")
            }
        }
    }

    fun removeAttachment(path: String) {
        _attachments.value = _attachments.value.filter { it.path != path }
        File(path).delete()
    }

    private fun copyAndDownscale(uri: Uri): File? {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.use { BitmapFactory.decodeStream(it, null, opts) }
        val maxSide = 1280
        var sample = 1
        while (opts.outWidth / sample > maxSide * 2 || opts.outHeight / sample > maxSide * 2) {
            sample *= 2
        }
        val bitmap = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scaled = scaleToFit(bitmap, maxSide)
        val dir = File(getApplication<Application>().filesDir, "images").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_${(0..999).random()}.jpg")
        out.outputStream().use { fos -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos) }
        return out
    }

    private fun scaleToFit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun imageToDataUri(path: String): String? = try {
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    } catch (_: Exception) {
        null
    }

    // ---------------- 发送与生成 ----------------

    fun send(text: String) {
        val content = text.trim()
        val images = _attachments.value.map { it.path }.toList()
        if (content.isEmpty() && images.isEmpty()) return
        if (_ui.value.streaming) return

        appendMessage(UiMessage(role = Role.USER.value, content = content, images = images))
        ensureTitle(content)
        // 注意：仅清空待发送列表，不能删除已拷贝的图片文件
        // （消息与历史记录仍引用这些路径，删除会导致缩略图失效且模型收不到图）
        _attachments.value = emptyList()
        runAgent()
    }

    /** 重新生成最后一条助手回复 */
    fun regenerate() {
        if (_ui.value.streaming) return
        dropTrailingAssistant()
        runAgent()
    }

    fun stopGeneration() {
        // 取消后由 runAgent 的 CancellationException 分支保留已生成的部分内容
        job?.cancel()
    }

    private fun runAgent() {
        val s = settings.value
        val provider = s.activeProvider
        if (provider == null) {
            _ui.value = _ui.value.copy(error = "未配置服务商，请前往设置添加")
            return
        }
        if (provider.apiKey.isBlank()) {
            _ui.value = _ui.value.copy(error = "请先在设置中填写 API Key")
            return
        }

        val history = buildApiHistory(_ui.value.messages)
        _ui.value = _ui.value.copy(
            streaming = true,
            streamedContent = "",
            streamedReasoning = "",
            reasoningElapsedSec = 0,
            pendingTools = emptyList(),
            error = null
        )

        job = viewModelScope.launch {
            var content = StringBuilder()
            var reasoning = StringBuilder()
            var firstReasoningAt = 0L
            var firstContentAt = 0L
            val toolsMap = LinkedHashMap<String, ToolCallDisplay>()

            try {
                executor.execute(
                    config = RunConfig(
                        provider = provider,
                        modelId = s.activeModelId.ifBlank { provider.models.firstOrNull() ?: "" },
                        systemPrompt = s.systemPrompt,
                        temperature = s.temperature,
                        maxTokens = s.maxTokens,
                        agentEnabled = s.agentEnabled,
                        webSearchEnabled = s.webSearchEnabled ?: true
                    ),
                    history = history
                ).collect { ev ->
                    when (ev) {
                        is AgentEvent.ReasoningDelta -> {
                            if (firstReasoningAt == 0L) firstReasoningAt = System.currentTimeMillis()
                            reasoning.append(ev.text)
                            updateStream(reasoning.toString(), content.toString(), toolsMap.values.toList())
                        }
                        is AgentEvent.ContentDelta -> {
                            if (firstContentAt == 0L && firstReasoningAt != 0L) {
                                firstContentAt = System.currentTimeMillis()
                            }
                            content.append(ev.text)
                            updateStream(reasoning.toString(), content.toString(), toolsMap.values.toList())
                        }
                        is AgentEvent.ToolCallsPreview -> {
                            mergeToolPreview(toolsMap, ev.calls)
                            updateStream(reasoning.toString(), content.toString(), toolsMap.values.toList())
                        }
                        is AgentEvent.ToolCallStarted -> {
                            toolsMap[ev.callId] =
                                ToolCallDisplay(ev.callId, ev.toolName, ev.arguments, running = true)
                            updateStream(reasoning.toString(), content.toString(), toolsMap.values.toList())
                        }
                        is AgentEvent.ToolCallFinished -> {
                            toolsMap[ev.callId] = ToolCallDisplay(
                                ev.callId, ev.toolName,
                                toolsMap[ev.callId]?.arguments ?: "{}",
                                result = ev.output.take(2000), running = false
                            )
                            updateStream(reasoning.toString(), content.toString(), toolsMap.values.toList())
                        }
                        is AgentEvent.Completed -> { /* finalize 使用本地累积 */ }
                        is AgentEvent.Failed -> throw ev.error
                        is AgentEvent.StepStart -> {}
                    }
                }
                finalizeTurn(false, content, reasoning, toolsMap, firstReasoningAt, firstContentAt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finalizeTurn(true, content, reasoning, toolsMap, firstReasoningAt, firstContentAt)
            } catch (e: Exception) {
                finalizeTurn(true, content, reasoning, toolsMap, firstReasoningAt, firstContentAt, e.message)
            }
        }
    }

    private fun mergeToolPreview(map: LinkedHashMap<String, ToolCallDisplay>, calls: List<AccumulatedToolCall>) {
        for (c in calls) {
            val key = c.callId ?: "pending_${c.index}"
            map[key] = ToolCallDisplay(key, c.name ?: map[key]?.name ?: "", c.arguments, running = true)
        }
    }

    private fun updateStream(
        reasoningText: String,
        contentText: String,
        tools: List<ToolCallDisplay>
    ) {
        _ui.value = _ui.value.copy(
            streamedReasoning = reasoningText,
            streamedContent = contentText,
            pendingTools = tools
        )
    }

    private fun finalizeTurn(
        cancelled: Boolean,
        content: StringBuilder = StringBuilder(),
        reasoning: StringBuilder = StringBuilder(),
        toolsMap: LinkedHashMap<String, ToolCallDisplay> = LinkedHashMap(),
        firstReasoningAt: Long = 0,
        firstContentAt: Long = 0,
        errorMessage: String? = null
    ) {
        val text = content.toString()
        val think = reasoning.toString()
        val secs = if (firstReasoningAt != 0L) {
            ((firstContentAt.takeIf { it != 0L } ?: System.currentTimeMillis()) - firstReasoningAt) / 1000
        } else 0

        if (text.isNotBlank() || toolsMap.isNotEmpty()) {
            appendMessage(
                UiMessage(
                    role = Role.ASSISTANT.value,
                    content = text,
                    reasoning = think,
                    reasoningSeconds = secs.toInt(),
                    toolCalls = toolsMap.values.toList()
                )
            )
        }
        val err = when {
            errorMessage != null -> friendlyError(errorMessage)
            cancelled && text.isBlank() && toolsMap.isEmpty() -> null
            else -> null
        }
        persistConversation()
        _ui.value = _ui.value.copy(streaming = false, error = err)
        job = null
    }

    private fun friendlyError(raw: String?): String {
        val r = raw ?: return "请求失败，请稍后重试"
        return when {
            r.contains("401") -> "API Key 无效或未授权 (401)"
            r.contains("402") -> "余额不足 (402)，请前往 DeepSeek 平台充值"
            r.contains("422") -> "参数错误 (422)：$r"
            r.contains("429") -> "请求频率过高 (429)，请稍后重试"
            r.contains("500") || r.contains("503") -> "服务器繁忙 ($r)，请稍后重试"
            r.contains("Unable to resolve host", true) ||
                r.contains("Failed to connect", true) -> "网络连接失败，请检查网络或服务商地址"
            else -> "出错了：${r.take(300)}"
        }
    }

    private fun appendMessage(msg: UiMessage) {
        _ui.value = _ui.value.copy(
            conversation = _ui.value.conversation.copy(
                messages = _ui.value.messages + msg,
                updatedAt = System.currentTimeMillis(),
                providerId = settings.value.activeProviderId,
                modelId = settings.value.activeModelId
            )
        )
    }

    private fun ensureTitle(firstUserText: String) {
        if (_ui.value.conversation.title == "新对话" && firstUserText.isNotBlank()) {
            _ui.value = _ui.value.copy(
                conversation = _ui.value.conversation.copy(
                    title = ConversationStore.suggestTitle(firstUserText)
                )
            )
        }
    }

    private fun dropTrailingAssistant() {
        val msgs = _ui.value.messages.toMutableList()
        while (msgs.isNotEmpty() && msgs.last().role == Role.ASSISTANT.value) {
            msgs.removeAt(msgs.size - 1)
        }
        _ui.value = _ui.value.copy(conversation = _ui.value.conversation.copy(messages = msgs))
    }

    private fun persistConversation() {
        val conv = _ui.value.conversation
        if (conv.messages.isNotEmpty()) store.save(conv)
    }

    // ---------------- 历史 → API 消息 ----------------

    private fun buildApiHistory(messages: List<UiMessage>): List<ApiMessage> {
        val s = settings.value
        val result = ArrayList<ApiMessage>()
        if (s.systemPrompt.isNotBlank()) {
            result.add(ApiMessage.text(Role.SYSTEM, s.systemPrompt))
        }
        for (m in messages) {
            when (Role.from(m.role)) {
                Role.USER -> {
                    val uris = m.images.mapNotNull { imageToDataUri(it) }
                    if (uris.isNotEmpty()) {
                        result.add(ApiMessage.vision(m.content, uris))
                    } else {
                        result.add(ApiMessage.text(Role.USER, m.content))
                    }
                }
                Role.ASSISTANT -> {
                    val apiCalls = m.toolCalls.filter { it.name.isNotBlank() }.map {
                        com.deepseekv2.agent.data.model.ApiToolCall(
                            id = it.id,
                            function = com.deepseekv2.agent.data.model.FunctionCall(
                                it.name,
                                it.arguments.ifBlank { "{}" }
                            )
                        )
                    }
                    result.add(
                        ApiMessage(
                            role = Role.ASSISTANT.value,
                            content = m.content.ifBlank { null },
                            toolCalls = apiCalls.ifEmpty { null }
                        )
                    )
                    m.toolCalls.filter { it.result != null }.forEach {
                        result.add(ApiMessage.toolResult(it.id, it.name, it.result!!))
                    }
                }
                else -> {}
            }
        }
        return result
    }
}
