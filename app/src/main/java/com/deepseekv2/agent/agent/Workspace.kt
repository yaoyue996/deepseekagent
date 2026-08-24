package com.deepseekv2.agent.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class WsEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * Agent 文件工作区抽象：
 * - [FileWorkspace] 默认目录（应用私有，直接文件系统读写）
 * - [SafWorkspace] 用户通过系统文件夹选择器授权的目录（SAF 树 URI）
 * 两者统一以「工作区内相对路径」读写，禁止越界。
 */
sealed class Workspace {
    abstract val label: String

    abstract fun list(rel: String): List<WsEntry>
    abstract fun read(rel: String): String
    /** 写入成功后返回描述信息，失败抛出异常 */
    abstract fun write(rel: String, content: String): String
}

class FileWorkspace(private val root: File) : Workspace() {

    override val label: String get() = root.absolutePath

    private fun resolve(rel: String): File {
        val base = root.canonicalFile
        val cleaned = rel.trim().trimStart('/')
        val target = if (cleaned.isEmpty()) base else File(base, cleaned).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "非法路径：超出工作区范围"
        }
        return target
    }

    override fun list(rel: String): List<WsEntry> {
        val dir = resolve(rel)
        require(dir.exists()) { "目录不存在" }
        require(dir.isDirectory) { "不是目录" }
        return dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { WsEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length()) }
            ?: emptyList()
    }

    override fun read(rel: String): String {
        val f = resolve(rel)
        require(f.exists()) { "文件不存在" }
        require(f.isFile) { "是目录而非文件" }
        return f.readText()
    }

    override fun write(rel: String, content: String): String {
        require(rel.isNotBlank()) { "path 不能为空" }
        val f = resolve(rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "已写入文件 $rel（${content.length} 字符）"
    }
}

class SafWorkspace(
    private val context: Context,
    private val treeUri: Uri
) : Workspace() {

    override val label: String get() = treeUri.toString()

    private fun rootDoc(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问工作区目录（权限可能已失效，请重新选择）")

    private fun resolve(rel: String): DocumentFile {
        val cleaned = rel.trim().trimStart('/')
        var cur = rootDoc()
        if (cleaned.isEmpty()) return cur
        for (seg in cleaned.split('/').filter { it.isNotBlank() }) {
            if (seg == "..") throw IllegalArgumentException("非法路径：包含 ..")
            cur = cur.findFile(seg) ?: throw IllegalArgumentException("路径不存在: $rel")
        }
        return cur
    }

    private fun resolveForWrite(rel: String): DocumentFile {
        val cleaned = rel.trim().trimStart('/')
        require(cleaned.isNotBlank()) { "path 不能为空" }
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "非法路径：包含 .." }
        var cur = rootDoc()
        for (seg in parts.dropLast(1)) {
            cur = cur.findFile(seg) ?: cur.createDirectory(seg)
            ?: throw IllegalStateException("无法创建目录: $seg")
        }
        val name = parts.last()
        return cur.findFile(name)
            ?: cur.createFile("text/plain", name)
            ?: throw IllegalStateException("无法创建文件: $name")
    }

    override fun list(rel: String): List<WsEntry> {
        val dir = resolve(rel)
        require(dir.isDirectory) { "不是目录" }
        return dir.listFiles().map {
            WsEntry(it.name ?: "(未命名)", it.isDirectory, it.length())
        }
    }

    override fun read(rel: String): String {
        val doc = resolve(rel)
        require(!doc.isDirectory) { "是目录而非文件" }
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IllegalStateException("无法打开文件")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun write(rel: String, content: String): String {
        val doc = resolveForWrite(rel)
        val out = context.contentResolver.openOutputStream(doc.uri, "wt")
            ?: throw IllegalStateException("无法写入文件")
        out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return "已写入文件 $rel（${content.length} 字符）"
    }
}
