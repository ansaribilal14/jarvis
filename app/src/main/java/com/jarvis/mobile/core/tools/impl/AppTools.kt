package com.jarvis.mobile.core.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification
import kotlinx.serialization.json.JsonObject

/** Fuzzy-resolve a user-visible app name to a launchable activity. */
object AppResolve {
    data class AppEntry(val label: String, val pkg: String)

    private var cache: List<AppEntry>? = null

    fun launchables(context: Context): List<AppEntry> {
        cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
        cache = list
        return list
    }

    fun find(context: Context, query: String): AppEntry? {
        val q = query.trim().lowercase()
        val all = launchables(context)
        all.firstOrNull { it.label.lowercase() == q }?.let { return it }
        all.firstOrNull { it.pkg.lowercase() == q || it.pkg.lowercase() == "${q}.android" }?.let { return it }
        all.firstOrNull { it.label.lowercase().startsWith(q) }?.let { return it }
        all.firstOrNull { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }?.let { return it }
        return null
    }

    /** Known alias map for common apps people call by short names. */
    private val aliases = mapOf(
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera2",
        "settings" to "com.android.settings",
        "phone" to "com.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "play store" to "com.android.vending",
        "files" to "com.google.android.documentsui",
    )

    fun findWithAliases(context: Context, query: String): AppEntry? {
        find(context, query)?.let { return it }
        val q = query.trim().lowercase()
        val pkg = aliases[q] ?: aliases.entries.firstOrNull { q.contains(it.key) }?.value
        if (pkg != null) {
            launchables(context).firstOrNull { it.pkg == pkg }?.let { return it }
            // Package may be installed but not launchable-listed yet; verify existence.
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess) return AppEntry(q, pkg)
        }
        return null
    }
}

class OpenAppTool : Tool(
    ToolSpec(
        "open_app", "Open an installed app by name (e.g. \"WhatsApp\", \"Settings\").",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("app", "string", true, "app name or package")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = T.str(args, "app") ?: return ToolResult.fail("Missing required arg: app.")
        val context = JarvisApp.instance
        val entry = AppResolve.findWithAliases(context, query)
            ?: return ToolResult.fail("No installed app matches \"$query\".", "list-apps-or-check-spelling")
        val intent = context.packageManager.getLaunchIntentForPackage(entry.pkg)
            ?: return ToolResult.fail("App \"${entry.label}\" has no launchable activity.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            val ok = T.awaitWindowChange(4000)?.first == entry.pkg || T.freshObserve(900)?.packageName == entry.pkg
            ToolResult(
                com.jarvis.mobile.core.tools.ToolStatus.SUCCESS,
                "Opened ${entry.label}.",
                if (ok) Verification.VERIFIED else Verification.UNVERIFIED,
                detail = "pkg=${entry.pkg}",
            )
        }.getOrElse { ToolResult.fail("Failed to open ${entry.label}: ${it.message?.take(80) ?: "unknown error"}") }
    }
}

class CloseAppTool : Tool(
    ToolSpec(
        "close_app", "Leave the current app and return to the home screen. Note: Android does not permit force-stopping other apps; JARVIS navigates away instead.",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("app", "string", false, "app name (optional; informational)")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = T.svc() ?: return ToolResult.unavailable("Accessibility service not connected.")
        val ok = svc.globalHome()
        return if (ok) ToolResult.ok("Left the app (home screen). Android does not allow force-stopping other apps; background processes are managed by the system.", Verification.UNVERIFIED)
        else ToolResult.fail("Could not go home.")
    }
}

class LaunchIntentTool : Tool(
    ToolSpec(
        "launch_intent", "Open a URL, dialer, map query or other standard Android intent.",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("kind", "string", true, "one of: url, search, dial, map, share"),
            com.jarvis.mobile.core.tools.ParamSpec("value", "string", true, "url / query / number / text"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val kind = T.str(args, "kind")?.lowercase() ?: return ToolResult.fail("Missing kind.")
        val value = T.str(args, "value") ?: return ToolResult.fail("Missing value.")
        val context = JarvisApp.instance
        val intent: Intent = when (kind) {
            "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(value.ensureScheme()))
            "search" -> Intent(Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", value) })
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(value)}"))
            "map" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(value)}"))
            "share" -> Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }, "Share via")
            else -> return ToolResult.fail("Unknown intent kind \"$kind\" (use url/search/dial/map/share).")
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching {
            context.startActivity(intent)
            val changed = T.awaitWindowChange(4000)
            ToolResult.ok("Launched $kind intent.", if (changed != null) Verification.VERIFIED else Verification.UNVERIFIED)
        }.getOrElse { ToolResult.fail("Intent failed: ${it.message?.take(80) ?: "no handler found"}.") }
    }

    private fun String.ensureScheme(): String = if (startsWith("http://") || startsWith("https://")) this else "https://$this"
}

class ShareContentTool : Tool(
    ToolSpec(
        "share_content", "Open the Android share sheet with plain text (e.g. send a message via any app).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("text", "string", true, "text to share"),
            com.jarvis.mobile.core.tools.ParamSpec("title", "string", false, "chooser title"),
        ),
        com.jarvis.mobile.core.tools.Risk.MEDIUM,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = T.str(args, "text") ?: return ToolResult.fail("Missing required arg: text.")
        val context = JarvisApp.instance
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, T.str(args, "title") ?: "Share via")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            T.settle(600)
            ToolResult.ok("Share sheet opened with the text. Complete the send in the target app.", Verification.UNVERIFIED, recoveryHint = "user-may-need-to-pick-target")
        }.getOrElse { ToolResult.fail("Could not open share sheet: ${it.message?.take(80)}") }
    }
}
