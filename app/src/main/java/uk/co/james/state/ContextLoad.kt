package uk.co.james.state
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.location.ownershipIntervals
import uk.co.james.location.ownershipSummary
enum class VisitType { PERSONAL, NEUTRAL, OBLIGATION, UNKNOWN }
enum class ContextLoadLabel { VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH }
enum class DifficultInteractionType { RAISED_VOICE, DISMISSED, CONTROLLING, ARGUMENT, INSULT_HOSTILITY, OTHER }
data class ContextPeriod(val id:String,val start:Instant,val end:Instant?,val visitType:VisitType,val placeId:String?,val placeName:String?,val source:String,val jamesDayId:String?)
data class ContextLoadSummary(val score:Int,val label:ContextLoadLabel,val active:ContextPeriod?,val difficultActive:Boolean,val difficultMinutes:Long,val explanation:String,val confidence:String)
data class LifeBalanceWindow(val days:Int,val score:Int?,val personalMinutes:Long,val obligationMinutes:Long,val constrainedMinutes:Long,val workMinutes:Long,val unknownMinutes:Long,val classifiedMinutes:Long,val difficultMinutes:Long,val positiveActivities:Int,val recordedDays:Int)
data class LifeBalanceSummary(val current:LifeBalanceWindow,val days7:LifeBalanceWindow,val days14:LifeBalanceWindow,val days28:LifeBalanceWindow,val trend:String,val autonomy:String,val helping:List<String>,val hurting:List<String>)
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
 val from=clock.minus(Duration.ofDays(days.toLong()));val ledger=ownershipSummary(ownershipIntervals(rows,from,clock));val ps=rows.mapNotNull {it.period()}.filter {it.start<clock&&(it.end?:clock)>from}
 val personal=ledger.autonomousMinutes;val obligation=ledger.committedMinutes
 val difficult=rows.filter {it.kind=="ContextDifficultInterval"}.sumOf {r->r.fieldTime("start")?.let {overlap(it,r.fieldTime("end")?:clock,from,clock)}?:0}
 val activities=rows.count {it.kind=="LifeFactActivity"&&it.timestamp.takeIf(::validTime)?.let {t->Instant.parse(t) in from..clock}==true}
 val daysRecorded=ps.map {it.start.atZone(ZoneId.systemDefault()).toLocalDate()}.distinct().size
 // Unknown is evidence quality, never a negative conclusion. Activity count is
 // supporting context only and cannot create personal-time credit.
 if(ledger.classifiedMinutes<120)return LifeBalanceWindow(days,null,personal,obligation,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,difficult,activities,daysRecorded)
 val baseScore=(50+(personal/60.0*1.6).coerceAtMost(18.0)-(obligation/60.0*.65).coerceAtMost(12.0)-(ledger.constrainedMinutes/60.0*1.0).coerceAtMost(14.0)-(difficult/60.0*1.4).coerceAtMost(18.0)).roundToInt().coerceIn(0,100)
 val score=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(baseScore,rows,"life_balance")
 return LifeBalanceWindow(days,score,personal,obligation,ledger.constrainedMinutes,ledger.workMinutes,ledger.unknownMinutes,ledger.classifiedMinutes,difficult,activities,daysRecorded)
}
fun lifeBalance(rows:List<StoredRecord>,clock:Instant=Instant.now()):LifeBalanceSummary {val a=window(rows,clock,7);val b=window(rows,clock,14);val c=window(rows,clock,28);val trend=when {a.score==null->"LEARNING";b.score==null->"STEADY";a.score-b.score/2>=5->"IMPROVING";a.score-b.score/2<=-5->"BELOW USUAL";else->"STEADY"};val help=buildList {if(a.personalMinutes>=60L)add("Confirmed personal time");if(a.constrainedMinutes==0L&&a.classifiedMinutes>=180L)add("No confirmed constrained time")};val hurt=buildList {if(a.classifiedMinutes>=120L&&a.personalMinutes<60L)add("Low confirmed personal time");if(a.constrainedMinutes>=120L)add("Constrained time");if(a.difficultMinutes>0L)add("Difficult context")};return LifeBalanceSummary(a,a,b,c,trend,if(a.score==null)"LEARNING" else if(a.personalMinutes<60L)"LOW PERSONAL TIME" else "STEADY",help,hurt)}
