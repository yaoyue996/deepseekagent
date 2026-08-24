package com.deepseekv2.agent.data.prefs

import android.content.Context
import com.deepseekv2.agent.data.model.DEEPSEEK_OFFICIAL_URL
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProviderProfile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("baseUrl") val baseUrl: String,
    @SerializedName("apiKey") val apiKey: String = "",
    /** 该服务商可用模型 id（含内置 + 自定义） */
    @SerializedName("models") val models: List<String> = emptyList()
)

data class AppSettings(
    @SerializedName("providers") val providers: List<ProviderProfile> = emptyList(),
    @SerializedName("activeProviderId") val activeProviderId: String = "",
    @SerializedName("activeModelId") val activeModelId: String = "",
    @SerializedName("systemPrompt") val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    @SerializedName("temperature") val temperature: Double = 0.7,
    @SerializedName("maxTokens") val maxTokens: Int = 0,
    @SerializedName("agentEnabled") val agentEnabled: Boolean = true,
    /** 联网搜索开关（控制 http_fetch 工具是否可用） */
    @SerializedName("webSearchEnabled") val webSearchEnabled: Boolean? = null,
    /** 主题模式：system / light / dark（空视为 system） */
    @SerializedName("themeMode") val themeMode: String? = null,
    /** 自定义工作区（SAF 树 URI），为空则使用应用默认目录 */
    @SerializedName("workspaceUri") val workspaceUri: String? = null,
    @SerializedName("workspaceLabel") val workspaceLabel: String? = null
) {
    val activeProvider: ProviderProfile?
        get() = providers.firstOrNull { it.id == activeProviderId }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是 DeepSeek Agent，一个乐于助人的中文 AI 智能助手。回答要准确、简洁，适当使用 Markdown 格式。"
        const val OFFICIAL_PROVIDER_ID = "deepseek-official"

        fun default(): AppSettings {
            val official = ProviderProfile(
                id = OFFICIAL_PROVIDER_ID,
                name = "DeepSeek 官方",
                baseUrl = DEEPSEEK_OFFICIAL_URL,
                apiKey = "",
                models = listOf(
                    "deepseek-v4-pro",
                    "deepseek-v4-flash",
                    "deepseek-v4-flash-vision-exp"
                )
            )
            return AppSettings(
                providers = listOf(official),
                activeProviderId = official.id,
                activeModelId = "deepseek-v4-pro"
            )
        }
    }
}

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("ds_agent_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = try {
        prefs.getString(KEY_JSON, null)?.let { gson.fromJson(it, AppSettings::class.java) }
            ?: AppSettings.default()
    } catch (_: Exception) {
        AppSettings.default()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        // 一致性：激活模型必须属于激活服务商
        val fixed = if (next.activeProvider?.models?.contains(next.activeModelId) == false) {
            next.copy(activeModelId = next.activeProvider?.models?.firstOrNull() ?: "")
        } else next
        _settings.value = fixed
        prefs.edit().putString(KEY_JSON, gson.toJson(fixed)).apply()
    }

    fun updateProvider(profile: ProviderProfile) = update { s ->
        s.copy(providers = s.providers.map { if (it.id == profile.id) profile else it })
    }

    fun addProvider(profile: ProviderProfile) = update { s ->
        s.copy(
            providers = s.providers + profile,
            activeProviderId = profile.id,
            activeModelId = profile.models.firstOrNull() ?: ""
        )
    }

    fun removeProvider(id: String) = update { s ->
        val rest = s.providers.filter { it.id != id }
        if (rest.isEmpty()) return@update AppSettings.default()
        if (s.activeProviderId == id) {
            s.copy(
                providers = rest,
                activeProviderId = rest.first().id,
                activeModelId = rest.first().models.firstOrNull() ?: ""
            )
        } else s.copy(providers = rest)
    }

    private companion object {
        const val KEY_JSON = "settings_json"
    }
}
