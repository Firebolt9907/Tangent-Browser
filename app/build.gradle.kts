import java.util.concurrent.TimeUnit

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

/*
 * Compile-time sync of the bundled reader-rules floor from a local clone of the rules repo (a plain
 * sibling folder, NOT a git submodule). Before assets are packaged, this best-effort `git pull`s that
 * clone and copies its reader_rules.json over app/src/main/assets/reader_rules.json — so every build
 * bundles the latest rules as the trusted compile-time floor (the remote/signed channel handles
 * post-release updates; this keeps the shipped baseline current).
 *
 * Point it at your clone via either:
 *   - Gradle property:  -PluminaRulesRepo=/abs/path/to/lumina-reader-rules
 *     (or put `luminaRulesRepo=/abs/path` in ~/.gradle/gradle.properties), or
 *   - Env var:          LUMINA_RULES_REPO=/abs/path/to/lumina-reader-rules
 *
 * When unset (CI, other machines), the task no-ops and the checked-in asset is used — the build never
 * fails for a missing folder or an offline `git pull`. The copy is skipped when contents are identical
 * so routine builds don't churn the tracked asset.
 */
val syncReaderRules by tasks.registering {
    group = "lumina"
    description = "Pull the local reader-rules repo and copy reader_rules.json into assets (compile-time floor)."
    doLast {
        val repoPath = (project.findProperty("luminaRulesRepo") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("LUMINA_RULES_REPO")?.takeIf { it.isNotBlank() }
        if (repoPath == null) {
            logger.lifecycle("[reader-rules] luminaRulesRepo/LUMINA_RULES_REPO not set; keeping checked-in asset.")
            return@doLast
        }
        val repoDir = File(repoPath)
        if (!repoDir.isDirectory) {
            logger.warn("[reader-rules] '$repoPath' is not a directory; keeping checked-in asset.")
            return@doLast
        }
        /* Best-effort refresh; never fail the build if offline or not a git checkout. */
        try {
            val proc = ProcessBuilder("git", "pull", "--ff-only")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                proc.destroyForcibly()
                logger.warn("[reader-rules] git pull timed out; using local copy.")
            } else {
                logger.lifecycle("[reader-rules] git pull: ${out.ifBlank { "(no output)" }}")
            }
        } catch (e: Exception) {
            logger.warn("[reader-rules] git pull skipped (${e.message}); using local copy.")
        }
        val srcJson = File(repoDir, "reader_rules.json")
        if (!srcJson.exists()) {
            logger.warn("[reader-rules] ${srcJson.path} not found; keeping checked-in asset.")
            return@doLast
        }
        val dest = layout.projectDirectory.file("src/main/assets/reader_rules.json").asFile
        if (dest.exists() && dest.readText() == srcJson.readText()) {
            logger.lifecycle("[reader-rules] bundled asset already up to date.")
            return@doLast
        }
        srcJson.copyTo(dest, overwrite = true)
        logger.lifecycle("[reader-rules] synced ${srcJson.path} -> ${dest.path}")
    }
}

/* Run the sync before any variant's assets are merged, so the packaged APK gets the fresh floor. */
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncReaderRules) }
