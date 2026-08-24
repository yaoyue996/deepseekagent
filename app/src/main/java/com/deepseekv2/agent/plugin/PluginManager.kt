package com.deepseekv2.agent.plugin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.deepseekv2.agent.data.model.FunctionSpec
import com.deepseekv2.agent.data.model.ToolSpec
import com.deepseekv2.agent.plugin.api.DeepSeekPlugin
import com.deepseekv2.agent.plugin.api.PluginTool
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String
)

/**
 * 插件管理器：从 /storage/emulated/0/DSA/Plugins 加载 jar 插件（内含 classes.dex），
 * 通过 plugin.properties 定位入口类，聚合为 agent 可调用的工具。
 */
object PluginManager {

    const val PLUGIN_DIR_PATH = "/storage/emulated/0/DSA/Plugins"
    private const val MARKER = "plugin.properties"

    private val plugins = mutableListOf<DeepSeekPlugin>()

    private val _info = MutableStateFlow<List<PluginInfo>>(emptyList())
    val info: StateFlow<List<PluginInfo>> = _info.asStateFlow()

    /** 创建插件目录（安装后首次启动自动生成） */
    fun ensureDirectory(): Boolean {
        val dir = File(PLUGIN_DIR_PATH)
        return dir.exists() || dir.mkdirs()
    }

    /** 重新扫描并加载目录下全部 jar 插件 */
    fun loadAll(context: Context) {
        ensureDirectory()
        val next = mutableListOf<DeepSeekPlugin>()
        val dir = File(PLUGIN_DIR_PATH)
        dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".jar") }
            ?.forEach { jar ->
                try {
                    load(context, jar)?.let { next.add(it) }
                } catch (_: Exception) {
                }
            }
        synchronized(plugins) {
            plugins.clear()
            plugins.addAll(next)
            _info.value = next.map { PluginInfo(it.id(), it.name(), it.version(), it.description()) }
        }
    }

    /** 加载单个 jar，失败返回 null */
    fun load(context: Context, jar: File): DeepSeekPlugin? {
        val entryClass = readEntryClass(jar) ?: return null
        val optimized = File(context.codeCacheDir, "dsa-plugin").apply { mkdirs() }
        val loader = DexClassLoader(
            jar.absolutePath,
            optimized.absolutePath,
            null,
            context.classLoader
        )
        val cls = loader.loadClass(entryClass)
        val instance = cls.getDeclaredConstructor().newInstance()
        return instance as? DeepSeekPlugin
    }

    /** 导入 jar（复制到插件目录并加载），成功返回插件、失败返回 null */
    fun importJar(context: Context, uri: Uri): DeepSeekPlugin? {
        ensureDirectory()
        val fileName = queryDisplayName(context, uri)
            ?: "plugin_${System.currentTimeMillis()}.jar"
        val safeName = if (fileName.lowercase().endsWith(".jar")) fileName else "$fileName.jar"
        val dest = File(PLUGIN_DIR_PATH, safeName)
        val ok = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (_: Exception) {
            false
        }
        if (!ok) return null
        val loaded = try {
            load(context, dest)
        } catch (_: Exception) {
            null
        } ?: run {
            dest.delete()
            return null
        }
        synchronized(plugins) {
            plugins.removeAll { it.id() == loaded.id() }
            plugins.add(loaded)
            _info.value = plugins.map { PluginInfo(it.id(), it.name(), it.version(), it.description()) }
        }
        return loaded
    }

    /** 聚合全部插件工具对应的 ToolSpec（供模型工具列表使用） */
    fun toolSpecs(): List<ToolSpec> = synchronized(plugins) {
        plugins.flatMap { p ->
            try {
                p.tools().map { t -> toSpec(t) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** 执行插件工具，命中返回结果，未命中返回 null */
    fun executeTool(name: String, arguments: String): String? = synchronized(plugins) {
        for (p in plugins) {
            for (t in p.tools()) {
                if (t.name() == name) return t.execute(arguments)
            }
        }
        null
    }

    private fun toSpec(t: PluginTool): ToolSpec {
        val params = try {
            JsonParser.parseString(t.parameters().ifBlank { "{}" }).asJsonObject
        } catch (_: Exception) {
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        }
        return ToolSpec(
            function = FunctionSpec(
                name = t.name(),
                description = t.description(),
                parameters = params
            )
        )
    }

    private fun readEntryClass(jar: File): String? = try {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(MARKER) ?: return null
            val props = Properties()
            props.load(zip.getInputStream(entry))
            props.getProperty("class")?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
