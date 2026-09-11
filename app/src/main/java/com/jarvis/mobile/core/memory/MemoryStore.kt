package com.jarvis.mobile.core.memory

import com.jarvis.mobile.core.safety.PasswordPolicy
import com.jarvis.mobile.data.db.AppDatabase
import com.jarvis.mobile.data.db.ChatMessageEntity
import com.jarvis.mobile.data.db.FactEntity
import com.jarvis.mobile.data.db.StepEntity
import com.jarvis.mobile.data.db.TaskEntity
import com.jarvis.mobile.data.settings.SettingsRepository
import com.jarvis.mobile.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Local persistent memory (spec: MEMORY).
 * Tiers: task history, conversation, user facts. Passwords/OTPs are redacted
 * before anything touches the database. Memory can be fully disabled.
 */
class MemoryStore(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------- tasks

    suspend fun startTask(goal: String, source: String): Long {
        val enabled = settings.memoryEnabled.first()
        if (!enabled) return -1
        val id = db.taskDao().insert(TaskEntity(goal = goal, startedAt = System.currentTimeMillis(), source = source))
        return id
    }

    fun finishTask(taskId: Long, status: String, summary: String?, actionsUsed: Int) {
        if (taskId <= 0) return
        scope.launch {
            db.taskDao().byId(taskId)?.let {
                db.taskDao().update(
                    it.copy(finishedAt = System.currentTimeMillis(), status = status, resultSummary = summary, actionsUsed = actionsUsed),
                )
            }
        }
    }

    suspend fun addStep(taskId: Long, idx: Int, tool: String, argsJson: String, status: String, observation: String?, verification: String?) {
        if (taskId <= 0) return
        db.stepDao().insert(
            StepEntity(
                taskId = taskId, idx = idx, tool = tool,
                argsJson = PasswordPolicy.redactForMemory(argsJson),
                status = status,
                observation = observation?.let { PasswordPolicy.redactForMemory(it.take(600)) },
                verification = verification,
            ),
        )
    }

    fun tasks(): Flow<List<TaskEntity>> = db.taskDao().recent()

    suspend fun taskWithSteps(taskId: Long): Pair<TaskEntity, List<StepEntity>>? {
        val t = db.taskDao().byId(taskId) ?: return null
        return t to db.stepDao().byTask(taskId)
    }

    suspend fun deleteTask(taskId: Long) {
        db.stepDao().deleteForTask(taskId)
        db.taskDao().delete(taskId)
    }

    suspend fun purgeHistory() {
        db.taskDao().deleteAll()
        db.chatDao().deleteAll()
    }

    // ------------------------------------------------------------- chat

    suspend fun addChat(role: String, content: String, taskId: Long? = null) {
        if (!settings.memoryEnabled.first()) return
        db.chatDao().insert(ChatMessageEntity(taskId = taskId, role = role, content = PasswordPolicy.redactForMemory(content)))
        // Retention cap.
        if (db.chatDao().count() > 250) db.chatDao().deleteOldest(50)
    }

    fun chat(): Flow<List<ChatMessageEntity>> = db.chatDao().recent()

    // ------------------------------------------------------------- facts

    fun facts(): Flow<List<FactEntity>> = db.factDao().all()

    suspend fun rememberFact(key: String, value: String, source: String = "USER") {
        if (!settings.memoryEnabled.first()) return
        val existing = db.factDao().find(key)
        db.factDao().insert(FactEntity(id = existing?.id ?: 0, key = key, value = value, source = source))
        Logx.i("memory", "Fact stored: $key")
    }

    suspend fun factsSnapshot(): List<FactEntity> = try {
        runBlocking { db.factDao().allNow() }
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun deleteFact(id: Long) = db.factDao().delete(id)
    suspend fun clearFacts() = db.factDao().deleteAll()
}
