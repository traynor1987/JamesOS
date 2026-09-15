package uk.co.james

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.database.JamesDatabase
import uk.co.james.database.MIGRATION_1_2
import uk.co.james.database.MIGRATION_2_3
import uk.co.james.database.MIGRATION_3_4

@RunWith(AndroidJUnit4::class)
class P1PersistenceInstrumentedTest {
    @Test fun releasedSchemaMigratesWithoutDataLoss(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name).apply {parentFile?.mkdirs()}
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use {legacy->
            legacy.execSQL("CREATE TABLE records (store TEXT NOT NULL, recordId TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, timestamp TEXT NOT NULL, localDate TEXT NOT NULL, updatedAt TEXT NOT NULL, externalId TEXT, rawJson TEXT NOT NULL, PRIMARY KEY(store,recordId))")
            legacy.execSQL("CREATE INDEX index_records_kind ON records(kind)")
            legacy.execSQL("CREATE INDEX index_records_source ON records(source)")
            legacy.execSQL("CREATE INDEX index_records_localDate ON records(localDate)")
            legacy.execSQL("CREATE INDEX index_records_timestamp ON records(timestamp)")
            legacy.execSQL("CREATE INDEX index_records_source_externalId ON records(source,externalId)")
            legacy.execSQL("CREATE TABLE archives (id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, digest TEXT NOT NULL, createdAt TEXT NOT NULL, purpose TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE import_history (id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, timestamp TEXT NOT NULL, found INTEGER NOT NULL, added INTEGER NOT NULL, duplicates INTEGER NOT NULL, conflicts INTEGER NOT NULL, errors TEXT NOT NULL, originalArchiveId TEXT NOT NULL)")
            legacy.execSQL("INSERT INTO records VALUES ('metadata','kept','Metadata','james','2026-09-12T00:00:00Z','2026-09-12','2026-09-12T00:00:00Z',NULL,'{}')")
            legacy.version=1
        }
        val migrated=Room.databaseBuilder(context,JamesDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4).build()
        try {runBlocking {assertNotNull(migrated.records().get("metadata","kept"))}} finally {migrated.close()}
        context.deleteDatabase(name)
    }

    @Test fun roomBatchAndBoundedRangeSurviveRepositoryReconstruction() {
        runBlocking {
            val context=ApplicationProvider.getApplicationContext<Context>()
            val name="p1-${System.nanoTime()}.db"
            val at=Instant.parse("2026-09-12T12:00:00Z")
            val first=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
            try {
                val repo=JamesRepository(context,first)
                repo.externalBatch((0 until 100).map {i->personal("HealthMetric",fields("metric" to p("HRV"),"value" to p(i)),"i-$i","whoop",at.minusSeconds(i.toLong()).toString()).changed("externalId" to p("i-$i"))})
            } finally {first.close()}
            val reopened=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
            try {assertEquals(61,reopened.records().between(at.minusSeconds(60).toString(),at.toString()).size)} finally {reopened.close()}
            context.deleteDatabase(name)
        }
    }
}