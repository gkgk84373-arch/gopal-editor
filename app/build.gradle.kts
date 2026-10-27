plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

fun prop(name: String, def: String): String = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: def

android {
    namespace = "com.gopal.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gopal.editor"
        minSdk = 24
        targetSdk = 34
        versionCode = prop("appVersionCode", "1").toInt()
        versionName = prop("appVersionName", "1.0.0")
        buildConfigField("String", "UPDATE_REPO", "\"${prop("updateRepo", "GITHUB_USERNAME/GopalEditor")}\"")
        vectorDrawables { useSupportLibrary = true }
    }

    // Stage 2A: a FIXED debug key committed in the repo (app/debug.keystore), so every debug build
    // (yours on GitHub Actions, or local) has the same signature and updates install over each other.
    // Anyone who has this file can sign an update for this package: keep the repo private.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Release APKs are built UNSIGNED here and signed afterwards with apksigner (v1+v2+v3),
    // because AGP skips the v1 (JAR) signature when minSdk >= 24 and some MIUI/HyperOS
    // installers still look for it. See README / .github/workflows/release.yml.
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "ExpiredTargetSdkVersion")
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.2.1-android")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")   // EncryptedSharedPreferences for API keys
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    val m3 = "1.4.1"
    implementation("androidx.media3:media3-common:$m3")
    implementation("androidx.media3:media3-exoplayer:$m3")
    implementation("androidx.media3:media3-ui:$m3")
    implementation("androidx.media3:media3-transformer:$m3")
    implementation("androidx.media3:media3-effect:$m3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")   // real org.json on the JVM (android.jar has stubs)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")   // Room migration test on the JVM (real SQLite)
}
