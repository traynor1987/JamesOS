package uk.co.james.location

import java.time.Duration
import java.time.Instant
import uk.co.james.core.text
import uk.co.james.core.validTime

internal data class OwnershipInterval(val start:Instant,val end:Instant,val ownership:TimeOwnership,val interruption:Boolean=false)
internal data class OwnershipSummary(val autonomousMinutes:Long,val committedMinutes:Long,val constrainedMinutes:Long,val workMinutes:Long,val unknownMinutes:Long,val interruptions:Int,val longestAutonomousBlockMinutes:Long)

/** Intervals are expected to be non-overlapping visit/context slices.  Unknown is
 * retained rather than being quietly promoted to personal opportunity. */
internal fun ownershipSummary(intervals:List<OwnershipInterval>):OwnershipSummary {
    val sorted=intervals.filter {it.end>it.start}.sortedBy {it.start}
    fun minutes(kind:TimeOwnership)=sorted.filter {it.ownership==kind&&!it.interruption}.sumOf {Duration.between(it.start,it.end).toMinutes()}
    val blocks=sorted.filter {it.ownership==TimeOwnership.AUTONOMOUS&&!it.interruption}
    var longest=0L;var runStart:Instant?=null;var runEnd:Instant?=null
    blocks.forEach {block->if(runEnd!=null&&block.start==runEnd){runEnd=block.end}else{runStart=block.start;runEnd=block.end};longest=maxOf(longest,Duration.between(runStart!!,runEnd!!).toMinutes())}
    return OwnershipSummary(minutes(TimeOwnership.AUTONOMOUS),minutes(TimeOwnership.COMMITTED),minutes(TimeOwnership.CONSTRAINED),minutes(TimeOwnership.WORK),minutes(TimeOwnership.UNKNOWN),sorted.count {it.interruption},longest)
}

/** Builds a non-overlapping daily ledger.  Later explicit corrections win; raw
 * location remains evidence and is never rewritten. */
internal fun ownershipIntervals(records:List<uk.co.james.database.StoredRecord>,from:Instant,to:Instant):List<OwnershipInterval> =
    records.asSequence().filter {it.kind=="PlaceVisit"}.mapNotNull {row->
        val d=row.data();val start=d.text("start").takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
        val end=d.text("end").takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
        val owner=runCatching {TimeOwnership.valueOf(d.text("ownership","UNKNOWN"))}.getOrDefault(TimeOwnership.UNKNOWN)
        OwnershipInterval(maxOf(start,from),minOf(end,to),owner)
    }.filter {it.end>it.start}.toList()
