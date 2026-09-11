package com.jarvis.mobile.core.tools.impl

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.notifications.NotificationCache
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import java.util.TimeZone

class ReadNotificationsTool : Tool(
    ToolSpec(
        "read_notifications", "Read recent notifications (requires notification access permission).",
        emptyList(),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val cache = JarvisApp.instance.container.notificationCache
        if (!cache.hasListenerAccess()) {
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "Notification access is not enabled. Enable \"JARVIS\" in Settings → Notification access, then ask again.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "open-notification-listener-settings",
            )
        }
        val items = cache.snapshot()
        if (items.isEmpty()) return ToolResult.ok("No recent notifications.", Verification.VERIFIED, detail = "0 notifications")
        val det = items.take(12).joinToString("\n") { "• [${it.app}] ${it.title} — ${it.text.take(70)}" }
        return ToolResult.ok("${items.size} recent notification(s).", Verification.VERIFIED, detail = det)
    }
}

class GetLocationTool : Tool(
    ToolSpec(
        "get_location", "Get the device's last known location (approximate).",
        emptyList(),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "Location permission not granted. I will not access location without it.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "grant-location-permission",
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
        val last = providers.asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return ToolResult.unavailable("No recent location fix available (location may be off).", "ask-user-to-enable-location")
        return ToolResult.ok(
            "Location: %.4f, %.4f (accuracy ±%.0fm, age %d min).".format(last.latitude, last.longitude, last.accuracy, (System.currentTimeMillis() - last.time) / 60000),
            Verification.VERIFIED,
        )
    }
}

class ReadCalendarTool : Tool(
    ToolSpec(
        "read_calendar", "Read upcoming calendar events for the next N days.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("days", "int", false, "how many days ahead (default 2)")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "Calendar permission not granted.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "grant-calendar-permission",
            )
        }
        val days = (T.int(args, "days") ?: 2).coerceIn(1, 14)
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = StringBuilder()
        runCatching {
            context.contentResolver.query(
                uri, proj,
                "${CalendarContract.Instances.BEGIN} >= ? AND ${CalendarContract.Instances.BEGIN} <= ?",
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 20) {
                    val title = c.getString(0) ?: "(untitled)"
                    val at = c.getLong(1)
                    val loc = c.getString(2)
                    val t = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
                    out.append("• $t $title").append(if (loc.isNullOrBlank()) "" else " @$loc").append('\n')
                    n++
                }
            }
        }.onFailure { return ToolResult.fail("Calendar read failed: ${it.message?.take(60)}") }
        return if (out.isBlank()) ToolResult.ok("No events in the next $days day(s).", Verification.VERIFIED, detail = "empty")
        else ToolResult.ok("Upcoming events:", Verification.VERIFIED, detail = out.toString().trim())
    }
}

class CreateCalendarEventTool : Tool(
    ToolSpec(
        "create_calendar_event", "Create a calendar event.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("title", "string", true, "event title"),
            com.jarvis.mobile.core.tools.ParamSpec("startEpochMs", "int", true, "start time (epoch ms)"),
            com.jarvis.mobile.core.tools.ParamSpec("durationMin", "int", false, "duration minutes (default 30)"),
        ),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                "Calendar permission not granted.",
                Verification.COULD_NOT_VERIFY,
                recoveryHint = "grant-calendar-permission",
            )
        }
        val title = T.str(args, "title") ?: return ToolResult.fail("Missing required arg: title.")
        val start = T.int(args, "startEpochMs")?.toLong() ?: T.dbl(args, "startEpochMs")?.toLong()
            ?: return ToolResult.fail("Missing required arg: startEpochMs (epoch ms). If the user says \"tomorrow 5pm\", compute it from the current date.")
        val durationMin = (T.int(args, "durationMin") ?: 30).coerceIn(5, 720)
        val end = start + durationMin * 60_000L

        // Duplicate protection (spec: DUPLICATE ACTION PROTECTION).
        val dup = runCatching { queryDuplicate(context, title, start) }.getOrDefault(false)
        if (dup) return ToolResult.ok("Event \"$title\" already exists at that time - not creating a duplicate.", Verification.VERIFIED, detail = "duplicate-guard")

        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult.fail("Calendar provider refused the insert.")
            val id = ContentUris.parseId(uri)
            ToolResult.ok("Created event \"$title\" ($durationMin min).", Verification.VERIFIED, detail = "eventId=$id")
        }.getOrElse { ToolResult.fail("Event creation failed: ${it.message?.take(80)}") }
    }

    private fun queryDuplicate(context: Context, title: String, start: Long): Boolean {
        val proj = arrayOf(CalendarContract.Events._ID)
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj,
            "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} = ?",
            arrayOf(title, start.toString()), null,
        )?.use { return it.count > 0 }
        return false
    }
}

class FindFileTool : Tool(
    ToolSpec(
        "find_file", "Find files on the device by name (searches Downloads and other media).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("name", "string", true, "filename or part of it"),
            com.jarvis.mobile.core.tools.ParamSpec("downloadedSinceDays", "int", false, "only files added in the last N days (default 7)"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val name = T.str(args, "name") ?: return ToolResult.fail("Missing required arg: name.")
        val sinceDays = (T.int(args, "downloadedSinceDays") ?: 7).coerceIn(1, 365)
        val since = System.currentTimeMillis() - sinceDays * 86_400_000L

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE)
        val results = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                collection, proj,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf("%$name%", (since / 1000).toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && results.size < 10) {
                    results.add(
                        "• ${c.getString(1)} (${c.getLong(3) / 1024} KB, id=${c.getLong(0)})",
                    )
                }
            }
        }
        if (results.isEmpty()) {
            // Broader fallback across all files collection.
            runCatching {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"), proj,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$name%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && results.size < 10) {
                        results.add("• ${c.getString(1)} (${c.getLong(3) / 1024} KB, id=${c.getLong(0)})")
                    }
                }
            }
        }
        return if (results.isEmpty()) {
            ToolResult.fail("No files matching \"$name\" found in the last $sinceDays day(s).", "try-different-name")
        } else {
            ToolResult.ok("Found ${results.size} file(s):", Verification.VERIFIED, detail = results.joinToString("\n"))
        }
    }
}

class OpenFileTool : Tool(
    ToolSpec(
        "open_file", "Open a file from MediaStore by its id (from find_file).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("fileId", "int", true, "the numeric file id from find_file output")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val id = (T.int(args, "fileId") ?: T.dbl(args, "fileId")?.toInt()) ?: return ToolResult.fail("Missing required arg: fileId.")
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toLong())
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult.ok("Opened the file.", Verification.VERIFIED)
        }.getOrElse { ToolResult.fail("No app can open this file type.") }
    }
}

class ShareFileTool : Tool(
    ToolSpec(
        "share_file", "Share a file from MediaStore by its id (from find_file) via the Android share sheet.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("fileId", "int", true, "numeric file id from find_file"),
            com.jarvis.mobile.core.tools.ParamSpec("text", "string", false, "optional message to include"),
        ),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val id = (T.int(args, "fileId") ?: T.dbl(args, "fileId")?.toInt()) ?: return ToolResult.fail("Missing required arg: fileId.")
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toLong())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            T.str(args, "text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                "Share sheet opened with the file. Pick the target app and send.",
                Verification.UNVERIFIED,
                null,
                "user-completes-send-in-app",
            )
        }.getOrElse { ToolResult.fail("Could not open share sheet for the file.") }
    }
}

class ListAppsTool : Tool(
    ToolSpec("list_apps", "List installed apps (names JARVIS can open).", emptyList(), com.jarvis.mobile.core.tools.Risk.LOW),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apps = AppResolve.launchables(JarvisApp.instance)
            .sortedBy { it.label.lowercase() }
        val det = apps.take(25).joinToString(", ") { it.label }
        return ToolResult.ok("${apps.size} apps installed.", Verification.VERIFIED, detail = det + if (apps.size > 25) " …" else "")
    }
}
