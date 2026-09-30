package uk.co.james.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.database.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class)
class IntervalMigrationTest {
    @Test fun versionSevenBackfillPreservesRawEvidenceAndIndexesOverlappingIntervals()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>();val name="interval-migration.db"
        context.deleteDatabase(name)
        val schema=json.parseToJsonElement(File("schemas/uk.co.james.database.JamesDatabase/7.json").readText()).jsonObject.obj("database")
        val raw=personal("OwnershipPeriod",fields("start" to p("2026-06-01T10:00:00Z"),"end" to p("2026-08-01T10:00:00Z")),"old","manual","2026-06-01T10:00:00Z")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply {parentFile?.mkdirs()},null).use {legacy->
            schema.array("entities").forEach {element->val entity=element.jsonObject;val table=entity.text("tableName");legacy.execSQL(entity.text("createSql").replace("\${TABLE_NAME}",table));entity.array("indices").forEach {index->legacy.execSQL(index.jsonObject.text("createSql").replace("\${TABLE_NAME}",table))}}
            legacy.execSQL("INSERT INTO records (store,recordId,kind,source,timestamp,localDate,updatedAt,rawJson) VALUES ('personalRecords','old','OwnershipPeriod','manual','2026-06-01T10:00:00Z','2026-06-01','2026-06-01T10:00:00Z',?)",arrayOf(canonical(raw)))
            legacy.execSQL("INSERT INTO records (store,recordId,kind,source,timestamp,localDate,updatedAt,rawJson) VALUES ('personalRecords','corrupt','OwnershipPeriod','legacy','2026-06-01T10:00:00Z','','',NULL)")
            legacy.version=7
        }
        val db=Room.databaseBuilder(context,JamesDatabase::class.java,name).addMigrations(MIGRATION_7_8).allowMainThreadQueries().build()
        try {
            val kept=db.records().get("personalRecords","old")!!
            assertEquals(canonical(raw),kept.rawJson)
            assertEquals("2026-08-01T10:00:00Z",kept.intervalEnd)
            assertEquals("MISSING_PAYLOAD",db.records().get("personalRecords","corrupt")!!.rawPayloadIssue())
        } finally {db.close();context.deleteDatabase(name)}
    }
}
