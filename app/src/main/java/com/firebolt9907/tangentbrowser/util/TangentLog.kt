package com.firebolt9907.tangentbrowser.util

import android.util.Log
import com.firebolt9907.tangentbrowser.BuildConfig

/**
 * Centralized debug logging.
 *
 * Verbose trace/debug logs (reader pipeline, addon popups, content-host lifecycle) are useful while
 * developing but must not ship: in release they spam logcat, cost a little CPU/string-building on hot
 * paths, and can leak page content (article titles, URLs) to anything reading the system log. Route
 * that noise through [debug] / [verbose] so it compiles out to a no-op when `BuildConfig.DEBUG` is
 * false. Genuine failures that operators may need in the field should still use [Log] directly at
 * `w`/`e` level.
 */
object TangentLog {
    /** Debug-level trace. No-op in release builds. */
    inline fun debug(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    /** Debug-level trace with a throwable. No-op in release builds. */
    inline fun debug(tag: String, throwable: Throwable?, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message(), throwable)
    }

    /** Verbose-level trace. No-op in release builds. */
    inline fun verbose(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }
}
