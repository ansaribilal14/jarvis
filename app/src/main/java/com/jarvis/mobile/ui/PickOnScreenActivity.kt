package com.jarvis.mobile.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.skills.ElementTarget
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Pick on screen" - the skills-v3 primary targeting flow (docs/SKILLS_V3.md).
 *
 * Works in EVERY app (including games and canvas views) because it never needs
 * the target app's cooperation: JARVIS screenshots the screen (public
 * AccessibilityService API, capability already declared), the user taps the
 * exact spot on the screenshot, and JARVIS probes the accessibility windows
 * for a node under that point to prefill a semantic descriptor when one is
 * available. The stored target uses FRACTIONAL coordinates, so rotation, DPI
 * and device changes cannot break replay.
 *
 * Flow: builder -> [request] (posts a Pick notification, JARVIS backgrounds) ->
 * user opens the target screen -> taps the notification -> this activity
 * screenshots + shows the picker -> tap -> confirm -> [PickResultBus].
 */
class PickOnScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "pick-screen"
        private const val PICK_NOTIFICATION_ID = 10011
        private const val ACTION_CANCEL = "com.jarvis.mobile.PICK_CANCEL"

        /** Called by the builder: hand off to the notification flow. */
        fun request(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val pickIntent = Intent(context, PickOnScreenActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 3, pickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, JarvisApp.CH_AGENT)
                .setContentTitle("Pick the target element")
                .setContentText("Open the screen with the button/field, then tap here")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(PICK_NOTIFICATION_ID, n) }
        }

        fun cancelNotification(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(PICK_NOTIFICATION_ID) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        PickResultBus.result.value = null
        val svc = JarvisAccessibilityService.INSTANCE
        if (svc == null) {
            android.widget.Toast.makeText(this, "Enable JARVIS in Settings → Accessibility first", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            android.widget.Toast.makeText(this, "Pick on screen needs Android 11+ (screenshot API)", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val pkgAtCapture = svc.currentPackage()
        // Brief pause so the notification shade is gone before the capture.
        android.os.Handler(mainLooper).postDelayed({
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            scope.launch {
                val bmp = runCatching { svc.takeScreenshot() }.getOrNull()
                cancelNotification(this@PickOnScreenActivity)
                if (bmp == null) {
                    android.widget.Toast.makeText(this@PickOnScreenActivity, "Screenshot failed - try again", android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                setContent {
                    PickScreen(
                        bitmap = bmp,
                        pkgAtCapture = pkgAtCapture,
                        svc = svc,
                        onDone = { finish() },
                    )
                }
            }
        }, 450)
    }
}

/** Hand-off from the picker back to the skill builder. */
object PickResultBus {
    val result = mutableStateOf<PickResult?>(null)
    data class PickResult(val target: ElementTarget, val appLabel: String?)
}

@Composable
private fun PickScreen(
    bitmap: Bitmap,
    pkgAtCapture: String?,
    svc: JarvisAccessibilityService,
    onDone: () -> Unit,
) {
    var picked by remember { mutableStateOf<Pair<Float, Float>?>(null) } // fractions
    var probe by remember { mutableStateOf<JarvisAccessibilityService.ElementProbe?>(null) }
    var confirmOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val containerW = maxWidth
        val containerH = maxHeight
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Screenshot to pick from",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fx = offset.x / size.width
                        val fy = offset.y / size.height
                        picked = fx to fy
                        // Probe the (possibly occluded) app windows for a node under the point.
                        val px = (fx * bitmap.width).toInt()
                        val py = (fy * bitmap.height).toInt()
                        probe = runCatching { svc.describeNodeAtPoint(px, py) }.getOrNull()
                        confirmOpen = true
                    }
                },
            contentScale = ContentScale.FillBounds,
        )
        picked?.let { (fx, fy) ->
            Box(
                Modifier
                    .absoluteOffset {
                        IntOffset(
                            (fx * containerW.toPx()).toInt() - 26.dp.roundToPx(),
                            (fy * containerH.toPx()).toInt() - 26.dp.roundToPx(),
                        )
                    }
                    .size(52.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x33E4574F), RoundedCornerShape(50))
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(8.dp)
                        .background(Color(0xFFE4574F), RoundedCornerShape(50))
                )
            }
        }
        Text(
            "Tap the button or field you want this action to use",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp)
                .background(Color(0xB3000000), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (confirmOpen && picked != null) {
        val (fx, fy) = picked!!
        val label = probe?.text ?: probe?.desc ?: probe?.viewId?.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text(if (label != null) "Use \"$label\"?" else "Use this point?") },
            text = {
                Column {
                    Text(
                        buildString {
                            append("Point: ${(fx * 100).toInt()}%, ${(fy * 100).toInt()}% of screen")
                            probe?.pkg?.let { append("\nApp: ${it.substringBefore('.')}") }
                            if (probe?.text != null) append("\nText: \"${probe!!.text}\"")
                            if (probe?.desc != null) append("\nDesc: \"${probe!!.desc}\"")
                            if (probe?.viewId != null) append("\nId: ${probe!!.viewId}")
                            if (probe == null) append("\n\nNo element data here (the app hides its controls) - JARVIS will tap this exact point, which works in every app.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = ElementTarget(
                        mode = if (probe?.text != null || probe?.desc != null || probe?.viewId != null) "MIXED" else "POINT",
                        text = probe?.text?.take(80),
                        desc = probe?.desc?.take(60),
                        viewId = probe?.viewId,
                        className = probe?.className,
                        fx = fx,
                        fy = fy,
                        pkg = probe?.pkg ?: pkgAtCapture,
                    )
                    PickResultBus.result.value = PickResultBus.PickResult(target, probe?.pkg)
                    Logx.i("pick-screen", "picked ${target.describe()} pkg=${target.pkg}")
                    onDone()
                }) { Text("Use this target") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Pick again") }
            },
        )
    }
}
