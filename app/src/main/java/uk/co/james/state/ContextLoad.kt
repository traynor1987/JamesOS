package uk.co.james.state
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.location.ownershipIntervals
import uk.co.james.location.ownershipSummary
import uk.co.james.time.jamesDayWindow
/** Situation only. Legacy PERSONAL/NEUTRAL/OBLIGATION values are retained for
 * imported history; they are never treated as Time Ownership. */
enum class VisitType { HOME, WORK, DRIVING, SHOPPING, FAMILY, SOCIAL, APPOINTMENT, ERRAND, LEISURE, PERSONAL_PROJECT, RESTING, OTHER, PERSONAL, NEUTRAL, OBLIGATION, UNKNOWN }
fun VisitType.contextLabel()=when(this) {
    VisitType.PERSONAL->"Personal context (legacy)"
    VisitType.NEUTRAL->"Neutral context (legacy)"
    VisitType.OBLIGATION->"Obligation context (legacy)"
    else->name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
}
enum class ContextLoadLabel { VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH }
enum class DifficultInteractionType { RAISED_VOICE, DISMISSED, CONTROLLING, ARGUMENT, INSULT_HOSTILITY, OTHER }
data class ContextPeriod(val id:String,val start:Instant,val end:Instant?,val visitType:VisitType,val placeId:String?,val placeName:String?,val source:String,val jamesDayId:String?)
data class ContextLoadSummary(val score:Int,val label:ContextLoadLabel,val active:ContextPeriod?,val difficultActive:Boolean,val difficultMinutes:Long,val explanation:String,val confidence:String)
data class LifeBalanceWindow(val days:Int,val score:Int?,val personalMinutes:Long,val obligationMinutes:Long,val constrainedMinutes:Long,val workMinutes:Long,val unknownMinutes:Long,val classifiedMinutes:Long,val difficultMinutes:Long,val positiveActivities:Int,val recordedDays:Int,val interruptions:Int,val interruptionMinutes:Long,val longestAutonomousBlockMinutes:Long,val relevantMinutes:Long=0,val ownershipCoveragePercent:Int=0) {
    /** Time with no defensible ownership interval is unclassified, not silently zero Personal. */
    val unclassifiedMinutes:Long get()=(relevantMinutes-(classifiedMinutes+unknownMinutes)).coerceAtLeast(0)
}
data class LifeBalanceSummary(val current:LifeBalanceWindow,val days7:LifeBalanceWindow,val days14:LifeBalanceWindow,val days28:LifeBalanceWindow,val trend:String,val autonomy:String,val helping:List<String>,val hurting:List<String>)

/**
 * Version 2 is deliberately a separate calculation trace.  Version 1 remains
 * the compatibility input for Low Mood and Mental Reserve; v2 never feeds a
 * v1 coefficient by accident.  It consumes ownership and interruption facts
 * only, and is rebuilt deterministically from those facts on every bounded
 * route snapshot rather than persisted as self-invalidating derived state.
 */
data class LifeBalanceV2Window(
    val days:Int,
    val score:Int?,
    val evidenceState:String,
    val autonomousMinutes:Long,
    val committedMinutes:Long,
    val constrainedMinutes:Long,
    val workMinutes:Long,
    val unknownMinutes:Long,
    val classifiedMinutes:Long,
    val observedWakingMinutes:Long,
    val coveragePercent:Int,
    val interruptions:Int,
    val interruptionMinutes:Long,
    val longestAutonomousBlockMinutes:Long,
    val algorithmVersion:String="2.0.0"
) {
    val ownershipDistribution:Map<String,Int> get() {
        val denominator=classifiedMinutes.takeIf {it>0}?:return emptyMap()
        fun share(minutes:Long)=((minutes*100)/denominator).toInt()
        return linkedMapOf("Autonomous" to share(autonomousMinutes),"Committed" to share(committedMinutes),"Constrained" to share(constrainedMinutes),"Work" to share(workMinutes),"Unknown" to ((unknownMinutes*100)/observedWakingMinutes.coerceAtLeast(1)).toInt())
    }
    val label:String get()=when(score) {
        null->"LEARNING"
        in 0..19->"STRONGLY CONSTRAINED"
        in 20..39->"CONSTRAINED"
        in 40..59->"MIXED"
        in 60..79->"ALIGNED"
        else->"STRONGLY ALIGNED"
    }
}
data class LifeBalanceV2Summary(val days7:LifeBalanceV2Window,val days28:LifeBalanceV2Window,val days90:LifeBalanceV2Window,val primary:LifeBalanceV2Window,val trend:String) {
    val current:LifeBalanceV2Window get()=primary
}
private fun StoredRecord.fieldTime(key:String)=data().text(key).takeIf(::validTime)?.let {Instant.parse(it)}
private fun StoredRecord.period():ContextPeriod? {if(kind!="ContextPeriod")return null;val start=fieldTime("start")?:return null;return ContextPeriod(recordId,start,fieldTime("end"),runCatching {VisitType.valueOf(data().text("visitType","UNKNOWN"))}.getOrDefault(VisitType.UNKNOWN),data().text("placeId").ifBlank {null},data().text("placeName").ifBlank {null},source,data().text("jamesDayId").ifBlank {null})}
private fun overlap(start:Instant,end:Instant,from:Instant,to:Instant)=Duration.between(maxOf(start,from),minOf(end,to)).toMinutes().coerceAtLeast(0)
fun currentContext(records:List<StoredRecord>,clock:Instant=Instant.now()):ContextLoadSummary {
 val active=records.mapNotNull {it.period()}.filter {(it.end==null||it.end>clock)&&it.start<=clock}.maxByOrNull {it.start}?:return ContextLoadSummary(0,ContextLoadLabel.VERY_LOW,null,false,0,"No current context recorded. Unknown has no penalty.","LEARNING")
 val ds=records.filter {it.kind=="ContextDifficultInterval"&&it.data().text("contextId")==active.id}.mapNotNull {r->r.fieldTime("start")?.let {it to r.fieldTime("end")}}
 val activeD=ds.any {it.second==null||it.second!!>clock};val minutes=ds.sumOf {overlap(it.first,it.second?:clock,active.start,clock)}
 val interactions=records.count {it.kind=="DifficultInteraction"&&it.data().text("contextId")==active.id}
 val hrs=Duration.between(active.start,clock).toMinutes().coerceAtLeast(0)/60.0
 val baseScore=((if(active.visitType==VisitType.OBLIGATION)12+8*(hrs/(hrs+2))else 0.0)+(if(activeD)28 else if(minutes>0)10 else 0)+(interactions*5).coerceAtMost(15)).roundToInt().coerceIn(0,100)
 val score=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(baseScore,records,"context_load")
 val label=when(score){in 0..19->ContextLoadLabel.VERY_LOW;in 20..39->ContextLoadLabel.LOW;in 40..59->ContextLoadLabel.MODERATE;in 60..79->ContextLoadLabel.HIGH;else->ContextLoadLabel.VERY_HIGH}
 val note=when {activeD->"Difficult is active: meaningful, bounded personal context.";minutes>0->"A difficult interval was recorded in this context.";active.visitType==VisitType.OBLIGATION->"Obligation is recorded, not assumed distress.";else->"No difficult interval is recorded."}
 return ContextLoadSummary(score,label,active,activeD,minutes,note,if(activeD||interactions>0)"MODERATE" else "LOW")
}
private fun window(rows:List<StoredRecord>,clock:Instant,days:Int):LifeBalanceWindow {
 // The rolling window is anchored to the current James Day boundary (accepted
 // main-sleep end), never a midnight reset. Historical intervals are clipped
 // to that bounded window before ownership arithmetic.
 val from=jamesDayWindow(rows,clock).start.minus(Duration.ofDays((days-1).toLong()));val ledger=ownershipSummary(ownershipIntervals(rows,from,clock));val ps=rows.mapNotNull {it.period()}.filter {it.start<clock&&(it.end?:clock)>from}
 val personal=ledger.autonomousMinutes;val obligation=ledger.committedMinutes;val relevant=Duration.between(from,clock).toMinutes().coerceAtLeast(0);val covered=ledger.classifiedMinutes+ledger.unknownMinutes;val coverage=if(relevant==0L)0 else ((covered*100)/relevant).toInt().coerceIn(0,100)
 val difficult=rows.filter {it.kind=="ContextDifficultInterval"}.sumOf {r->r.fieldTime("start")?.let {overlap(it,r.fieldTime("end")?:clock,from,clock)}?:0}
 val activities=rows.count {it.kind=="LifeFactActivity"&&it.timestamp.takeIf(::validTime)?.let {t->Instant.parse(t) in from..clock}==true}
 val daysRecorded=ps.map {it.start.atZone(ZoneId.systemDefault()).toLocalDate()}.distinct().size
 // Unknown is evidence quality, never a negative conclusion. Activity count is
 // supporting context only and cannot create personal-time credit.
 if(ledger.classifiedMinutes<120)return LifeBalanceWindow(days,null,personal,obligation,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,difficult,activities,daysRecorded,ledger.interruptions,ledger.interruptionMinutes,ledger.longestAutonomousBlockMinutes,relevant,coverage)
 val baseScore=(50+(personal/60.0*1.6).coerceAtMost(18.0)-(obligation/60.0*.65).coerceAtMost(12.0)-(ledger.constrainedMinutes/60.0*1.0).coerceAtMost(14.0)-(difficult/60.0*1.4).coerceAtMost(18.0)).roundToInt().coerceIn(0,100)
 val score=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(baseScore,rows,"life_balance")
 return LifeBalanceWindow(days,score,personal,obligation,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,difficult,activities,daysRecorded,ledger.interruptions,ledger.interruptionMinutes,ledger.longestAutonomousBlockMinutes,relevant,coverage)
}
fun lifeBalance(rows:List<StoredRecord>,clock:Instant=Instant.now()):LifeBalanceSummary {val a=window(rows,clock,7);val b=window(rows,clock,14);val c=window(rows,clock,28);val trend=when {a.score==null->"LEARNING";b.score==null->"STEADY";(a.score-b.score)/2>=5->"IMPROVING";(a.score-b.score)/2<=-5->"BELOW USUAL";else->"STEADY"};val help=buildList {if(a.personalMinutes>=60L)add("Confirmed personal time");if(a.constrainedMinutes==0L&&a.classifiedMinutes>=180L)add("No confirmed constrained time")};val hurt=buildList {if(a.classifiedMinutes>=120L&&a.personalMinutes<60L)add("Low confirmed personal time");if(a.constrainedMinutes>=120L)add("Constrained time");if(a.difficultMinutes>0L)add("Difficult context")};return LifeBalanceSummary(a,a,b,c,trend,if(a.score==null)"LEARNING" else if(a.personalMinutes<60L)"LOW PERSONAL TIME" else "STEADY",help,hurt)}

private fun balanceV2Window(rows:List<StoredRecord>,clock:Instant,days:Int):LifeBalanceV2Window {
    val from=jamesDayWindow(rows,clock).start.minus(Duration.ofDays((days-1).toLong()))
    val ledger=ownershipSummary(ownershipIntervals(rows,from,clock))
    // When retained main-sleep intervals are available, remove them from the
    // denominator.  Older/fallback history has no invented sleep ownership;
    // it remains a conservative selected-window coverage calculation.
    val sleepIntervals=rows.asSequence().filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"&&!it.data().flag("nap")}
        .mapNotNull {row->
            val data=row.data();val start=data.text("start",row.timestamp).takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
            val minutes=data.number("value",Double.NaN).takeIf {it.isFinite()&&it>0}?.toLong()?:return@mapNotNull null
            val end=data.text("end").takeIf(::validTime)?.let(Instant::parse)?:start.plus(Duration.ofMinutes(minutes))
            maxOf(start,from) to minOf(end,clock)
        }.filter {it.second>it.first}.sortedBy {it.first}.toList()
    var sleepMinutes=0L;var mergedStart:Instant?=null;var mergedEnd:Instant?=null
    sleepIntervals.forEach {(start,end)->if(mergedEnd==null||start>mergedEnd){if(mergedStart!=null)sleepMinutes+=Duration.between(mergedStart!!,mergedEnd!!).toMinutes();mergedStart=start;mergedEnd=end}else if(end>mergedEnd!!)mergedEnd=end}
    if(mergedStart!=null)sleepMinutes+=Duration.between(mergedStart!!,mergedEnd!!).toMinutes()
    val observed=Duration.between(from,clock).toMinutes().coerceAtLeast(0).minus(sleepMinutes).coerceAtLeast(0)
    val covered=ledger.classifiedMinutes+ledger.unknownMinutes
    val coverage=if(observed==0L)0 else ((covered*100)/observed).toInt().coerceIn(0,100)
    // A bounded fact model needs both meaningful classified time and enough of
    // the selected horizon understood. Unknown lowers availability only.
    val enough=ledger.classifiedMinutes>=360L&&coverage>=35
    if(!enough)return LifeBalanceV2Window(days,null,"LEARNING",ledger.autonomousMinutes,ledger.committedMinutes,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,observed,coverage,ledger.interruptions,ledger.interruptionMinutes,ledger.longestAutonomousBlockMinutes)
    val denominator=ledger.classifiedMinutes.toDouble()
    val autonomous=ledger.autonomousMinutes/denominator
    val constrained=ledger.constrainedMinutes/denominator
    // Work and commitments are described, not punished. Fragmentation has a
    // deliberately small bounded effect: it cannot outweigh ownership itself.
    val fragmentation=if(ledger.autonomousMinutes==0L)0.0 else (ledger.interruptionMinutes.toDouble()/ledger.autonomousMinutes).coerceIn(0.0,1.0)
    val base=50.0+autonomous*38.0-constrained*38.0-fragmentation*8.0
    val score=base.roundToInt().coerceIn(0,100)
    return LifeBalanceV2Window(days,score,"COVERED",ledger.autonomousMinutes,ledger.committedMinutes,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,observed,coverage,ledger.interruptions,ledger.interruptionMinutes,ledger.longestAutonomousBlockMinutes)
}

/** Modern Life Balance.  No RUT, location category, activity label, health or
 * mood metric is consulted here; those remain independently inspectable facts. */
fun lifeBalanceV2(rows:List<StoredRecord>,clock:Instant=Instant.now()):LifeBalanceV2Summary {
    val days7=balanceV2Window(rows,clock,7)
    val days28=balanceV2Window(rows,clock,28)
    val days90=balanceV2Window(rows,clock,90)
    val primary=days28.score?.let {days28}?:days7
    val trend=when {
        days7.score==null||days28.score==null->"INSUFFICIENT TREND EVIDENCE"
        days7.score-days28.score>=6->"RECENTLY MORE ALIGNED"
        days28.score-days7.score>=6->"RECENTLY MORE CONSTRAINED"
        else->"STEADY"
    }
    return LifeBalanceV2Summary(days7,days28,days90,primary,trend)
}
