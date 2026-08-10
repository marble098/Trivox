package com.trivox.client.core

import android.content.Context
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject

data class CoreCapabilities(
    val protocols: Set<String>,
    val transports: Set<String>,
    val androidTun: Boolean,
    val configValidation: Boolean,
    val realDelayTest: Boolean
)

data class CoreManifest(
    val name: String,
    val adapter: String,
    val version: String,
    val artifact: String,
    val sha256: String,
    val sourceUrl: String,
    val buildDate: String,
    val abis: List<String>,
    val capabilities: List<String>,
    val prepared: Boolean
) {
    companion object {
        fun parse(text: String): CoreManifest {
            val json = JSONObject(text)
            val abis = json.optJSONArray("abis")
            val caps = json.optJSONArray("capabilities")
            return CoreManifest(
                name = json.getString("name"), adapter = json.getString("adapter"),
                version = json.getString("version"), artifact = json.getString("artifact"),
                sha256 = json.optString("sha256"), sourceUrl = json.optString("sourceUrl"),
                buildDate = json.optString("buildDate"),
                abis = if (abis == null) emptyList() else (0 until abis.length()).map { abis.getString(it) },
                capabilities = if (caps == null) emptyList() else (0 until caps.length()).map { caps.getString(it) },
                prepared = json.optBoolean("prepared")
            )
        }

        fun load(context: Context): CoreManifest = context.assets.open("core-manifest.json")
            .bufferedReader().use { parse(it.readText()) }
    }
}

data class CoreStartRequest(
    val profile: ConfigProfile,
    val settings: AppSettings,
    val mode: ConnectionMode,
    val tunFd: Int? = null,
    val bootstrapIps: List<String> = emptyList(),
    val verifyUserRoute: Boolean = true
)

data class CoreResult(val success: Boolean, val error: String = "", val data: JSONObject? = null)

interface CoreAdapter {
    val id: String
    val capabilities: CoreCapabilities
    fun isAvailable(): Boolean
    fun version(): String
    fun validate(configJson: String): CoreResult
    fun start(configJson: String, protectSocket: ((Int) -> Boolean)? = null): CoreResult
    fun stop(): CoreResult
    fun state(): CoreResult
    fun realDelay(configPath: String, timeoutSeconds: Int, url: String): CoreResult
}
