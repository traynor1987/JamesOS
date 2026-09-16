package uk.co.james.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceIdentityTest {
    @Test fun nearby_spelling_variant_with_accurate_fix_is_a_duplicate_candidate() {
        val match=placeSaveDuplicateCandidate(
            enteredName="Sisters House",
            fixAccuracyMetres=12f,
            matches=listOf(PlaceMatch("family-home","Sister House","Family",18f,150f))
        )
        assertEquals("family-home",match?.id)
    }

    @Test fun nearby_but_different_place_name_requires_a_choice_not_an_automatic_merge() {
        val match=placeSaveDuplicateCandidate(
            enteredName="Corner Shop",
            fixAccuracyMetres=10f,
            matches=listOf(PlaceMatch("family-home","Sister House","Family",20f,150f))
        )
        assertEquals("family-home",match?.id)
        assertFalse(match!!.sameName)
    }

    @Test fun inaccurate_fix_never_claims_a_duplicate_identity() {
        val match=placeSaveDuplicateCandidate(
            enteredName="Sisters House",
            fixAccuracyMetres=90f,
            matches=listOf(PlaceMatch("family-home","Sister House","Family",18f,150f))
        )
        assertEquals(null,match)
    }

    @Test fun spatially_separate_places_are_not_duplicate_candidates_even_when_names_match() {
        val match=placeSaveDuplicateCandidate(
            enteredName="Sister House",
            fixAccuracyMetres=8f,
            matches=listOf(PlaceMatch("family-home","Sister House","Family",120f,150f))
        )
        assertEquals(null,match)
    }

    @Test fun merge_keeps_explicit_category_over_unclassified() {
        assertEquals("Family",mergedPlaceCategory("Unclassified","Family"))
        assertEquals("Family",mergedPlaceCategory("Family","Unclassified"))
        assertTrue(placeNamesSupportSameIdentity("Sisters House","Sister House"))
    }
}
