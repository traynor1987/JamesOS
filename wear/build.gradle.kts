plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val releaseKey = System.getenv("JAMES_KEYSTORE_PATH")
val signingReady = listOf("JAMES_KEYSTORE_PATH", "JAMES_KEYSTORE_PASSWORD", "JAMES_KEY_ALIAS", "JAMES_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }
val allowUnsignedRelease = providers.environmentVariable("JAMES_ALLOW_UNSIGNED_RELEASE").orElse("false").get().toBoolean()
val samsungSensorAarPath = providers.gradleProperty("jamesSamsungHealthSensorAar")
    .orElse(providers.environmentVariable("JAMES_SAMSUNG_SENSOR_AAR_PATH"))
    .orElse(layout.projectDirectory.file("libs/samsung-health-sensor-api-1.4.1.aar").asFile.absolutePath)
    .get()
val samsungSensorAar = file(samsungSensorAarPath)
android {
    namespace = "uk.co.james.wear"
    compileSdk = 36
    defaultConfig {
        // Data Layer peers must use the same application id and signing identity.
        // The APK still has an independent Wear version and is installed on the watch.
        applicationId = "uk.co.james.personal"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.environmentVariable("JAMES_WEAR_VERSION_CODE").orElse("196").get().toInt()
        versionName = providers.environmentVariable("JAMES_WEAR_VERSION_NAME").orElse("0.2.3").get()
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs { if (signingReady) create("stable") {
        storeType = "PKCS12"; storeFile = file(releaseKey!!); storePassword = System.getenv("JAMES_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("JAMES_KEY_ALIAS"); keyPassword = System.getenv("JAMES_KEY_PASSWORD")
    } }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-test" }
        release { isMinifyEnabled = false; if (signingReady) signingConfig = signingConfigs.getByName("stable") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst { check(signingReady || allowUnsignedRelease) { "Wear release signing is not configured." } }
}
tasks.register("verifySamsungHealthSensorSdk") {
    group = "verification"
    description = "Checks that the separately acquired Samsung Health Sensor SDK AAR is available."
    doLast {
        check(samsungSensorAar.isFile) {
            "Samsung Health Sensor SDK AAR is required for Wear builds. Download samsung-health-sensor-api-1.4.1.aar from Samsung under its terms, place it at wear/libs/samsung-health-sensor-api-1.4.1.aar, or set JAMES_SAMSUNG_SENSOR_AAR_PATH / -PjamesSamsungHealthSensorAar. The AAR is intentionally not distributed by this repository."
        }
    }
}
tasks.configureEach {
    if (name != "verifySamsungHealthSensorSdk" && name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn("verifySamsungHealthSensorSdk")
    }
}

dependencies {
    implementation(files(samsungSensorAar))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.tiles:tiles-material:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.guava:guava:33.4.8-android")
    testImplementation("junit:junit:4.13.2")
}
