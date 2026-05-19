package com.firebolt9907.luminabrowser

import android.app.Application
import com.firebolt9907.luminabrowser.engine.LuminaBrowserComponents
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.rusthttp.RustHttpConfig

class LuminaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServicesInitializer.init(AppServicesInitializer.Config(null))
        RustHttpConfig.setClient(lazy { HttpURLConnectionClient() })
    }

    val components: LuminaBrowserComponents by lazy {
        LuminaBrowserComponents(this)
    }
}
