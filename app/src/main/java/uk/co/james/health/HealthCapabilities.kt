package uk.co.james.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass

/**
 * Single authority for every Health Connect type James OS can request and read.
 * Keep the manifest declaration beside the type so a new reader cannot quietly
 * become impossible to authorise on device.
 */
internal data class HealthCapability(
    val label:String,
    val type:KClass<out Record>,
    val manifestPermission:String
) {
    val readPermission:String get()=HealthPermission.getReadPermission(type)
}

internal object HealthCapabilities {
    val all=listOf(
        HealthCapability("Steps",StepsRecord::class,"android.permission.health.READ_STEPS"),
        HealthCapability("Sleep",SleepSessionRecord::class,"android.permission.health.READ_SLEEP"),
        HealthCapability("Exercise",ExerciseSessionRecord::class,"android.permission.health.READ_EXERCISE"),
        HealthCapability("Heart rate",HeartRateRecord::class,"android.permission.health.READ_HEART_RATE"),
        HealthCapability("Resting heart rate",RestingHeartRateRecord::class,"android.permission.health.READ_RESTING_HEART_RATE"),
        HealthCapability("HRV",HeartRateVariabilityRmssdRecord::class,"android.permission.health.READ_HEART_RATE_VARIABILITY_RMSSD"),
        HealthCapability("Blood oxygen",OxygenSaturationRecord::class,"android.permission.health.READ_OXYGEN_SATURATION"),
        HealthCapability("Respiratory rate",RespiratoryRateRecord::class,"android.permission.health.READ_RESPIRATORY_RATE"),
        HealthCapability("Distance",DistanceRecord::class,"android.permission.health.READ_DISTANCE"),
        HealthCapability("Calories",TotalCaloriesBurnedRecord::class,"android.permission.health.READ_TOTAL_CALORIES_BURNED"),
        HealthCapability("Weight",WeightRecord::class,"android.permission.health.READ_WEIGHT"),
        HealthCapability("Nutrition",NutritionRecord::class,"android.permission.health.READ_NUTRITION"),
        HealthCapability("Hydration",HydrationRecord::class,"android.permission.health.READ_HYDRATION")
    )
    val byLabel=all.associateBy {it.label}
    val byType=all.associateBy {it.type}
}
