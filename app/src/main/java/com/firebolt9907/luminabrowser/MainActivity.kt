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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.firebolt9907.luminabrowser.ui.LuminaRoot
import mozilla.components.concept.engine.activity.ActivityDelegate
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.AndroidDownloadManager

class MainActivity : ComponentActivity(), ActivityDelegate {

    private val app get() = application as LuminaApplication

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
        com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance.setUseGoogleCache(app.components.globalPreferencesStore.useGoogleCache)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
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

        setContent {
            LuminaRoot(
                isInPipMode = isInPipMode,
                onFullScreenChanged = { isVideoFullscreen = it },
                onRequestPipMode = ::requestPipMode
            )
        }
        // Only handle the launch intent on a fresh start. On recreation (process-death restore or a
        // config change) onCreate re-runs with the original launch intent still set as getIntent(),
        // which would re-open a duplicate tab from the same ACTION_VIEW/SEND. Live intents that
        // arrive while the activity exists are delivered through onNewIntent instead.
        if (savedInstanceState == null) handleIncomingIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // MainActivity only receives its own explicit intents now: the LAUNCHER entry (no URL) and the
    // "Add to Home Screen" shortcut (an explicit ACTION_VIEW that bypasses intent-filters). External
    // links from other apps go to CustomTabActivity instead. A home-shortcut opens as a normal,
    // persisted tab (no reader auto-attempt / Custom-Tab surface).
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_HOME_SHORTCUT, false) != true) return
        val url = IntentUrls.extractUrl(intent) ?: return
        app.components.tabRepository.addTab(url = url, title = "")
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
        if (isVideoFullscreen && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) { }
        }
    }

    fun requestPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1

        /** Set true on an "Add to Home Screen" shortcut intent so it opens as a normal saved tab. */
        const val EXTRA_HOME_SHORTCUT = "com.firebolt9907.luminabrowser.HOME_SHORTCUT"
    }
}
