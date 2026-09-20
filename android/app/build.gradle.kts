plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.iphoto.mobile"
    // Kept a step ahead of targetSdk: some AndroidX AARs released around the same time as
    // API 37 (navigation-compose 2.10.1, Sept 2026) are compiled against it and fail AGP's
    // AAR-metadata compatibility check if compileSdk lags behind.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.iphoto.mobile"
        minSdk = 26
        // Left at 36 (Android 16) - the current Google Play requirement; 37 isn't mandatory
        // until August 2027 and targeting it early would pull in its behavior changes too.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Plain JVM unit tests link against the Android SDK's stub jar, where every method (including
    // android.util.Log.*) throws by default instead of doing anything - and this codebase now logs
    // in most error paths. isReturnDefaultValues makes unmocked stub calls return a harmless
    // default (0/false/null) instead, so a test doesn't crash just for exercising a path that logs.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Replaces the old android.kotlinOptions {} block - AGP 9's built-in Kotlin support moved
// compiler configuration to this top-level DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.compose.material:material-icons-extended")

    // Coil 3: Maven coordinates and package names moved from io.coil-kt/coil to
    // io.coil-kt.coil3/coil3 - see AppContainer.kt, PhotoThumbnail.kt, PhotoDetailScreen.kt.
    // OkHttp support is now a separate artifact instead of being bundled in coil-compose.
    implementation("io.coil-kt.coil3:coil-compose:3.6.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.2")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")

    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")

    // Lightbox video playback (PhotoDetailScreen) - Coil/AsyncImage, used for everything else in
    // the lightbox, has no video decode path at all; this is the counterpart to the web app's own
    // <video> element, both pointed at the same GET /api/photos/{id}/stream endpoint (which
    // supports HTTP range requests for seeking - PhotoController#streamVideo). media3-datasource-
    // okhttp lets ExoPlayer reuse the same authenticated OkHttpClient as every other network call
    // (see AppContainer#videoDataSourceFactory) - the stream endpoint needs the same Bearer token
    // header as everything else, and ExoPlayer's own default HTTP stack wouldn't carry it.
    // Versions pinned from a web lookup, not a live Gradle sync (androidx.media3 is Google-Maven-
    // hosted, unreachable from this environment - see android/TODO.md's dependency-version entry
    // for why several other AndroidX pins in this file carry the same caveat).
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // On-device folder sync: WorkManager runs the actual upload/garbage job in the background,
    // either a one-off from a user tapping "Sync now" or periodically once they opt into
    // automatic sync (see SyncWorker/SyncPrefs.autoSyncEnabled); DocumentFile walks the SAF-picked
    // folder tree. work-runtime-ktx is genuinely empty as of 2.9.0+ - confirmed
    // against the androidx source tree - its Kotlin APIs (CoroutineWorker,
    // getWorkInfosForUniqueWorkFlow, etc.) live directly in work-runtime now.
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.documentfile:documentfile:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.11")
}
