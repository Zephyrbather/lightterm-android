package com.lightterm.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightterm.data.model.ServerConfigEntity

@Database(
    entities = [ServerConfigEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class LightTermDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE server_configs ADD COLUMN jumpChainJson TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE server_configs
                    ADD COLUMN lastUsedAtEpochMillis INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
        }
    }
}
