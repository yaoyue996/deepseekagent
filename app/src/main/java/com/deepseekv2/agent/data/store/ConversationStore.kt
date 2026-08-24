package com.deepseekv2.agent.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.UUID

data class ToolCallDisplay(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String,
    @SerializedName("result") val result: String? = null,
    @SerializedName("running") val running: Boolean = true
)

data class UiMessage(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String = "",
    @SerializedName("reasoning") val reasoning: String = "",
    @SerializedName("reasoningSeconds") val reasoningSeconds: Int = 0,
    @SerializedName("toolCalls") val toolCalls: List<ToolCallDisplay> = emptyList(),
    /** 用户消息附带的本地图片路径 */
    @SerializedName("images") val images: List<String> = emptyList(),
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class Conversation(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("title") val title: String = "新对话",
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("providerId") val providerId: String = "",
    @SerializedName("modelId") val modelId: String = "",
    @SerializedName("messages") val messages: List<UiMessage> = emptyList()
)

class ConversationStore(context: Context) {

    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val gson = Gson()

    fun list(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try {
                    gson.fromJson(f.readText(), Conversation::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun load(id: String): Conversation? =
        list().firstOrNull { it.id == id }

    fun save(conversation: Conversation) {
        try {
            File(dir, "${conversation.id}.json")
                .writeText(gson.toJson(conversation))
        } catch (_: Exception) {
        }
    }

    fun delete(id: String) {
        try {
            File(dir, "$id.json").delete()
        } catch (_: Exception) {
        }
    }

    fun newConversation(): Conversation = Conversation()

    companion object {
        fun suggestTitle(text: String): String =
            text.replace('\n', ' ').trim().let {
                if (it.length > 24) it.take(24) + "…" else if (it.isEmpty()) "新对话" else it
            }
    }
}
