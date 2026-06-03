package com.kevshupp.kevmusicplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.DatabaseConfiguration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AudioFile::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kev_music_player_db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
