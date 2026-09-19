package com.jarvis.mobile.core.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.core.skills.SkillDefinition
import com.jarvis.mobile.core.skills.SkillStore
import com.jarvis.mobile.util.Logx
import java.util.Calendar

/**
 * Daily TIME triggers via AlarmManager. The OpenTasker ladder, honestly applied:
 * exact-and-idle when the permission exists (API 31+ canUseExactAlarm), else
 * setAndAllowWhileIdle (Doze may defer by minutes - the skills screen says so).
 * One alarm per TIME skill, re-armed for the next day each time it fires.
 */
object TimeTriggerScheduler {

    private const val TAG = "triggers"
    private const val EXTRA_SKILL_ID = "skill_id"

    fun receiverIntent(context: Context, skillId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        skillId.hashCode(),
        Intent(context, TriggerAlarmReceiver::class.java).putExtra(EXTRA_SKILL_ID, skillId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** (Re)arm one skill's daily alarm. No-op when the skill has no TIME trigger. */
    fun arm(context: Context, skill: SkillDefinition) {
        val t = skill.trigger ?: return
        if (t.type != "TIME" || t.hour !in 0..23 || t.minute !in 0..59) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = receiverIntent(context, skill.id)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, t.hour)
            set(Calendar.MINUTE, t.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val at = cal.timeInMillis
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Logx.i(TAG, "TIME trigger \"${skill.name}\" armed ${if (exact) "exact" else "inexact"} at %02d:%02d"
            .format(t.hour, t.minute))
    }

    fun cancel(context: Context, skillId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(receiverIntent(context, skillId))
    }

    /** Arm every saved skill that has a TIME trigger (boot, save, update). */
    fun armAll(context: Context) {
        SkillStore.list(context).forEach { arm(context, it) }
    }
}

/** Alarm target: re-validates the skill (it may have been deleted/edited). */
class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val id = intent?.getStringExtra("skill_id") ?: return
        if (id.isNotBlank()) TriggerEngine.onTimeFired(JarvisApp.instance, id)
    }
}
