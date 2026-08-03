package com.trivox.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.ThemeMode
import com.trivox.client.ui.UiInsets
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val themeMode =
            SettingsRepository(this)
                .load()
                .themeMode

        AppCompatDelegate
            .setDefaultNightMode(
                if (
                    themeMode ==
                    ThemeMode.LIGHT
                ) {
                    AppCompatDelegate
                        .MODE_NIGHT_NO
                } else {
                    AppCompatDelegate
                        .MODE_NIGHT_YES
                }
            )

        Diagnostics.initialize(this)

        registerActivityLifecycleCallbacks(
            object :
                ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState:
                        Bundle?
                ) {
                    UiInsets.apply(activity)
                    Diagnostics.activity(
                        "created",
                        activity
                            .javaClass
                            .simpleName
                    )
                }

                override fun onActivityStarted(
                    activity: Activity
                ) {
                    Diagnostics.activity(
                        "started",
                        activity
                            .javaClass
                            .simpleName
                    )
                }

                override fun onActivityResumed(
                    activity: Activity
                ) {
                    Diagnostics.activity(
                        "resumed",
                        activity
                            .javaClass
                            .simpleName
                    )
                }

                override fun onActivityPaused(
                    activity: Activity
                ) {
                    Diagnostics.activity(
                        "paused",
                        activity
                            .javaClass
                            .simpleName
                    )
                }

                override fun onActivityStopped(
                    activity: Activity
                ) {
                    Diagnostics.activity(
                        "stopped",
                        activity
                            .javaClass
                            .simpleName
                    )
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(
                    activity: Activity
                ) {
                    Diagnostics.activity(
                        "destroyed",
                        activity
                            .javaClass
                            .simpleName
                    )
                }
            }
        )
    }

    override fun onTrimMemory(
        level: Int
    ) {
        when {
            level >=
                MEMORY_TRIM_BACKGROUND ->
                Diagnostics.warning(
                    "Background memory trim: " +
                        level
                )

            level >=
                MEMORY_TRIM_RUNNING_CRITICAL ->
                Diagnostics.warning(
                    "Critical running memory trim: " +
                        level
                )

            else ->
                Diagnostics.debug(
                    "Routine memory trim: " +
                        level
                )
        }

        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        Diagnostics.warning(
            "System reported low memory"
        )
        super.onLowMemory()
    }

    private companion object {
        // Stable ComponentCallbacks2 levels. Android deprecated the symbolic
        // constants but still delivers these numeric levels to onTrimMemory.
        const val MEMORY_TRIM_RUNNING_CRITICAL = 15
        const val MEMORY_TRIM_BACKGROUND = 40
    }
}
