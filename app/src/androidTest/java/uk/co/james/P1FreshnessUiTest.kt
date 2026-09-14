package uk.co.james

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import uk.co.james.state.WellbeingContribution
import uk.co.james.ui.contributorFreshnessLine

class P1FreshnessUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun staleContributorIsPresentedAsStale(){
        val item=WellbeingContribution("James Stress",20.0,18.0,"STEADY",1.0,0.0,0.0,"Excluded",observedAt="2020-01-01T00:00:00Z",freshnessClass="LIVE_FAST",freshnessState="STALE",freshnessMultiplier=0.0,included=false)
        val rendered=contributorFreshnessLine(item).orEmpty()
        assertTrue(rendered.contains("live fast · stale"))
        compose.setContent {MaterialTheme {Text(rendered,Modifier.testTag("freshness"))}}
        compose.onNodeWithTag("freshness").assertTextEquals(rendered)
    }
}
