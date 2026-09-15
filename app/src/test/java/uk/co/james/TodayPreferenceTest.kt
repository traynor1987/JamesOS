package uk.co.james

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.settings.Preferences
import uk.co.james.settings.preferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class TodayPreferenceTest {
    private lateinit var context:Context
    @Before fun clearBefore() {runBlocking {context=ApplicationProvider.getApplicationContext();context.preferences.edit {it.clear()}}}
    @After fun clearAfter() {runBlocking {context.preferences.edit {it.clear()}}}

    @Test fun compactIsDefaultAndExplicitClassicChoiceSurvivesRepositoryRecreation()=runBlocking {
        assertTrue(Preferences(context).compactToday.first())
        Preferences(context).compactToday(false)
        assertFalse(Preferences(context).compactToday.first())
        Preferences(context).compactToday(true)
        assertTrue(Preferences(context).compactToday.first())
    }
}
