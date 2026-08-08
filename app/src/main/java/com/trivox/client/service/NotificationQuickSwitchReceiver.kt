package com.trivox.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trivox.client.util.Diagnostics

class NotificationQuickSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        runCatching {
            when (action) {
                BEGIN -> NotificationQuickSwitchStore.begin(context)
                PREV_GROUP -> NotificationQuickSwitchStore.moveGroup(context, -1)
                NEXT_GROUP -> NotificationQuickSwitchStore.moveGroup(context, 1)
                OPEN_PROFILES -> NotificationQuickSwitchStore.openProfiles(context)
                PREV_PROFILE -> NotificationQuickSwitchStore.moveProfile(context, -1)
                NEXT_PROFILE -> NotificationQuickSwitchStore.moveProfile(context, 1)
                CANCEL -> NotificationQuickSwitchStore.clear(context)
                else -> return
            }
            NotificationSupport.refresh(context)
        }.onFailure {
            Diagnostics.recordThrowable("Notification quick-switch action", it)
            NotificationQuickSwitchStore.clear(context)
            runCatching { NotificationSupport.refresh(context) }
        }
    }

    companion object {
        const val BEGIN = "com.trivox.client.NOTIFY_SWITCH_BEGIN"
        const val PREV_GROUP = "com.trivox.client.NOTIFY_SWITCH_PREV_GROUP"
        const val NEXT_GROUP = "com.trivox.client.NOTIFY_SWITCH_NEXT_GROUP"
        const val OPEN_PROFILES = "com.trivox.client.NOTIFY_SWITCH_PROFILES"
        const val PREV_PROFILE = "com.trivox.client.NOTIFY_SWITCH_PREV_PROFILE"
        const val NEXT_PROFILE = "com.trivox.client.NOTIFY_SWITCH_NEXT_PROFILE"
        const val CANCEL = "com.trivox.client.NOTIFY_SWITCH_CANCEL"
    }
}
