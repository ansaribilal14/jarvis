package com.jarvis.mobile.core.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx

/**
 * Notification intelligence (spec: NOTIFICATION INTELLIGENCE).
 * Stores only a small recent ring; never persists OTP-like texts.
 */
class JarvisNotificationListener : NotificationListenerService() {

    private fun cache(): NotificationCache = JarvisApp.instance.container.notificationCache

    override fun onListenerConnected() {
        super.onListenerConnected()
        cache().setListenerAccess(true)
        Logx.i("notif", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        cache().setListenerAccess(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.isOngoing) return
        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        cache().add(
            NotificationCache.Item(
                app = n.packageName ?: "?",
                title = title.take(80),
                text = text.take(160),
                at = n.postTime,
            ),
        )
        // v2.0: NOTIFICATION triggers fire skills ({{title}}/{{text}} dynamics).
        runCatching {
            com.jarvis.mobile.core.triggers.TriggerEngine.onNotification(
                JarvisApp.instance, n.packageName ?: "?", title, text,
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
