package com.trivox.client.service

import android.content.Context
import com.trivox.client.data.ConnectionMode

internal object QuickConnectStore {
    data class Last(
        val mode: ConnectionMode,
        val profileId: String,
        val profileName: String
    )

    private const val PREFS = "quick_connect"

    fun save(
        context: Context,
        mode: ConnectionMode,
        profileId: String,
        profileName: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("mode", mode.name)
            .putString("profile_id", profileId)
            .putString("profile_name", profileName)
            .commit()
    }

    fun load(context: Context): Last? {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profileId = preferences
            .getString("profile_id", null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val mode = runCatching {
            ConnectionMode.valueOf(
                preferences.getString("mode", "").orEmpty()
            )
        }.getOrNull() ?: return null

        return Last(
            mode = mode,
            profileId = profileId,
            profileName = preferences
                .getString("profile_name", "")
                .orEmpty()
        )
    }
}
