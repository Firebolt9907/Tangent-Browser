package com.firebolt9907.tangentbrowser.util

import android.os.StrictMode

/**
 * Debug-only StrictMode wiring.
 *
 * StrictMode surfaces the classes of bug that don't crash but degrade a shipping browser: disk or
 * network I/O on the main thread (jank / ANRs during startup and navigation), leaked `Closeable`s
 * (DB cursors, streams, GeckoView surfaces), and leaked `BroadcastReceiver`/`ContentObserver`
 * registrations. We run it with `penaltyLog` only — violations are logged (grep logcat for
 * `StrictMode`) so they're caught in development, never `penaltyDeath`, so a stray third-party
 * violation can't crash a dev build.
 *
 * Call once from `Application.onCreate`, gated on `BuildConfig.DEBUG`, so release builds pay nothing.
 */
object TangentStrictMode {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .detectResourceMismatches()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }
}
