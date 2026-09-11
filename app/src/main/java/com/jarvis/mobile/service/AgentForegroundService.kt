package com.jarvis.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.R
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.core.agent.AgentStatus
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground agent service (spec: FOREGROUND AGENT SERVICE).
 * Always shows: current task, active action and a STOP control.
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AgentEngine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val task = intent?.getStringExtra(EXTRA_TASK) ?: "Working"
                startAsForeground(task)
            }
        }
        // Live-update notification while running.
        scope.launch {
            AgentEngine.state.collect { st ->
                if (st.status == AgentStatus.IDLE || st.status == AgentStatus.COMPLETED ||
                    st.status == AgentStatus.FAILED || st.status == AgentStatus.STOPPED
                ) {
                    if (st.status != AgentStatus.IDLE) stopSelf()
                    return@collect
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(st.goal.ifBlank { "Working" }, st.activeTool))
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(task: String) {
        val notif = buildNotification(task, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(task: String, activeTool: String?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openPi = PendingIntent.getActivity(this, 1, open ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, JarvisApp.CH_AGENT)
            .setSmallIcon(R.drawable.ic_tile_orb)
            .setContentTitle(getString(R.string.agent_running))
            .setContentText(if (activeTool != null) "$task · $activeTool" else task)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop_agent), stopPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_AGENT"
        const val EXTRA_TASK = "task"
        const val NOTIF_ID = 41

        fun start(task: String) {
            val ctx = JarvisApp.instance
            val i = Intent(ctx, AgentForegroundService::class.java).putExtra(EXTRA_TASK, task)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { Logx.e("fgs", "start failed: ${it.message}") }
        }

        fun stopAll() {
            val ctx = JarvisApp.instance
            ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
        }
    }
}

/** Receives Confirm/Deny from the notification actions. */
class ConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        AgentEngine.answerConfirmation(id, approved)
    }

    companion object {
        const val EXTRA_ID = "confirmation_id"
        const val EXTRA_APPROVED = "approved"
    }
}
