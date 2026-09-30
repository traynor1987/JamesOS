package uk.co.james.wear.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="snapshot")
data class SnapshotEntity(@PrimaryKey val id:Int=1,val json:String,val generatedAt:String,val receivedAt:Long)

@Entity(tableName="observations",indices=[Index(value=["type","observedAt"]),Index(value=["acknowledgedAt","observedAt"])])
data class ObservationEntity(
    @PrimaryKey val id:String,
    val type:String,
    val value:Double,
    val unit:String,
    val observedAt:Long,
    val createdAt:Long=System.currentTimeMillis(),
    val attempts:Int=0,
    val acknowledgedAt:Long?=null
)

@Dao interface WearDao {
    @Query("SELECT * FROM snapshot WHERE id=1") fun snapshot():Flow<SnapshotEntity?>
    @Query("SELECT * FROM snapshot WHERE id=1") suspend fun currentSnapshot():SnapshotEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSnapshot(value:SnapshotEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putObservation(value:ObservationEntity):Long
    @Query("SELECT * FROM observations WHERE acknowledgedAt IS NULL ORDER BY observedAt LIMIT :limit") suspend fun pending(limit:Int=100):List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE acknowledgedAt IS NULL AND observedAt >= :since ORDER BY observedAt DESC LIMIT :limit") suspend fun pendingSince(since:Long,limit:Int=20):List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE observedAt >= :since ORDER BY observedAt DESC LIMIT :limit") suspend fun observationsSince(since:Long,limit:Int=20):List<ObservationEntity>
    @Query("SELECT COUNT(*) FROM observations WHERE acknowledgedAt IS NULL") fun pendingCount():Flow<Int>
    @Query("UPDATE observations SET acknowledgedAt=:at WHERE id IN (:ids) AND acknowledgedAt IS NULL") suspend fun acknowledge(ids:List<String>,at:Long=System.currentTimeMillis())
    @Query("UPDATE observations SET attempts=attempts+1 WHERE id IN (:ids) AND acknowledgedAt IS NULL") suspend fun attempted(ids:List<String>)
    @Query("DELETE FROM observations WHERE acknowledgedAt IS NOT NULL AND createdAt < :cutoff") suspend fun purge(cutoff:Long)
    @Query("SELECT * FROM observations WHERE type=:type ORDER BY observedAt DESC LIMIT :limit") fun recent(type:String,limit:Int=30):Flow<List<ObservationEntity>>
}

@Database(entities=[SnapshotEntity::class,ObservationEntity::class],version=2,exportSchema=true)
abstract class WearDatabase:RoomDatabase(){abstract fun dao():WearDao}

/** Delivery state is additive; retained v1 readings remain pending after upgrade. */
val WEAR_MIGRATION_1_2=object:androidx.room.migration.Migration(1,2){
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN acknowledgedAt INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_acknowledgedAt_observedAt ON observations(acknowledgedAt,observedAt)")
    }
}
