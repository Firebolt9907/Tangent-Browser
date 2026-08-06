package com.firebolt9907.tangentbrowser.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Launch a native-app deep link the current page requested (the [Intent] resolved by
 * `TangentRequestInterceptor`). Runs from the application context, so it forces
 * [Intent.FLAG_ACTIVITY_NEW_TASK]. If the app has since vanished, follows the site's declared web
 * [fallbackUrl] via [onFallback] (loading it back in the tab); with no fallback, it reports through
 * [onMessage] so the caller can raise its themed snackbar (this is a top-level helper — see
 * [shareUrl] for why a `Toast` here was the wrong channel).
 */
internal fun launchExternalApp(
    context: Context,
    intent: Intent,
    fallbackUrl: String?,
    onFallback: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val toLaunch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(toLaunch)
    } catch (_: ActivityNotFoundException) {
        if (fallbackUrl != null) {
            onFallback(fallbackUrl)
        } else {
            onMessage("No app found to open this link")
        }
    }
}

/**
 * Open [url] in the non-browser app that claims it (e.g. instagram.com in Instagram), the passive
 * "this site has an app" suggestion. Targets [packageName] specifically; if that app is gone, falls
 * back to a plain VIEW so the system can still route the link. Both guarded — nothing happens if the
 * URL is unhandleable. Mirrors the launch the aA sheet's old "Open in <app>" row used to do.
 */
internal fun launchAssociatedApp(context: Context, url: String, packageName: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        setPackage(packageName)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
