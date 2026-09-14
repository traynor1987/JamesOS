package uk.co.james.calibration

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.JamesAlgorithmRegistry

class CalibrationEngineTest {
    private fun snapshot(vararg pairs:Pair<String,JsonElement>)=fields("prediction" to p(50),*pairs)
    private fun row(raw:JsonObject)=StoredRecord.from("personalRecords",raw)
    private fun observation(index:Int,prediction:Double,observedLabel:String,context:String="PERSONAL"):CalibrationObservation {
        val at=Instant.parse("2026-01-"+(index+1).toString().padStart(2,'0')+"T12:00:00Z")
        val raw=JamesCalibrationEngine.event("live_energy",prediction,observedLabel,JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,"1.0.0","day-"+index,snapshot("contextType" to p(context)),timestamp=at)
        return JamesCalibrationEngine.parse(row(raw))!!
    }

    @Test fun bodyBatteryTooHighCreatesStructuredError() {
        val raw=JamesCalibrationEngine.event("body_battery",65.0,"MUCH TOO HIGH",JamesAlgorithmRegistry.BODY_BATTERY_VERSION,"1.0.0","day",snapshot("sleepMinutes" to p(242),"recovery" to p(41)))
        val stored=row(raw);assertEquals("body_battery",stored.algorithmId);assertEquals("1.0.0",stored.calibrationVersion)
        val event=JamesCalibrationEngine.parse(stored)!!
        assertEquals(40.0,event.observed,0.001);assertEquals(25.0,event.error,0.001);assertEquals(ErrorDirection.OVERESTIMATED,event.direction)
        assertEquals("calibration-input-v2",raw.obj("data").text("inputSnapshotSchemaVersion"))
        assertEquals("HIGH",raw.obj("data").text("evidenceConfidence"))
    }
    @Test fun tooLowAndAboutRightMapCorrectly() {
        assertEquals(60.0,JamesCalibrationEngine.normalizeObserved("live_energy","MUCH TOO LOW",35.0)!!,0.001)
        assertEquals(17.0,JamesCalibrationEngine.normalizeObserved("body_battery","ABOUT RIGHT",17.0)!!,0.001)
    }
    @Test fun sleepinessFeedbackIsStructuredAndDirectionallyCorrect() {
        assertEquals(95.0,JamesCalibrationEngine.normalizeObserved("sleepiness","STRUGGLING TO STAY AWAKE",30.0)!!,0.001)
        assertEquals(55.0,JamesCalibrationEngine.normalizeObserved("sleepiness","PREDICTION TOO LOW",30.0)!!,0.001)
        assertEquals(85.0,JamesCalibrationEngine.normalizeObserved("sleepiness","ABOUT RIGHT",85.0)!!,0.001)
        val raw=JamesCalibrationEngine.event("sleepiness",30.0,"STRUGGLING TO STAY AWAKE",JamesAlgorithmRegistry.SLEEPINESS_VERSION,JamesAlgorithmRegistry.SLEEPINESS_CALIBRATION,"day",snapshot("timeAwakeMinutes" to p(900),"sleepMinutes" to p(230)),calibrationSetId="sleepiness-set")
        val event=JamesCalibrationEngine.parse(row(raw))!!
        assertEquals(ErrorDirection.UNDERESTIMATED,event.direction);assertEquals("sleepiness-set",event.calibrationSetId);assertEquals("day",event.jamesDayId)
    }
    @Test fun repeatedSleepinessUnderpredictionCreatesBoundedUnactivatedCandidate() {
        val events=(0 until 25).map {index->
            val prediction=if(index%2==0)30.0 else 60.0
            val stamp=Instant.parse("2026-01-"+(index%20+1).toString().padStart(2,'0')+"T12:00:00Z").plusSeconds(index.toLong())
            row(JamesCalibrationEngine.event("sleepiness",prediction,"STRUGGLING TO STAY AWAKE",JamesAlgorithmRegistry.SLEEPINESS_VERSION,JamesAlgorithmRegistry.SLEEPINESS_CALIBRATION,"day-$index",snapshot("sleepMinutes" to p(230),"timeAwakeMinutes" to p(900)),timestamp=stamp)).let {JamesCalibrationEngine.parse(it)!!}
        }
        val candidate=JamesCalibrationEngine.candidate(events,"sleepiness","1.0.0",clock=Instant.parse("2026-02-01T00:00:00Z"))!!
        val bound=JamesCalibrationCatalog.get("sleepiness")!!.parameters.first {it.id=="outputBias"}
        assertTrue(candidate.parameters.getValue("outputBias") in bound.minimumAllowed..bound.maximumAllowed)
        assertEquals(CandidateStatus.DRAFT,candidate.status)
        assertNotNull(JamesCalibrationEngine.backTest(events,candidate))
    }
    @Test fun adaptersPreserveLoadDirections() {
        assertEquals(0.0,JamesCalibrationEngine.normalizeObserved("anxiety_load","VERY LOW")!!,0.001)
        assertEquals(75.0,JamesCalibrationEngine.normalizeObserved("anxiety_load","HIGH")!!,0.001)
        assertEquals(100.0,JamesCalibrationEngine.normalizeObserved("life_balance","DEFINITELY YES")!!,0.001)
    }
    @Test fun missingAndFutureEvidenceAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {JamesCalibrationEngine.event("live_energy",50.0,"ABOUT RIGHT","1","1",null,snapshot(),timestamp=Instant.now().plusSeconds(600))}
        assertNull(JamesCalibrationEngine.parse(StoredRecord.from("personalRecords",personal("CalibrationEvent",fields("algorithmId" to p("live_energy"))))))
    }
    @Test fun ignoredObservationIsExcludedButPreserved() {
        val raw=JamesCalibrationEngine.event("live_energy",50.0,"ABOUT RIGHT","1","1",null,snapshot()).let {it.changed("data" to it.obj("data").changed("ignored" to p(true)))}
        val stored=row(raw);assertNull(JamesCalibrationEngine.parse(stored));assertTrue(stored.data().flag("ignored"))
        assertTrue(JamesCalibrationEngine.parse(stored,includeIgnored=true)!!.ignored)
    }
    @Test fun statisticsUseKnownErrorsRangesContextsAndRecency() {
        val events=listOf(observation(0,10.0,"LOW"),observation(1,40.0,"LOW"),observation(2,60.0,"HIGH","OBLIGATION"),observation(3,90.0,"VERY HIGH"))
        val metrics=JamesCalibrationEngine.evaluate(events,Instant.parse("2026-02-01T12:00:00Z"))
        assertEquals(4,metrics.count);assertEquals(13.75,metrics.meanAbsoluteError!!,0.001);assertEquals(-6.25,metrics.meanError!!,0.001)
        assertEquals(4,metrics.scoreRanges.size);assertNotNull(metrics.recencyWeightedMae);assertTrue(metrics.contexts.any {it.name=="OBLIGATION"})
    }
    @Test fun narrowEvidenceCannotClaimStrongQuality() {
        val events=(0..79).map {observation(it%20,50.0,"ABOUT RIGHT")}
        assertNotEquals(CalibrationQuality.STRONG,JamesCalibrationEngine.evaluate(events).quality)
    }
    @Test fun oneEventNeverChangesOrCreatesCandidate() {
        assertNull(JamesCalibrationEngine.candidate(listOf(observation(0,65.0,"LOW")),"live_energy","1.0.0"))
        assertEquals(65,JamesCalibrationEngine.applyActiveScore(65,emptyList(),"live_energy"))
    }
    @Test fun optimizerStaysCloseAndInsideSafeBounds() {
        val events=(0 until 20).map {observation(it,if(it%2==0)30.0 else 70.0,"LOW")}
        val candidate=JamesCalibrationEngine.candidate(events,"live_energy","1.0.0")!!
        assertEquals(-4.0,candidate.parameters["outputBias"]!!,0.001)
        val definition=JamesCalibrationCatalog.get("live_energy")!!.parameters.first {it.id=="outputBias"}
        assertTrue(candidate.parameters["outputBias"]!! in definition.minimumAllowed..definition.maximumAllowed)
    }
    @Test fun backtestKeepsOriginalPredictionSeparate() {
        val events=(0 until 25).map {observation(it%20,if(it%2==0)40.0 else 70.0,"HIGH")}
        val candidate=JamesCalibrationEngine.candidate(events,"live_energy","1.0.0")!!
        val original=events.first().prediction
        val result=JamesCalibrationEngine.backTest(events,candidate)
        assertEquals(original,events.first().prediction,0.0);assertEquals(5,result.validationCount);assertTrue(result.robustValidation)
    }
    @Test fun invalidNovaCandidateCannotBypassBounds() {
        val raw=personal("CalibrationCandidate",fields("candidateId" to p("nova"),"algorithmId" to p("live_energy"),"algorithmVersion" to p(JamesAlgorithmRegistry.LIVE_ENERGY_VERSION),"baseCalibrationVersion" to p("1.0.0"),"parameters" to fields("outputBias" to p(999)),"reason" to p("proposal"),"evidenceIds" to JsonArray(emptyList()),"createdBy" to p("NOVA"),"status" to p("ACTIVE")),recordId="nova")
        assertNull(JamesCalibrationEngine.parseCandidate(row(raw)))
    }
    @Test fun compatibleActiveCalibrationAppliesWithoutApkUpdate() {
        val candidate=CandidateCalibration("c","live_energy",JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,"1.0.0",mapOf("outputBias" to -4.0),"Repeated overestimate",emptyList(),Instant.now(),CandidateCreator.CALIBRATION_ENGINE,CandidateStatus.TESTED)
        val profile=row(JamesCalibrationEngine.activeProfileRecord(candidate,"1.1.0","approved"))
        assertEquals(61,JamesCalibrationEngine.applyActiveScore(65,listOf(profile),"live_energy"))
        assertEquals("1.1.0",JamesCalibrationEngine.activeCalibration(listOf(profile),"live_energy","1.0.0",JamesAlgorithmRegistry.LIVE_ENERGY_VERSION).version)
    }
    @Test fun incompatibleAndCorruptProfilesFallBackToDefault() {
        val candidate=CandidateCalibration("c","live_energy","old-version","1.0.0",mapOf("outputBias" to -4.0),"old",emptyList(),Instant.now(),CandidateCreator.CALIBRATION_ENGINE,CandidateStatus.TESTED)
        val profile=row(JamesCalibrationEngine.activeProfileRecord(candidate,"1.1.0","old"))
        assertEquals(65,JamesCalibrationEngine.applyActiveScore(65,listOf(profile),"live_energy"))
    }
    @Test fun snapshotKeepsProvenanceAndMissingValuesMissing() {
        val meal=StoredRecord.from("personalRecords",personal("Nutrition",fields("sourceDisplay" to p("MyNetDiary via Health Connect"),"caffeineMg" to p(95)),source="health_connect",timestamp="2026-01-01T10:00:00Z"))
        val snap=calibrationSnapshot(listOf(meal),35,"GOOD","day",Instant.parse("2026-01-01T11:00:00Z"))
        assertEquals("MyNetDiary via Health Connect",snap.text("nutritionSource"));assertTrue(snap["hrv"] is JsonNull)
    }
    @Test fun defaultCatalogDoesNotAlterCurrentBehaviour() {
        listOf("body_battery","sleepiness","live_energy","mental_reserve","anxiety_load","time_pressure","context_load","life_balance","low_mood_load").forEach {id->assertEquals(42,JamesCalibrationEngine.applyActiveScore(42,emptyList(),id))}
    }
    @Test fun explicitDefaultProfilesDoNotAlterCurrentBehaviour() {
        listOf("body_battery","james_stress","sleepiness","live_energy","mental_reserve","anxiety_load","time_pressure","context_load","life_balance","low_mood_load").forEach {algorithmId->
            val entry=JamesAlgorithmRegistry.get(algorithmId)!!;val registration=JamesCalibrationCatalog.get(algorithmId)!!;val defaults=registration.parameters.associate {it.id to it.defaultValue}
            val candidate=CandidateCalibration("default-$algorithmId",algorithmId,entry.algorithmVersion,entry.calibrationVersion,defaults,"default",emptyList(),Instant.now(),CandidateCreator.MANUAL,CandidateStatus.ACTIVE,baseParameters=defaults)
            val profile=row(JamesCalibrationEngine.activeProfileRecord(candidate,entry.calibrationVersion,"default"))
            assertEquals(42,JamesCalibrationEngine.applyActiveScore(42,listOf(profile),algorithmId))
        }
    }

    @Test fun homogeneousDatasetCannotGenerateCandidate() {
        val events=(0 until 40).map {observation(it%20,55.0,"LOW")}
        assertNull(JamesCalibrationEngine.candidate(events,"live_energy","1.0.0"))
    }

    @Test fun candidateGenerationIsDeterministicAndRelativeToActiveBias() {
        val events=(0 until 20).map {observation(it,if(it%2==0)30.0 else 70.0,"LOW")}
        val clock=Instant.parse("2026-02-01T00:00:00Z")
        val first=JamesCalibrationEngine.candidate(events,"live_energy","1.4.0",mapOf("outputBias" to 2.0),clock)!!
        val second=JamesCalibrationEngine.candidate(events,"live_energy","1.4.0",mapOf("outputBias" to 2.0),clock)!!
        assertEquals(first.id,second.id);assertEquals(first.parameters,second.parameters);assertEquals(2.0,first.baseParameters["outputBias"]!!,0.0)
    }

    @Test fun corruptActiveProfileHashAndMissingRequiredBiasFallBackSafely() {
        val bad=personal("CalibrationProfile",fields("algorithmId" to p("live_energy"),"algorithmVersion" to p(JamesAlgorithmRegistry.LIVE_ENERGY_VERSION),"calibrationSchemaVersion" to p("calibration-schema-v1"),"calibrationVersion" to p("1.9.0"),"calibrationSetId" to p("wrong"),"parameters" to fields("outputBias" to p(-4)),"active" to p(true)),recordId="bad")
        assertEquals(65,JamesCalibrationEngine.applyActiveScore(65,listOf(row(bad)),"live_energy"))
        val missing=bad.changed("data" to bad.obj("data").changed("parameters" to fields()))
        assertEquals(65,JamesCalibrationEngine.applyActiveScore(65,listOf(row(missing)),"live_energy"))
    }

    @Test fun parameterTypeAndFiniteValidationRejectsBadValues() {
        val definition=CalibrationParameterDefinition("x","X","test",1.0,0.0,10.0,CalibrationParameterType.INTEGER)
        assertTrue(definition.accepts(0.0));assertTrue(definition.accepts(10.0))
        assertFalse(definition.accepts(-1.0));assertFalse(definition.accepts(11.0));assertFalse(definition.accepts(1.5))
        assertFalse(definition.accepts(Double.NaN));assertFalse(definition.accepts(Double.POSITIVE_INFINITY))
    }

    @Test fun dependencyGraphIsExplicitAndAcyclic() {
        assertTrue("body_battery" in JamesAlgorithmRegistry.dependencies.getValue("mental_reserve"))
        assertNull(JamesAlgorithmRegistry.dependencyCycle())
    }

    @Test fun candidateIdentityIncludesFullDatasetBaseAndDependencies() {
        val events=(0 until 25).map {observation(it,if(it%2==0)30.0 else 70.0,"LOW")}
        val clock=Instant.parse("2026-02-01T00:00:00Z")
        val first=JamesCalibrationEngine.candidate(events,"live_energy","1.0.0",clock=clock,baseCalibrationSetId="set-a",dependencyVersions=mapOf("body_battery" to "1.0@a"))!!
        val edited=events.toMutableList().also {it[0]=it[0].copy(observed=it[0].observed+1,error=it[0].error-1,absoluteError=kotlin.math.abs(it[0].error-1))}
        val second=JamesCalibrationEngine.candidate(edited,"live_energy","1.0.0",clock=clock,baseCalibrationSetId="set-a",dependencyVersions=mapOf("body_battery" to "1.0@a"))!!
        assertNotEquals(first.datasetHash,second.datasetHash);assertNotEquals(first.id,second.id)
        val active=ActiveCalibration("1.0.0","set-a",emptyMap(),CalibrationParameterSource.DEFAULT)
        assertNotNull(JamesCalibrationEngine.candidateStalenessReason(first,edited,active,mapOf("body_battery" to "1.0@a")))
        assertNotNull(JamesCalibrationEngine.candidateStalenessReason(first,events,active,mapOf("body_battery" to "1.1@b")))
    }

    @Test fun stableSubmissionIdProducesStableCalibrationEventKey() {
        val stamp=Instant.now().minusSeconds(10)
        val first=JamesCalibrationEngine.event("live_energy",35.0,"LOW",JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,"1.0.0","day",fields("prediction" to p(35)),timestamp=stamp,recordId="calibration-event:request-1")
        val retry=JamesCalibrationEngine.event("live_energy",35.0,"LOW",JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,"1.0.0","day",fields("prediction" to p(35)),timestamp=stamp,recordId="calibration-event:request-1")
        assertEquals(first.text("id"),retry.text("id"));assertEquals(first.obj("data"),retry.obj("data"));assertEquals(first.text("timestamp"),retry.text("timestamp"))
    }
}
