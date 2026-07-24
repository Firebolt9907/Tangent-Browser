package com.firebolt9907.tangentbrowser.ui.chrome

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri

/**
 * App/browser resolution for the "aA" sheet's "Open in…" actions.
 *
 * Pure [PackageManager] logic (no Compose) — extracted from `AaSheet.kt` so the sheet's
 * composables stay focused on UI. Callers run these off the main thread ([installedBrowsers]
 * in particular issues several `queryIntentActivities` calls).
 */

private const val OWN_PKG = "com.firebolt9907.tangentbrowser"

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
    // A "browser" = an app that handles a **host-less** `http:` VIEW/BROWSABLE intent — the exact
    // definition the Android system uses for its default-browser picker. Two things matter here and
    // both were missing before (so only the hardcoded few showed up):
    //  - CATEGORY_BROWSABLE: browsers register their web intent-filter under it; without the category
    //    the query matches almost nothing.
    //  - a host-less URI (`http:` with empty host, not `https://example.com`): every real browser
    //    claims the generic web scheme, while host-specific apps (Instagram, Maps, …) do NOT — so
    //    this returns browsers only, and MATCH_ALL returns *every* installed one, not just the default.
    val probe = Intent()
        .setAction(Intent.ACTION_VIEW)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setData(Uri.fromParts("http", "", null))
    val dynamic = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        .map { ri ->
            BrowserInfo(
                packageName = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
            )
        }
        .distinctBy { it.packageName }
        .filter { it.packageName != OWN_PKG }
        .sortedBy { it.label.lowercase() }

    // Fallback for the rare device/OS combo where the system query returns nothing: verify the
    // hardcoded packages are actually installed.
    val discovered = dynamic.ifEmpty {
        HARDCODED_BROWSERS.drop(1).filter {
            runCatching { pm.getApplicationInfo(it.packageName, 0) }.isSuccess
        }
    }

    // "Default browser" (no package constraint) always leads, then every installed browser A→Z.
    return listOf(BrowserInfo("", "Default browser")) + discovered
}
