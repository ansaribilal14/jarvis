package com.jarvis.mobile.core.routines

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.mobile.core.agent.AgentEngine
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Local routine engine (spec: ROUTINES / SCHEDULED AGENTS).
 * WorkManager-scheduled daily instruction runs. Routines are transparent and
 * can only execute LOW-risk tools (engine blocks confirmations in this mode).
 */
class RoutineManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val memory: com.jarvis.mobile.core.memory.MemoryStore,
) {
    fun schedule(routine: com.jarvis.mobile.data.db.RoutineEntity) {
        val wm = WorkManager.getInstance(context)
        val delay = nextDelayMs(routine.hour, routine.minute)
        val request = PeriodicWorkRequestBuilder<RoutineWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("routineId" to routine.id))
            .addTag(ROUTINE_TAG)
            .build()
        wm.enqueueUniquePeriodicWork("routine-${routine.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
        Logx.i("routines", "Scheduled \"${routine.name}\" in ${delay / 3600000}h")
    }

    fun cancel(routineId: Long) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork("routine-$routineId") }
    }

    private fun nextDelayMs(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }

    companion object {
        const val ROUTINE_TAG = "jarvis-routine"
    }
}

/** Executes one routine run. LOW-risk only; day-of-week aware. */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = com.jarvis.mobile.JarvisApp.instance
        val id = inputData.getLong("routineId", -1)
        if (id <= 0) return Result.failure()
        val routine = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.container.db.routineDao().byId(id)
        } ?: return Result.failure()
        if (!routine.enabled) return Result.success()

        // Day-of-week gate (bit0=Monday..bit6=Sunday).
        val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (routine.daysMask and (1 shl today) == 0) return Result.success()

        Logx.i("routines", "Running routine \"${routine.name}\"")
        app.container.memory.addChat("SYSTEM", "Routine: ${routine.name}", null)
        AgentEngine.runGoal(routine.instruction, source = "ROUTINE")
        app.container.db.routineDao().setLastRun(id, System.currentTimeMillis())
        return Result.success()
    }
}
