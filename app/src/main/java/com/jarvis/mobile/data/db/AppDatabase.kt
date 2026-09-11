package com.jarvis.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, StepEntity::class, FactEntity::class, RoutineEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun stepDao(): StepDao
    abstract fun factDao(): FactDao
    abstract fun routineDao(): RoutineDao
    abstract fun chatDao(): ChatDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jarvis.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
