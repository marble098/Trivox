package com.trivox.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.trivox.client.data.SettingsRepository
import com.trivox.client.ui.UiInsets
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val darkMode =
            SettingsRepository(this)
                .load()
                .darkMode

        AppCompatDelegate
            .setDefaultNightMode(
                if (darkMode) {
                    AppCompatDelegate
                        .MODE_NIGHT_YES
                } else {
                    AppCompatDelegate
                        .MODE_NIGHT_NO
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
                TRIM_MEMORY_COMPLETE ->
                Diagnostics.warning(
                    "Critical memory trim: " +
                        level
                )

            level >=
                TRIM_MEMORY_BACKGROUND ->
                Diagnostics.info(
                    "Background memory trim: " +
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
}
