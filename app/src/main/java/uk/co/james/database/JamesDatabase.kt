package uk.co.james.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import uk.co.james.core.*
import kotlinx.serialization.json.JsonObject

private data class PreparedStoredRaw(val value:JsonObject,val issue:String?)

@Entity(tableName = "records", primaryKeys = ["store", "recordId"], indices = [Index("kind"), Index("source"), Index("localDate"), Index("timestamp"), Index(value = ["source", "externalId"]), Index(value=["source","sourceShiftId"]), Index(value=["kind","timestamp"]), Index(value=["source","kind","timestamp"]), Index(value=["store","timestamp"]), Index(value=["algorithmId","timestamp"]), Index(value=["algorithmId","calibrationVersion","timestamp"]), Index(value=["candidateStatus","timestamp"]), Index(value=["jamesDayId","algorithmId","timestamp"]), Index(value=["jamesDayId","timestamp"])])
data class StoredRecord(val store: String, val recordId: String, val kind: String, val source: String, val timestamp: String, val localDate: String, val updatedAt: String, val externalId: String?, val rawJson: String?, val algorithmId:String?=null, val calibrationVersion:String?=null, val candidateStatus:String?=null, val jamesDayId:String?=null, val sourceShiftId:String?=null) {
    /** Room rows are immutable. Parsing once per emitted row removes the former
     * parse-on-every-filter/map/sort behaviour without a cross-snapshot cache.
     *
     * Version-5 databases can contain historical rows whose payload is NULL,
     * malformed or a non-object JSON value. Those rows retain their database
     * identity and are quarantined from semantic calculation instead of making
     * a lazy parse crash an unrelated route. */
    @delegate:Ignore private val parsedRaw by lazy(LazyThreadSafetyMode.NONE) { prepareRaw(rawJson) }
    @delegate:Ignore private val parsedData by lazy(LazyThreadSafetyMode.NONE) { parsedRaw.value.obj("data") }
    fun raw(): JsonObject = parsedRaw.value
    fun data(): JsonObject = parsedData
    /** Privacy-safe shape diagnostic. It never includes record payload data. */
    fun rawPayloadIssue():String?=parsedRaw.issue
    fun hasUsableRawPayload():Boolean=parsedRaw.issue==null
    companion object {
        private fun prepareRaw(payload:String?):PreparedStoredRaw {
            if(payload==null) return PreparedStoredRaw(JsonObject(emptyMap()),"MISSING_PAYLOAD")
            if(payload.isBlank()) return PreparedStoredRaw(JsonObject(emptyMap()),"EMPTY_PAYLOAD")
            val element=runCatching { json.parseToJsonElement(payload) }.getOrElse {
                return PreparedStoredRaw(JsonObject(emptyMap()),"MALFORMED_JSON")
            }
            val objectPayload=element as? JsonObject
                ?:return PreparedStoredRaw(JsonObject(emptyMap()),"NON_OBJECT_PAYLOAD")
            return PreparedStoredRaw(objectPayload,null)
        }
        fun from(store: String, raw: JsonObject): StoredRecord {
            val timestamp = raw.text("timestamp", raw.text("updatedAt", "1970-01-01T00:00:00Z"))
            val data=raw.obj("data")
            return StoredRecord(store, keyFor(store, raw), raw.text("kind", store), raw.text("source", if (store in rutStores) "rut" else "manual"), timestamp,
                raw.text("localDate", raw.text("date", data.text("date", if (validTime(timestamp)) dayOf(timestamp) else ""))), raw.text("updatedAt"), raw.text("externalId").ifBlank { null }, canonical(raw), data.text("algorithmId").ifBlank { null }, data.text("calibrationVersion").ifBlank { null }, data.text("status").ifBlank { null }, data.text("jamesDayId").ifBlank { null }, data.text("externalShiftId").ifBlank { null })
        }
    }
}
@Entity(tableName = "archives")
data class ArchiveRecord(@PrimaryKey val id: String, val path: String, val digest: String, val createdAt: String, val purpose: String)
@Entity(tableName = "import_history")
data class ImportHistory(@PrimaryKey val id: String, val source: String, val timestamp: String, val found: Int, val added: Int, val duplicates: Int, val conflicts: Int, val errors: String, val originalArchiveId: String)
@Dao
interface JamesDao {
    /** Legacy full snapshot only for explicit backup/import work. Never collect in UI state. */
    @Query("SELECT * FROM records ORDER BY store, recordId") suspend fun all(): List<StoredRecord>
    @Query("SELECT * FROM records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun between(start:String,end:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind=:kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun kindBetween(kind:String,start:String,end:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE source=:source AND kind=:kind AND timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun sourceKindBetween(source:String,kind:String,start:String,end:String):List<StoredRecord>
    /** Compatibility mappers may walk retained provider evidence, but only in
     * fixed-size pages. This keeps mature-database upgrade work bounded without
     * making normal UI/state queries historical scans. */
    @Query("SELECT * FROM records WHERE source=:source AND kind=:kind AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND recordId > :afterRecordId)) ORDER BY timestamp, recordId LIMIT :limit") suspend fun sourceKindPage(source:String,kind:String,afterTimestamp:String,afterRecordId:String,limit:Int):List<StoredRecord>
    @Query("SELECT * FROM records WHERE store=:store ORDER BY timestamp") suspend fun store(store:String):List<StoredRecord>
    /** Place/context is deliberately scoped: live tracking never reads the historical records table. */
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind='Place' AND (candidateStatus IS NULL OR candidateStatus!='MERGED') ORDER BY updatedAt DESC LIMIT :limit") suspend fun places(limit:Int=100):List<StoredRecord>
    /** Recent candidates are a tiny indexed state set. The JSON end marker is
     * resolved in Kotlin so historical Room upgrades stay non-destructive. */
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind='OwnershipPeriod' ORDER BY timestamp DESC LIMIT :limit") suspend fun ownershipCandidates(limit:Int=64):List<StoredRecord>
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind='OwnershipPeriod' ORDER BY timestamp DESC LIMIT :limit") fun observeOwnershipCandidates(limit:Int=64):Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind='LocationAnchor' AND recordId=:id LIMIT 1") suspend fun currentLocationAnchor(id:String):StoredRecord?
    @Query("SELECT * FROM records WHERE (store='personalRecords' AND kind IN ('Place','LocationAnchor')) OR (store='personalRecords' AND kind IN ('PlaceVisit','OwnershipPeriod','VisitInterruption','ContextPeriod','LifeFactActivity') AND timestamp BETWEEN :start AND :end) ORDER BY timestamp DESC") fun observePlacesContext(start:String,end:String):Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind='PlaceVisit' AND timestamp BETWEEN :start AND :end ORDER BY timestamp") suspend fun visitsBetween(start:String,end:String):List<StoredRecord>
    /** Rare explicit place repair only. Tracking/UI never invoke this history
     * page, so a merge cannot become a reactive mature-database scan. */
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind IN ('PlaceVisit','LocationAnchor','LocationEvent','ContextPeriod','LifeFactActivity') AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND recordId > :afterRecordId)) ORDER BY timestamp, recordId LIMIT :limit") suspend fun placeReferencePage(afterTimestamp:String,afterRecordId:String,limit:Int):List<StoredRecord>
    /** Explicit one-off compatibility read.  It is intentionally limited to the
     * legacy context kinds, never a replay of the whole records table. */
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind IN ('ContextPeriod','LocationEvent') ORDER BY timestamp") suspend fun legacyVisitEvidence():List<StoredRecord>
    @Query("SELECT * FROM records WHERE store='personalRecords' AND kind IN ('ContextPeriod','LocationEvent') AND updatedAt > :after ORDER BY updatedAt, timestamp") suspend fun legacyVisitEvidenceUpdatedAfter(after:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind='CalibrationEvent' AND algorithmId=:algorithmId AND timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationEvents(algorithmId:String,start:String,end:String,limit:Int=1000):List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind='CalibrationCandidate' AND algorithmId=:algorithmId AND candidateStatus IN (:statuses) ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationCandidates(algorithmId:String,statuses:List<String>,limit:Int=100):List<StoredRecord>
    @Query("SELECT * FROM records WHERE algorithmId=:algorithmId AND kind IN (:kinds) ORDER BY timestamp DESC LIMIT :limit") suspend fun calibrationRows(algorithmId:String,kinds:List<String>,limit:Int=1000):List<StoredRecord>
    // Active parameter sets are long-lived configuration, not time-series data.
    // Always retain them in the bounded scoring input set or a calibration older
    // than the history window would silently stop affecting production.
    @Query("SELECT * FROM records WHERE timestamp>=:since OR store IN ('metadata','settings','loggedEvents','eventTemplates') OR kind IN ('Routine','CalibrationProfile') ORDER BY store,recordId") suspend fun stateInputs(since:String):List<StoredRecord>
    @Query("SELECT * FROM records WHERE timestamp>=:since OR store IN ('metadata','settings','loggedEvents','eventTemplates') OR kind IN ('Routine','CalibrationProfile') ORDER BY store,recordId") fun observeStateInputs(since:String):Flow<List<StoredRecord>>
    /** A cheap readiness query.  UI must not mistake an as-yet-unloaded Room
     * stream for an actually empty James OS installation. */
    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE store NOT IN ('metadata','settings','eventTemplates') AND kind NOT IN ('CalibrationProfile','CalibrationCandidate','CalibrationEvent'))") fun observeHasUserHistory():Flow<Boolean>
    @Query("SELECT * FROM records WHERE (timestamp BETWEEN :start AND :end) OR store IN ('metadata','settings','eventTemplates') OR kind IN ('Routine','CalibrationProfile') ORDER BY timestamp, store, recordId") fun observeRouteWindow(start:String,end:String):Flow<List<StoredRecord>>
    @Query("SELECT * FROM records WHERE store = :store AND recordId = :id") suspend fun get(store: String, id: String): StoredRecord?
    /** Rare provider retractions use this indexed provenance lookup; ordinary
     * state/UI flows never scan historical shift evidence. */
    @Query("SELECT * FROM records WHERE source=:source AND sourceShiftId=:shiftId") suspend fun sourceShiftFacts(source:String,shiftId:String):List<StoredRecord>
    /** Pre-v7 rows did not have a materialised shift key. This deliberately
     * remains a retraction-only compatibility read: normal UI never touches
     * it, and a provider deletion must still be able to retract an older
     * mirrored shift after an in-place upgrade. */
    @Query("SELECT * FROM records WHERE source=:source AND sourceShiftId IS NULL AND kind IN ('WorkEvent','LifeFactActivity','OwnershipPeriod')") suspend fun legacySourceShiftFacts(source:String):List<StoredRecord>
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
@Database(entities = [StoredRecord::class, ArchiveRecord::class, ImportHistory::class], version = 7, exportSchema = true)
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

val MIGRATION_4_5=object:androidx.room.migration.Migration(4,5){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_kind_timestamp ON records(kind,timestamp)")
}}

/**
 * Early native installations could retain a nullable `rawJson` column despite
 * Kotlin later assuming a non-null payload. Preserve every row while aligning
 * Room's contract with that historical reality; [StoredRecord] quarantines a
 * missing/malformed payload from semantic consumers without deleting it.
 */
val MIGRATION_5_6=object:androidx.room.migration.Migration(5,6){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE records_v6 (store TEXT NOT NULL, recordId TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, timestamp TEXT NOT NULL, localDate TEXT NOT NULL, updatedAt TEXT NOT NULL, externalId TEXT, rawJson TEXT, algorithmId TEXT, calibrationVersion TEXT, candidateStatus TEXT, jamesDayId TEXT, PRIMARY KEY(store,recordId))")
    db.execSQL("INSERT INTO records_v6 (store,recordId,kind,source,timestamp,localDate,updatedAt,externalId,rawJson,algorithmId,calibrationVersion,candidateStatus,jamesDayId) SELECT store,recordId,kind,source,timestamp,localDate,updatedAt,externalId,rawJson,algorithmId,calibrationVersion,candidateStatus,jamesDayId FROM records")
    db.execSQL("DROP TABLE records")
    db.execSQL("ALTER TABLE records_v6 RENAME TO records")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_kind ON records(kind)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source ON records(source)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_localDate ON records(localDate)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_timestamp ON records(timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source_externalId ON records(source,externalId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_kind_timestamp ON records(kind,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source_kind_timestamp ON records(source,kind,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_store_timestamp ON records(store,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_algorithmId_timestamp ON records(algorithmId,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_algorithmId_calibrationVersion_timestamp ON records(algorithmId,calibrationVersion,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_candidateStatus_timestamp ON records(candidateStatus,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_jamesDayId_algorithmId_timestamp ON records(jamesDayId,algorithmId,timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_jamesDayId_timestamp ON records(jamesDayId,timestamp)")
}}

/** Adds an indexed, factual provenance key so a deleted Shift Tracker shift
 * can retract only its own materialised records without a broad history scan. */
val MIGRATION_6_7=object:androidx.room.migration.Migration(6,7){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE records ADD COLUMN sourceShiftId TEXT")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_records_source_sourceShiftId ON records(source,sourceShiftId)")
}}
