package com.firebolt9907.tangentbrowser.state

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the data-saver blocking extension (`assets/extensions/datasaver/`) should cancel right now.
 *
 * Deliberately *not* a "block CSS and JS" switch, which is the intuitive shape but the wrong one on
 * two counts:
 *
 * 1. **Blocking script at the docshell breaks the app, not just the page.** The obvious lever,
 *    `GeckoSessionSettings.setAllowJavascript(false)`, stops *all* script execution for the
 *    document — including our own `javascript:` injection, which is how the top/bottom viewport
 *    spacers (AGENTS.md invariant #11), the theme-color probe and the reader extractor's fallback
 *    path all work. Cancelling **requests** instead leaves page-world script enabled, so the
 *    injection machinery is untouched while the bytes never arrive.
 * 2. **Stylesheets are the worst byte-for-damage trade on the page.** CSS is a low single-digit
 *    percentage of a typical page's transfer, and dropping it costs the entire visual design. It is
 *    supported here ([aggressiveTypes]) but is only ever applied to hosts where reader mode is known
 *    to render the page from extracted text anyway, so nobody is left looking at unstyled HTML.
 *
 * The three tiers, cheapest damage first:
 * - [blockThirdParty] — types cancelled only when the request is cross-site to the document. This is
 *   where the bulk of the savings live: ad, analytics and tracker script on a typical news page
 *   outweighs the site's own script several times over, and cancelling it leaves the site working.
 * - [blockAll] — types cancelled regardless of party. Images/media/fonts: large, and a page without
 *   them is degraded but perfectly readable.
 * - [aggressiveTypes] on [aggressiveHosts] — first-party script and stylesheet too, for hosts that
 *   have previously produced a reader-mode article ([ReaderHostEvidence]). The reader renders from
 *   extracted DOM text, so neither is needed to read the page.
 *
 * [exemptHosts] is the escape hatch: a host the user has asked to load in full, which overrides all
 * three tiers.
 */
data class DataSaverPolicy(
    val enabled: Boolean,
    val blockAll: Set<String> = emptySet(),
    val blockThirdParty: Set<String> = emptySet(),
    val aggressiveTypes: Set<String> = emptySet(),
    val aggressiveHosts: Set<String> = emptySet(),
    val exemptHosts: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "policy")
        put("enabled", enabled)
        put("blockAll", JSONArray(blockAll.toList()))
        put("blockThirdParty", JSONArray(blockThirdParty.toList()))
        put("aggressiveTypes", JSONArray(aggressiveTypes.toList()))
        put("aggressiveHosts", JSONArray(aggressiveHosts.toList()))
        put("exemptHosts", JSONArray(exemptHosts.toList()))
    }

    companion object {
        /** Inert policy — the extension cancels nothing. */
        val OFF = DataSaverPolicy(enabled = false)

        /**
         * Types cancelled regardless of party while data saver is active. Images dominate a typical
         * mobile page's transfer, `media` covers video/audio the user never asked to play, and web
         * fonts are pure presentation that the system font substitutes for at zero cost.
         */
        val BLOCK_ALL_TYPES = setOf("image", "media", "font")

        /**
         * Types cancelled only cross-site. `script` is the whole point: third-party script *is* the
         * ad/analytics/tag-manager payload, and it also pulls in most of the other third-party bytes
         * behind it. `object` covers legacy plugin embeds. First-party script is deliberately left
         * alone so pages keep working.
         */
        val BLOCK_THIRD_PARTY_TYPES = setOf("script", "object")

        /** Types additionally cancelled on reader-proven hosts, where the article is read as text. */
        val AGGRESSIVE_TYPES = setOf("script", "stylesheet")

        /**
         * Build the policy to push to the extension. [active] is the OS Data Saver signal
         * ([isDataSaverActive]); everything else is host state.
         */
        fun compute(
            active: Boolean,
            readerProvenHosts: Set<String>,
            exemptHosts: Set<String>,
        ): DataSaverPolicy {
            if (!active) return OFF
            return DataSaverPolicy(
                enabled = true,
                blockAll = BLOCK_ALL_TYPES,
                blockThirdParty = BLOCK_THIRD_PARTY_TYPES,
                aggressiveTypes = AGGRESSIVE_TYPES,
                // A host the user asked to load in full must not be reachable through the aggressive
                // tier either, so subtract the exemptions here as well as in the extension's own check.
                aggressiveHosts = readerProvenHosts - exemptHosts,
                exemptHosts = exemptHosts,
            )
        }
    }
}

/**
 * True when the OS Data Saver is restricting this app *and* the current connection is one Data Saver
 * actually applies to.
 *
 * Both halves matter. `restrictBackgroundStatus` reports the app's Data Saver standing, not the
 * network's: it stays `ENABLED` on unmetered Wi-Fi, so keying off it alone would strip images from
 * every page on home Wi-Fi — exactly when there is no reason to. Android's Data Saver only restricts
 * metered connections, so [ConnectivityManager.isActiveNetworkMetered] is the other half of the same
 * question.
 *
 * `RESTRICT_BACKGROUND_STATUS_WHITELISTED` (the user allowlisted Tangent under Data Saver → "Unrestricted
 * data") reads as *not* active: the user has explicitly said this app may spend data.
 *
 * Fails **open** (no blocking) when connectivity can't be read — a data-saver feature should never be
 * the reason a page renders without its assets on an unknown network. Mirrors the Data Saver check in
 * [isPreloadAllowed], which fails closed instead because there the safe default is the opposite (don't
 * spend data on a page the user never opened).
 */
fun isDataSaverActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    if (cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
        return false
    }
    return cm.isActiveNetworkMetered
}
