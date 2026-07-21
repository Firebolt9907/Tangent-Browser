package com.firebolt9907.luminabrowser.ui.archive

import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageVisit

/**
 * Wraps the real [HistoryTrackingDelegate] so a visit to a Wayback (web.archive.org) snapshot is
 * recorded under the **original** page URL, not the archive wrapper. Without this, browsing an
 * archived page pollutes history with `web.archive.org/web/<timestamp>/…` entries, which is
 * inconsistent with the rest of the app — the address pill, share, bookmark and tab switcher all
 * present the de-archived URL (see [WaybackManager]).
 *
 * Only the **recording** side ([onVisited] / [onTitleChanged] / [onPreviewImageChange] /
 * [shouldStoreUri]) is rewritten. The link-"visited" query methods ([getVisited]) pass through
 * untouched: those answer for the links inside the current page, which on an archived page are
 * themselves archive URLs, so rewriting them would mismatch the engine's own link identities.
 *
 * De-archiving is a no-op for anything that isn't a snapshot URL (the Internet Archive's own site,
 * search/listing pages, etc.), so ordinary web.archive.org browsing is recorded verbatim.
 */
class DeArchivingHistoryDelegate(
    private val wrapped: HistoryTrackingDelegate,
    private val wayback: WaybackManager = WaybackManager.instance,
) : HistoryTrackingDelegate {

    private fun deArchive(uri: String): String =
        if (wayback.isArchiveUrl(uri)) wayback.originalFromArchiveUrl(uri) ?: uri else uri

    override suspend fun onVisited(uri: String, visit: PageVisit) =
        wrapped.onVisited(deArchive(uri), visit)

    override suspend fun onTitleChanged(uri: String, title: String) =
        wrapped.onTitleChanged(deArchive(uri), title)

    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) =
        wrapped.onPreviewImageChange(deArchive(uri), previewImageUrl)

    override suspend fun getVisited(uris: List<String>): List<Boolean> = wrapped.getVisited(uris)

    override suspend fun getVisited(): List<String> = wrapped.getVisited()

    override fun shouldStoreUri(uri: String): Boolean = wrapped.shouldStoreUri(deArchive(uri))
}
