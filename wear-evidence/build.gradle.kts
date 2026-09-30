// Compiles/tests the actual Wear persistence and clock sources without Samsung's proprietary SDK.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "uk.co.james.wear.evidence"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin.sourceSets.getByName("main").kotlin.apply {
    setSrcDirs(listOf("../wear/src/main/java"))
    include("uk/co/james/wear/data/WearDatabase.kt", "uk/co/james/wear/health/PassiveObservationTime.kt")
}
ksp { arg("room.schemaLocation", rootProject.file("wear/schemas").absolutePath) }
dependencies {
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    ksp("androidx.room:room-compiler:2.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
