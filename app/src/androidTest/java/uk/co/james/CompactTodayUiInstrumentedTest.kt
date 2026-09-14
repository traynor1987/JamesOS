package uk.co.james

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import uk.co.james.ui.CompactMetricCard
import uk.co.james.ui.CompactMetricUi
import uk.co.james.ui.JamesTheme

class CompactTodayUiInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun primaryMetricHasScoreLabelAndAccessibleText() {
        compose.setContent {JamesTheme("dark") {CompactMetricCard(CompactMetricUi("sleepiness","SLEEPINESS",71,"HIGH"))}}
        compose.onNodeWithText("SLEEPINESS").assertIsDisplayed()
        compose.onNodeWithText("71").assertIsDisplayed()
        compose.onNodeWithText("HIGH").assertIsDisplayed()
    }
}
