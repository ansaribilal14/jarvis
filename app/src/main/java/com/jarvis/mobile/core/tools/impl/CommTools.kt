package com.jarvis.mobile.core.tools.impl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.tools.Tool
import com.jarvis.mobile.core.tools.ToolContext
import com.jarvis.mobile.core.tools.ToolResult
import com.jarvis.mobile.core.tools.ToolSpec
import com.jarvis.mobile.core.tools.Verification
import kotlinx.serialization.json.JsonObject

/**
 * Communication tools, built for JARVIS's honest-and-grounded model:
 *  - 4-tier contact resolution: exact → LIKE → word-start fuzzy → nickname map
 *  - Comms tools open the real app with the content prefilled; the user always
 *    presses the final "send" button (honest: JARVIS prepares, human confirms).
 */
object ContactResolver {

    sealed class Resolution {
        data class Found(val name: String, val phone: String) : Resolution
        data class Ambiguous(val query: String, val candidates: List<String>) : Resolution
        data class NotFound(val query: String, val reason: String) : Resolution
    }

    /** Built-in relationship nicknames so "call dad"/"text mom" resolve instantly with zero AI. */
    private val NICKNAMES: Map<String, List<String>> = mapOf(
        "dad" to listOf("dad", "daddy", "papa", "father", "abba"),
        "mom" to listOf("mom", "mum", "mama", "mother", "mommy", "ammi"),
        "brother" to listOf("brother", "bhai", "bro"),
        "sister" to listOf("sister", "sis", "behen", "apa"),
        "wife" to listOf("wife", "biwi", "honey", "jaan"),
        "husband" to listOf("husband", "shohar", "hubby"),
        "son" to listOf("son", "beta"),
        "daughter" to listOf("daughter", "beti"),
        "boss" to listOf("boss", "sir", "madam"),
        "friend" to listOf("friend", " dost"),
    )

    fun resolve(rawInput: String): Resolution {
        val q = rawInput.trim
        if (q.isEmpty) return Resolution.NotFound(q, "empty query")

        // Tier 0: already a phone number?
        val digits = q.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 7 && digits.all { it.isDigit || it == '+' }) {
            return Resolution.Found(q, digits)
        }

        val context = JarvisApp.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Resolution.NotFound(q, "Contacts permission not granted")
        }

        data class Row(val name: String, val phone: String)
        val rows = mutableListOf<Row>
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext && rows.size < 800) {
                    val n = c.getString(0) ?: continue
                    val p = c.getString(1) ?: continue
                    rows.add(Row(n.trim, p.trim))
                }
            }
        }
        if (rows.isEmpty) return Resolution.NotFound(q, "no contacts readable")

        val lower = q.lowercase
        // Tier 1: exact (case-insensitive)
        rows.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return Resolution.Found(it.name, it.phone) }
        // Tier 2: LIKE
        val like = rows.filter { it.name.lowercase.contains(lower) }
        if (like.size == 1) return Resolution.Found(like[0].name, like[0].phone)
        if (like.size > 1) return Resolution.Ambiguous(q, like.take(5).map { "${it.name} ${it.phone}" })
        // Tier 3: word-start fuzzy (first/last name token starts with query)
        val fuzzy = rows.filter { r -> r.name.lowercase.split(Regex("\\s+")).any { it.startsWith(lower) } }
        if (fuzzy.size == 1) return Resolution.Found(fuzzy[0].name, fuzzy[0].phone)
        if (fuzzy.size > 1) return Resolution.Ambiguous(q, fuzzy.take(5).map { "${it.name} ${it.phone}" })
        // Tier 4: relationship nicknames ("dad" matches "Papa Ali", "Father", ...)
        val synonyms = NICKNAMES[lower] ?: listOf(lower)
        val nick = rows.filter { r ->
            val n = r.name.lowercase
            synonyms.any { s -> n.contains(s) }
        }
        if (nick.size == 1) return Resolution.Found(nick[0].name, nick[0].phone)
        if (nick.size > 1) return Resolution.Ambiguous(q, nick.take(5).map { "${it.name} ${it.phone}" })
        return Resolution.NotFound(q, "no contact matched")
    }

    fun permissionGranted: Boolean =
        ContextCompat.checkSelfPermission(JarvisApp.instance, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}

private fun start(intent: Intent): ToolResult = runCatching {
    JarvisApp.instance.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    ToolResult.ok("Prepared.", Verification.UNVERIFIED)
}.getOrElse { ToolResult.fail("Could not open the app: ${it.message?.take(60)}") }

class CallContactTool : Tool(
    ToolSpec(
        "call_contact", "Resolve a contact name or number and open the dialer with the number filled in (press call to confirm).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("contact", "string", true, "contact name, nickname like dad, or raw number")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val who = T.str(args, "contact") ?: return ToolResult.fail("Missing required arg: contact.")
        return when (val r = ContactResolver.resolve(who)) {
            is ContactResolver.Found -> {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(r.phone)}"))
                start(i).copy(
                    message = "Dialer open with ${r.name} (${r.phone}) - press call to place the call.",
                    detail = "resolved=${r.name}",
                )
            }
            is ContactResolver.Ambiguous -> ToolResult.fail(
                "Several contacts match \"$who\": ${r.candidates.joinToString("; ")}. Ask the user which one, then retry with the exact name.",
                "ask-user-to-pick-contact",
            )
            is ContactResolver.NotFound -> {
                if (!ContactResolver.permissionGranted) ToolResult(
                    com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                    "Contacts permission not granted - I cannot look up \"$who\". Grant Contacts access, or give me the raw number.",
                    Verification.COULD_NOT_VERIFY,
                    recoveryHint = "grant-contacts-permission",
                )
                else ToolResult.fail("No contact matched \"$who\" (${r.reason}). Try the raw number.")
            }
        }
    }
}

class SendSmsTool : Tool(
    ToolSpec(
        "send_sms", "Open the SMS app with the recipient and message prefilled (the user presses send).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("contact", "string", true, "contact name, nickname, or raw number"),
            com.jarvis.mobile.core.tools.ParamSpec("message", "string", true, "message body"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val who = T.str(args, "contact") ?: return ToolResult.fail("Missing required arg: contact.")
        val body = T.str(args, "message") ?: return ToolResult.fail("Missing required arg: message.")
        val phone = when (val r = ContactResolver.resolve(who)) {
            is ContactResolver.Found -> r.phone
            is ContactResolver.Ambiguous -> ToolResult.fail(
                "Several contacts match \"$who\": ${r.candidates.joinToString("; ")}. Ask the user which one.",
                "ask-user-to-pick-contact",
            ).let { return it }
            is ContactResolver.NotFound -> {
                if (!ContactResolver.permissionGranted) return ToolResult(
                    com.jarvis.mobile.core.tools.ToolStatus.REQUIRES_CONFIRMATION,
                    "Contacts permission not granted - grant Contacts access or give me the raw number.",
                    Verification.COULD_NOT_VERIFY,
                    recoveryHint = "grant-contacts-permission",
                )
                return ToolResult.fail("No contact matched \"$who\". Try the raw number.")
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
            putExtra("sms_body", body)
        }
        return start(i).copy(message = "SMS app open to $phone with the message prefilled - press send to deliver.")
    }
}

class WhatsAppMessageTool : Tool(
    ToolSpec(
        "whatsapp_message", "Open WhatsApp to a contact/number with the message text prefilled (the user or the agent presses send).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("contact", "string", false, "contact name, nickname, or raw number with country code"),
            com.jarvis.mobile.core.tools.ParamSpec("message", "string", true, "message text"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = JarvisApp.instance
        val body = T.str(args, "message") ?: return ToolResult.fail("Missing required arg: message.")
        val who = T.str(args, "contact")
        val encoded = Uri.encode(body)
        if (who != null) {
            val phone = when (val r = ContactResolver.resolve(who)) {
                is ContactResolver.Found -> r.phone.filter { it.isDigit || it == '+' }
                is ContactResolver.Ambiguous -> return ToolResult.fail(
                    "Several contacts match \"$who\": ${r.candidates.joinToString("; ")}. Ask the user which one.",
                    "ask-user-to-pick-contact",
                )
                is ContactResolver.NotFound -> null
            }
            if (phone != null && phone.length >= 7) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")).apply {
                    setPackage("com.whatsapp")
                }
                val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (ok) return ToolResult.ok("WhatsApp chat open with the message prefilled - press send to deliver.", Verification.UNVERIFIED, detail = "phone=$phone")
            }
        }
        // No resolvable number: hand the draft to WhatsApp's own share-into-chat flow.
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=$encoded"))
        return start(i).copy(message = "WhatsApp open with the text prefilled - pick the chat and send.")
    }
}

class TelegramMessageTool : Tool(
    ToolSpec(
        "telegram_message", "Open a Telegram chat by @handle or name (Telegram cannot receive prefilled text - the agent or user types it there).",
        listOf(com.jarvis.mobile.core.tools.ParamSpec("handle", "string", true, "telegram @username, channel handle, or contact name")),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val h = T.str(args, "handle")?.trim?.removePrefix("@")
            ?: return ToolResult.fail("Missing required arg: handle.")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$h")).apply {
            setPackage("org.telegram.messenger")
        }
        val context = JarvisApp.instance
        val opened = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) return ToolResult.ok("Telegram chat \"$h\" is open.", Verification.UNVERIFIED)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$h"))
        return start(web).copy(message = "Telegram app not found - opened t.me/$h in the browser.")
    }
}

class SendEmailTool : Tool(
    ToolSpec(
        "send_email", "Open the email app with a drafted message (the user presses send).",
        listOf(
            com.jarvis.mobile.core.tools.ParamSpec("to", "string", true, "recipient email address"),
            com.jarvis.mobile.core.tools.ParamSpec("subject", "string", false, "subject line"),
            com.jarvis.mobile.core.tools.ParamSpec("body", "string", false, "message body"),
        ),
        com.jarvis.mobile.core.tools.Risk.LOW,
    ),
) {
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val to = T.str(args, "to") ?: return ToolResult.fail("Missing required arg: to.")
        if (!to.contains('@')) return ToolResult.fail("\"$to\" does not look like an email address.")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}")).apply {
            T.str(args, "subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            T.str(args, "body")?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return start(i).copy(message = "Email draft open to $to - press send to deliver.")
    }
}
