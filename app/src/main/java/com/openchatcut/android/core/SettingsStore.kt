package com.openchatcut.android.core

import android.content.Context
import android.content.SharedPreferences

/** Only local-model settings: the Ollama endpoint and model name. No cloud keys. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("openchatcut_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        set(value) = prefs.edit().putString("baseUrl", value.trim().trimEnd('/')).apply()

    var model: String
        get() = prefs.getString("model", "qwen2.5:7b") ?: "qwen2.5:7b"
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var lastExportDir: String
        get() = prefs.getString("lastExportDir", "") ?: ""
        set(value) = prefs.edit().putString("lastExportDir", value).apply()
}
