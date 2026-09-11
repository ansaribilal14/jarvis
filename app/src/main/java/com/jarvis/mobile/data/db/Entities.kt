package com.jarvis.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A complete agent task (request → plan → actions → outcome). */
@Entity(tableName = "tasks", indices = [Index("startedAt")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val startedAt: Long,
    var finishedAt: Long? = null,
    var status: String = "RUNNING", // RUNNING, COMPLETED, PARTIAL, FAILED, STOPPED, BLOCKED
    var resultSummary: String? = null,
    var source: String = "TEXT", // TEXT, VOICE, ROUTINE, QUICK
    var actionsUsed: Int = 0,
)

/** One executed step inside a task. */
@Entity(tableName = "steps", indices = [Index("taskId")])
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val idx: Int,
    val tool: String,
    val argsJson: String,
    var status: String, // SUCCESS, FAILED, BLOCKED, UNAVAILABLE, REQUIRES_CONFIRMATION
    var observation: String? = null,
    var verification: String? = null,
    var at: Long = System.currentTimeMillis(),
)

/** Long-lived user fact / preference the agent may recall. Never stores credentials. */
@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val source: String = "USER", // USER, AGENT, INFERRED
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Local scheduled automation. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instruction: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = 0b1111111, // bit0=Monday .. bit6=Sunday
    var enabled: Boolean = true,
    var lastRunAt: Long? = null,
)

/** Conversation / result transcript kept locally for continuity. */
@Entity(tableName = "chat", indices = [Index("taskId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val role: String, // USER, JARVIS, SYSTEM
    val content: String,
    val at: Long = System.currentTimeMillis(),
)
