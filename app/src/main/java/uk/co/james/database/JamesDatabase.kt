package uk.co.james.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import uk.co.james.core.*
import kotlinx.serialization.json.JsonObject

@Entity(tableName = "records", primaryKeys = ["store", "recordId"], indices = [Index("kind"), Index("source"), Index("localDate"), Index("timestamp"), Index(value = ["source", "externalId"]), Index(value=["kind","timestamp"]), Index(value=["source","kind","timestamp"]), Index(value=["store","timestamp"]), Index(value=["algorithmId","timestamp"]), Index(value=["algorithmId","calibrationVersion","timestamp"]), Index(value=["candidateStatus","timestamp"]), Index(value=["jamesDayId","algorithmId","timestamp"])])
data class StoredRecord(val store: String, val recordId: String, val kind: String, val source: String, val timestamp: String, val localDate: String, val updatedAt: String, val externalId: String?, val rawJson: String, val algorithmId:String?=null, val calibrationVersion:String?=null, val candidateStatus:String?=null, val jamesDayId:String?=null) {
    fun raw(): JsonObject = json.parseToJsonElement(rawJson) as JsonObject
    fun data(): JsonObject = raw().obj("data")
    companion object {
        fun from(store: String, raw: JsonObject): StoredRecord {
            val timestamp = raw.text("timestamp", raw.text("updatedAt", "1970-01-01T00:00:00Z"))
            return StoredRecord(store, keyFor(store, raw), raw.text("kind", store), raw.text("source", if (store in rutStores) "rut" else "manual"), timestamp,
                raw.text("localDate", raw.text("date", raw.obj("data").text("date", if (validTime(timestamp)) dayOf(timestamp) else ""))), raw.text("updatedAt"), raw.text("externalId").ifBlank { null }, canonical(raw), raw.obj("data").text("algorithmId").ifBlank { null }, raw.obj("data").text("calibrationVersion").ifBlank { null }, raw.obj("data").text("status").ifBlank { null }, raw.obj("data").text("jamesDayId").ifBlank { null })
        }
    }
}
@Entity(tableName = "archives")
data class ArchiveRecord(@PrimaryKey val id: String, val path: String, val digest: String, val createdAt: String, val purpose: String)
@Entity(tableName = "import_history")
data class ImportHistory(@PrimaryKey val id: String, val source: String, val timestamp: String, val found: Int, val added: Int, val duplicates: Int, val conflicts: Int, val errors: String, val originalArchiveId: String)
@Dao
interface JamesDao {
    @Query("SELECT * FROM records ORDER BY store, recordId") suspend fun all(): List<StoredRecord>
    @Query("SELECT * FROM records ORDER BY store, recordId") fun observe(): Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun between(start:String,end:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind=:kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun kindBetween(kind:String,start:String,end:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE source=:source AND kind=:kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun sourceKindBetween(source:String,kind:String,start:String,end:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE store=:store ORDER BY timestamp") suspend fun store(store:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind='CalibrationEvent' AND algorithmId=:algorithmId AND timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationEvents(algorithmId:String,start:String,end:String,limit:Int=1000):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind='CalibrationCandidate' AND algorithmId=:algorithmId AND candidateStatus IN (:statuses) ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationCandidates(algorithmId:String,statuses:List<String>,limit:Int=100):List<StoredRecord>
    @Query("SELECT * FROM records WHERE algorithmId=:algorithmId AND kind IN (:kinds) ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationRows(algorithmId:String,kinds:List<String>,limit:Int=1000):List<StoredRecord>
    // Active parameter sets are long-lived configuration, not time-series data.
    // Always retain them in the bounded scoring input set or a calibration older
    // than the history window would silently stop affecting production.
    @Query("SELECT * FROM records WHERE timestamp>=:since OR store IN ('metadata','settings','loggedEvents','eventTemplates') OR kind IN ('Routine','CalibrationProfile') ORDER BY store,recordId") suspend fun stateInputs(since:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE timestamp>=:since OR store IN ('metadata','settings','loggedEvents','eventTemplates') OR kind IN ('Routine','CalibrationProfile') ORDER BY store,recordId") fun observeStateInputs(since:String):Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE store = :store AND recordId = :id") suspend fun get(store: String, id: String): StoredRecord?
    @Query("SELECT * FROM records WHERE store=:store AND recordId IN (:ids)") suspend fun getByIds(store:String,ids:List<String>):List<StoredRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: StoredRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAll(records: List<StoredRecord>)
    @Query("DELETE FROM records WHERE store=:store AND recordId=:id") suspend fun delete(store: String, id: String)
    @Insert suspend fun archive(record: ArchiveRecord)
    @Query("SELECT * FROM archives ORDER BY createdAt DESC") suspend fun archives(): List<ArchiveRecord>
    @Query("DELETE FROM archives WHERE id=:id") suspend fun deleteArchive(id: String)
    @Insert suspend fun importHistory(record: ImportHistory)
    @Query("SELECT * FROM import_history ORDER BY timestamp DESC") fun imports(): Flow<List<ImportHistory>>
}
@Database(entities = [StoredRecord::class, ArchiveRecord::class, ImportHistory::class], version = 4, exportSchema = true)
abstract class JamesDatabase : RoomDatabase() {
 abstract fun records(): JamesDao
}

val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2){
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_kind_timestamp ON records(kind,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source_kind_timestamp ON records(source,kind,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_store_timestamp ON records(store,timestamp)")
    }
}


val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3){
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE records ADD COLUMN algorithmId TEXT")
        db.execSQL("ALTER TABLE records ADD COLUMN calibrationVersion TEXT")
        db.execSQL("ALTER TABLE records ADD COLUMN candidateStatus TEXT")
        db.execSQL("ALTER TABLE records ADD COLUMN jamesDayId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_algorithmId_timestamp ON records(algorithmId,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_algorithmId_calibrationVersion_timestamp ON records(algorithmId,calibrationVersion,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_candidateStatus_timestamp ON records(candidateStatus,timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_records_jamesDayId_algorithmId_timestamp ON records(jamesDayId,algorithmId,timestamp)")
    }
}


val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source_externalId ON records(source,externalId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_records_jamesDayId_timestamp ON records(jamesDayId,timestamp)")}}
