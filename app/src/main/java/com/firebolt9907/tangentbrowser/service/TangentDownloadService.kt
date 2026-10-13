package com.firebolt9907.tangentbrowser.service

import com.firebolt9907.tangentbrowser.TangentApplication
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.support.utils.DefaultDateTimeProvider

/** Shared, lifecycle-independent queue for the browser and Custom Tabs. */
class TangentDownloadService : AbstractFetchDownloadService() {
    private val components get() = (application as TangentApplication).components
    override val store get() = components.store
    override val notificationsDelegate get() = components.downloadNotifications
    override val packageNameProvider by lazy { DefaultPackageNameProvider(this) }
    override val httpClient get() = components.downloadHttpClient

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID)
        val status = id?.let { store.state.downloads[it]?.status }
        if (id != null && status != mozilla.components.browser.state.state.content.DownloadState.Status.PAUSED) {
            resumableJobs.add(id)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        resumableJobs.clear()
        super.onDestroy()
    }

    companion object {
        // The upstream service's live job holds the exact byte offset. Its broadcast resume path
        // preserves that offset; recreating a job through TRY_AGAIN restarts the transfer.
        private val resumableJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun canResume(id: String): Boolean = id in resumableJobs
    }

    // Downloads continue when an external caller closes its Custom Tab task.
    override fun onTaskRemoved(rootIntent: android.content.Intent?) = Unit
    override val fileSizeFormatter by lazy { DefaultFileSizeFormatter(this) }
    override val downloadEstimator = DownloadEstimator(DefaultDateTimeProvider())
    override val downloadFileUtils get() = components.downloadFileUtils
    override val downloadFileWriter by lazy { DefaultDownloadFileWriter(this, downloadFileUtils) }
}
