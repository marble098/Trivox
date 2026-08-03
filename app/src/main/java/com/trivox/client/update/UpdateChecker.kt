package com.trivox.client.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {
    private const val API_URL =
        "https://api.github.com/repos/marble098/Trivox/releases/latest"
    private const val PREFS = "update_checker"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val worker = Executors.newSingleThreadExecutor()

    data class Result(
        val available: Boolean,
        val version: String = "",
        val url: String = "",
        val error: String = ""
    )

    fun checkIfDue(activity: Activity) {
        if (!SettingsRepository(activity).load().autoUpdateCheck) {
            return
        }
        val preferences = activity.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
        val elapsed = System.currentTimeMillis() -
            preferences.getLong("last_check", 0L)
        if (elapsed < CHECK_INTERVAL_MS) {
            return
        }
        check(activity, showCurrentResult = false, callback = null)
    }

    fun check(
        activity: Activity,
        showCurrentResult: Boolean = true,
        callback: ((Result) -> Unit)? = null
    ) {
        worker.execute {
            val result = fetchLatest()
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_check", System.currentTimeMillis())
                .apply()

            activity.runOnUiThread {
                callback?.invoke(result)
                if (activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                when {
                    result.available -> showUpdateDialog(activity, result)
                    showCurrentResult && result.error.isBlank() -> Unit
                }
            }
        }
    }

    private fun fetchLatest(): Result = runCatching {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty(
            "User-Agent",
            "Trivox/${BuildConfig.VERSION_NAME}"
        )

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("GitHub returned HTTP $responseCode")
            }
            val body = connection.inputStream
                .bufferedReader()
                .use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("tag_name")
                .ifBlank { json.optString("name") }
            val releaseUrl = json.optString("html_url")
            require(version.isNotBlank() && releaseUrl.startsWith("https://")) {
                "The latest release response is incomplete"
            }
            Result(
                available = VersionComparator.isNewer(
                    version,
                    BuildConfig.VERSION_NAME
                ),
                version = version,
                url = releaseUrl
            )
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        Diagnostics.warning("Update check failed: ${error.message}")
        Result(
            available = false,
            error = error.message.orEmpty()
        )
    }

    private fun showUpdateDialog(
        activity: Activity,
        result: Result
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available)
            .setMessage(
                activity.getString(
                    R.string.update_available_message,
                    result.version
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_release) { _, _ ->
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(result.url)
                    )
                )
            }
            .show()
    }
}
