package com.deepseekv2.agent.agent

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import com.deepseekv2.agent.data.api.DeepSeekClient
import com.deepseekv2.agent.data.model.FunctionSpec
import com.deepseekv2.agent.data.model.ToolSpec
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ToolOutput(val success: Boolean, val output: String)

/**
 * 内置智能体工具集：时间 / 计算器 / 网页抓取 / 设备信息 / 文件读写
 */
object ToolRegistry {

    private lateinit var appContext: Context
    private var okHttpClient: DeepSeekClient? = null
    private var workspaceUriProvider: () -> String? = { null }

    fun init(context: Context, client: DeepSeekClient, workspaceUri: () -> String? = { null }) {
        appContext = context.applicationContext
        okHttpClient = client
        workspaceUriProvider = workspaceUri
    }

    val definitions: List<ToolSpec> by lazy {
        listOf(
            ToolSpec(
                function = FunctionSpec(
                    name = "get_current_time",
                    description = "获取当前本地日期、时间与星期",
                    parameters = params()
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "calculate",
                    description = "精确计算数学表达式，支持 + - * / % ^ 括号以及 sqrt() abs()",
                    parameters = params(
                        "expression" to strProp("数学表达式，例如 (3+4)*2^3"),
                        required = listOf("expression")
                    )
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "http_fetch",
                    description = "抓取指定网页 URL 的文本内容（自动去除 HTML 标签），用于联网查询资料",
                    parameters = params(
                        "url" to strProp("要抓取的完整 URL，必须以 http:// 或 https:// 开头"),
                        required = listOf("url")
                    )
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "get_device_info",
                    description = "获取当前安卓设备的品牌型号、系统版本、电量等信息",
                    parameters = params()
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "list_files",
                    description = "列出工作区目录中的文件与子目录",
                    parameters = params(
                        "path" to strProp("相对工作区的目录路径，留空表示工作区根目录")
                    )
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "read_file",
                    description = "读取工作区中的文本文件内容",
                    parameters = params(
                        "path" to strProp("相对工作区的文件路径"),
                        required = listOf("path")
                    )
                )
            ),
            ToolSpec(
                function = FunctionSpec(
                    name = "write_file",
                    description = "将文本内容写入工作区文件（自动创建父目录，覆盖已有文件）",
                    parameters = params(
                        "path" to strProp("相对工作区的文件路径"),
                        "content" to strProp("要写入文件的文本内容"),
                        required = listOf("path", "content")
                    )
                )
            )
        )
    }

    /** 构造标准 parameters: {type, properties: {名 -> 属性schema}, required} */
    private fun params(
        vararg props: Pair<String, JsonObject>,
        required: List<String> = emptyList()
    ): JsonObject {
        val o = JsonObject()
        o.addProperty("type", "object")
        val p = JsonObject()
        for ((k, v) in props) p.add(k, v)
        o.add("properties", p)
        if (required.isNotEmpty()) {
            val r = JsonArray()
            for (name in required) r.add(name)
            o.add("required", r)
        }
        return o
    }

    private fun strProp(desc: String): JsonObject {
        val o = JsonObject()
        o.addProperty("type", "string")
        o.addProperty("description", desc)
        return o
    }

    /** 内置工具 + 插件工具（供 Agent 工具列表使用） */
    fun availableTools(): List<ToolSpec> =
        definitions + com.deepseekv2.agent.plugin.PluginManager.toolSpecs()

    fun execute(name: String, argumentsJson: String): ToolOutput = try {
        val args = try {
            JsonParser.parseString(argumentsJson.ifBlank { "{}" }).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
        when (name) {
            "get_current_time" -> ToolOutput(true, currentTime())
            "calculate" -> ToolOutput(
                true,
                "计算结果: ${Calculator.eval(args.get("expression")?.asString ?: "")}"
            )
            "http_fetch" -> fetchUrl(args.get("url")?.asString ?: "")
            "get_device_info" -> ToolOutput(true, deviceInfo())
            "list_files" -> listFiles(args.get("path")?.asString)
            "read_file" -> readFile(args.get("path")?.asString ?: "")
            "write_file" -> writeFile(
                args.get("path")?.asString ?: "",
                args.get("content")?.asString ?: ""
            )
            else -> {
                // 尝试插件工具
                com.deepseekv2.agent.plugin.PluginManager.executeTool(name, argumentsJson)
                    ?.let { return ToolOutput(true, it) }
                ToolOutput(false, "未知工具: $name")
            }
        }
    } catch (e: Exception) {
        ToolOutput(false, "工具执行出错: ${e.message}")
    }

    private fun currentTime(): String {
        val fmt =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)
        val zone = TimeZone.getDefault()
        fmt.timeZone = zone
        return "当前本地时间: ${fmt.format(Date())} (时区 ${zone.id})"
    }

    private fun fetchUrl(url: String): ToolOutput {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutput(false, "无效 URL: $url")
        }
        val client = okHttpClient?.okHttpClient ?: return ToolOutput(false, "网络客户端未初始化")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) DeepSeekAgent/1.0")
            .get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return ToolOutput(false, "HTTP ${resp.code}")
            val contentType = resp.header("Content-Type") ?: ""
            val raw = resp.body?.string() ?: ""
            val text = if (contentType.contains("html", true)) stripHtml(raw) else raw
            val clipped = if (text.length > 4000) text.take(4000) + "\n…(内容已截断)" else text
            return ToolOutput(
                true,
                if (clipped.isBlank()) "页面无文本内容 ($contentType)" else "网页内容:\n$clipped"
            )
        }
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun deviceInfo(): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return buildString {
            appendLine("设备品牌: ${Build.MANUFACTURER}")
            appendLine("设备型号: ${Build.MODEL}")
            appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (level >= 0) appendLine("电池电量: $level%")
            append("屏幕: ${appContext.resources.displayMetrics.widthPixels}x${appContext.resources.displayMetrics.heightPixels}px")
        }
    }

    // ---------------- 文件读写（用户可自定义工作区） ----------------

    /** 当前工作区：优先用户自定义的 SAF 目录，否则使用应用默认私有目录 */
    private fun currentWorkspace(): Workspace {
        val uri = workspaceUriProvider()
        return if (!uri.isNullOrBlank()) {
            SafWorkspace(appContext, Uri.parse(uri))
        } else {
            val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "workspace")
            dir.mkdirs()
            FileWorkspace(dir)
        }
    }

    private fun wsExec(block: (Workspace) -> ToolOutput): ToolOutput = try {
        block(currentWorkspace())
    } catch (e: Exception) {
        ToolOutput(false, e.message ?: "工作区操作失败")
    }

    private fun listFiles(path: String?): ToolOutput = wsExec { ws ->
        val entries = ws.list(path ?: "")
        if (entries.isEmpty()) {
            ToolOutput(true, "目录为空")
        } else {
            val sb = buildString {
                appendLine("工作区 ${ws.label} 内容:")
                for (e in entries) {
                    val kind = if (e.isDirectory) "[目录]" else "[文件] ${e.size} 字节"
                    appendLine("  ${e.name}  $kind")
                }
            }
            ToolOutput(true, sb)
        }
    }

    private fun readFile(path: String): ToolOutput = wsExec { ws ->
        val text = ws.read(path)
        val clipped = if (text.length > 8000) {
            text.take(8000) + "\n…(内容已截断，共 ${text.length} 字符)"
        } else text
        ToolOutput(true, "文件 $path 内容:\n$clipped")
    }

    private fun writeFile(path: String, content: String): ToolOutput = wsExec { ws ->
        ToolOutput(true, ws.write(path, content))
    }

    // ---------------- 递归下降算术解析器 ----------------

    object Calculator {
        fun eval(expression: String): String = Parser(expression).run {
            val v = parseExpr()
            skip()
            require(pos >= src.length) { "无法解析字符: '${src[pos]}'" }
            format(v)
        }

        private fun format(v: Double): String =
            if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15)
                v.toLong().toString() else v.toString()

        private class Parser(val src: String) {
            var pos = 0

            fun skip() {
                while (pos < src.length && src[pos].isWhitespace()) pos++
            }

            fun parseExpr(): Double {
                var v = parseTerm()
                while (true) {
                    skip()
                    if (pos < src.length && src[pos] == '+') {
                        pos++; v += parseTerm()
                    } else if (pos < src.length && src[pos] == '-') {
                        pos++; v -= parseTerm()
                    } else return v
                }
            }

            private fun parseTerm(): Double {
                var v = parseFactor()
                while (true) {
                    skip()
                    when {
                        pos < src.length && src[pos] == '*' -> {
                            pos++; v *= parseFactor()
                        }
                        pos < src.length && src[pos] == '/' -> {
                            pos++; v /= parseFactor()
                        }
                        pos < src.length && src[pos] == '%' -> {
                            pos++; v %= parseFactor()
                        }
                        else -> return v
                    }
                }
            }

            private fun parseFactor(): Double {
                val base = parseUnary()
                skip()
                if (pos < src.length && src[pos] == '^') {
                    pos++
                    return Math.pow(base, parseFactor())
                }
                return base
            }

            private fun parseUnary(): Double {
                skip()
                if (pos < src.length && src[pos] == '-') {
                    pos++; return -parseUnary()
                }
                if (pos < src.length && src[pos] == '+') {
                    pos++; return parseUnary()
                }
                return parsePrimary()
            }

            private fun parsePrimary(): Double {
                skip()
                require(pos < src.length) { "表达式不完整" }
                val c = src[pos]
                when {
                    c == '(' -> {
                        pos++
                        val v = parseExpr()
                        skip()
                        require(pos < src.length && src[pos] == ')') { "缺少右括号" }
                        pos++
                        return v
                    }
                    c.isLetter() -> {
                        val start = pos
                        while (pos < src.length && src[pos].isLetterOrDigit()) pos++
                        val fn = src.substring(start, pos).lowercase(Locale.ROOT)
                        skip()
                        require(src.getOrNull(pos) == '(') { "未知函数: $fn" }
                        pos++
                        val arg = parseExpr()
                        skip()
                        require(src.getOrNull(pos) == ')') { "缺少右括号" }
                        pos++
                        return when (fn) {
                            "sqrt" -> Math.sqrt(arg)
                            "abs" -> Math.abs(arg)
                            "sin" -> Math.sin(Math.toRadians(arg))
                            "cos" -> Math.cos(Math.toRadians(arg))
                            "log" -> Math.log10(arg)
                            "ln" -> Math.log(arg)
                            else -> throw IllegalArgumentException("未知函数: $fn")
                        }
                    }
                    else -> {
                        val start = pos
                        while (pos < src.length &&
                            (src[pos].isDigit() || src[pos] == '.')
                        ) pos++
                        require(start != pos) { "非法字符: '$c'" }
                        return src.substring(start, pos).toDouble()
                    }
                }
            }
        }
    }
}
