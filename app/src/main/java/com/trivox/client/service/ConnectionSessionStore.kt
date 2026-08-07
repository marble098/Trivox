package com.trivox.client.service

import android.content.Context
import com.trivox.client.data.ConnectionMode

internal object ConnectionSessionStore {
    data class Saved(
        val mode: ConnectionMode,
        val profileId: String,
        val profileName: String,
        val startedElapsed: Long
    )

    private const val PREFS =
        "connection_session"
    private const val KEY_DESIRED =
        "desired_running"
    private const val KEY_MODE =
        "mode"
    private const val KEY_PROFILE_ID =
        "profile_id"
    private const val KEY_PROFILE_NAME =
        "profile_name"
    private const val KEY_STARTED_ELAPSED =
        "started_elapsed"

    fun load(
        context: Context
    ): Saved? {
        val prefs =
            context
                .applicationContext
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        if (
            !prefs.getBoolean(
                KEY_DESIRED,
                false
            )
        ) {
            return null
        }

        val mode =
            runCatching {
                ConnectionMode.valueOf(
                    prefs.getString(
                        KEY_MODE,
                        ""
                    ).orEmpty()
                )
            }.getOrNull()
                ?: return null

        val profileId =
            prefs.getString(
                KEY_PROFILE_ID,
                null
            )
                ?.takeIf(
                    String::isNotBlank
                )
                ?: return null

        return Saved(
            mode = mode,
            profileId = profileId,
            profileName =
                prefs.getString(
                    KEY_PROFILE_NAME,
                    ""
                ).orEmpty(),
            startedElapsed =
                prefs.getLong(
                    KEY_STARTED_ELAPSED,
                    0L
                )
        )
    }

    fun markDesired(
        context: Context,
        mode: ConnectionMode,
        profileId: String,
        profileName: String = "",
        startedElapsed: Long = 0L
    ) {
        context
            .applicationContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_DESIRED,
                true
            )
            .putString(
                KEY_MODE,
                mode.name
            )
            .putString(
                KEY_PROFILE_ID,
                profileId
            )
            .putString(
                KEY_PROFILE_NAME,
                profileName
            )
            .putLong(
                KEY_STARTED_ELAPSED,
                startedElapsed
            )
            .commit()
    }

    fun markConnected(
        context: Context,
        mode: ConnectionMode,
        profileId: String,
        profileName: String,
        startedElapsed: Long
    ) {
        val current =
            load(context)

        if (
            current != null &&
            (
                current.mode != mode ||
                    current.profileId !=
                    profileId
                )
        ) {
            return
        }

        markDesired(
            context = context,
            mode = mode,
            profileId = profileId,
            profileName = profileName,
            startedElapsed =
                startedElapsed
        )
    }

    fun clear(
        context: Context
    ) {
        context
            .applicationContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .clear()
            .commit()
    }
}
