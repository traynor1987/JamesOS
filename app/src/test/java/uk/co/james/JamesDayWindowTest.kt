package uk.co.james

import java.time.*
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.bodyBattery
import uk.co.james.time.*

class JamesDayWindowTest {
    private fun sleep(start:String,end:String,nap:Boolean=false,id:String="sleep")=StoredRecord.from(
        "personalRecords",personal("HealthMetric",fields(
            "metric" to p("Sleep"),"value" to p(Duration.between(Instant.parse(start),Instant.parse(end)).toMinutes().toDouble()),
            "unit" to p("min"),"provider" to p("whoop"),"start" to p(start),"end" to p(end),"nap" to p(nap)
        ),id,"whoop",end)
    )
    private fun block(category:String,start:String,minutes:Long,id:String="$category:$start")=StoredRecord.from(
        "personalRecords",personal("TimeBlock",fields("category" to p(category),"end" to p(Instant.parse(start).plus(Duration.ofMinutes(minutes)).toString())),id,"manual",start)
    )
    private fun metric(name:String,value:Double,at:String)=StoredRecord.from(
        "personalRecords",personal("HealthMetric",fields("metric" to p(name),"value" to p(value),"provider" to p("whoop")),"$name:$at","whoop",at)
    )

    @Test fun acceptedMainSleepNotMidnightOwnsAllIntradayTime() {
        val wake="2026-09-12T07:43:00Z"
        val now=Instant.parse("2026-09-12T10:00:00Z")
        val rows=listOf(
            sleep("2026-09-12T03:48:00Z",wake),
            block("Work","2026-09-12T00:30:00Z",60,"before-sleep"),
            block("Work","2026-09-12T06:00:00Z",30,"during-sleep"),
            block("Work","2026-09-12T08:00:00Z",60,"after-wake")
        )
        val window=jamesDayWindow(rows,now,ZoneOffset.UTC)
        assertEquals(Instant.parse(wake),window.start)
        assertEquals("james-day:${Instant.parse(wake).toEpochMilli()}",window.id)
        assertEquals(3600L,timeBreakdownSeconds(rows,window)["Work"])
    }

    @Test fun exactDurationsRemainSecondsAndConvertOnceToHourlyBodyCost() {
        listOf(1L,10L,30L,60L,120L).forEach {minutes->
            val start=Instant.parse("2026-09-12T08:00:00Z")
            val window=JamesDayWindow("test",start,start.plus(Duration.ofMinutes(minutes)),null,null,null,"2026-09-12")
            assertEquals(minutes*60L,timeBreakdownSeconds(listOf(block("Work",start.toString(),minutes)),window)["Work"])
        }
        val wake="2026-09-12T07:00:00Z"
        val now=Instant.parse("2026-09-12T09:00:00Z")
        val battery=bodyBattery(listOf(
            sleep("2026-09-11T23:00:00Z",wake),metric("Recovery",60.0,wake),metric("Strain",0.0,wake),
            block("Work","2026-09-12T08:00:00Z",60)
        ),now,ZoneOffset.UTC)
        assertEquals(0.4,battery.trace!!.workloadCost,.001)
        assertEquals(3600L,battery.trace!!.contextTimeSeconds["Work"])
        assertEquals("seconds",battery.trace!!.contextTimeUnit)
    }

    @Test fun napDoesNotCreateNewJamesDayAndLateSleepDoes() {
        val main=sleep("2026-09-12T05:00:00Z","2026-09-12T12:00:00Z",id="main")
        val nap=sleep("2026-09-12T15:00:00Z","2026-09-12T15:30:00Z",nap=true,id="nap")
        val window=jamesDayWindow(listOf(main,nap),Instant.parse("2026-09-12T17:00:00Z"),ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-09-12T12:00:00Z"),window.start)
        assertTrue(window.acceptedMainSleepId!!.contains("main"))
    }
}
