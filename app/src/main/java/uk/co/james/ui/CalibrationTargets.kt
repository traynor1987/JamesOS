package uk.co.james.ui

import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*
import uk.co.james.calibration.*

fun currentCalibrationTarget(algorithmId:String,rows:List<StoredRecord>,wellbeingSettings:uk.co.james.settings.WellbeingSettings,energyTimeSettings:uk.co.james.settings.EnergyTimeSettings,clock:java.time.Instant=java.time.Instant.now(),captureEvidence:Boolean=true):JamesViewModel.CalibrationTarget? {
        val wellbeing=uk.co.james.state.mentalWellbeing(rows,wellbeingSettings,clock);val entry=JamesAlgorithmRegistry.get(algorithmId)?:return null
        val right=if(algorithmId in setOf("live_energy","sleepiness","energy_sustainability","crash_risk","time_pressure"))rightNowSummary(rows,energyTimeSettings,clock)else null
        val target=when(algorithmId) {
            "body_battery"->bodyBattery(rows,clock).let {b->b.value?.let {JamesViewModel.CalibrationTarget(it,b.confidence,b.trace?.jamesDayId,b.algorithmVersion,b.calibrationVersion,b.calibrationSetId.orEmpty())}}
            "live_energy"->right?.liveEnergy?.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "sleepiness"->right?.let {summary->
                val metric=summary.sleepiness
                val details=summary.sleepinessDetails
                JamesViewModel.CalibrationTarget(metric.score,metric.confidence,summary.jamesDay.id,metric.algorithmVersion,metric.calibrationVersion,metric.calibrationSetId,details.rawExpressedSleepiness,details.calibrationApplied)
            }
            "energy_sustainability"->right?.sustainability?.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "crash_risk"->right?.crashRisk?.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "time_pressure"->right?.timePressure?.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "mental_reserve"->wellbeing.reserve.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.calibrationVersions["mental_reserve"]?:entry.calibrationVersion,wellbeing.calibrationSetIds["mental_reserve"].orEmpty())}
            "anxiety_load"->wellbeing.anxiety.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.calibrationVersions["anxiety_load"]?:entry.calibrationVersion,wellbeing.calibrationSetIds["anxiety_load"].orEmpty())}
            "low_mood_load"->wellbeing.lowMood.let {JamesViewModel.CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.calibrationVersions["low_mood_load"]?:entry.calibrationVersion,wellbeing.calibrationSetIds["low_mood_load"].orEmpty())}
            "context_load"->uk.co.james.state.currentContext(rows,clock).let {val active=JamesCalibrationEngine.activeCalibration(rows,"context_load",entry.calibrationVersion,entry.algorithmVersion);JamesViewModel.CalibrationTarget(it.score,it.confidence,it.active?.jamesDayId,entry.algorithmVersion,active.version,active.setId)}
            "life_balance"->uk.co.james.state.lifeBalance(rows,clock).current.score?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"life_balance",entry.calibrationVersion,entry.algorithmVersion);JamesViewModel.CalibrationTarget(it,"ROLLING",null,entry.algorithmVersion,active.version,active.setId)}
            "life_balance_v2"->uk.co.james.state.lifeBalanceV2(rows,clock).current.score?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"life_balance_v2",entry.calibrationVersion,entry.algorithmVersion);JamesViewModel.CalibrationTarget(it,"OWNERSHIP COVERAGE",null,entry.algorithmVersion,active.version,active.setId)}
            "james_stress"->rows.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="James Stress"}.maxByOrNull {it.timestamp}?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"james_stress",entry.calibrationVersion,entry.algorithmVersion);JamesViewModel.CalibrationTarget(JamesCalibrationEngine.applyActiveScore(it.data().number("value").toInt().coerceIn(0,100),rows,"james_stress"),"SOURCE",it.data().text("jamesDayId").ifBlank {null},entry.algorithmVersion,active.version,active.setId)}
            else->null
        }?:return null
        if(!captureEvidence)return target
        var snapshot=calibrationSnapshot(rows,target.score,target.confidence,target.jamesDayId,clock,wellbeingSettings,energyTimeSettings)
        if(algorithmId=="low_mood_load") {
            val current=wellbeing.lowMood
            val evidence=uk.co.james.state.lowMoodEvidence(rows,current)
            snapshot=snapshot.changed("lowMoodMoodConfidence" to p(evidence.moodConfidence),"lowMoodDirectEvidence" to p(evidence.directEvidence),"lowMoodInputCoverage" to p(evidence.inputCoverage),"lowMoodContributors" to JsonArray(current.contributors.map {item->fields("source" to p(item.source),"contribution" to p(item.contribution),"direction" to p(item.direction),"included" to p(item.included))}))
        }
        return target.copy(evidenceSnapshot=snapshot)
    }
