package uk.co.james.wear.health

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.sync.WearSync

class JamesPassiveDataService:PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints:DataPointContainer) {
        runBlocking {
            val repo=(application as JamesWearApplication).repository
            dataPoints.getData(DataType.HEART_RATE_BPM).filter {it.value>0}.maxByOrNull {it.timeDurationFromBoot}?.let {repo.observe("heart_rate",it.value,"bpm")}
            dataPoints.getData(DataType.STEPS_DAILY).maxByOrNull {it.endDurationFromBoot}?.let {repo.observe("steps",it.value.toDouble(),"steps")}
            dataPoints.getData(DataType.CALORIES_DAILY).maxByOrNull {it.endDurationFromBoot}?.let {repo.observe("calories",it.value,"kcal")}
            dataPoints.getData(DataType.DISTANCE_DAILY).maxByOrNull {it.endDurationFromBoot}?.let {repo.observe("distance",it.value,"m")}
            saveAutomaticStressPulse(repo)
            WearSync(this@JamesPassiveDataService,repo).uploadPending()
        }
    }

    /** Uses only the already-delivered passive heart/activity readings. No Samsung
     * high-detail tracker is started in the background. */
    private suspend fun saveAutomaticStressPulse(repo:uk.co.james.wear.data.WearRepository) {
        val now=System.currentTimeMillis()
        if(!repo.sensorSettings.value.automaticStress||now-repo.lastAutoStressAt<20*60_000L)return
        val heart=repo.dao.recent("heart_rate",12).first()
            .filter {now-it.observedAt<=30*60_000L}.map {it.value}
        val resting=repo.snapshot.first().restingHeartRate.value
        val steps=repo.dao.recent("steps",3).first().filter {now-it.observedAt<=10*60_000L}
        val moving=steps.zipWithNext().any {(a,b)->a.value!=b.value}
        val estimate=StressEngine().estimate(heart,resting,moving)
        estimate.score?.let {
            repo.observe("james_stress",it.toDouble(),"/100",now)
            repo.autoStressRecorded(now)
        }
    }
}
