package com.deepseekv2.agent.di

import android.app.Application
import com.deepseekv2.agent.agent.AgentExecutor
import com.deepseekv2.agent.agent.ToolRegistry
import com.deepseekv2.agent.data.api.DeepSeekClient
import com.deepseekv2.agent.data.prefs.SettingsRepository
import com.deepseekv2.agent.data.store.ConversationStore

object AppGraph {

    lateinit var app: Application
        private set

    val client: DeepSeekClient by lazy { DeepSeekClient() }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val conversations: ConversationStore by lazy { ConversationStore(app) }
    val executor: AgentExecutor by lazy { AgentExecutor(client) }

    fun init(application: Application) {
        app = application
        com.deepseekv2.agent.plugin.PluginManager.ensureDirectory()
        com.deepseekv2.agent.plugin.PluginManager.loadAll(application)
        ToolRegistry.init(application, client) { settings.settings.value.workspaceUri }
    }
}
