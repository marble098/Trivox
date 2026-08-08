package com.trivox.client

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.ThemeMode
import com.trivox.client.network.NetworkBufferPool
import com.trivox.client.service.ConnectionSwitchCoordinator
import com.trivox.client.ui.UiInsets
import com.trivox.client.ui.LauncherIconManager
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val startupSettings = SettingsRepository(this).load()
        val themeMode = startupSettings.themeMode
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                ThemeMode.SYSTEM ->
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT ->
                    AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK ->
                    AppCompatDelegate.MODE_NIGHT_YES
            }
        )

        Diagnostics.initialize(this)
        LauncherIconManager.ensureApplied(this, startupSettings.launcherIconStyle)
        ConnectionSwitchCoordinator.recoverPending(this)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                    UiInsets.apply(activity)
                    Diagnostics.activity("created", activity.javaClass.simpleName)
                }

                override fun onActivityStarted(activity: Activity) {
                    Diagnostics.activity("started", activity.javaClass.simpleName)
                }

                override fun onActivityResumed(activity: Activity) {
                    Diagnostics.activity("resumed", activity.javaClass.simpleName)
                }

                override fun onActivityPaused(activity: Activity) {
                    Diagnostics.activity("paused", activity.javaClass.simpleName)
                }

                override fun onActivityStopped(activity: Activity) {
                    Diagnostics.activity("stopped", activity.javaClass.simpleName)
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    Diagnostics.activity("destroyed", activity.javaClass.simpleName)
                }
            }
        )
    }

    override fun onTrimMemory(level: Int) {
        when {
            level >= MEMORY_TRIM_BACKGROUND -> {
                NetworkBufferPool.clear()
                Diagnostics.warning("Background memory trim: $level")
            }

            level >= MEMORY_TRIM_RUNNING_CRITICAL -> {
                NetworkBufferPool.clear()
                Diagnostics.warning("Critical running memory trim: $level")
            }

            else -> Diagnostics.debug("Routine memory trim: $level")
        }
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        NetworkBufferPool.clear()
        Diagnostics.warning("System reported low memory")
        super.onLowMemory()
    }

    private companion object {
        const val MEMORY_TRIM_RUNNING_CRITICAL = 15
        const val MEMORY_TRIM_BACKGROUND = 40
    }
}
