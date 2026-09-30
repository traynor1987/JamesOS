package uk.co.james.data

import kotlinx.coroutines.flow.*
import androidx.room.withTransaction
import uk.co.james.database.StoredRecord
import uk.co.james.calibration.JamesCalibrationCatalog
import java.time.*

const val CALIBRATION_EVIDENCE_LIMIT=5000
private val CALIBRATION_KINDS=listOf("CalibrationProfile","CalibrationCandidate","CalibrationBacktest","CalibrationActivation","CalibrationAnalysis")

/** Share the same retained dataset used by candidate save/activation validation. */
suspend fun JamesRepository.calibrationEvidence(algorithmId:String):List<StoredRecord> =
    dao.calibrationEvents(algorithmId,"1970-01-01T00:00:00Z","9999-12-31T23:59:59Z",CALIBRATION_EVIDENCE_LIMIT)

suspend fun JamesRepository.calibrationAnalysisInputs(algorithmId:String):List<StoredRecord> = db.withTransaction {
        val dependencies=uk.co.james.state.JamesAlgorithmRegistry.dependencies[algorithmId].orEmpty()
        calibrationEvidence(algorithmId)+dao.calibrationRows(algorithmId,CALIBRATION_KINDS,1000)+dependencies.flatMap {dao.calibrationRows(it,listOf("CalibrationProfile"),1000)}
}

fun JamesRepository.observeCalibrationLab(algorithmId:String?=null):Flow<List<StoredRecord>> {
    val ids=algorithmId?.let(::listOf)?:JamesCalibrationCatalog.all().filter {it.supportsCalibration}.map {it.algorithmId}
    return combine(ids.map {dao.observeCalibrationLab(it,CALIBRATION_EVIDENCE_LIMIT)}) {rows->rows.flatMap {it}.distinctBy {it.store to it.recordId}}
}

/** Historical evidence and future plans use separate horizons. A future metric
 * can never become current physiology merely because Schedule looks ahead. */
fun JamesRepository.observeScreenRecords(screen:String,selectedDay:LocalDate,mapRange:Int,at:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):Flow<List<StoredRecord>> {
    if(screen in setOf("Calibration Lab","Calibrate James OS")||screen.startsWith("Calibration:"))return observeCalibrationLab(screen.takeIf {it.startsWith("Calibration:")}?.substringAfter(':'))
    if(screen in setOf("Location","Location map")) {
        val end=selectedDay.plusDays(1).atStartOfDay(zone).toInstant()
        return dao.observePlacesContext(end.minus(Duration.ofDays(if(screen=="Location map")mapRange.toLong() else 14L)).toString(),end.toString())
    }
    val days=when(screen){"Timeline"->3L;"Insights","Weekly review"->8L;"Me","Life Balance"->90L;else->40L}
    val start=selectedDay.minusDays(if(screen=="Timeline")1 else days-1).atStartOfDay(zone).toInstant()
    val end=if(screen=="Timeline")selectedDay.plusDays(2).atStartOfDay(zone).toInstant() else at.plus(Duration.ofMinutes(5))
    val history=dao.observeRouteWindow(start.toString(),end.toString())
    val future=when(screen){"Schedule"->Duration.ofDays(7);"Today","Right now"->Duration.ofHours(36);else->null}?:return history
    return combine(history,dao.observeCommitments(at.toString(),at.plus(future).toString())) {past,plans->(past+plans).distinctBy {it.store to it.recordId}}
}

/** Minute/resume clock keeps current query bounds live without changing route readiness. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun JamesRepository.observeLiveScreenRecords(screen:String,selectedDay:LocalDate,mapRange:Int,clock:Flow<Instant>,zone:ZoneId=ZoneId.systemDefault()):Flow<List<StoredRecord>> {
    if(screen in setOf("Calibration Lab","Calibrate James OS")||screen.startsWith("Calibration:"))return observeCalibrationLab(screen.takeIf {it.startsWith("Calibration:")}?.substringAfter(':'))
    val historical=screen in setOf("Timeline","Location","Location map")
    val times=if(historical)clock.take(1)else clock
    return times.flatMapLatest {at->observeScreenRecords(screen,if(historical)selectedDay else at.atZone(zone).toLocalDate(),mapRange,at,zone)}.distinctUntilChanged()
}

data class CalibrationProductionInputs(val records:List<StoredRecord>,val loaded:Boolean,val at:Instant)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun JamesRepository.observeCalibrationProductionInputs(clock:Flow<Instant>,zone:ZoneId=ZoneId.systemDefault()):Flow<CalibrationProductionInputs> = flow {
    emit(CalibrationProductionInputs(emptyList(),false,Instant.EPOCH))
    emitAll(clock.flatMapLatest {at->observeScreenRecords("Today",at.atZone(zone).toLocalDate(),1,at,zone).map {CalibrationProductionInputs(it,true,at)}})
}
