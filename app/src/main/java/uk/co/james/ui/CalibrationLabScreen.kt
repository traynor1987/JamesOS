package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import uk.co.james.calibration.*
import uk.co.james.core.*
import uk.co.james.state.JamesAlgorithmRegistry

@Composable
fun CalibrationLabScreen(vm:JamesViewModel,algorithmId:String?=null) {
    val rows by vm.records.collectAsStateWithLifecycle()
    val events by produceState<List<CalibrationObservation>>(emptyList(),rows,algorithmId) {
        value=withContext(Dispatchers.Default) {rows.asSequence().mapNotNull(JamesCalibrationEngine::parse).filter {algorithmId==null||it.algorithmId==algorithmId}.take(4000).toList()}
    }
    if(algorithmId==null) CalibrationOverview(vm,rows,events) else CalibrationAlgorithm(vm,rows,events,algorithmId)
}

@Composable
private fun CalibrationOverview(vm:JamesViewModel,rows:List<uk.co.james.database.StoredRecord>,events:List<CalibrationObservation>) {
    val supported=JamesCalibrationCatalog.all().filter {it.supportsCalibration&&JamesAlgorithmRegistry.get(it.algorithmId)!=null}
    val candidateCount=rows.count {it.kind=="CalibrationCandidate"&&it.data().text("status") in setOf("DRAFT","TESTED")}
    AdaptiveCards(buildList {
        add {PageTitle("Calibration Lab","JAMES IS THE GROUND TRUTH")}
        add {JamesCard("James Calibration Engine","v"+JamesCalibrationEngine.VERSION+" · ACTIVE") {
            Text(events.size.toString()+" observations")
            Text(supported.count {id->events.any {it.algorithmId==id.algorithmId}}.toString()+" algorithms with evidence")
            Text(candidateCount.toString()+" candidates available")
            Muted("Scores remain deterministic. Feedback is stored first; one observation never changes production.")
        }}
        add {JamesCard("Calibrate James OS","WHAT FEELS WRONG — OR RIGHT?") {
            supported.forEach {registration->
                val entry=JamesAlgorithmRegistry.get(registration.algorithmId)!!
                TextButton(onClick={vm.navigate("Calibration:"+registration.algorithmId)},modifier=Modifier.fillMaxWidth()) {Text(entry.displayName)}
            }
        }}
        supported.forEach {registration->
            val entry=JamesAlgorithmRegistry.get(registration.algorithmId)!!
            val metric=JamesCalibrationEngine.evaluate(events.filter {it.algorithmId==registration.algorithmId})
            add {JamesCard(entry.displayName,metric.quality.name.replace('_',' ')) {
                Text(metric.count.toString()+" observations")
                metric.meanAbsoluteError?.let {Text("Typical error: ±"+String.format(java.util.Locale.UK,"%.1f",it)+" points")}
                JamesCalibrationEngine.biasInterpretation(metric.meanError)?.let {Text(it.description)}
                TextButton(onClick={vm.navigate("Calibration:"+registration.algorithmId)}){Text("OPEN")}
            }}
        }
    })
}

@Composable
private fun CalibrationAlgorithm(vm:JamesViewModel,rows:List<uk.co.james.database.StoredRecord>,events:List<CalibrationObservation>,algorithmId:String) {
    val entry=JamesAlgorithmRegistry.get(algorithmId)
    val registration=JamesCalibrationCatalog.get(algorithmId)
    if(entry==null||registration==null) {AdaptiveCards(listOf({PageTitle("Calibration","UNAVAILABLE")}));return}
    val target by produceState<JamesViewModel.CalibrationTarget?>(null,rows,algorithmId) {value=withContext(Dispatchers.Default){vm.calibrationTarget(algorithmId)}}
    val metric=remember(events){JamesCalibrationEngine.evaluate(events)}
    val profiles=rows.filter {it.kind=="CalibrationProfile"&&it.data().text("algorithmId")==algorithmId}.sortedByDescending {it.timestamp}
    val active=profiles.firstOrNull {it.data().flag("active")}
    val candidates=rows.filter {it.kind=="CalibrationCandidate"&&it.data().text("algorithmId")==algorithmId}.sortedByDescending {it.timestamp}
    val backtests=rows.filter {it.kind=="CalibrationBacktest"&&it.data().text("algorithmId")==algorithmId}.associateBy {it.data().text("candidateId")}
    val analysis=rows.firstOrNull {it.kind=="CalibrationAnalysis"&&it.data().text("algorithmId")==algorithmId}?.data()
    fun validationDetail(test:kotlinx.serialization.json.JsonObject?):String? {
        if(test==null)return null
        val stored=test.text("validationDetail")
        if(stored.isNotBlank())return stored
        return when {
            !test.flag("robustValidation") -> "Held-out validation is limited: "+test.number("validationCount").toInt()+" of "+JamesCalibrationEngine.MINIMUM_HELD_OUT_VALIDATION_OBSERVATIONS+" required observations."
            test.array("regressions").isNotEmpty() -> "Candidate worsened one or more protected validation slices."
            test.number("improvementPercent")<=0.0 -> "Candidate did not improve held-out validation error."
            else -> "Candidate improved held-out validation without protected-slice regressions."
        }
    }
    val newestTest=candidates.firstNotNullOfOrNull {candidate->backtests[candidate.recordId]?.data()}
    var calibrating by rememberSaveable {mutableStateOf(false)}
    var calibrationTargetAtOpen by remember {mutableStateOf<JamesViewModel.CalibrationTarget?>(null)}
    var submissionId by rememberSaveable {mutableStateOf("")}
    var feedback by rememberSaveable {mutableStateOf("")}
    var capacity by rememberSaveable {mutableStateOf("")}
    var sleepiness by rememberSaveable {mutableStateOf("")}
    var note by rememberSaveable {mutableStateOf("")}
    var activate by remember {mutableStateOf<uk.co.james.database.StoredRecord?>(null)}
    var editing by remember {mutableStateOf<CalibrationObservation?>(null)}
    var editFeedback by rememberSaveable {mutableStateOf("")}
    var editNote by rememberSaveable {mutableStateOf("")}
    var restoreConfirm by rememberSaveable {mutableStateOf(false)}
    var deleteConfirm by remember {mutableStateOf<CalibrationObservation?>(null)}
    val ignored=rows.filter {it.kind=="CalibrationEvent"&&it.data().text("algorithmId")==algorithmId&&it.data().flag("ignored")}.sortedByDescending {it.timestamp}
    AdaptiveCards(buildList {
        add {PageTitle(entry.displayName,"JAMES ALGORITHM FIRMWARE")}
        add {JamesCard("Current",entry.status.name) {
            Text("Algorithm v"+entry.algorithmVersion)
            Text("Calibration v"+(active?.data()?.text("calibrationVersion")?:entry.calibrationVersion))
            Text("Calibration Engine v"+JamesCalibrationEngine.VERSION)
            Text("Input confidence: "+(target?.confidence?:"UNAVAILABLE"))
            Text("Calibration accuracy: "+metric.quality.name.replace('_',' '))
            Text(metric.count.toString()+" observations")
            target?.let {shown->
                Text("Current production prediction: "+shown.score+"/100")
                if(algorithmId=="sleepiness") {
                    Text("Raw model prediction: "+(shown.rawScore?:shown.score)+"/100")
                    Muted(if(shown.calibrationApplied)"An accepted personal calibration is applied to the production prediction." else "No accepted personal correction is applied to this production prediction.")
                }
                Button(onClick={calibrationTargetAtOpen=shown;submissionId=id();calibrating=true}){Text("CALIBRATE")}
            }
        }}
        add {JamesCard("Accuracy","STRUCTURED EVIDENCE") {
            metric.meanAbsoluteError?.let {Text("MAE: "+String.format(java.util.Locale.UK,"%.2f",it))}
            metric.medianAbsoluteError?.let {Text("Median absolute error: "+String.format(java.util.Locale.UK,"%.2f",it))}
            metric.recencyWeightedMae?.let {Text("Recency-weighted MAE: "+String.format(java.util.Locale.UK,"%.2f",it))}
            metric.meanError?.let {bias->
                Text("Raw signed bias (prediction − James): "+String.format(java.util.Locale.UK,"%+.2f",bias))
                JamesCalibrationEngine.biasInterpretation(bias)?.let {Text(it.description)}
            }
            if(metric.count<registration.minimumEvidence)Muted("More data needed: at least "+registration.minimumEvidence+" suitable observations before automatic candidate generation.")
            Button(enabled=analysis?.text("status")!="ANALYSING",onClick={vm.analyseCalibration(algorithmId)}){Text(if(analysis?.text("status")=="ANALYSING")"ANALYSING…" else "ANALYSE NOW")}
            analysis?.let {state->
                val stored=state.text("detail")
                val detail=if(stored=="Validation limited or regression detected.")validationDetail(newestTest)?:"Analysis recorded before detailed validation diagnostics were available." else stored
                Muted("Last analysis: "+state.text("status")+(detail.takeIf {it.isNotBlank()}?.let {" · $it"}?:""))
            }
            Muted("Runs locally in a background coroutine. Nova cannot activate or bypass validation.")
        }}
        add {JamesCard("Coverage","ERROR BY PREDICTION RANGE") {
            listOf("0-19","20-49","50-79","80-100").forEach {name->
                val range=metric.scoreRanges.firstOrNull {it.name==name}
                Text(name+": "+(range?.count?.toString()?:"no observations"))
                range?.let {Muted("MAE "+String.format(java.util.Locale.UK,"%.1f",it.meanAbsoluteError)+" · bias "+String.format(java.util.Locale.UK,"%+.1f",it.meanError))}
            }
            if(metric.contexts.isNotEmpty()) {Text("Context slices");metric.contexts.take(8).forEach {Text(it.name+" · "+it.count+" · MAE "+String.format(java.util.Locale.UK,"%.1f",it.meanAbsoluteError))}}
        }}
        add {JamesCard("Active parameters","SAFE BOUNDED SCHEMA") {
            registration.parameters.forEach {definition->
                val current=active?.data()?.obj("parameters")?.get(definition.id)?.jsonPrimitive?.doubleOrNull?:definition.defaultValue
                Text(definition.displayName+": "+String.format(java.util.Locale.UK,"%.2f",current))
                Muted(definition.description+" · allowed "+definition.minimumAllowed+"–"+definition.maximumAllowed)
            }
            if(active!=null)TextButton(onClick={vm.rollbackCalibration(algorithmId)}){Text("ROLL BACK CALIBRATION")}
            TextButton(onClick={restoreConfirm=true}){Text("RESTORE DEFAULT CALIBRATION")}
            Muted("Default values reproduce the existing algorithm. No score changes until James activates a tested candidate.")
        }}
        candidates.take(10).forEach {candidate->
            val d=candidate.data();val test=backtests[candidate.recordId]?.data()
            add {JamesCard("Candidate",d.text("status")) {
                Text(d.text("reason"))
                d.obj("parameters").forEach {(name,value)->
                    val before=d.obj("baseParameters")[name]?.jsonPrimitive?.contentOrNull?:active?.data()?.obj("parameters")?.get(name)?.jsonPrimitive?.contentOrNull?:registration.parameters.firstOrNull {it.id==name}?.defaultValue?.toString()?:"—"
                    Text(name+": "+before+" → "+value.jsonPrimitive.content)
                }
                test?.let {
                    Text("Eligible training observations: "+test.number("trainCount").toInt())
                    Text("Held-out validation: "+test.number("validationCount").toInt()+" observations (minimum "+test.number("minimumValidationObservations",JamesCalibrationEngine.MINIMUM_HELD_OUT_VALIDATION_OBSERVATIONS.toDouble()).toInt()+")")
                    Text("Validation MAE: "+test.number("currentMae")+" → "+test.number("candidateMae"))
                    Text("Validation bias (prediction − James): "+String.format(java.util.Locale.UK,"%+.1f",test.number("currentBias"))+" → "+String.format(java.util.Locale.UK,"%+.1f",test.number("candidateBias")))
                    Text("Expected improvement: "+String.format(java.util.Locale.UK,"%.1f",test.number("improvementPercent"))+"%")
                    validationDetail(test)?.let {detail->Muted(detail)}
                    if(test.array("regressions").isNotEmpty())test.array("regressions").forEach {Muted("Regression: "+it.jsonPrimitive.content)}
                }
                if(d.text("status")=="TESTED")Button(onClick={activate=candidate}){Text("ACTIVATE")}
                Muted("Activation requires James's approval. Historical scores remain on their original calibration.")
            }}
        }
        add {JamesCard("Observation history","MOST RECENT") {
            if(events.isEmpty())Muted("No calibration observations yet.")
            events.sortedByDescending {it.timestamp}.take(30).forEach {event->
                Text(event.timestamp.toString()+" · prediction "+event.prediction.roundToInt()+" · "+(if(algorithmId=="low_mood_load")"James low/flat rating " else "James ")+event.observed.roundToInt())
                Muted(event.direction.name.replace('_',' ')+" · error "+String.format(java.util.Locale.UK,"%+.1f",event.error)+" · Algorithm v"+event.algorithmVersion+" · Calibration v"+event.calibrationVersion)
                val snap=event.snapshot
                listOf("sleepMinutes" to "Sleep minutes","recovery" to "Recovery","bodyBattery" to "Body Battery","mentalReserve" to "Mental Reserve","liveEnergy" to "Live Energy","contextLoad" to "Context Load","nutritionSource" to "Nutrition").forEach {(key,label)->snap[key]?.takeUnless {it.toString()=="null"}?.let {Muted(label+": "+it.toString().trim('"'))}}
                Row {TextButton(onClick={editing=event;editFeedback=event.feedback;editNote=rows.firstOrNull {it.recordId==event.id}?.data()?.text("note").orEmpty()}){Text("EDIT")};TextButton(onClick={vm.ignoreCalibration(event.id,true)}){Text("IGNORE")};TextButton(onClick={deleteConfirm=event}){Text("DELETE")}}
            }
        }}
        if(ignored.isNotEmpty())add {JamesCard("Ignored observations","RETAINED · EXCLUDED") {ignored.take(30).forEach {row->Text(row.timestamp+" · "+row.data().text("feedback"));TextButton(onClick={vm.ignoreCalibration(row.recordId,false)}){Text("INCLUDE AGAIN")}}}}
        if(profiles.isNotEmpty())add {JamesCard("Calibration history","IMMUTABLE VERSIONS") {profiles.forEach {profile->Text("v"+profile.data().text("calibrationVersion")+" · "+if(profile.data().flag("active"))"ACTIVE"else"INACTIVE");Muted(profile.data().text("createdBy",profile.data().text("source","UNKNOWN"))+" · Engine v"+profile.data().text("calibrationEngineVersion","1.0.0"));Muted(profile.data().text("changeReason","Preserved calibration version"))}}}
    })
    val currentTarget=calibrationTargetAtOpen
    if(calibrating&&currentTarget!=null) AlertDialog(onDismissRequest={calibrating=false;calibrationTargetAtOpen=null},title={Text(if(algorithmId=="low_mood_load")"How low or flat has the past week felt?" else "How does this compare?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(algorithmId=="low_mood_load")"James OS estimate: ${currentTarget.score}/100 low/flat" else "James OS prediction: "+currentTarget.score)
        if(algorithmId=="low_mood_load")Muted("0 = not low or flat · 100 = extremely low or flat. Higher means more low/flat mood. This is personal and non-diagnostic.")
        vm.calibrationOptions(algorithmId).forEach {option->FilterChip(selected=feedback==option,onClick={feedback=option},label={Text(option)})}
        if(algorithmId=="body_battery") {
            Text("Optional physical capacity")
            listOf("EMPTY","VERY LOW","LOW","OKAY","GOOD","HIGH","VERY HIGH").forEach {option->FilterChip(selected=capacity==option,onClick={capacity=option},label={Text(option)})}
            Text("Optional sleepiness")
            listOf("VERY LOW","LOW","MODERATE","HIGH","VERY HIGH").forEach {option->FilterChip(selected=sleepiness==option,onClick={sleepiness=option},label={Text(option)})}
        }
        if(algorithmId=="sleepiness") {
            Text("How sleepy are you actually?")
            listOf("NONE","SLIGHTLY SLEEPY","SLEEPY","VERY SLEEPY","STRUGGLING TO STAY AWAKE").forEach {option->FilterChip(selected=sleepiness==option,onClick={sleepiness=option},label={Text(option)})}
            Muted("Sleepiness is separate from physical capacity, current energy and Mental Reserve.")
        }
        OutlinedTextField(note,{note=it.take(240)},label={Text("Optional note")},modifier=Modifier.fillMaxWidth())
    }},confirmButton={Button(enabled=feedback.isNotBlank()||capacity.isNotBlank()||sleepiness.isNotBlank(),onClick={vm.calibrate(algorithmId,currentTarget,submissionId,feedback.ifBlank {capacity.ifBlank {sleepiness}},note,capacity.ifBlank {null},sleepiness.ifBlank {null});calibrating=false;calibrationTargetAtOpen=null}){Text("SAVE CALIBRATION")}},dismissButton={TextButton(onClick={calibrating=false;calibrationTargetAtOpen=null}){Text("CANCEL")}})
    editing?.let {event->AlertDialog(onDismissRequest={editing=null},title={Text("Edit calibration feedback")},text={Column {if(algorithmId=="low_mood_load")Muted("Higher means more low/flat mood.");vm.calibrationOptions(algorithmId).forEach {option->FilterChip(selected=editFeedback==option,onClick={editFeedback=option},label={Text(option)})};OutlinedTextField(editNote,{editNote=it.take(240)},label={Text("Optional note")})}},confirmButton={Button(enabled=editFeedback.isNotBlank(),onClick={vm.editCalibration(event.id,editFeedback,editNote);editing=null}){Text("SAVE")}},dismissButton={TextButton(onClick={editing=null}){Text("CANCEL")}})}
    deleteConfirm?.let {event->AlertDialog(onDismissRequest={deleteConfirm=null},title={Text("Delete calibration observation?")},text={Text("This removes only James's feedback observation. Health, nutrition and context source records are not deleted. Existing candidates will be invalidated.")},confirmButton={Button(onClick={vm.deleteCalibration(event.id);deleteConfirm=null}){Text("DELETE OBSERVATION")}},dismissButton={TextButton(onClick={deleteConfirm=null}){Text("CANCEL")}})}
    if(restoreConfirm)AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("Restore default calibration?")},text={Text("Future scores will use the original compatible defaults. Observations, candidates and history will not be deleted.")},confirmButton={Button(onClick={vm.restoreDefaultCalibration(algorithmId);restoreConfirm=false}){Text("RESTORE DEFAULT")}},dismissButton={TextButton(onClick={restoreConfirm=false}){Text("CANCEL")}})
    activate?.let {candidate->AlertDialog(onDismissRequest={activate=null},title={Text("Activate tested calibration?")},text={Text("Current: "+(active?.data()?.text("calibrationVersion")?:entry.calibrationVersion)+"\nNew parameter set: "+candidate.data().obj("parameters")+"\nFuture scores change immediately. History is not rewritten.")},confirmButton={Button(onClick={vm.activateCalibration(candidate.recordId);activate=null}){Text("ACTIVATE CALIBRATION")}},dismissButton={TextButton(onClick={activate=null}){Text("KEEP CURRENT")}})}
}
