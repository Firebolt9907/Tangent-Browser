package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/** One element the user zapped on a site: the CSS [selector] plus a human [label] for the undo UI. */
data class ZappedElement(val selector: String, val label: String)

/** Per-host cap. Generous for a heavy de-clutterer, bounded so a runaway can't bloat the prefs blob. */
internal const val MaxZappedPerHost = 100

/** Longest selector we will store; the generator's own path is capped well below this. */
private const val MaxSelectorLength = 512

/**
 * Characters a stored zap selector may contain. Deliberately narrower than CSS itself: the selectors
 * are re-emitted verbatim into a `<style>` rule's text, so anything that could *close* a rule or open
 * a CSS comment (`{`, `}`, `;`, `@`, or a slash-star) would let a page-authored id/class smuggle
 * arbitrary CSS in —
 * including a `display:none` on everything. The picker only ever generates tag / `#id` / `.class` /
 * `:nth-of-type(n)` segments joined by ` > `, all of which fit inside this set.
 */
private val SafeSelectorChars = Regex("^[A-Za-z0-9_\\-#.>:()\\[\\]=\"' *,]+$")

/**
 * True when [selector] is safe to persist and to splice into a stylesheet. Pure and top-level so the
 * JVM tests can exercise it without an Android context. See [SafeSelectorChars] for why this is an
 * allowlist rather than a blocklist.
 */
internal fun isSafeZapSelector(selector: String): Boolean {
    val s = selector.trim()
    if (s.isEmpty() || s.length > MaxSelectorLength) return false
    if (!SafeSelectorChars.matches(s)) return false
    // Never let a rule target the whole document — a stored `html`/`body`/`*` would blank the site
    // with no obvious way back for a user who doesn't know where "Restore hidden elements" lives.
    val head = s.substringBefore(' ').trim()
    if (head == "html" || head == "body" || head == "*" || head == ":root") return false
    return true
}

/**
 * Per-host list of "zapped" (user-hidden) page elements, persisted in [android.content.SharedPreferences]
 * and re-applied on every load of that host by `ContentHost` (see `ui/content/ElementZap.kt`).
 *
 * Keyed by the same host function as [SitePreferencesStore] — `siteHostFromUrl` — so an archived view
 * of a site inherits the site's own hidden elements. [updates] emits the changed host so the live page
 * can re-apply immediately without a reload.
 */
class ZappedElementsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tangent_zapped_elements", Context.MODE_PRIVATE)
    private val _updates = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun get(host: String): List<ZappedElement> {
        if (host.isEmpty()) return emptyList()
        val raw = prefs.getString(host, null) ?: return emptyList()
        return parse(raw)
    }

    /** Just the selectors, in insertion order — what the injected stylesheet is built from. */
    fun selectors(host: String): List<String> = get(host).map { it.selector }

    fun count(host: String): Int = get(host).size

    /**
     * Adds [selector] for [host]. Returns false when the selector is unsafe ([isSafeZapSelector]),
     * already present, or the host is at [MaxZappedPerHost].
     */
    fun add(host: String, selector: String, label: String): Boolean {
        if (host.isEmpty()) return false
        val sel = selector.trim()
        if (!isSafeZapSelector(sel)) return false
        val current = get(host)
        if (current.size >= MaxZappedPerHost) return false
        if (current.any { it.selector == sel }) return false
        write(host, current + ZappedElement(sel, label.take(120)))
        return true
    }

    fun remove(host: String, selector: String) {
        if (host.isEmpty()) return
        val current = get(host)
        val next = current.filterNot { it.selector == selector }
        if (next.size != current.size) write(host, next)
    }

    fun clear(host: String) {
        if (host.isEmpty()) return
        prefs.edit { remove(host) }
        _updates.tryEmit(host)
    }

    private fun write(host: String, elements: List<ZappedElement>) {
        val array = JSONArray()
        elements.forEach { element ->
            array.put(JSONObject().put(KeySelector, element.selector).put(KeyLabel, element.label))
        }
        prefs.edit { putString(host, array.toString()) }
        _updates.tryEmit(host)
    }

    private fun parse(raw: String): List<ZappedElement> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val selector = obj.optString(KeySelector).orEmpty()
                // Re-validate on read: a selector written by an older, laxer build (or a hand-edited
                // prefs file on a rooted device) must not reach the stylesheet builder.
                if (!isSafeZapSelector(selector)) continue
                add(ZappedElement(selector, obj.optString(KeyLabel).ifBlank { selector }))
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KeySelector = "s"
        const val KeyLabel = "l"
    }
}
