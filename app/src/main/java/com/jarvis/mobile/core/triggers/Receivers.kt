package com.jarvis.mobile.core.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx

/** Re-arm TIME triggers after reboot or app update (the Easer BootUpReceiver pattern). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { TimeTriggerScheduler.armAll(JarvisApp.instance) }
                .onSuccess { Logx.i("triggers", "TIME triggers re-armed after $action") }
                .onFailure { Logx.w("triggers", "re-arm failed: ${it.message}") }
        }
    }
}

/** System battery broadcasts -> BATTERY_LOW trigger. */
class PowerTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BATTERY_LOW -> TriggerEngine.onBatteryLow(JarvisApp.instance)
            Intent.ACTION_BATTERY_OKAY -> Unit // reserved: BATTERY_OKAY trigger type if wanted
        }
    }
}
