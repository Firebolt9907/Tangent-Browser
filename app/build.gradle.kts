plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// FCM push (instant remote-tab / Send-Tab delivery) is opt-in: the Google Services plugin is only
// applied when a real google-services.json is present. Without it the app still builds and runs —
// push stays inactive and FirefoxSyncController falls back to its foreground polling loop. Drop a
// Firebase config for applicationId "com.firebolt9907.luminabrowser" into app/google-services.json
// to activate push. See AGENTS.md / IMPLEMENTATION.md.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.firebolt9907.luminabrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.firebolt9907.luminabrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            if (project.findProperty("singleAbi") != "false") {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        create("localRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("localRelease")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

configurations.configureEach {
    exclude(group = "org.mozilla.telemetry", module = "glean-native")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.browser.state)
    implementation(libs.mozilla.browser.storage.sync)
    implementation(libs.mozilla.concept.engine)
    implementation(libs.mozilla.concept.sync)
    implementation(libs.mozilla.concept.storage)
    implementation(libs.mozilla.concept.push)
    implementation(libs.mozilla.feature.session)
    implementation(libs.mozilla.feature.downloads)
    implementation(libs.mozilla.feature.tabs)
    implementation(libs.mozilla.feature.customtabs)
    implementation(libs.mozilla.feature.syncedtabs)
    implementation(libs.mozilla.lib.fetch.httpurlconnection)
    implementation(libs.mozilla.service.firefox.accounts)
    implementation(libs.mozilla.feature.push)
    implementation(libs.mozilla.feature.accounts.push)
    implementation(libs.mozilla.lib.push.firebase)
    implementation(libs.mozilla.support.appservices)
    implementation(libs.mozilla.appservices.logins)
    implementation(libs.mozilla.telemetry.glean)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Ed25519 signature verification for the remote reader-rules channel (platform Ed25519 is API 33+
    // only; minSdk is 26, so use BouncyCastle's low-level primitive for uniform coverage).
    implementation(libs.bouncycastle.prov)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (Android's bundled org.json is a throwing stub under
    // unit tests); lets ReaderExtractor.parseArticleJson be tested off-device.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
