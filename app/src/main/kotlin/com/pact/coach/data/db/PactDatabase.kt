package com.pact.coach.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pact.coach.data.db.dao.ActionEventDao
import com.pact.coach.data.db.dao.BehaviorDao
import com.pact.coach.data.db.dao.ChecklistDao
import com.pact.coach.data.db.dao.CoachMessageDao
import com.pact.coach.data.db.dao.DailyInstructionDao
import com.pact.coach.data.db.dao.ExceptionDao
import com.pact.coach.data.db.dao.GoalDao
import com.pact.coach.data.db.dao.InstanceDao
import com.pact.coach.data.db.dao.ProgressionDao
import com.pact.coach.data.db.dao.ScheduleDao
import com.pact.coach.data.db.dao.TemplateDao
import com.pact.coach.data.db.entity.ActionEventEntity
import com.pact.coach.data.db.entity.BehaviorEntity
import com.pact.coach.data.db.entity.BehaviorExceptionEntity
import com.pact.coach.data.db.entity.BehaviorInstanceEntity
import com.pact.coach.data.db.entity.BehaviorTemplateEntity
import com.pact.coach.data.db.entity.ChecklistItemEntity
import com.pact.coach.data.db.entity.ChecklistTickEntity
import com.pact.coach.data.db.entity.CoachMessageEntity
import com.pact.coach.data.db.entity.DailyInstructionEntity
import com.pact.coach.data.db.entity.GoalEntity
import com.pact.coach.data.db.entity.ProgressionStepEntity
import com.pact.coach.data.db.entity.ScheduleEntity

/**
 * The one and only database. Everything the user creates lives here, on the device, and nowhere
 * else. There is no sync layer, no remote mirror and no telemetry.
 *
 * Destructive migration is deliberately **not** configured. If a future schema change ships
 * without a matching [Migrations] entry the app will fail loudly in development rather than
 * silently deleting months of the user's history on their phone.
 */
@Database(
    entities = [
        GoalEntity::class,
        BehaviorEntity::class,
        ScheduleEntity::class,
        DailyInstructionEntity::class,
        ChecklistItemEntity::class,
        ChecklistTickEntity::class,
        BehaviorInstanceEntity::class,
        ActionEventEntity::class,
        BehaviorExceptionEntity::class,
        BehaviorTemplateEntity::class,
        ProgressionStepEntity::class,
        CoachMessageEntity::class,
    ],
    version = PactDatabase.VERSION,
    exportSchema = true,
)
abstract class PactDatabase : RoomDatabase() {

    abstract fun goalDao(): GoalDao
    abstract fun behaviorDao(): BehaviorDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun instanceDao(): InstanceDao
    abstract fun eventDao(): ActionEventDao
    abstract fun instructionDao(): DailyInstructionDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun exceptionDao(): ExceptionDao
    abstract fun templateDao(): TemplateDao
    abstract fun progressionDao(): ProgressionDao
    abstract fun coachMessageDao(): CoachMessageDao

    companion object {
        const val VERSION = 1
        const val NAME = "pact.db"

        @Volatile
        private var instance: PactDatabase? = null

        fun get(context: Context): PactDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): PactDatabase =
            Room.databaseBuilder(context, PactDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Room only enforces foreign keys when asked, and the cascade rules on
                        // behavior deletion depend on it.
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()

        /** For tests: swap in an in-memory database. */
        fun setForTesting(database: PactDatabase?) {
            instance = database
        }
    }
}

/**
 * Schema migrations.
 *
 * Version 1 is the initial schema, so this list is empty. Every future version must add a
 * [Migration] here and a matching JSON schema under `app/schemas`, which is what the migration
 * test reads to verify the upgrade path.
 */
object Migrations {
    val ALL: Array<Migration> = arrayOf()
}
