plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val releaseKey = System.getenv("JAMES_KEYSTORE_PATH")
val signingReady = listOf("JAMES_KEYSTORE_PATH", "JAMES_KEYSTORE_PASSWORD", "JAMES_KEY_ALIAS", "JAMES_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }
val allowUnsignedRelease = providers.environmentVariable("JAMES_ALLOW_UNSIGNED_RELEASE").orElse("false").get().toBoolean()
android {
    namespace = "uk.co.james"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.co.james.personal"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.environmentVariable("JAMES_VERSION_CODE").orElse("204").get().toInt()
        versionName = providers.environmentVariable("JAMES_VERSION_NAME").orElse("0.3.204").get()
        testInstrumentationRunner = "uk.co.james.JamesTestRunner"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"${providers.environmentVariable("JAMES_UPDATE_REPOSITORY").orElse("traynor1987/JamesOS").get()}\"")
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
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst { check(signingReady || allowUnsignedRelease) { "Release signing is not configured. Use assembleDebug or the validation-only unsigned release flag." } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("com.google.guava:guava:31.1-android")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
