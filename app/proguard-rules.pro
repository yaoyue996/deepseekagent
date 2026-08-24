# Keep Gson model classes
-keep class com.deepseekv2.agent.data.model.** { *; }
-keep class com.deepseekv2.agent.data.prefs.AppSettings { *; }
-keep class com.deepseekv2.agent.data.store.Conversation { *; }
-keep class com.deepseekv2.agent.data.store.UiMessage { *; }
-keep class com.deepseekv2.agent.data.store.ToolCallDisplay { *; }
-keep class com.deepseekv2.agent.data.prefs.ProviderProfile { *; }
-dontwarn okhttp3.internal.platform.**
