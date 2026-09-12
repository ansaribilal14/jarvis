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
                nm.notify(NOTIF_ID, buildNotification(st))
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(task: String) {
        val notif = buildNotification(
            com.jarvis.mobile.core.agent.AgentUiState(goal = task),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** Live notification: phase · elapsed · tokens — mirrors the in-app progress strip. */
    private fun buildNotification(st: com.jarvis.mobile.core.agent.AgentUiState): Notification {
        val task = st.goal.ifBlank { "Working" }
        val phase = when (st.status) {
            com.jarvis.mobile.core.agent.AgentStatus.THINKING -> "Thinking"
            com.jarvis.mobile.core.agent.AgentStatus.ACTING -> "Acting" + (st.activeTool?.let { " · $it" } ?: "")
            com.jarvis.mobile.core.agent.AgentStatus.VERIFYING -> "Verifying"
            com.jarvis.mobile.core.agent.AgentStatus.WAITING_CONFIRMATION -> "Waiting for you"
            else -> null
        }
        val s = st.elapsedMs / 1000
        val clock = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        val gen = JarvisApp.instance.container.modelManager.llama.genState.value
        val tokenBit = if (st.status == com.jarvis.mobile.core.agent.AgentStatus.THINKING && gen.generating && gen.outTokens > 0) {
            " · ${gen.outTokens} tok"
        } else ""
        val detail = listOfNotNull(phase, if (st.elapsedMs > 0) clock else null).joinToString(" · ")
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
            .setContentText(if (detail.isBlank()) task else "$task · $detail$tokenBit")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop_agent), stopPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
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
