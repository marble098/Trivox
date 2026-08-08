package com.trivox.client.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.trivox.client.data.LauncherIconStyle
import com.trivox.client.util.Diagnostics

object LauncherIconManager {
    private const val PREFS = "trivox_launcher_icon_v26"
    private const val KEY_APPLIED = "applied"

    fun ensureApplied(context: Context, style: LauncherIconStyle) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_APPLIED, "") == style.name && isAliasEnabled(app, style)) {
            return
        }
        apply(app, style)
    }

    fun apply(context: Context, style: LauncherIconStyle): Boolean {
        val app = context.applicationContext
        val manager = app.packageManager
        return runCatching {
            // Enable the target first so the launcher never observes a window
            // where all aliases are disabled.
            manager.setComponentEnabledSetting(
                ComponentName(app.packageName, aliasName(app, style)),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            LauncherIconStyle.entries
                .filterNot { it == style }
                .forEach { candidate ->
                    manager.setComponentEnabledSetting(
                        ComponentName(app.packageName, aliasName(app, candidate)),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_APPLIED, style.name).apply()
            Diagnostics.info("Launcher icon changed: ${style.name}")
            true
        }.getOrElse {
            Diagnostics.recordThrowable("Launcher icon switch", it)
            false
        }
    }

    private fun isAliasEnabled(context: Context, style: LauncherIconStyle): Boolean {
        val state = runCatching {
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context.packageName, aliasName(context, style))
            )
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (style == LauncherIconStyle.DEFAULT && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    private fun aliasName(context: Context, style: LauncherIconStyle): String =
        context.packageName + when (style) {
            LauncherIconStyle.DEFAULT -> ".LauncherDefault"
            LauncherIconStyle.OCEAN -> ".LauncherOcean"
            LauncherIconStyle.AURORA -> ".LauncherAurora"
            LauncherIconStyle.MONO -> ".LauncherMono"
        }
}
