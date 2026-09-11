package com.jarvis.mobile.service

import android.content.Intent
import android.service.quicksettings.TileService
import com.jarvis.mobile.MainActivity
import com.jarvis.mobile.util.Logx

/** Quick Settings tile: opens Jarvis in voice mode (spec: QUICK INVOCATION). */
class JarvisTileService : TileService() {

    override fun onClick() {
        super.onClick()
        Logx.i("tile", "Quick tile tapped")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_VOICE_MODE, true)
        }
        startActivityAndCollapseCompat(intent)
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                this, 7, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT,
            )
            runCatching {
                val m = qsTile?.javaClass?.methods?.firstOrNull { it.name == "startActivityAndCollapse" && it.parameterTypes.firstOrNull() == android.app.PendingIntent::class.java }
                m?.invoke(qsTile, pi) ?: startActivity(intent)
            }.onFailure { runCatching { startActivity(intent) } }
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
