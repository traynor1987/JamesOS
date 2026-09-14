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
 @Test fun sleepIsAsleepStagesNotTimeInBed(){val rows=WhoopMapper.records("sleep",raw(""""score_state":"SCORED","start":"2026-09-08T23:00:00Z","end":"2026-09-09T07:00:00Z","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000,"total_in_bed_time_milli":28800000}}"""));assertEquals(360.0,rows.last().obj("data").number("value"),0.0)}
 @Test fun calibratingRecoveryIsNotShown(){val rows=WhoopMapper.records("recovery",raw("\"score_state\":\"SCORED\",\"score\":{\"user_calibrating\":true,\"recovery_score\":44}"));assertEquals(1,rows.size)}
}
