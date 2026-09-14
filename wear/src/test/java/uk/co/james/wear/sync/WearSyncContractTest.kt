package uk.co.james.wear.sync

import org.junit.Assert.*
import org.junit.Test
import uk.co.james.wear.data.ObservationEntity

class WearSyncContractTest {
    private fun row(id:String,type:String,time:Long)=ObservationEntity(id,type,1.0,"unit",time)

    @Test fun correlatedStressRowsBeatPassiveBacklog() {
        val backlog=(1..50).map {row("passive-$it","steps",it.toLong())}
        val result=listOf(row("hr","heart_rate",2_001L),row("stress","james_stress",2_002L))
        val selected=rowsForUpload(backlog,result,true)
        assertTrue(selected.any {it.id=="stress"})
        assertEquals("hr",selected.first().id)
        assertEquals(50,selected.size)
    }

    @Test fun correlatedEnvelopeCannotBeSentWithoutStressResult() {
        val passive=listOf(row("hr","heart_rate",2_001L),row("steps","steps",2_002L))
        assertTrue(rowsForUpload(passive,passive,true).isEmpty())
        assertFalse(rowsForUpload(passive,emptyList(),false).isEmpty())
    }
}
