package com.trivox.client.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/" +
            "marble098/Trivox/releases/latest"
    private const val TAGS_API =
        "https://api.github.com/repos/" +
            "marble098/Trivox/tags?per_page=1"
    private const val RELEASES_PAGE =
        "https://github.com/marble098/Trivox/releases"
    private const val TAGS_PAGE =
        "https://github.com/marble098/Trivox/tags"
    private const val PREFS = "update_checker"
    private const val CHECK_INTERVAL_MS =
        24L * 60L * 60L * 1000L
    private const val FAILURE_RETRY_INTERVAL_MS =
        60L * 60L * 1000L

    private val worker =
        Executors.newSingleThreadExecutor()
    private val checkInProgress =
        AtomicBoolean(false)

    data class Result(
        val available: Boolean,
        val version: String = "",
        val url: String = "",
        val error: String = ""
    )

    fun checkIfDue(
        activity: Activity
    ) {
        if (
            !SettingsRepository(activity)
                .load()
                .autoUpdateCheck
        ) {
            return
        }

        val preferences =
            activity.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
        val now =
            System.currentTimeMillis()
        val sinceSuccess =
            now -
                preferences.getLong(
                    "last_success",
                    0L
                )
        val sinceAttempt =
            now -
                preferences.getLong(
                    "last_attempt",
                    0L
                )

        if (
            sinceSuccess <
            CHECK_INTERVAL_MS ||
            sinceAttempt <
            FAILURE_RETRY_INTERVAL_MS
        ) {
            return
        }

        check(
            activity,
            showCurrentResult = false,
            callback = null
        )
    }

    fun check(
        activity: Activity,
        showCurrentResult: Boolean = true,
        callback: ((Result) -> Unit)? = null
    ) {
        if (
            !checkInProgress.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        worker.execute {
            val result =
                try {
                    fetchLatest()
                } finally {
                    checkInProgress.set(false)
                }
            val now =
                System.currentTimeMillis()
            val editor =
                activity.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                ).edit()
                    .putLong(
                        "last_attempt",
                        now
                    )

            if (result.error.isBlank()) {
                editor.putLong(
                    "last_success",
                    now
                )
            }

            editor.apply()

            activity.runOnUiThread {
                callback?.invoke(result)

                if (
                    activity.isFinishing ||
                    activity.isDestroyed
                ) {
                    return@runOnUiThread
                }

                when {
                    result.available ->
                        showUpdateDialog(
                            activity,
                            result
                        )
                    showCurrentResult &&
                        result.error.isBlank() ->
                        Unit
                }
            }
        }
    }

    private fun fetchLatest(): Result {
        val release =
            requestJsonObject(
                LATEST_RELEASE_API
            )

        if (release.status in 200..299) {
            val json =
                release.objectValue
                    ?: return Result(
                        available = false,
                        error =
                            "Latest release response is invalid"
                    )
            val version =
                json.optString("tag_name")
                    .ifBlank {
                        json.optString("name")
                    }
            val releaseUrl =
                json.optString("html_url")
                    .takeIf {
                        it.startsWith("https://")
                    }
                    ?: RELEASES_PAGE

            if (version.isBlank()) {
                return Result(
                    available = false,
                    error =
                        "Latest release has no version"
                )
            }

            return Result(
                available =
                    VersionComparator.isNewer(
                        version,
                        BuildConfig.VERSION_NAME
                    ),
                version = version,
                url = releaseUrl
            )
        }

        if (release.status != 404) {
            val error =
                release.error.ifBlank {
                    "GitHub returned HTTP " +
                        release.status
                }
            Diagnostics.warning(
                "Update check failed: $error"
            )
            return Result(
                available = false,
                error = error
            )
        }

        val tags =
            requestJsonArray(TAGS_API)

        if (tags.status in 200..299) {
            val version =
                tags.arrayValue
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()

            if (version.isBlank()) {
                return Result(
                    available = false
                )
            }

            return Result(
                available =
                    VersionComparator.isNewer(
                        version,
                        BuildConfig.VERSION_NAME
                    ),
                version = version,
                url = TAGS_PAGE
            )
        }

        val error =
            tags.error.ifBlank {
                "GitHub returned HTTP " +
                    tags.status
            }
        Diagnostics.warning(
            "Update check failed: $error"
        )

        return Result(
            available = false,
            error = error
        )
    }

    private fun requestJsonObject(
        url: String
    ): JsonResponse =
        request(url) {
            objectValue = JSONObject(it)
        }

    private fun requestJsonArray(
        url: String
    ): JsonResponse =
        request(url) {
            arrayValue = JSONArray(it)
        }

    private fun request(
        url: String,
        parse: JsonResponse.(String) -> Unit
    ): JsonResponse {
        val connection =
            URL(url).openConnection() as
                HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "Accept",
            "application/vnd.github+json"
        )
        connection.setRequestProperty(
            "X-GitHub-Api-Version",
            "2022-11-28"
        )
        connection.setRequestProperty(
            "User-Agent",
            "Trivox/${BuildConfig.VERSION_NAME}"
        )

        return try {
            val status = connection.responseCode
            val response =
                JsonResponse(status = status)
            val stream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            val body =
                stream
                    ?.use(::readLimited)
                    .orEmpty()

            if (status in 200..299) {
                runCatching {
                    parse.invoke(
                        response,
                        body
                    )
                }.onFailure {
                    response.error =
                        it.message
                            ?: "Invalid JSON"
                }
            } else {
                response.error =
                    runCatching {
                        JSONObject(body)
                            .optString("message")
                    }.getOrDefault("")
            }

            response
        } catch (error: Throwable) {
            JsonResponse(
                status = 0,
                error =
                    error.message
                        ?: error.javaClass.simpleName
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(
        input: InputStream
    ): String {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(8 * 1024)
        var total = 0

        while (true) {
            val count =
                input.read(buffer)

            if (count < 0) {
                break
            }

            total += count

            check(
                total <=
                MAX_RESPONSE_BYTES
            ) {
                "GitHub response is too large"
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return output
            .toByteArray()
            .toString(Charsets.UTF_8)
    }

    private fun showUpdateDialog(
        activity: Activity,
        result: Result
    ) {
        AlertDialog.Builder(activity)
            .setTitle(
                R.string.update_available
            )
            .setMessage(
                activity.getString(
                    R.string.update_available_message,
                    result.version
                )
            )
            .setNegativeButton(
                R.string.cancel,
                null
            )
            .setPositiveButton(
                R.string.open_release
            ) { _, _ ->
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(result.url)
                    )
                )
            }
            .show()
    }

    private const val MAX_RESPONSE_BYTES =
        512 * 1024

    private data class JsonResponse(
        val status: Int,
        var objectValue: JSONObject? = null,
        var arrayValue: JSONArray? = null,
        var error: String = ""
    )
}
