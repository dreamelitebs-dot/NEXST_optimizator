package ru.booster.network.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.booster.network.core.model.GameInfo

@Database(entities = [GameInfo::class], version = 1, exportSchema = false)
abstract class BoosterDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao

    companion object {
        @Volatile private var INSTANCE: BoosterDatabase? = null

        fun get(context: Context): BoosterDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    BoosterDatabase::class.java,
                    "booster.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
