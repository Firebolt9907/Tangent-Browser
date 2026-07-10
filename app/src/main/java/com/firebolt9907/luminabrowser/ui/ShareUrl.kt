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
