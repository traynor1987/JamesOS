package uk.co.james.wear.data

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun parsesAdditiveV1Snapshot(){val value=parseSnapshot("""{"schemaVersion":1,"generatedAt":"2026-09-10T12:00:00Z","bodyBattery":{"value":64,"headline":"Steady","startingReserve":75,"used":11,"confidence":"Good"},"health":{"recovery":{"value":71,"unit":"%"},"steps":{"value":6421,"unit":"steps"}},"routines":{"due":4,"completed":3}}""");assertEquals(64,value.battery.value);assertEquals(75,value.battery.starting);assertEquals(6421.0,value.steps.value!!,0.0);assertEquals(3,value.routinesCompleted)}
    @Test fun oldSnapshotWithoutNewFieldsStillParses(){val value=parseSnapshot("""{"schemaVersion":1,"bodyBattery":{"value":20,"headline":"Running low"},"health":{}}""");assertEquals(20,value.battery.value);assertNull(value.battery.starting)}
    @Test fun parsesRightNowSnapshot(){val value=parseSnapshot("""{"schemaVersion":2,"bodyBattery":{"value":35},"wellbeing":{},"rightNow":{"liveEnergy":84,"energyLabel":"VERY HIGH","sustainability":31,"sustainabilityLabel":"LOW","crashRisk":67,"crashLabel":"ELEVATED","timePressure":78,"timePressureLabel":"HIGH"},"health":{}}""");assertEquals(84,value.rightNow.liveEnergy);assertEquals(31,value.rightNow.sustainability);assertEquals("ELEVATED",value.rightNow.crashLabel);assertEquals(78,value.rightNow.timePressure)}
}
