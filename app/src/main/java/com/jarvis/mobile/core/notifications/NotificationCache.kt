package com.jarvis.mobile.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.agent.ConfirmationRequest
import com.jarvis.mobile.service.ConfirmationReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory recent-notification ring + confirmation notification surface. */
class NotificationCache {

    data class Item(val app: String, val title: String, val text: String, val at: Long) {
        fun render(): String {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
            return "$t [$app] $title — $text"
        }
    }

    private val buffer = ArrayDeque<Item>(40)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> get() = _items

    @Volatile private var listenerAccess = false

    fun setListenerAccess(v: Boolean) { listenerAccess = v }

    fun hasListenerAccess(): Boolean = listenerAccess

    fun snapshot(): List<Item> = _items.value

    fun add(item: Item) {
        synchronized(buffer) {
            // OTP safety: never retain verification-code style notifications.
            if (item.text.matches(Regex(".*(code|otp|pin).*", RegexOption.IGNORE_CASE)) &&
                item.text.matches(Regex(".*\\b\\d{4,8}\\b.*"))
            ) return
            if (buffer.size >= 40) buffer.removeFirst()
            buffer.addLast(item)
        }
        _items.value = buffer.toList()
    }

    /** Confirmation requests surface as heads-up notifications with Confirm/Deny actions. */
    fun emitConfirmation(req: ConfirmationRequest) {
        val context: Context = JarvisApp.instance
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentPi = PendingIntent.getActivity(
            context, 0, openApp ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val yes = PendingIntent.getBroadcast(
            context, req.id.hashCode(),
            Intent(context, ConfirmationReceiver::class.java)
                .putExtra(ConfirmationReceiver.EXTRA_ID, req.id)
                .putExtra(ConfirmationReceiver.EXTRA_APPROVED, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val no = PendingIntent.getBroadcast(
            context, req.id.hashCode() + 1,
            Intent(context, ConfirmationReceiver::class.java)
                .putExtra(ConfirmationReceiver.EXTRA_ID, req.id)
                .putExtra(ConfirmationReceiver.EXTRA_APPROVED, false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, JarvisApp.CH_ALERTS)
            .setSmallIcon(com.jarvis.mobile.R.drawable.ic_tile_orb)
            .setContentTitle("JARVIS needs confirmation")
            .setContentText("${req.what} → ${req.target}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${req.what}\nTarget: ${req.target}\n${req.details}\n\n${req.why}"))
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Confirm", yes)
            .addAction(0, "Deny", no)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(req.id.hashCode(), notif)
        }
    }

    fun cancelConfirmations() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(JarvisApp.instance).cancelAll()
        }
    }
}
