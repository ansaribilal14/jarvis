package com.jarvis.mobile.core.tools.impl

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.view.KeyEvent
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.accessibility.JarvisAccessibilityService
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Media, navigation, alarm and system-action tools with JARVIS's honest
 * result model: every tool reports what ACTUALLY happened, never fake success).
 */
class OpenUrlTool : Tool(
    ToolSpec(
        "open_url", "Open a web URL in the browser.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("url", "string", true, "full or bare domain, e.g. example.com")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        var url = T.str(args, "url")?.trim ?: return ToolResult.fail("Missing required arg: url.")
        if (url.startsWith("javascript:")) return ToolResult.fail("Refusing non-web URL.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return CommStart.start(i).copy(message = "Opened $url in the browser.")
    }
}

class YoutubeSearchTool : Tool(
    ToolSpec(
        "youtube_search", "Search on YouTube and open the results (plays the top result if the user taps it).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("query", "string", true, "search text")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = T.str(args, "query") ?: return ToolResult.fail("Missing required arg: query.")
        val enc = URLEncoder.encode(q, "UTF-8")
        val context = JarvisApp.instance
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc")).apply {
            setPackage("com.google.android.youtube")
        }
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            return ToolResult.ok("YouTube search results for \"$q\" are open.", Verification.UNVERIFIED)
        }
        return CommStart.start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc")))
            .copy(message = "YouTube app not found - opened results in the browser.")
    }
}

class MapsNavigateTool : Tool(
    ToolSpec(
        "maps_navigate", "Open Google Maps directions/search for a destination.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("destination", "string", true, "place name or address")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val d = T.str(args, "destination") ?: return ToolResult.fail("Missing required arg: destination.")
        val enc = URLEncoder.encode(d, "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
        return CommStart.start(i).copy(message = "Maps is open for \"$d\" - pick a route if needed.")
    }
}

class SetAlarmTool : Tool(
    ToolSpec(
        "set_alarm", "Set an alarm in the clock app (shows the alarm UI for the user to save).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("hour", "int", true, "hour 0-23 (24h format)"),
            com.jarvis.mobile.core.tools.ParamSpec("minute", "int", true, "minute 0-59"),
            com.jarvis.mobile.core.tools.ParamSpec("label", "string", false, "alarm label"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val hour = T.int(args, "hour") ?: return ToolResult.fail("Missing required arg: hour (0-23). If the user says \"6 pm\" that is 18.")
        val minute = T.int(args, "minute") ?: return ToolResult.fail("Missing required arg: minute (0-59).")
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.fail("Hour must be 0-23 and minute 0-59 (got $hour:$minute).")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            T.str(args, "label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return CommStart.start(i).copy(
            message = "Alarm set for %02d:%02d in the clock app.".format(hour, minute),
            detail = "alarm=$hour:$minute",
        )
    }
}

class SetTimerTool : Tool(
    ToolSpec(
        "set_timer", "Start a countdown timer in the clock app.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("seconds", "int", true, "duration in seconds"),
            com.jarvis.mobile.core.tools.ParamSpec("label", "string", false, "timer label"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = T.int(args, "seconds") ?: return ToolResult.fail("Missing required arg: seconds.")
        if (s <= 0 || s > 86_400) return ToolResult.fail("seconds must be 1-86400.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH_IN_SECONDS, s)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            T.str(args, "label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return CommStart.start(i).copy(
            message = "Timer started for ${s / 60}m ${s % 60}s.",
            detail = "timer=${s}s",
        )
    }
}

class MediaPlayPauseTool : Tool(
    ToolSpec("media_play_pause", "Toggle play/pause on the current media session.", emptyList, com.jarvis.mobile.core.tools.Risk.LOW),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val am = JarvisApp.instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            ToolResult.ok("Play/pause sent to the active media session.", Verification.UNVERIFIED)
        }.getOrElse { ToolResult.fail("Media key failed: ${it.message?.take(60)}") }
    }
}

class TakeScreenshotTool : Tool(
    ToolSpec("take_screenshot", "Take a screenshot of the current screen (saved by the system).", emptyList, com.jarvis.mobile.core.tools.Risk.LOW),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (Build.VERSION.SDK_INT < 30) {
            return ToolResult.unavailable("Screenshots via accessibility need Android 11+; this device runs ${Build.VERSION.SDK_INT}.", "use-device-shortcut")
        }
        val svc = JarvisAccessibilityService.INSTANCE
            ?: return ToolResult.unavailable("Accessibility service is off - screenshots need it.", "enable-accessibility")
        val ok = runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }
            .getOrDefault(false)
        return if (ok) ToolResult.ok("Screenshot captured (check the notification/shade).", Verification.UNVERIFIED)
        else ToolResult.fail("The system refused the screenshot action.")
    }
}

class ToggleDndTool : Tool(
    ToolSpec(
        "toggle_dnd", "Turn Do-Not-Disturb on or off (needs Do-Not-Disturb access once).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("on", "bool", true, "true = DND on, false = off")),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val nm = JarvisApp.instance.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val on = T.bool(args, "on") ?: return ToolResult.fail("Missing required arg: on (true/false).")
        if (!nm.isNotificationPolicyAccessGranted) {
            val i = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { JarvisApp.instance.startActivity(i) }
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "I need \"Do Not Disturb access\" once - grant it to JARVIS in the screen I opened, then ask again.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "grant-dnd-access",
            )
        }
        return runCatching {
            nm.setInterruptionFilter(
                if (on) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            val readBack = nm.currentInterruptionFilter
            ToolResult.ok(
                "Do-Not-Disturb turned ${if (on) "on" else "off"}.",
                if ((on && readBack == android.app.NotificationManager.INTERRUPTION_FILTER_NONE) ||
                    (!on && readBack == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                ) Verification.VERIFIED else Verification.UNVERIFIED,
            )
        }.getOrElse { ToolResult.fail("DND change failed: ${it.message?.take(60)}") }
    }
}

/** Shared launcher used by the intent-based tools in this file and CommTools. */
object CommStart {
    fun start(intent: Intent): ToolResult = runCatching {
        JarvisApp.instance.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ToolResult.ok("Opened.", Verification.UNVERIFIED)
    }.getOrElse { ToolResult.fail("Could not open it: ${it.message?.take(60)}") }
}
