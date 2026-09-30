package uk.co.james.wear.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class WearMigrationTest {
    @Test fun versionOneMigrationKeepsSnapshotAndPendingSensorEvidence()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>();val name="wear-migration.db";context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply {parentFile?.mkdirs()},null).use {legacy->
            legacy.execSQL("CREATE TABLE snapshot (id INTEGER NOT NULL PRIMARY KEY,json TEXT NOT NULL,generatedAt TEXT NOT NULL,receivedAt INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE observations (id TEXT NOT NULL PRIMARY KEY,type TEXT NOT NULL,value REAL NOT NULL,unit TEXT NOT NULL,observedAt INTEGER NOT NULL,createdAt INTEGER NOT NULL,attempts INTEGER NOT NULL)")
            legacy.execSQL("CREATE INDEX index_observations_type_observedAt ON observations(type,observedAt)")
            legacy.execSQL("INSERT INTO snapshot VALUES (1,'retained snapshot','2026-09-30T12:00:00Z',123)")
            legacy.execSQL("INSERT INTO observations VALUES ('retained','heart_rate',63,'bpm',100,200,2)")
            legacy.version=1
        }
        val db=Room.databaseBuilder(context,WearDatabase::class.java,name).addMigrations(WEAR_MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            assertEquals("retained snapshot",db.dao().currentSnapshot()!!.json)
            assertEquals(2,db.dao().pending().single().attempts)
            assertEquals(100L,db.dao().pending().single().observedAt)
            db.dao().acknowledge(listOf("retained"))
            assertTrue(db.dao().pending().isEmpty())
            assertEquals(63.0,db.dao().recent("heart_rate").first().single().value,0.0)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
