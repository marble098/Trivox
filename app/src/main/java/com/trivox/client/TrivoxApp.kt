package com.trivox.client

import android.app.Application
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }
}
