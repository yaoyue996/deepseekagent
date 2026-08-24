package com.deepseekv2.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseekv2.agent.data.model.ModelCatalog
import com.deepseekv2.agent.di.AppGraph
import com.deepseekv2.agent.ui.chat.ChatScreen
import com.deepseekv2.agent.ui.chat.ChatViewModel
import com.deepseekv2.agent.ui.models.ModelPickerSheet
import com.deepseekv2.agent.ui.settings.SettingsScreen
import com.deepseekv2.agent.ui.splash.SplashScreen
import com.deepseekv2.agent.ui.theme.DeepSeekAgentTheme
import kotlinx.coroutines.launch

private enum class Screen { CHAT, SETTINGS }

class MainActivity : ComponentActivity() {

    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermission()
        setContent {
            val settings by AppGraph.settings.settings.collectAsState()
            val darkTheme = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            DeepSeekAgentTheme(darkTheme = darkTheme) {
                var showSplash by remember { mutableStateOf(true) }
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(500),
                    label = "splash"
                ) { splash ->
                    if (splash) {
                        SplashScreen(onFinished = { showSplash = false })
                    } else {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 授权返回后刷新插件列表
        com.deepseekv2.agent.plugin.PluginManager.loadAll(applicationContext)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                }
            }
        } else if (
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val vm: ChatViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(Screen.CHAT) }
    var showModelPicker by remember { mutableStateOf(false) }
    var historyVersion by remember { mutableIntStateOf(0) }

    val closeDrawer = { scope.launch { drawerState.close() } }

    // 错误提示 → Snackbar
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            vm.consumeError()
        }
    }

    Box {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == Screen.CHAT,
        drawerContent = {
            AppDrawer(
                versionKey = historyVersion,
                activeConversationId = ui.conversation.id,
                onSelect = { id ->
                    vm.selectConversation(id)
                    historyVersion++
                    closeDrawer()
                },
                onNewChat = {
                    vm.newChat()
                    historyVersion++
                    closeDrawer()
                },
                onDelete = { id ->
                    vm.deleteConversation(id)
                    historyVersion++
                },
                onOpenSettings = {
                    screen = Screen.SETTINGS
                    closeDrawer()
                }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(Modifier.fillMaxSize()) {
                if (screen == Screen.CHAT) {
                    ChatTopBar(
                        modelName = ModelCatalog.displayName(settings.activeModelId),
                        onMenu = { scope.launch { drawerState.open() } },
                        onModelClick = { showModelPicker = true },
                        onNewChat = { vm.newChat(); historyVersion++ },
                        onSettings = { screen = Screen.SETTINGS }
                    )
                    ChatScreen(vm = vm)
                } else {
                    BackHandler { screen = Screen.CHAT }
                    SettingsScreen(onBack = { screen = Screen.CHAT }, snackbar = {})
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            settings = settings,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, info ->
                AppGraph.settings.update { s ->
                    s.copy(activeProviderId = provider.id, activeModelId = info.id)
                }
                showModelPicker = false
            }
        )
    }
    }
}

// ---------------- 顶栏（DeepSeek 风格：居中模型选择） ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelName: String,
    onMenu: () -> Unit,
    onModelClick: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Outlined.Menu, contentDescription = "菜单")
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onModelClick),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    modelName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Outlined.ExpandMore, contentDescription = "切换模型", Modifier.size(20.dp))
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "新对话")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

// ---------------- 侧边抽屉 ----------------

@Composable
private fun AppDrawer(
    versionKey: Int,
    activeConversationId: String,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm: ChatViewModel = viewModel()
    val conversations = remember(versionKey) { vm.historyConversations() }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(300.dp)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 头部 Logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF4D6BFE), Color(0xFF7B93FF))),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("🐳", fontSize = 20.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("DeepSeekAgentV2", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("智能助手 · V2.0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(
                onClick = onNewChat,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("开启新对话")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            Text(
                "历史对话",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(conversations, key = { _, c -> c.id }) { _, conv ->
                    val active = conv.id == activeConversationId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(conv.id) }
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                conv.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                "${conv.messages.size} 条消息",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onDelete(conv.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text("设置", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
