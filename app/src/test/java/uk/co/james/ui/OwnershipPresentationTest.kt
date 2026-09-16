package uk.co.james.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.james.location.TimeOwnership

class OwnershipPresentationTest {
    @Test fun obligation_button_emits_the_canonical_committed_ownership_value() {
        val choice=ownershipChoices.single {it.label=="OBLIGATION"}
        assertEquals(TimeOwnership.COMMITTED,choice.ownership)
    }
}
