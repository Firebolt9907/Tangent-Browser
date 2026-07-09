package com.firebolt9907.luminabrowser.ui.chrome

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/**
 * App/browser resolution for the "aA" sheet's "Open in…" actions.
 *
 * Pure [PackageManager] logic (no Compose) — extracted from `AaSheet.kt` so the sheet's
 * composables stay focused on UI. Callers run these off the main thread ([installedBrowsers]
 * in particular issues several `queryIntentActivities` calls).
 */

private const val OWN_PKG = "com.firebolt9907.luminabrowser"

// packageName "" = "Default browser" (no package constraint on the intent)
internal data class BrowserInfo(val packageName: String, val label: String)

private val HARDCODED_BROWSERS = listOf(
    BrowserInfo("", "Default browser"),
    BrowserInfo("com.android.chrome", "Chrome"),
    BrowserInfo("org.mozilla.firefox", "Firefox"),
    BrowserInfo("org.mozilla.fenix", "Firefox Nightly"),
)

// A native app (e.g. Instagram, YouTube) that is associated with a specific URL.
internal data class NativeApp(val packageName: String, val label: String)

/**
 * Resolve the non-browser app associated with [url], the way Chrome offers to open an
 * instagram.com link in the Instagram app. The heuristic: a "native app" handler is one
 * that handles this *specific* URL but does NOT handle arbitrary https URLs (which is what
 * a browser does). Returns null when only browsers (or nothing) can open the link.
 */
internal fun nativeAppFor(pm: PackageManager, url: String): NativeApp? {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null

    // Packages that handle *any* https URL are general-purpose browsers.
    val genericProbe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browserPkgs = pm.queryIntentActivities(genericProbe, 0)
        .map { it.activityInfo.packageName }
        .toSet()

    val specific = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    val handler = pm.queryIntentActivities(specific, 0).firstOrNull { ri ->
        val pkg = ri.activityInfo.packageName
        pkg != OWN_PKG && pkg !in browserPkgs
    } ?: return null

    return NativeApp(
        packageName = handler.activityInfo.packageName,
        label = handler.loadLabel(pm).toString(),
    )
}

internal fun installedBrowsers(pm: PackageManager): List<BrowserInfo> {
    // Use flags=0 to get every app that handles https, not only the current default.
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
    val dynamic = pm.queryIntentActivities(probe, 0)
        .map { ri ->
            BrowserInfo(
                packageName = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
            )
        }
        .distinctBy { it.packageName }
        .filter { it.packageName != OWN_PKG }

    val dynamicPkgs = dynamic.map { it.packageName }.toSet()

    // For hardcoded entries also verify installation directly, since some packages
    // may not surface via queryIntentActivities on all Android versions.
    val hardcodedAvailable = HARDCODED_BROWSERS.filter { b ->
        b.packageName.isEmpty() ||
            b.packageName in dynamicPkgs ||
            runCatching { pm.getApplicationInfo(b.packageName, 0) }.isSuccess
    }
    val hardcodedPkgs = hardcodedAvailable.map { it.packageName }.toSet()
    val rest = dynamic.filter { it.packageName !in hardcodedPkgs }.sortedBy { it.label }
    return hardcodedAvailable + rest
}
