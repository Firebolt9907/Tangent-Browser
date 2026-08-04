package com.firebolt9907.tangentbrowser.state

import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageVisit

/**
 * Keeps the transient hops of a federated sign-in chain out of browsing history.
 *
 * The History sheet already filters `VisitType.REDIRECT_TEMPORARY`/`_PERMANENT`, so plain 302 hops
 * never showed up. SAML chains are the gap: their hops are **auto-submitted forms**, which Places
 * records as ordinary `LINK` visits. Without this, one Microsoft 365 sign-in deposits half a dozen
 * `login.microsoftonline.com/…?SAMLRequest=…` entries — each unrepeatable (the tokens in them are
 * single-use), each pushing real pages out of the recent-history view and the address-bar ranking.
 *
 * Wraps rather than replaces so it composes with [com.firebolt9907.tangentbrowser.ui.archive.DeArchivingHistoryDelegate].
 * Only the recording side is filtered; [getVisited] passes through untouched, exactly as in the
 * de-archiving delegate — those answer for links inside the current page and must stay truthful.
 */
class SsoFilteringHistoryDelegate(
    private val wrapped: HistoryTrackingDelegate,
) : HistoryTrackingDelegate {

    override suspend fun onVisited(uri: String, visit: PageVisit) {
        if (SsoFlow.isAuthUrl(uri)) return
        wrapped.onVisited(uri, visit)
    }

    override suspend fun onTitleChanged(uri: String, title: String) {
        if (SsoFlow.isAuthUrl(uri)) return
        wrapped.onTitleChanged(uri, title)
    }

    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) {
        if (SsoFlow.isAuthUrl(uri)) return
        wrapped.onPreviewImageChange(uri, previewImageUrl)
    }

    override suspend fun getVisited(uris: List<String>): List<Boolean> = wrapped.getVisited(uris)

    override suspend fun getVisited(): List<String> = wrapped.getVisited()

    override fun shouldStoreUri(uri: String): Boolean =
        !SsoFlow.isAuthUrl(uri) && wrapped.shouldStoreUri(uri)
}
