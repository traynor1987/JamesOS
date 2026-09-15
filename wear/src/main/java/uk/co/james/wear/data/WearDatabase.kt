package uk.co.james.wear.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="snapshot")
data class SnapshotEntity(@PrimaryKey val id:Int=1,val json:String,val generatedAt:String,val receivedAt:Long)

@Entity(tableName="observations",indices=[Index(value=["type","observedAt"])])
data class ObservationEntity(
    @PrimaryKey val id:String,
    val type:String,
    val value:Double,
    val unit:String,
    val observedAt:Long,
    val createdAt:Long=System.currentTimeMillis(),
    val attempts:Int=0
)

@Dao interface WearDao {
    @Query("SELECT * FROM snapshot WHERE id=1") fun snapshot():Flow<SnapshotEntity?>
    @Query("SELECT * FROM snapshot WHERE id=1") suspend fun currentSnapshot():SnapshotEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSnapshot(value:SnapshotEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putObservation(value:ObservationEntity):Long
    @Query("SELECT * FROM observations ORDER BY observedAt LIMIT :limit") suspend fun pending(limit:Int=100):List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE observedAt >= :since ORDER BY observedAt DESC LIMIT :limit") suspend fun pendingSince(since:Long,limit:Int=20):List<ObservationEntity>
    @Query("SELECT COUNT(*) FROM observations") fun pendingCount():Flow<Int>
    @Query("DELETE FROM observations WHERE id IN (:ids)") suspend fun acknowledge(ids:List<String>)
    @Query("UPDATE observations SET attempts=attempts+1 WHERE id IN (:ids)") suspend fun attempted(ids:List<String>)
    @Query("DELETE FROM observations WHERE createdAt < :cutoff") suspend fun purge(cutoff:Long)
    @Query("SELECT * FROM observations WHERE type=:type ORDER BY observedAt DESC LIMIT :limit") fun recent(type:String,limit:Int=30):Flow<List<ObservationEntity>>
}

@Database(entities=[SnapshotEntity::class,ObservationEntity::class],version=1,exportSchema=true)
abstract class WearDatabase:RoomDatabase(){abstract fun dao():WearDao}
