package com.firebolt9907.luminabrowser.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Fires the system share sheet for a page URL — the single share path behind the SafariBar
 * toolbar's share button, the aA sheet's Share action, and the Custom Tab bottom bar. [title]
 * becomes the subject line in apps that use one (email, etc.). No-ops on blank/`about:blank` URLs.
 * When [context] is not an Activity (the toolbar passes the application context), the chooser
 * needs `FLAG_ACTIVITY_NEW_TASK` to be allowed to launch.
 */
fun shareUrl(context: Context, url: String, title: String? = null) {
    if (url.isBlank() || url == "about:blank") return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    val chooser = Intent.createChooser(send, null).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Fires the system share sheet for a Web Share API request (`navigator.share({title, text, url})`).
 * Web Share carries any combination of title/text/url; we put the title on the subject line and
 * concatenate text + url into the body the way the platform share targets expect. Returns whether
 * the chooser launched, so the caller can resolve the JS promise (onSuccess) or reject it (onFailure).
 */
fun shareWebData(
    context: Context,
    title: String?,
    text: String?,
    url: String?,
): Boolean {
    val body = listOfNotNull(text?.takeIf { it.isNotBlank() }, url?.takeIf { it.isNotBlank() })
        .joinToString(" ")
    if (body.isBlank() && title.isNullOrBlank()) return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body.ifBlank { title })
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    val chooser = Intent.createChooser(send, null).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
        false
    }
}
