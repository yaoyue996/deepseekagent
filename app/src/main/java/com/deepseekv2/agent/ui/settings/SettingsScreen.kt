package com.deepseekv2.agent.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.deepseekv2.agent.data.model.ModelCatalog
import com.deepseekv2.agent.data.prefs.ProviderProfile
import com.deepseekv2.agent.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

private enum class SettingsTab { PROVIDERS, PREFERENCES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    snackbar: (String) -> Unit
) {
    val settings by AppGraph.settings.settings.collectAsState()
    var editingProvider by remember { mutableStateOf<ProviderProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(SettingsTab.PROVIDERS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        tab = when (tab) {
                            SettingsTab.PROVIDERS -> SettingsTab.PREFERENCES
                            SettingsTab.PREFERENCES -> SettingsTab.PROVIDERS
                        }
                    }) {
                        Text(
                            if (tab == SettingsTab.PROVIDERS) "生成参数" else "服务商与模型",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (tab == SettingsTab.PROVIDERS) {
                item {
                    SectionHeader("服务商（兼容 OpenAI / DeepSeek 接口格式）")
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            settings.providers.forEach { p ->
                                ProviderRow(
                                    profile = p,
                                    active = p.id == settings.activeProviderId,
                                    onClick = {
                                        AppGraph.settings.update { s ->
                                            s.copy(activeProviderId = p.id, activeModelId = p.models.firstOrNull() ?: "")
                                        }
                                    },
                                    onEdit = { editingProvider = p },
                                    onDelete = {
                                        AppGraph.settings.removeProvider(p.id)
                                        snackbar("已删除 ${p.name}")
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            FilledTonalButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("添加自定义服务商")
                            }
                        }
                    }
                }
                item { SectionHeader("说明") }
                item {
                    Text(
                        "· DeepSeek 官方地址为 https://api.deepseek.com\n" +
                            "· 内置模型：deepseek-v4-pro / deepseek-v4-flash / deepseek-v4-flash-vision-exp\n" +
                            "· 自定义服务商需兼容 chat/completions 接口，在「编辑」中可添加自定义模型 ID（每行一个）\n" +
                            "· API Key 仅保存在本机，不会上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            } else {
                item { SectionHeader("主题") }
                item {
                    ThemeSelector(
                        current = settings.themeMode ?: "system",
                        onSelect = { mode -> AppGraph.settings.update { it.copy(themeMode = mode) } }
                    )
                }
                item { SectionHeader("工作区") }
                item { WorkspaceSection(settings) }
                item { SectionHeader("联网搜索") }
                item {
                    SettingSwitchRow(
                        title = "启用联网搜索",
                        subtitle = "允许 Agent 通过网页抓取工具查询实时信息",
                        checked = settings.webSearchEnabled ?: true,
                        onChange = { v -> AppGraph.settings.update { it.copy(webSearchEnabled = v) } }
                    )
                }
                item { SectionHeader("插件") }
                item { PluginSection() }
                item { SectionHeader("当前模型") }
                item {
                    val modelId = settings.activeModelId.ifBlank { "未选择" }
                    val info = ModelCatalog.find(modelId)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(info?.displayName ?: modelId, style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                info?.description ?: (settings.activeProvider?.name ?: "") + " 自定义模型",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { SectionHeader("智能体") }
                item {
                    SettingSwitchRow(
                        title = "启用工具调用（Agent 模式）",
                        subtitle = "允许模型调用 时间/计算器/网页抓取/设备信息/文件读写 等内置工具并自动多轮推理",
                        checked = settings.agentEnabled,
                        onChange = { v -> AppGraph.settings.update { it.copy(agentEnabled = v) } }
                    )
                }
                item { SectionHeader("生成参数") }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Temperature", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.weight(1f))
                            Text(String.format("%.1f", settings.temperature), color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = settings.temperature.toFloat(),
                            onValueChange = { v ->
                                AppGraph.settings.update { it.copy(temperature = v.toDouble()) }
                            },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                        Spacer(Modifier.height(6.dp))
                        var maxTokensText by remember(settings.maxTokens) {
                            mutableStateOf(if (settings.maxTokens > 0) settings.maxTokens.toString() else "")
                        }
                        OutlinedTextField(
                            value = maxTokensText,
                            onValueChange = { v ->
                                maxTokensText = v.filter { it.isDigit() }.take(7)
                                val n = maxTokensText.toIntOrNull()
                                AppGraph.settings.update { it.copy(maxTokens = n ?: 0) }
                            },
                            label = { Text("最大生成长度（留空不限制）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item { SectionHeader("系统提示词") }
                item {
                    var prompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = {
                                prompt = it
                                AppGraph.settings.update { s -> s.copy(systemPrompt = it) }
                            },
                            minLines = 3,
                            maxLines = 8,
                            placeholder = { Text("设定助手的角色与行为…") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "DeepSeekAgentV2 v2.0.0\n兼容 https://api.deepseek.com 及任意 OpenAI 格式接口",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    // ---------- 编辑服务商 ----------
    editingProvider?.let { target ->
        ProviderEditDialog(
            initial = target,
            isNew = false,
            onSave = { updated ->
                AppGraph.settings.updateProvider(updated)
                editingProvider = null
            },
            onTest = { updated, done -> testProvider(updated, done) },
            onDismiss = { editingProvider = null }
        )
    }
    if (showAddDialog) {
        ProviderEditDialog(
            initial = ProviderProfile(UUID.randomUUID().toString(), "", "", "", listOf()),
            isNew = true,
            onSave = { p ->
                AppGraph.settings.addProvider(p)
                showAddDialog = false
            },
            onTest = { p, done -> testProvider(p, done) },
            onDismiss = { showAddDialog = false }
        )
    }
}

private fun testProvider(profile: ProviderProfile, done: (String?, String?) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val msg = AppGraph.client.testConnection(profile)
            launch(Dispatchers.Main) { done(msg, null) }
        } catch (e: Exception) {
            launch(Dispatchers.Main) { done(null, e.message ?: "连接失败") }
        }
    }
}

@Composable
private fun ProviderRow(
    profile: ProviderProfile,
    active: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(
                    profile.name.ifBlank { "未命名服务商" },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    profile.baseUrl,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${profile.models.size} 个模型 · Key ${if (profile.apiKey.isBlank()) "未配置" else "已配置"}" +
                        (if (active) " · 使用中" else ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.ExpandMore, contentDescription = "编辑", Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun ThemeSelector(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("system", "默认", "跟随系统"),
        Triple("light", "浅色", "明亮模式"),
        Triple("dark", "深色", "夜间模式")
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        options.forEach { (mode, title, sub) ->
            val selected = current == mode
            Surface(
                onClick = { onSelect(mode) },
                shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub,
                        fontSize = 11.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginSection() {
    val context = LocalContext.current
    val infos by com.deepseekv2.agent.plugin.PluginManager.info.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    val pickJar = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val loaded = com.deepseekv2.agent.plugin.PluginManager.importJar(context, uri)
            message = if (loaded != null) {
                "已导入插件：${loaded.name()}"
            } else {
                "导入失败：请确认是包含 plugin.properties 与 classes.dex 的 jar 文件"
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("插件（jar）", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))

            if (infos.isEmpty()) {
                Text(
                    "暂无已加载插件。开发完成后将 jar 放入插件目录，或点击「导入插件」选择 jar 文件。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                infos.forEach { p ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v${p.version}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            p.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("已导入")) Color(0xFF188038) else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { pickJar.launch("application/java-archive") }) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入插件")
                }
                TextButton(onClick = {
                    com.deepseekv2.agent.plugin.PluginManager.loadAll(context)
                    message = "已刷新插件列表"
                }) { Text("刷新") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "插件目录：/storage/emulated/0/DSA/Plugins",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun WorkspaceSection(settings: com.deepseekv2.agent.data.prefs.AppSettings) {
    val context = LocalContext.current
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val name = try {
                DocumentFile.fromTreeUri(context, uri)?.name
            } catch (_: Exception) {
                null
            } ?: uri.lastPathSegment ?: "自定义目录"
            AppGraph.settings.update {
                it.copy(workspaceUri = uri.toString(), workspaceLabel = name)
            }
        }
    }

    val customUri = settings.workspaceUri
    val label = if (customUri.isNullOrBlank()) {
        "默认（应用私有目录）"
    } else {
        settings.workspaceLabel ?: customUri
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Agent 文件读写目录", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { dirPicker.launch(null) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择文件夹")
                }
                if (!customUri.isNullOrBlank()) {
                    TextButton(onClick = {
                        AppGraph.settings.update {
                            it.copy(workspaceUri = null, workspaceLabel = null)
                        }
                    }) { Text("恢复默认") }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: ProviderProfile,
    isNew: Boolean,
    onSave: (ProviderProfile) -> Unit,
    onTest: (ProviderProfile, (String?, String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models.joinToString("\n")) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加自定义服务商" else "编辑服务商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("服务地址 Base URL") },
                    placeholder = { Text("https://api.deepseek.com 或自定义 URL") },
                    singleLine = true,
                    supportingText = { Text("将自动拼接 /chat/completions 与 /models") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    trailingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text("可用模型（每行一个，含自定义模型）") },
                    placeholder = { Text("deepseek-v4-pro\ndeepseek-v4-flash\ndeepseek-v4-vision-exp\nmy-custom-model") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(enabled = !testing && apiKey.isNotBlank() && baseUrl.isNotBlank(), onClick = {
                        testing = true; testResult = null
                        onTest(
                            ProviderProfile(initial.id, name, baseUrl, apiKey, emptyList())
                        ) { ok, err ->
                            testing = false
                            testResult = ok to err
                        }
                    }) {
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                testResult?.let { (ok, err) ->
                    Text(
                        ok ?: err ?: "",
                        fontSize = 12.sp,
                        color = if (ok != null) Color(0xFF188038) else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    val parsedModels = models.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            apiKey = apiKey.trim(),
                            models = parsedModels
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isNew) "取消" else "关闭")
            }
        }
    )
}
