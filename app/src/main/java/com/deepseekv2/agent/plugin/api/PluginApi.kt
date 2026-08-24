package com.deepseekv2.agent.plugin.api

/**
 * DeepSeekAgent 插件开发 API。
 *
 * 插件以「jar 文件」形式发布，内部需包含 classes.dex（Android 可加载格式），
 * 并含一个 `plugin.properties` 标记文件指定入口类（详见项目 README）。
 *
 * 约定：
 * - 插件实现 [DeepSeekPlugin]，并提供若干 [PluginTool] 作为 agent 可调用的工具；
 * - 工具通过 name/description/parameters 描述，parameters 为 JSON Schema 字符串；
 * - 运行时由 DexClassLoader 以应用类加载器为父加载器加载，接口须保持完全一致。
 */
interface PluginTool {
    /** 工具唯一名称（作为 function name 暴露给模型） */
    fun name(): String

    /** 工具用途描述（影响模型何时调用） */
    fun description(): String

    /** 参数 JSON Schema（如 {"type":"object","properties":{...},"required":[...]}） */
    fun parameters(): String

    /** 执行工具；入参为 JSON 字符串，返回结果文本（可为空字符串） */
    fun execute(arguments: String): String
}

interface DeepSeekPlugin {
    /** 插件唯一标识 */
    fun id(): String

    /** 插件显示名称 */
    fun name(): String

    /** 版本号 */
    fun version(): String

    /** 插件简介 */
    fun description(): String

    /** 该插件提供的工具列表（无工具可返回空列表） */
    fun tools(): List<PluginTool>
}
