package com.trivox.client

import android.app.Application
import com.trivox.client.network.NetworkBufferPool
import com.trivox.client.util.Diagnostics

class TrivoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        if (level >= MEMORY_TRIM_RUNNING_CRITICAL) {
            NetworkBufferPool.clear()
            Diagnostics.warning("Memory trim: $level")
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
    }
}
