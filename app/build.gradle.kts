import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// FCM push (instant remote-tab / Send-Tab delivery) is opt-in: the Google Services plugin is only
// applied when a real google-services.json is present. Without it the app still builds and runs —
// push stays inactive and FirefoxSyncController falls back to its foreground polling loop. Drop a
// Firebase config for applicationId "com.firebolt9907.tangentbrowser" into app/google-services.json
// to activate push. See AGENTS.md / IMPLEMENTATION.md.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

// --- Release/distribution inputs (Obtainium) -------------------------------------------------
// Tagged CI builds inject the version from the git tag and sign with a real upload keystore; a
// plain local build keeps the dev version and the debug-keystore signing config. See
// .github/workflows/release.yml and docs/OBTAINIUM.md.
fun releaseInput(env: String, prop: String): String? =
    providers.environmentVariable(env).orNull ?: (project.findProperty(prop) as String?)

val tangentVersionName = releaseInput("TANGENT_VERSION_NAME", "tangentVersionName") ?: "1.0"
val tangentVersionCode =
    releaseInput("TANGENT_VERSION_CODE", "tangentVersionCode")?.toIntOrNull() ?: 1
val uploadKeystore = releaseInput("TANGENT_KEYSTORE_FILE", "tangentKeystoreFile")
    ?.let(::file)
    ?.takeIf { it.exists() }
// CI must never silently publish an APK signed with a newly generated debug key.
if (releaseInput("TANGENT_REQUIRE_RELEASE_SIGNING", "tangentRequireReleaseSigning") == "true") {
    check(uploadKeystore != null) { "A release upload keystore is required" }
    for ((env, prop) in listOf(
        "TANGENT_KEYSTORE_PASSWORD" to "tangentKeystorePassword",
        "TANGENT_KEY_ALIAS" to "tangentKeyAlias",
        "TANGENT_KEY_PASSWORD" to "tangentKeyPassword",
    )) {
        check(!releaseInput(env, prop).isNullOrBlank()) { "Missing release signing input: $env" }
    }
}

android {
    namespace = "com.firebolt9907.tangentbrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.firebolt9907.tangentbrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = tangentVersionCode
        versionName = tangentVersionName

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
        // Distribution key. Obtainium (and Android itself) will only accept an update signed by
        // the SAME key as the installed build, so this must stay stable across every release —
        // never regenerate it, and never fall back to the debug keystore for a published APK.
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = releaseInput("TANGENT_KEYSTORE_PASSWORD", "tangentKeystorePassword")
                keyAlias = releaseInput("TANGENT_KEY_ALIAS", "tangentKeyAlias")
                keyPassword = releaseInput("TANGENT_KEY_PASSWORD", "tangentKeyPassword")
            }
        }
    }

    buildTypes {
        // Instrumentation cleanup uninstalls its target. Never target the regular browser ID.
        create("uiTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfig = signingConfigs.getByName(if (uploadKeystore != null) "upload" else "localRelease")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testBuildType = "uiTest"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Keep the build honest: lint errors fail the build (so regressions like a bad
        // Context→Activity cast are caught), but don't fail on the plain warnings.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Dependency-freshness nags are pure noise here — versions are pinned deliberately (Gecko /
        // appservices lockstep), so "a newer version exists" is never actionable in this loop.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "UseTomlInstead",
        )
    }
    // Skip stripping debug symbols from the ~1 GB of Gecko/appservices native libraries.
    // This is the single slowest packaging step; for local dev installs (debug keystore, no
    // distribution) the larger on-disk APK is irrelevant. Keep it on for release builds too:
    // measured, it costs nothing in APK size — the .so files in the GeckoView/appservices AARs
    // are already stripped upstream, so the strip step only burns packaging time.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

configurations.configureEach {
    exclude(group = "org.mozilla.telemetry", module = "glean-native")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    // On-device search index (history + bookmarks surfaced to system/launcher search).
    implementation(libs.androidx.appsearch)
    implementation(libs.androidx.appsearch.local.storage)
    implementation(libs.androidx.appsearch.platform.storage)
    implementation(libs.androidx.concurrent.futures.ktx)
    // AppSearch's API returns Guava ListenableFuture. GeckoView drags full Guava onto the runtime
    // classpath (via media3), which evicts the `listenablefuture` stub AppSearch would otherwise
    // resolve — but only at runtime, so the class is missing at compile time unless declared here.
    implementation(libs.guava)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.browser.state)
    implementation(libs.mozilla.browser.icons)
    implementation(libs.mozilla.browser.storage.sync)
    implementation(libs.mozilla.concept.engine)
    implementation(libs.mozilla.concept.sync)
    implementation(libs.mozilla.concept.storage)
    implementation(libs.mozilla.concept.toolbar)
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
    implementation(libs.mozilla.support.utils)
    implementation(libs.mozilla.appservices.logins)
    implementation(libs.mozilla.service.sync.autofill)
    implementation(libs.mozilla.telemetry.glean)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    // Generic roaming FIDO2 security keys over USB/NFC. This is deliberately separate from
    // Android Credential Manager: third-party browser origin delegation does not reliably expose
    // external keys, while this client speaks CTAP directly to the authenticator.
    implementation(libs.yubikit.fido.android.ui)
    // SMS User Consent API (one-time-code / 2FA autofill from incoming SMS, no SMS permission).
    implementation(libs.play.services.auth.api.phone)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.activity.compose)
    // Keep the entire Compose stack compatible. Transitive Material alpha constraints otherwise
    // pair an older CustomStyle implementation with Foundation's renamed applyStyle API.
    implementation(enforcedPlatform(libs.androidx.compose.bom))
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
    androidTestImplementation(enforcedPlatform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// uiTest has a minimal application/manifest and never initializes Firebase or the browser graph.
// Its Compose-only host needs no Gecko/appservices native libraries. Keep wireless test installs small.
androidComponents {
    onVariants(selector().withBuildType("uiTest")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/*.so")
    }
}
// The personal google-services.json only describes the regular browser's application ID.
tasks.matching { it.name == "processUiTestGoogleServices" }.configureEach {
    enabled = false
}

/*
 * Compile-time sync of the bundled reader-rules floor from a local clone of the rules repo (a plain
 * sibling folder, NOT a git submodule). Before assets are packaged, this best-effort `git pull`s that
 * clone and copies its reader_rules.json over app/src/main/assets/reader_rules.json — so every build
 * bundles the latest rules as the trusted compile-time floor (the remote/signed channel handles
 * post-release updates; this keeps the shipped baseline current).
 *
 * Point it at your clone via either:
 *   - Gradle property:  -PtangentRulesRepo=/abs/path/to/tangent-reader-rules
 *     (or put `tangentRulesRepo=/abs/path` in ~/.gradle/gradle.properties), or
 *   - Env var:          TANGENT_RULES_REPO=/abs/path/to/tangent-reader-rules
 *
 * When unset (CI, other machines), the task no-ops and the checked-in asset is used — the build never
 * fails for a missing folder or an offline `git pull`. The copy is skipped when contents are identical
 * so routine builds don't churn the tracked asset.
 */
val syncReaderRules by tasks.registering {
    group = "tangent"
    description = "Pull the local reader-rules repo and copy reader_rules.json into assets (compile-time floor)."
    // Resolve these at configuration time into plain local values (String?/File). doLast closes over
    // these locals only — never over Project/script objects — which is what the configuration cache
    // requires (Task.project and script-object references at execution time are disallowed).
    val configuredRulesRepoPath = (project.findProperty("tangentRulesRepo") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TANGENT_RULES_REPO")?.takeIf { it.isNotBlank() }
    val bundledRulesAsset = layout.projectDirectory.file("src/main/assets/reader_rules.json").asFile
    doLast {
        val repoPath = configuredRulesRepoPath
        if (repoPath == null) {
            logger.lifecycle("[reader-rules] tangentRulesRepo/TANGENT_RULES_REPO not set; keeping checked-in asset.")
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
        val dest = bundledRulesAsset
        if (dest.exists() && dest.readText() == srcJson.readText()) {
            logger.lifecycle("[reader-rules] bundled asset already up to date.")
            return@doLast
        }
        srcJson.copyTo(dest, overwrite = true)
        logger.lifecycle("[reader-rules] synced ${srcJson.path} -> ${dest.path}")
    }
}

/*
 * Compile-time build of the built-in reader extension from its shared source. Runs
 * `node extension/build.js tangent`, which regenerates app/src/main/assets/extensions/reader/
 * (extractor.js + manifest.json) from extension/src/ — the same extractor the standalone desktop
 * build embeds, so the app can never ship a stale copy. See extension/README.md.
 *
 * Best-effort like syncReaderRules: if Node isn't installed (some CI) or the build errors, it warns
 * and keeps the checked-in asset (which is committed and normally already in sync). build.js writes
 * only when content changed, so routine compiles don't churn the tracked files.
 */
val buildReaderExtension by tasks.registering {
    group = "tangent"
    description = "Regenerate the built-in reader extension asset from extension/src (node build.js tangent)."
    // Resolve to plain Files at configuration time — doLast closes over these locals only (config cache).
    val extensionDir = rootProject.layout.projectDirectory.dir("extension").asFile
    val buildScript = File(extensionDir, "build.js")
    doLast {
        if (!buildScript.isFile) {
            logger.warn("[reader-ext] ${buildScript.path} not found; keeping checked-in asset.")
            return@doLast
        }
        try {
            val proc = ProcessBuilder("node", "build.js", "tangent")
                .directory(extensionDir)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                proc.destroyForcibly()
                logger.warn("[reader-ext] node build.js timed out; keeping checked-in asset.")
            } else if (proc.exitValue() != 0) {
                logger.warn("[reader-ext] node build.js tangent failed (exit ${proc.exitValue()}); keeping checked-in asset.\n$out")
            } else {
                logger.lifecycle("[reader-ext] ${out.ifBlank { "built." }}")
            }
        } catch (e: Exception) {
            logger.warn("[reader-ext] skipped (${e.message}); keeping checked-in asset. Install Node to regenerate on build.")
        }
    }
}

/* Run both compile-time asset syncs before any variant merges assets, so the APK gets fresh copies. */
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(syncReaderRules, buildReaderExtension) }

// Pinned upstream uBO package, expanded only into build output. No network at build/startup.
val bundledUblockAssets = layout.buildDirectory.dir("generated/ublockAssets")
val prepareBundledUblock by tasks.registering(Sync::class) {
    val vendor = layout.projectDirectory.dir("vendor/ublock").asFile
    val archive = File(vendor, "uBlock0_1.75.0.firefox.signed.xpi")
    from(zipTree(archive)) {
        into("extensions/ublock")
        exclude("META-INF/**") // Modified built-in package is not the signed AMO install.
    }
    from(vendor) {
        include("*-cookies.txt")
        into("extensions/ublock/assets/tangent")
    }
    into(bundledUblockAssets)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287") {
            "Bundled uBlock Origin archive checksum mismatch"
        }
        val assetIndex = File(destinationDir, "extensions/ublock/assets/assets.json")
        @Suppress("UNCHECKED_CAST")
        val index = groovy.json.JsonSlurper().parse(assetIndex) as MutableMap<String, Any>
        for ((key, local) in mapOf(
            "fanboy-cookiemonster" to "easylist-cookies.txt",
            "ublock-cookies-easylist" to "annoyances-cookies.txt",
        )) {
            @Suppress("UNCHECKED_CAST")
            val entry = index.getValue(key) as MutableMap<String, Any>
            entry["off"] = false
            val urls = entry["contentURL"]
            entry["contentURL"] = (if (urls is List<*>) urls else listOf(urls)) +
                "assets/tangent/$local"
        }
        assetIndex.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(index)))
    }
}
android.sourceSets.getByName("main").assets.srcDir(bundledUblockAssets.get().asFile)
// Lint analysis and report models also read source-set assets directly, before asset merging.
// Declare their producer dependency too so release builds cannot inspect an unprepared directory.
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("lint", ignoreCase = true)
}
    .configureEach { dependsOn(prepareBundledUblock) }
