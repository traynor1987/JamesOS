package uk.co.james.whoop
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import uk.co.james.core.*
class WhoopMapperTest {
 private fun raw(extra:String)=json.parseToJsonElement("""{"id":"sleep-1","cycle_id":123,"created_at":"2026-09-09T07:00:00Z","updated_at":"2026-09-09T08:00:00Z",$extra}""").jsonObject
 @Test fun unscoredDoesNotInventZeros(){val rows=WhoopMapper.records("recovery",raw("\"score_state\":\"PENDING_SCORE\""));assertEquals(1,rows.size);assertEquals("ExternalRecord",rows.single().text("kind"))}
 @Test fun missingValuesStayMissing(){val rows=WhoopMapper.records("recovery",raw("\"score_state\":\"SCORED\",\"score\":{\"recovery_score\":0}"));assertEquals(2,rows.size);assertEquals(0.0,rows.last().obj("data").number("value"),0.0)}
 @Test fun repeatSyncUsesStableIdentity(){val r=raw("\"score_state\":\"SCORED\",\"score\":{\"recovery_score\":44,\"hrv_rmssd_milli\":31.8}");val a=WhoopMapper.records("recovery",r);val b=WhoopMapper.records("recovery",r);assertEquals(a.map {it.text("id")},b.map {it.text("id")});assertTrue(a.all {it.text("source")=="whoop"})}
 @Test fun sleepIsAsleepStagesNotTimeInBed(){val rows=WhoopMapper.records("sleep",raw(""""score_state":"SCORED","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000,"total_in_bed_time_milli":28800000}}"""));assertEquals(360.0,rows.single {it.text("kind")=="HealthMetric"&&it.obj("data").text("metric")=="Sleep"}.obj("data").number("value"),0.0)}
 @Test fun scoredSleepMapsOneStructuredDetailWithTransparentNeedAndQuality(){
  val rows=WhoopMapper.records("sleep",raw(""""score_state":"SCORED","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000,"total_in_bed_time_milli":28800000,"total_awake_time_milli":900000,"total_no_data_time_milli":60000,"sleep_cycle_count":4,"disturbance_count":7},"sleep_needed":{"baseline_milli":25200000,"need_from_sleep_debt_milli":3300000,"need_from_recent_strain_milli":600000,"need_from_recent_nap_milli":-1200000},"sleep_efficiency_percentage":88.0,"sleep_consistency_percentage":72.0}"""))
  val detail=rows.single {it.text("kind")=="SleepDetail"};val data=detail.obj("data")
  assertEquals("whoop:sleep-detail:sleep-1",detail.text("id"));assertEquals(27900000.0,data.number("totalSleepNeedMilli"),0.0)
  assertEquals(6300000.0,data.number("shortfallMilli"),0.0);assertEquals(88.0,data.number("efficiencyPercentage"),0.0);assertEquals(72.0,data.number("consistencyPercentage"),0.0)
  assertEquals(900000.0,data.number("awakeDurationMilli"),0.0);assertEquals(7.0,data.number("disturbanceCount"),0.0);assertEquals(14400000.0,data.obj("stages").number("lightMilli"),0.0)
 }
 @Test fun unscoredSleepDoesNotCreateFinalDetail(){
  val rows=WhoopMapper.records("sleep",raw(""""score_state":"PENDING_SCORE","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","score":{"sleep_needed":{"baseline_milli":25200000}}"""))
  assertFalse(rows.any {it.text("kind")=="SleepDetail"})
 }
 @Test fun partialSleepDetailKeepsMissingEvidenceMissingRatherThanZero(){
  val rows=WhoopMapper.records("sleep",raw(""""score_state":"SCORED","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000},"sleep_needed":{"baseline_milli":0,"need_from_sleep_debt_milli":0,"need_from_recent_strain_milli":0}}"""))
  val data=rows.single {it.text("kind")=="SleepDetail"}.obj("data")
  assertTrue(data["napAdjustmentMilli"] is JsonNull);assertTrue(data["totalSleepNeedMilli"] is JsonNull);assertTrue(data["efficiencyPercentage"] is JsonNull);assertTrue(data["disturbanceCount"] is JsonNull)
 }
 @Test fun providerRevisionKeepsOneStableSleepDetailIdentityAndUpdatesValues(){
  fun revision(updated:String,debt:Int)=raw(""""score_state":"SCORED","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","updated_at":"$updated","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000},"sleep_needed":{"baseline_milli":25200000,"need_from_sleep_debt_milli":$debt,"need_from_recent_strain_milli":0,"need_from_recent_nap_milli":0}}""")
  val first=WhoopMapper.records("sleep",revision("2026-09-09T08:00:00Z",0)).single {it.text("kind")=="SleepDetail"};val revised=WhoopMapper.records("sleep",revision("2026-09-09T09:00:00Z",3600000)).single {it.text("kind")=="SleepDetail"}
  assertEquals(first.text("id"),revised.text("id"));assertEquals("2026-09-09T09:00:00Z",revised.text("updatedAt"));assertEquals(28800000.0,revised.obj("data").number("totalSleepNeedMilli"),0.0)
 }
 @Test fun calibratingRecoveryIsNotShown(){val rows=WhoopMapper.records("recovery",raw("\"score_state\":\"SCORED\",\"score\":{\"user_calibrating\":true,\"recovery_score\":44}"));assertEquals(1,rows.size)}
}
