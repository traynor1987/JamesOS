package uk.co.james.location

import java.time.Duration
import java.time.Instant
import uk.co.james.core.text
import uk.co.james.core.validTime
import uk.co.james.database.StoredRecord

/** One canonical answer to "whose time was this?". Context, place and activity
 * are deliberately not converted into ownership. */
internal data class OwnershipInterval(val start:Instant,val end:Instant,val ownership:TimeOwnership,val interruption:Boolean=false,val provenance:String="UNKNOWN")
internal data class OwnershipSummary(val autonomousMinutes:Long,val committedMinutes:Long,val constrainedMinutes:Long,val workMinutes:Long,val unknownMinutes:Long,val interruptions:Int,val interruptionMinutes:Long,val longestAutonomousBlockMinutes:Long) {
    val classifiedMinutes get()=autonomousMinutes+committedMinutes+constrainedMinutes+workMinutes
}

internal fun ownershipSummary(intervals:List<OwnershipInterval>):OwnershipSummary {
    val sorted=intervals.filter {it.end>it.start}.sortedBy {it.start}
    fun minutes(kind:TimeOwnership)=sorted.filter {it.ownership==kind&&!it.interruption}.sumOf {Duration.between(it.start,it.end).toMinutes()}
    val blocks=sorted.filter {it.ownership==TimeOwnership.AUTONOMOUS&&!it.interruption}
    var longest=0L;var runStart:Instant?=null;var runEnd:Instant?=null
    blocks.forEach {block->if(runEnd!=null&&block.start==runEnd){runEnd=block.end}else{runStart=block.start;runEnd=block.end};longest=maxOf(longest,Duration.between(runStart!!,runEnd!!).toMinutes())}
    return OwnershipSummary(minutes(TimeOwnership.AUTONOMOUS),minutes(TimeOwnership.COMMITTED),minutes(TimeOwnership.CONSTRAINED),minutes(TimeOwnership.WORK),minutes(TimeOwnership.UNKNOWN),sorted.count {it.interruption},sorted.filter {it.interruption}.sumOf {Duration.between(it.start,it.end).toMinutes()},longest)
}

private data class Candidate(val interval:OwnershipInterval,val priority:Int)
/** OwnershipPeriod is an explicit semantic segment and therefore wins over its
 * enclosing Visit. Edges produce one winner per instant: no double counting. */
internal fun ownershipIntervals(records:List<StoredRecord>,from:Instant,to:Instant):List<OwnershipInterval> {
    val candidates=records.mapNotNull {row->
        val d=row.data();val start=d.text("start",row.timestamp).takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
        val end=d.text("end").takeIf(::validTime)?.let(Instant::parse)?:to
        if(row.kind!="PlaceVisit"&&row.kind!="OwnershipPeriod")return@mapNotNull null
        val owner=runCatching {TimeOwnership.valueOf(d.text("ownership","UNKNOWN"))}.getOrDefault(TimeOwnership.UNKNOWN)
        Candidate(OwnershipInterval(maxOf(start,from),minOf(end,to),owner,false,d.text("ownershipSource",if(row.kind=="OwnershipPeriod")"JAMES_CONFIRMED" else "UNKNOWN")),if(row.kind=="OwnershipPeriod")2 else 1)
    }.filter {it.interval.end>it.interval.start}
    val edges=(listOf(from,to)+candidates.flatMap {listOf(it.interval.start,it.interval.end)}).distinct().sorted()
    val ownership=edges.zipWithNext().mapNotNull {(a,b)->
        val winner=candidates.filter {it.interval.start<=a&&it.interval.end>=b}.maxWithOrNull(compareBy<Candidate>{it.priority}.thenBy {it.interval.provenance=="JAMES_CONFIRMED"})?:return@mapNotNull null
        winner.interval.copy(start=a,end=b)
    }
    // An interruption is evidence about continuity, not a second ownership
    // clock. Split only autonomous segments so the longest uninterrupted block
    // remains truthful while the ownership winner still counts each minute once.
    val interruptions=records.mapNotNull {row->
        if(row.kind!="VisitInterruption") return@mapNotNull null
        val d=row.data()
        val start=d.text("start",row.timestamp).takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
        val end=d.text("end").takeIf(::validTime)?.let(Instant::parse)?:to
        OwnershipInterval(maxOf(start,from),minOf(end,to),TimeOwnership.UNKNOWN,true,d.text("source","UNKNOWN"))
    }.filter {it.end>it.start}
    val splitOwnership=ownership.flatMap {segment->
        if(segment.ownership!=TimeOwnership.AUTONOMOUS) listOf(segment)
        else {
            val cuts=(listOf(segment.start,segment.end)+interruptions.filter {it.start<segment.end&&it.end>segment.start}.flatMap {listOf(maxOf(it.start,segment.start),minOf(it.end,segment.end))}).distinct().sorted()
            cuts.zipWithNext().mapNotNull {(a,b)->
                if(interruptions.any {it.start<=a&&it.end>=b}) null
                else segment.copy(start=a,end=b)
            }
        }
    }
    return splitOwnership+interruptions
}
