package com.trivox.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trivox.client.util.Diagnostics

class PauseResumeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action !=
            ConnectionPauseController.ACTION_RESUME
        ) {
            return
        }

        Diagnostics.initialize(context)
        ConnectionPauseController
            .resumeNow(context)
    }
}
