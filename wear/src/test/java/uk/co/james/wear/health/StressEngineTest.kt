package uk.co.james.wear.health

import org.junit.Assert.*
import org.junit.Test

class StressEngineTest {
    @Test fun refusesToInventWithoutBaseline(){assertNull(StressEngine().estimate(listOf(80.0,81.0,82.0),null,false).score)}
    @Test fun movementReducesFalseStress(){val engine=StressEngine();assertTrue(engine.estimate(listOf(100.0,102.0,101.0),60.0,true).score!! < engine.estimate(listOf(100.0,102.0,101.0),60.0,false).score!!)}
    @Test fun sensorCheckUsesHrvButDoesNotRequireRawPpg(){
        val check=SamsungSensorCheck(1L,82.0,18.0,1.4,33.1,12)
        val result=StressEngine().fromSensorCheck(check,60.0,false)
        assertNotNull(result.score);assertTrue(result.score!!>0);assertEquals("Experimental · sensor check",result.confidence)
    }
    @Test fun sensorCheckDoesNotInventAScoreWithNoSignals(){
        assertNull(StressEngine().fromSensorCheck(SamsungSensorCheck(1L,null,null,null,null,0),60.0,false).score)
    }
}
