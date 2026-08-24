package com.deepseekv2.agent.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekv2.agent.data.model.ModelCatalog
import com.deepseekv2.agent.data.store.ToolCallDisplay
import com.deepseekv2.agent.data.store.UiMessage
import com.deepseekv2.agent.ui.components.MarkdownText
import com.deepseekv2.agent.ui.components.TypingDots

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val attachments by vm.attachments.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val visionSupported = ModelCatalog.supportsVision(settings.activeModelId)

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> uris.forEach { vm.addAttachment(it) } }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (ui.messages.isEmpty() && !ui.streaming) {
                EmptyGreeting(onPick = { input = it }, modifier = Modifier.weight(1f))
            } else {
                MessageList(
                    ui = ui,
                    onRegenerate = { vm.regenerate() },
                    modifier = Modifier.weight(1f)
                )
            }

            // 附件预览
            if (attachments.isNotEmpty()) {
                AttachmentPreview(
                    paths = attachments.map { it.path },
                    onRemove = { vm.removeAttachment(it) }
                )
            }

            InputBar(
                text = input,
                onTextChange = { input = it },
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = { vm.stopGeneration() },
                streaming = ui.streaming,
                visionSupported = visionSupported,
                onPickImage = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
        }

        // 错误横幅
        ui.error?.let { err ->
            ErrorBanner(
                message = err,
                onDismiss = { vm.consumeError() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
            )
        }
    }
}

// ---------------- 消息列表 ----------------

@Composable
private fun MessageList(
    ui: ChatUiState,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val displayCount = ui.messages.size + (if (ui.streaming) 1 else 0)

    LaunchedEffect(displayCount, ui.streamedContent.length, ui.streamedReasoning.length, ui.pendingTools.size) {
        if (displayCount > 0) {
            try {
                listState.animateScrollToItem(index = 0)
            } catch (_: Exception) {
            }
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(List(displayCount) { it }) { index, _ ->
            val msgIndex = displayCount - 1 - index
            if (msgIndex < ui.messages.size) {
                val msg = ui.messages[msgIndex]
                when (com.deepseekv2.agent.data.model.Role.from(msg.role)) {
                    com.deepseekv2.agent.data.model.Role.USER -> UserBubble(msg)
                    else -> AssistantBlock(msg)
                }
            } else {
                StreamingTail(ui = ui, onRegenerate = onRegenerate)
            }
        }
    }
}

@Composable
private fun UserBubble(msg: UiMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 300.dp)) {
            if (msg.images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(msg.images) { _, path ->
                        Thumbnail(path, Modifier.size(140.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
            ) {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun AssistantBlock(msg: UiMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg.reasoning.isNotBlank()) {
            ReasoningCard(reasoning = msg.reasoning, seconds = msg.reasoningSeconds, live = false)
        }
        if (msg.toolCalls.isNotEmpty()) {
            msg.toolCalls.forEach { ToolCallCard(it, expandedDefault = false) }
        }
        if (msg.content.isNotBlank()) {
            MarkdownText(markdown = msg.content)
        }
    }
}

@Composable
private fun StreamingTail(
    ui: ChatUiState,
    onRegenerate: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.streamedReasoning.isNotBlank() || (ui.pendingTools.isEmpty() && ui.streamedContent.isBlank())) {
            ReasoningCard(
                reasoning = ui.streamedReasoning,
                seconds = ui.reasoningElapsedSec,
                live = ui.streamedContent.isBlank()
            )
        }
        if (ui.pendingTools.isNotEmpty()) {
            ui.pendingTools.forEach { ToolCallCard(it, expandedDefault = true) }
        }
        if (ui.streamedContent.isNotBlank()) {
            MarkdownText(markdown = ui.streamedContent)
        } else if (ui.pendingTools.isEmpty()) {
            TypingDots()
        }
    }
}

// ---------------- 深度思考卡片 ----------------

@Composable
private fun ReasoningCard(reasoning: String, seconds: Int, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val headerText = when {
        live && reasoning.isBlank() -> "思考中…"
        live -> "深度思考中…"
        seconds > 0 -> "已深度思考（用时 ${seconds} 秒）"
        else -> "已深度思考"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hardware,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    headerText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (live) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded && reasoning.isNotBlank()) {
                Text(
                    text = reasoning,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                )
            }
        }
    }
}

// ---------------- 工具调用卡片 ----------------

@Composable
private fun ToolCallCard(call: ToolCallDisplay, expandedDefault: Boolean) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    Icons.Outlined.Hardware,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (call.running) "正在调用工具：${call.name}…" else "工具调用 · ${call.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (call.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Text(
                    "参数: ${call.arguments.take(500)}",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                call.result?.let { r ->
                    Text(
                        "结果: ${r.take(800)}",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------------- 缩略图 ----------------

@Composable
fun Thumbnail(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = loadThumbnail(path)
    }
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

private suspend fun loadThumbnail(path: String): ImageBitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }

@Composable
private fun AttachmentPreview(paths: List<String>, onRemove: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        itemsIndexed(paths) { _, path ->
            Box {
                Thumbnail(path, Modifier.size(64.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clickable { onRemove(path) }
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除",
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
    }
}

// ---------------- 输入栏 ----------------

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    streaming: Boolean,
    visionSupported: Boolean,
    onPickImage: () -> Unit
) {
    val glassColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = glassColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onPickImage, enabled = visionSupported && !streaming) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = "添加图片",
                        tint = if (visionSupported) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 5,
                        cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!streaming && text.isNotBlank()) onSend() }),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) {
                                    Text(
                                        "给 DeepSeek 发送消息…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp, max = 130.dp)
                    )
                }
                SendOrStopButton(streaming, text.isNotBlank(), onSend, onStop)
            }
        }
    }
}

@Composable
private fun SendOrStopButton(
    streaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (streaming || canSend) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = streaming || canSend) {
                if (streaming) onStop() else if (canSend) onSend()
            },
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (streaming) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "停止生成",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

// ---------------- 空状态 ----------------

@Composable
private fun EmptyGreeting(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(84.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF4D6BFE), Color(0xFF7B93FF))
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("🐳", fontSize = 40.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Hi，我是 DeepSeekAgentV2", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "由 DeepSeek 大模型驱动的智能助手\n支持深度思考、联网查询与多模态理解",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        val suggestions = listOf(
            "帮我写一个快速排序算法",
            "计算 (128+256)*3^2 等于多少",
            "帮我查询今天的科技新闻",
            "把「你好，世界」翻译成英文"
        )
        for (row in suggestions.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in row) {
                    SuggestionChip2(text = s, onClick = { onPick(s) })
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SuggestionChip2(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

// ---------------- 错误横幅 ----------------

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onDismiss)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
