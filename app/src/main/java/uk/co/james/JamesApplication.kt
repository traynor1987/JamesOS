package uk.co.james
import android.app.Application
import androidx.room.Room
import uk.co.james.database.JamesDatabase
import uk.co.james.database.MIGRATION_1_2
import uk.co.james.database.MIGRATION_2_3
import uk.co.james.database.MIGRATION_3_4
import uk.co.james.data.JamesRepository
import uk.co.james.settings.Preferences
import uk.co.james.health.HealthSource
import uk.co.james.location.LocationSource
import uk.co.james.nutrition.NutritionHealthSource
import uk.co.james.sync.BackgroundJobs
import uk.co.james.work.WorkContextProvider
class JamesApplication : Application() {
    val database by lazy { Room.databaseBuilder(this,JamesDatabase::class.java,"james-native.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4).build() }
    val repository by lazy { JamesRepository(this,database) }
    val preferences by lazy { Preferences(this) }
    val health by lazy { HealthSource(this,repository) }
    val nutrition by lazy { NutritionHealthSource(this,repository) }
    val whoop by lazy { uk.co.james.whoop.WhoopSource(this,repository) }
    val location by lazy { LocationSource(this,repository,preferences) }
    val wear by lazy { uk.co.james.wear.WearCompanion(this) }
    val work by lazy { WorkContextProvider(this,repository) }
    override fun onCreate() {super.onCreate();BackgroundJobs.schedule(this);if(whoop.configured())BackgroundJobs.scheduleWhoop(this)}
}
