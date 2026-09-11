package com.jarvis.mobile.core.agent

import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConfirmationRequest(
    val id: String,
    val what: String,       // what will happen
    val target: String,     // app / element / recipient
    val details: String,    // important details (exact text, values)
    val why: String,        // why confirmation is required
    val risk: String,
)

/**
 * Confirmation UX gate (spec: CONFIRMATION UX).
 * Shows WHAT / TARGET / DETAILS / WHY, never a vague "Are you sure?".
 */
object ConfirmationManager {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<ConfirmationRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val requests = _requests

    suspend fun request(
        what: String,
        target: String,
        details: String,
        why: String,
        risk: String,
        timeoutMs: Long = 120_000,
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        val req = ConfirmationRequest(id, what, target, details, why, risk)
        Logx.i("confirm", "Requested: $what → $target")
        _requests.emit(req)
        // Also surface as a heads-up notification so it works while the agent runs in another app.
        runCatching { JarvisApp.instance.container.notificationCache.emitConfirmation(req) }
        val answer = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        pending.remove(id)
        Logx.i("confirm", "Answer=${answer} for $what")
        // Clear the specific heads-up notification (not the FGS one).
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(JarvisApp.instance).cancel(req.id.hashCode())
        }
        return answer
    }

    fun answer(id: String, approved: Boolean) {
        pending[id]?.complete(approved)
    }

    fun cancelAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }
}

/** Convenience for executors. */
fun JsonObject.toDisplayString(maxLen: Int = 160): String {
    val s = toString()
    return if (s.length > maxLen) s.take(maxLen) + "…" else s
}
