package com.trivox.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.trivox.client.ui.UiInsets
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                    UiInsets.apply(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }
}
