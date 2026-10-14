package com.posecam.core.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Persistent upload queue. Room is used ONLY for cloud sync state; the session catalog stays
 * file-based. The queue survives process death and reboots, and there is deliberately no
 * destructive-migration fallback: queue state is not something to silently drop.
 */
@Database(
    entities = [UploadEntity::class, CloudSessionEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        private const val NAME = "posecam-uploads.db"

        /**
         * Version 2: a recording's pipeline export is tracked per session. Sessions that exist already start at PENDING,
         * which is what queues their export (their raw files may be uploaded or even synced already; the export is
         * additional and never un-syncs a session).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN exportState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN exportNote TEXT")
            }
        }

        /**
         * Version 3: whether the backend published a synced recording to the website. Existing rows start at PENDING,
         * so the first check after the update learns the real answer for every recording already uploaded.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishedSets INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishNote TEXT")
            }
        }

        /**
         * Version 4 (marketplace): the account a recording uploads to, whether its raw data is uploaded, and what the
         * marketplace made of it. Only columns are added. Two data fixes ride along, both for rows that did not finish with
         * the old backend: a recording that was filed under a LabelNow pipe (white / black / black-white) names no campaign
         * and is not created in the cloud (so it shows "Choose a campaign to upload"), and the new backend never reports
         * publishing, so no recording waits for it. Recordings already SYNCED stay synced.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN accountId TEXT")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN rawRequired INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN marketplaceStatus TEXT")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN marketplaceReason TEXT")
                db.execSQL(
                    "UPDATE cloud_sessions SET pipe = NULL, cloudCreated = 0, permanentFailure = 0, lastError = NULL " +
                        "WHERE syncedAt IS NULL",
                )
                db.execSQL("UPDATE cloud_sessions SET publishState = 'UNSUPPORTED'")
            }
        }

        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UploadDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
