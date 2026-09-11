package com.jarvis.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: TaskEntity): Long

    @Update
    suspend fun update(t: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: StepEntity): Long

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY idx ASC")
    suspend fun byTask(taskId: Long): List<StepEntity>

    @Query("DELETE FROM steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}

@Dao
interface FactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: FactEntity): Long

    @Query("SELECT * FROM facts ORDER BY updatedAt DESC")
    fun all(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE LOWER(key) = LOWER(:key) LIMIT 1")
    suspend fun find(key: String): FactEntity?

    @Query("SELECT * FROM facts")
    suspend fun allNow(): List<FactEntity>

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM facts")
    suspend fun deleteAll()
}

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: RoutineEntity): Long

    @Update
    suspend fun update(r: RoutineEntity)

    @Query("SELECT * FROM routines ORDER BY hour, minute")
    fun all(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE enabled = 1")
    suspend fun enabled(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun byId(id: Long): RoutineEntity?

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE routines SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE routines SET lastRunAt = :at WHERE id = :id")
    suspend fun setLastRun(id: Long, at: Long)
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: ChatMessageEntity): Long

    @Query("SELECT * FROM chat ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat")
    suspend fun count(): Int

    @Query("DELETE FROM chat WHERE id IN (SELECT id FROM chat ORDER BY at ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM chat")
    suspend fun deleteAll()
}
