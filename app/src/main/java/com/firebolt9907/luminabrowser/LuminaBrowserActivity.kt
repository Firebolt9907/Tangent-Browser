package com.firebolt9907.luminabrowser

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import mozilla.components.concept.engine.activity.ActivityDelegate
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.AndroidDownloadManager

/**
 * The engine-hosting plumbing shared by both browser activities ([MainActivity] and
 * [CustomTabActivity]) — everything an activity must wire up for a Gecko page to work, independent
 * of which surface it shows:
 *
 *  - the engine [ActivityDelegate] (WebAuthn/passkeys — GeckoView hands us a PendingIntent for the
 *    system passkey sheet), registered in [onStart]/[onStop] per AGENTS.md invariant #12;
 *  - [DownloadsFeature] routing page downloads to the system DownloadManager;
 *  - the Gecko app-permission bridge (camera/mic/location runtime dialogs);
 *  - edge-to-edge + transparent system bars + splash screen;
 *  - picture-in-picture entry and state, and fullscreen-video volume keys.
 */
abstract class LuminaBrowserActivity : ComponentActivity(), ActivityDelegate {

    protected val app get() = application as LuminaApplication

    var isInPipMode by mutableStateOf(false)
        private set
    var isVideoFullscreen by mutableStateOf(false)

    // Captures file downloads from the engine and hands them to the system DownloadManager
    // (notification + storage handled by the OS). Started/stopped with the activity lifecycle.
    private val downloadsFeature by lazy {
        DownloadsFeature(
            applicationContext = applicationContext,
            store = app.components.store,
            useCases = app.components.downloadsUseCases,
            downloadManager = AndroidDownloadManager(applicationContext, app.components.store),
            onNeedToRequestPermissions = { permissions ->
                requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
            },
        )
    }

    private var pendingIntentSenderResult: ((Intent?) -> Unit)? = null
    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pendingIntentSenderResult?.invoke(result.data)
        pendingIntentSenderResult = null
    }

    // Bridges Gecko app-permission requests (camera/mic/location) to the OS runtime dialog.
    private var pendingPermissionResult: ((granted: Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        pendingPermissionResult?.invoke(granted)
        pendingPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ReaderManager.instance.setUseGoogleCache(app.components.globalPreferencesStore.useGoogleCache)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Pre-Android-11 has no animated IME inset, and the manifest's `adjustNothing` makes the
        // window entirely blind to the keyboard (verified on the S8: no visible-frame change, no
        // insets). Switch older devices to `adjustResize` so the keyboard surfaces in the window
        // insets and the address bar can clear it. API 30+ keeps `adjustNothing` + WindowInsets.ime.
        if (android.os.Build.VERSION.SDK_INT < 30) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        app.components.sitePermissionController.requestAppPermissions = { permissions, onResult ->
            val toRequest = permissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isEmpty()) {
                onResult(true)
            } else if (pendingPermissionResult != null) {
                // Another request is already in flight; don't clobber it.
                onResult(false)
            } else {
                pendingPermissionResult = onResult
                runCatching { permissionLauncher.launch(toRequest.toTypedArray()) }
                    .onFailure {
                        pendingPermissionResult = null
                        onResult(false)
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.components.engine.registerActivityDelegate(this)
        downloadsFeature.start()
    }

    override fun onStop() {
        super.onStop()
        app.components.engine.unregisterActivityDelegate()
        downloadsFeature.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_DOWNLOAD_PERMISSIONS) {
            downloadsFeature.onPermissionsResult(permissions, grantResults)
        }
    }

    override fun startIntentSenderForResult(intent: IntentSender, onResult: (Intent?) -> Unit) {
        pendingIntentSenderResult = onResult
        runCatching {
            intentSenderLauncher.launch(IntentSenderRequest.Builder(intent).build())
        }.onFailure {
            pendingIntentSenderResult = null
            onResult(null)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // While a video is fullscreen, hardware volume keys adjust the media stream silently (no
        // system panel) and move the overlay's volume slider instead.
        if (com.firebolt9907.luminabrowser.ui.video.handleVolumeKeyForVideo(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoFullscreen) enterPipIfSupported()
    }

    fun requestPipMode() {
        enterPipIfSupported()
    }

    private fun enterPipIfSupported() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) { }
        }
    }

    private companion object {
        const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
    }
}
