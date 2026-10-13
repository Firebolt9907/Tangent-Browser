package com.firebolt9907.tangentbrowser.ui.customtab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Freezes gesture pixels and retains the destination only through its first matching paint. */
internal class CustomTabBackPreview<T> {
    data class Preview<T>(
        val generation: Long,
        val tabId: String,
        val targetUrl: String?,
        val previous: T?,
        val current: T?,
        val committed: Boolean = false,
    )

    private var generation = 0L
    private val mutablePreview = MutableStateFlow<Preview<T>?>(null)
    val preview = mutablePreview.asStateFlow()

    fun isCurrent(token: Long?): Boolean = token != null && token == generation

    fun start(tabId: String, targetUrl: String?, previous: T?, current: T?): Long {
        mutablePreview.value = Preview(++generation, tabId, targetUrl, previous, current)
        return generation
    }

    fun captureCurrent(token: Long?, tabId: String, bitmap: T) {
        val p = mutablePreview.value ?: return
        if (!p.committed && p.generation == token && p.tabId == tabId) {
            mutablePreview.value = p.copy(current = bitmap)
        }
    }

    fun loadPrevious(tabId: String, targetUrl: String?, bitmap: T?) {
        val p = mutablePreview.value ?: return
        if (!p.committed && p.tabId == tabId && p.targetUrl == targetUrl && p.previous == null && bitmap != null) {
            mutablePreview.value = p.copy(previous = bitmap)
        }
    }

    fun commit(token: Long) {
        val p = mutablePreview.value ?: return
        if (p.generation != token || p.committed) return
        // An absent history capture must not produce a blank cover over a working live page.
        mutablePreview.value = if (p.previous != null && p.targetUrl != null) {
            p.copy(current = null, committed = true)
        } else null
    }

    fun painted(tabId: String, url: String) {
        val p = mutablePreview.value ?: return
        if (p.committed && p.tabId == tabId && p.targetUrl == url) clear()
    }

    fun cancel(token: Long) {
        val p = mutablePreview.value ?: return
        if (p.generation == token && !p.committed) clear()
    }

    fun clear() { mutablePreview.value = null }
}
