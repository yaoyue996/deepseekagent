# DeepSeekAgent 插件开发指南

DeepSeekAgent 开放了插件开发 API，允许第三方开发者以 **jar 插件** 的形式为 Agent 扩展自定义工具。本文档指导你如何开发、打包与安装插件。

## 1. 插件运行机制

- 插件以 **jar 文件** 分发，jar 内需包含 **`classes.dex`**（Android 可加载格式），而不是普通的 Java `.class` jar。
- 应用通过 `DexClassLoader` 动态加载插件，并以应用类加载器为父加载器（接口从宿主解析）。
- 插件须在 jar 根目录放置一个 **`plugin.properties`** 标记文件，用于指定入口类。

## 2. 目录约定

| 路径 | 说明 |
| --- | --- |
| `/storage/emulated/0/DSA/Plugins` | 插件目录。安装 APK 后自动创建；App 启动时自动扫描其中的 `.jar` 并加载 |

导入功能：在 App 内「设置 → 插件 → 导入插件」选择 jar 文件，会自动复制到上述目录并加载。

## 3. 插件 API

插件需实现以下接口（包名与签名必须完全一致，以保证运行时接口解析）：

```kotlin
package com.deepseekv2.agent.plugin.api

interface PluginTool {
    /** 工具唯一名称（作为 function name 暴露给模型） */
    fun name(): String

    /** 工具用途描述（影响模型何时调用） */
    fun description(): String

    /** 参数 JSON Schema，例如：
     *  {"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}
     */
    fun parameters(): String

    /** 执行工具；入参为 JSON 字符串，返回结果文本 */
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
```

> 上述接口代码可直接复制到你的插件工程中（`compileOnly` 作用域），**不要**打包进插件 jar。

## 4. 插件打包约定

一个合法的插件 jar 结构：

```
my-plugin.jar
├── classes.dex            # 编译后的 dex（含你的插件类）
├── plugin.properties      # 标记文件
└── (可选) 其他资源
```

`plugin.properties` 内容：

```properties
class=com.example.myplugin.MyPlugin
```

## 5. 开发步骤

### 5.1 编写插件类

```kotlin
package com.example.myplugin

import com.deepseekv2.agent.plugin.api.DeepSeekPlugin
import com.deepseekv2.agent.plugin.api.PluginTool

class MyPlugin : DeepSeekPlugin {
    override fun id() = "com.example.myplugin"
    override fun name() = "示例插件"
    override fun version() = "1.0.0"
    override fun description() = "一个演示工具调用方式的示例插件"

    override fun tools(): List<PluginTool> = listOf(
        object : PluginTool {
            override fun name() = "greet"
            override fun description() = "根据姓名打招呼"
            override fun parameters() =
                """{"type":"object","properties":{"name":{"type":"string","description":"姓名"}},"required":["name"]}"""
            override fun execute(arguments: String): String {
                // 自行解析 arguments JSON，例如使用 Gson 或 org.json
                val name = extractName(arguments)
                return "你好，$name！"
            }
        }
    )

    private fun extractName(arguments: String): String = TODO("用 JSON 库解析")
}
```

### 5.2 构建 dex 化的 jar

**方式 A：Gradle Android Library 模块**

1. 新建 `com.android.library` 模块，将 API 接口以 `compileOnly` 依赖加入。
2. 构建出 AAR，解压后取其 `classes.jar`（已含 `classes.dex`）。
3. 重命名为你的插件名 `.jar`。
4. 用 `jar`/`zip` 工具在根目录加入 `plugin.properties`。

**方式 B：手动 javac + d8（无需 Gradle）**

```bash
# 1. 编译（-classpath 指向 android.jar 与 API 接口编译产物）
javac -source 8 -target 8 -cp android.jar:api.jar -d classes $(find src -name '*.java')

# 2. 用 build-tools 的 d8 生成 dex
$ANDROID_HOME/build-tools/<版本>/d8 classes/**/*.class --release --output .

# 3. 打包（classes.dex 必须在 jar 根目录）
jar -cf my-plugin.jar classes.dex plugin.properties
```

> `d8` 需要 JDK 环境，可在任意机器上构建，产物为纯 jar，可跨设备使用。

## 6. 安装与使用

1. 将生成的 `.jar` 复制到 `/storage/emulated/0/DSA/Plugins/`；
2. 或在 App 内「设置 → 插件 → 导入插件」选择 jar 文件；
3. 重启 App（或点击「刷新」）后，插件提供的工具会自动加入 Agent 工具列表，模型即可调用。

## 7. 注意事项

- **接口一致性**：`DeepSeekPlugin` / `PluginTool` 的包名、方法名、签名必须与 API 完全一致，否则运行时类型转换失败。
- **Java 插件**：返回集合请使用 `java.util.List`（Kotlin 的 `List` 在字节码层即映射为 `java.util.List`）。
- **异常处理**：`execute` 抛出异常会被宿主捕获并反馈给模型，不会导致应用崩溃。
- **权限**：插件运行在宿主进程中，无独立权限；如需读写外部存储请遵循 Android 存储规范。
- **线程**：`execute` 在后台线程执行，但仍应避免长时间阻塞（建议超时 30s 内返回）。

## 8. 完整工程参考

插件 API 源码位于：`app/src/main/java/com/deepseekv2/agent/plugin/api/PluginApi.kt`

---

© 2026 DeepSeekAgent · 版本 2.4.0
