package com.firebolt9907.luminabrowser.ui.archive

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backs the in-app "Internet Archive" (Wayback Machine) time-travel mode. One [ArchiveSession] per
 * tab is kept in memory (like [com.firebolt9907.luminabrowser.ui.reader.ReaderManager]'s active
 * set) — nothing is persisted; entering archive mode is a per-tab action the user takes from the
 * aA sheet, and it ends when they Close the scrubber or navigate away.
 *
 * Data comes from the **same endpoints the Wayback desktop calendar uses**, so the in-app selector
 * replicates it (no dependency, plain [HttpURLConnection] like
 * [com.firebolt9907.luminabrowser.ui.reader.ReaderRules]):
 * - **sparkline** (`/__wb/sparkline`) → per-year totals + per-month counts (the year bar graph).
 * - **calendarcaptures v2** (`/__wb/calendarcaptures/2`) → per-day captures for one year (the
 *   highlighted calendar), fetched lazily when a year is selected.
 *
 * Snapshots load via the `if_` variant so the Wayback toolbar is stripped and only our own selector
 * shows; [buildMobilifyScript] then normalizes the (often pre-responsive) capture to device width.
 */
class WaybackManager {

    /** A year's capture activity: [total] captures, and [monthly] counts for Jan..Dec (12 ints). */
    data class YearData(val year: Int, val total: Int, val monthly: List<Int>)

    /** One day that has captures: [count] snapshots, [timestamp] a representative 14-digit stamp. */
    data class DayCapture(val month: Int, val day: Int, val count: Int, val timestamp: String)

    enum class Phase { Loading, Ready, Empty, Error }

    data class ArchiveSession(
        val tabId: String,
        /** The live URL the user entered archive mode from; Close returns here. */
        val originalUrl: String,
        val years: List<YearData> = emptyList(),
        val selectedYear: Int? = null,
        /** Per-day captures for [selectedYear] (empty while [yearLoading]). */
        val yearDays: List<DayCapture> = emptyList(),
        val yearLoading: Boolean = false,
        /** The 14-digit timestamp currently being viewed, or null before the first pick. */
        val selected: String? = null,
        val phase: Phase = Phase.Loading,
    )

    private val _sessions = MutableStateFlow<Map<String, ArchiveSession>>(emptyMap())
    val sessions: StateFlow<Map<String, ArchiveSession>> = _sessions.asStateFlow()

    fun sessionFor(tabId: String?): ArchiveSession? = tabId?.let { _sessions.value[it] }

    fun isActive(tabId: String?): Boolean = sessionFor(tabId) != null

    /**
     * Enter archive mode for [tabId] on [originalUrl]: publish a Loading session immediately (so the
     * selector appears with a spinner), fetch the year sparkline off the main thread, then default
     * to the newest year and load its calendar.
     */
    suspend fun enter(tabId: String, originalUrl: String, initialSelected: String? = null) {
        _sessions.update {
            it + (tabId to ArchiveSession(tabId, originalUrl, selected = initialSelected))
        }
        val years = runCatching { fetchSparkline(originalUrl) }.getOrNull()
        // Open on the initially-viewed snapshot's year (restart case), else the newest year.
        val initialYear = initialSelected?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        val settledYear = years?.firstOrNull { it.year == initialYear }?.year
            ?: years?.lastOrNull()?.year
        _sessions.update { current ->
            val existing = current[tabId] ?: return@update current
            val settled = when {
                years == null -> existing.copy(phase = Phase.Error)
                years.isEmpty() -> existing.copy(phase = Phase.Empty)
                else -> existing.copy(
                    years = years,
                    selectedYear = settledYear,
                    yearLoading = true,
                    phase = Phase.Ready,
                )
            }
            current + (tabId to settled)
        }
        if (years != null && settledYear != null) loadYear(tabId, originalUrl, settledYear)
    }

    /** Select a year in the bar graph; loads that year's calendar (per-day captures). */
    suspend fun selectYear(tabId: String, year: Int) {
        val session = _sessions.value[tabId] ?: return
        if (session.selectedYear == year && session.yearDays.isNotEmpty()) return
        _sessions.update { current ->
            val existing = current[tabId] ?: return@update current
            current + (tabId to existing.copy(
                selectedYear = year, yearLoading = true, yearDays = emptyList(),
            ))
        }
        loadYear(tabId, session.originalUrl, year)
    }

    private suspend fun loadYear(tabId: String, originalUrl: String, year: Int) {
        val days = runCatching { fetchYearCalendar(originalUrl, year) }.getOrNull().orEmpty()
        _sessions.update { current ->
            val existing = current[tabId] ?: return@update current
            // Ignore a stale response if the user moved to another year meanwhile.
            if (existing.selectedYear != year) return@update current
            current + (tabId to existing.copy(yearDays = days, yearLoading = false))
        }
    }

    /** Mark [timestamp] as the one being viewed (the UI then issues the load). */
    fun selectDate(tabId: String, timestamp: String) {
        _sessions.update { current ->
            val existing = current[tabId] ?: return@update current
            current + (tabId to existing.copy(selected = timestamp))
        }
    }

    fun exit(tabId: String) {
        _sessions.update { it - tabId }
    }

    // ---- URL helpers ----

    /** The seamless (`if_`, no Wayback toolbar) snapshot URL for [originalUrl] at [timestamp]. */
    fun archiveUrl(originalUrl: String, timestamp: String): String =
        "https://web.archive.org/web/${timestamp}if_/$originalUrl"

    /** True for any `web.archive.org/web/...` URL (used to gate the mobilify injection). */
    fun isArchiveUrl(url: String?): Boolean =
        url != null && ARCHIVE_PREFIX.containsMatchIn(url)

    /**
     * The original (non-archived) URL embedded in a `web.archive.org/web/<ts>[modifier]/<url>` URL,
     * so navigation *within* the archive keeps a stable original for the selector and Close.
     */
    fun originalFromArchiveUrl(url: String): String? =
        ARCHIVE_PREFIX.find(url)?.let { url.substring(it.range.last + 1) }

    /** The 14-digit capture timestamp embedded in a `web.archive.org/web/<ts>…` URL. */
    fun timestampFromArchiveUrl(url: String): String? =
        TIMESTAMP_IN_URL.find(url)?.groupValues?.getOrNull(1)

    // ---- Networking ----

    /** sparkline → [YearData] list ordered oldest→newest. `years` maps "YYYY" → [12 month counts]. */
    private suspend fun fetchSparkline(originalUrl: String): List<YearData> =
        withContext(Dispatchers.IO) {
            val enc = URLEncoder.encode(originalUrl, Charsets.UTF_8.name())
            val api = "https://web.archive.org/__wb/sparkline?output=json&url=$enc&collection=web"
            val body = httpGet(api) ?: return@withContext emptyList()
            val years = runCatching { JSONObject(body).optJSONObject("years") }.getOrNull()
                ?: return@withContext emptyList()
            years.keys().asSequence()
                .mapNotNull { key ->
                    val y = key.toIntOrNull() ?: return@mapNotNull null
                    val arr = years.optJSONArray(key) ?: return@mapNotNull null
                    val monthly = (0 until arr.length()).map { arr.optInt(it) }
                    YearData(y, monthly.sum(), monthly)
                }
                .filter { it.total > 0 }
                .sortedBy { it.year }
                .toList()
        }

    /**
     * calendarcaptures v2 → per-day captures for [year]. `items` entries are
     * `[MDDHHMMSS, statusCode, count]`; the first field encodes month(1–2)+day(2)+time(6). We
     * aggregate multiple captures per day into one [DayCapture] (representative earliest stamp,
     * summed count).
     */
    private suspend fun fetchYearCalendar(originalUrl: String, year: Int): List<DayCapture> =
        withContext(Dispatchers.IO) {
            val enc = URLEncoder.encode(originalUrl, Charsets.UTF_8.name())
            val api = "https://web.archive.org/__wb/calendarcaptures/2?url=$enc&date=$year"
            val body = httpGet(api) ?: return@withContext emptyList()
            val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull()
                ?: return@withContext emptyList()
            // day-key (month*100+day) → (count, earliest full timestamp)
            val byDay = LinkedHashMap<Int, Pair<Int, String>>()
            for (i in 0 until items.length()) {
                val it = items.optJSONArray(i) ?: continue
                val frag = it.optLong(0).toString()          // MDDHHMMSS (9 or 10 digits)
                if (frag.length < 8) continue
                val hhmmss = frag.takeLast(6)
                val dd = frag.dropLast(6).takeLast(2)
                val mm = frag.dropLast(8).ifEmpty { "0" }
                val month = mm.toIntOrNull() ?: continue
                val day = dd.toIntOrNull() ?: continue
                if (month !in 1..12 || day !in 1..31) continue
                val count = it.optInt(2, 1).coerceAtLeast(1)
                val stamp = "$year${mm.padStart(2, '0')}$dd$hhmmss"
                val key = month * 100 + day
                val prev = byDay[key]
                if (prev == null) {
                    byDay[key] = count to stamp
                } else {
                    byDay[key] = (prev.first + count) to minOf(prev.second, stamp)
                }
            }
            byDay.map { (key, v) ->
                DayCapture(month = key / 100, day = key % 100, count = v.first, timestamp = v.second)
            }.sortedWith(compareBy({ it.month }, { it.day }))
        }

    /**
     * Request a fresh capture of [url] via the Wayback Machine's anonymous "Save Page Now" endpoint
     * (`/save/<url>`). Returns true if the archive accepted/completed the capture. Runs off the main
     * thread with a generous read timeout — a save can take a while server-side.
     */
    suspend fun savePage(url: String): Boolean = withContext(Dispatchers.IO) {
        // The sub-URL is appended raw (unencoded), matching the Save Page Now bookmarklet convention.
        val api = "https://web.archive.org/save/$url"
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = NET_TIMEOUT_MS
            readTimeout = SAVE_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ExpeditionBrowser")
        }
        try {
            conn.responseCode in 200..399
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = NET_TIMEOUT_MS
            readTimeout = NET_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ExpeditionBrowser")
            // The sparkline endpoint 404s without a web.archive.org referer.
            setRequestProperty("Referer", "https://web.archive.org/")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val instance = WaybackManager()

        private const val NET_TIMEOUT_MS = 15000
        private const val SAVE_TIMEOUT_MS = 90000
        private val ARCHIVE_PREFIX =
            Regex("""https?://web\.archive\.org/web/\d{14}(?:\w{2}_)?/""")
        private val TIMESTAMP_IN_URL =
            Regex("""web\.archive\.org/web/(\d{14})""")

        /**
         * Force the archived document to lay out at device width. Old (pre-responsive) captures ship
         * no `<meta viewport>`, so Gecko falls back to a ~980px desktop viewport and the page renders
         * tiny/zoomed-out; we install (or rewrite) a device-width viewport and re-show the natural
         * text size. Also hides any residual Wayback chrome as belt-and-suspenders on top of `if_`.
         *
         * The width-cap rules reflow desktop-only captures to the screen: many sites server-render for
         * a desktop `deviceType` and bake fixed pixel widths (e.g. `width:740px`/`1024px`) straight
         * into elements, and their client JS — which would re-render for the real device — doesn't
         * replay from the archive, so a device-width viewport alone can't help. Capping media and any
         * inline-width element to `max-width:100%` (plus `overflow-x:clip`) shrinks that hardcoded
         * desktop layout to fit. It's a heuristic, not a true responsive re-render.
         *
         * The x-clamp uses `overflow-x:clip`, NOT `hidden`: per the CSS overflow spec, `overflow-x:
         * hidden` with a `visible` `overflow-y` promotes the computed `overflow-y` to `auto`, turning
         * `<body>` into its own scroll container. That clipped taller captures to the viewport height
         * (content below the fold became unreachable) and — because the ROOT no longer scrolled —
         * stopped Gecko's dynamic-toolbar/scroll callbacks, so the pill couldn't collapse. `clip`
         * suppresses horizontal panning the same way without establishing a scroll container, so the
         * document keeps scrolling normally.
         *
         * `fixImages` recovers the common lazy-loaded images: many sites (lazysizes/lozad/jetpack,
         * etc.) keep the real URL in `data-src`/`data-srcset` and only assign `src` via JS on scroll —
         * which often doesn't fire under replay — so we promote those attributes and force
         * `loading=eager`. (Fully JS-driven images with no `data-*` — e.g. Google's boq pages — can't
         * be recovered: their URLs live only in scripts the archive can't reproduce.)
         *
         * `color-scheme: light dark` makes the browser resolve *default* (unstyled) text/background
         * to the system-preferred system colors. Archived pages never declare a color scheme, so
         * their default text is always black — which reads as black-on-dark once a dark-mode
         * extension darkens the background. Declaring the scheme lets that default text follow the OS
         * theme (light on dark devices), so the archive view works with dark-mode extensions with no
         * user involvement, and old minimally-styled pages go dark on their own. Elements with their
         * own explicit colors are untouched; light-mode devices are unaffected.
         *
         * Injected nodes carry `data-luminaIgnoreSafeTop` so ContentHost's top-inset self-heal walk
         * skips them (AGENTS.md invariant #11). The call site sets `suppressJavascriptLoadCallback`.
         */
        fun buildMobilifyScript(): String = """
            javascript:(()=>{if(window.__luminaWayback)return;window.__luminaWayback=true;
            function fixViewport(){var m=document.querySelector('meta[name=viewport]');
            if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
            m.setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');
            m.setAttribute('data-luminaIgnoreSafeTop','1');}
            function addStyle(){if(document.getElementById('lumina-wb-style'))return;
            var s=document.createElement('style');s.id='lumina-wb-style';s.setAttribute('data-luminaIgnoreSafeTop','1');
            s.textContent=':root{color-scheme:light dark}'
            +'html{-webkit-text-size-adjust:100%;text-size-adjust:100%}'
            +'html,body{max-width:100%!important;overflow-x:clip!important}'
            +'img,picture,video,iframe,svg,canvas,embed,object{max-width:100%!important;height:auto!important}'
            +'[style*="width"]{max-width:100%!important}'
            +'#wm-ipp-base,#wm-ipp,#donato,.wb-autocomplete-suggestions,#playback{display:none!important}';
            (document.head||document.documentElement).appendChild(s);}
            function addColorSchemeMeta(){if(document.querySelector('meta[name=color-scheme]'))return;
            var m=document.createElement('meta');m.name='color-scheme';m.content='light dark';
            m.setAttribute('data-luminaIgnoreSafeTop','1');(document.head||document.documentElement).appendChild(m);}
            function fixImages(){try{
            var imgs=document.querySelectorAll('img');
            for(var i=0;i<imgs.length;i++){var im=imgs[i];
            var ds=im.getAttribute('data-src')||im.getAttribute('data-lazy-src')||im.getAttribute('data-original')||im.getAttribute('data-iurl');
            if(ds&&!im.getAttribute('src')){im.setAttribute('src',ds);}
            var dss=im.getAttribute('data-srcset')||im.getAttribute('data-lazy-srcset');
            if(dss&&!im.getAttribute('srcset')){im.setAttribute('srcset',dss);}
            if(im.getAttribute('loading')==='lazy'){im.setAttribute('loading','eager');}}
            var srcs=document.querySelectorAll('source[data-srcset]');
            for(var j=0;j<srcs.length;j++){var s=srcs[j];if(!s.getAttribute('srcset')){s.setAttribute('srcset',s.getAttribute('data-srcset'));}}
            }catch(e){}}
            function fixGrids(){var hit=0,seen=0;try{
            if(window.innerWidth>=700){console.log('LUMINA-WB skip grids, innerWidth='+window.innerWidth);return;}
            var all=document.querySelectorAll('*');
            for(var i=0;i<all.length;i++){var el=all[i];if(el.getAttribute('data-luminaGrid'))continue;
            var cs=getComputedStyle(el);
            if(cs.display.indexOf('grid')===-1)continue;
            seen++;
            var cols=cs.gridTemplateColumns;if(!cols||cols==='none')continue;
            var n=cols.split(' ').length;if(n<3)continue;
            hit++;el.setAttribute('data-luminaGrid','1');
            el.style.setProperty('grid-template-columns','1fr','important');
            el.style.setProperty('grid-auto-flow','row','important');
            for(var k=0;k<el.children.length;k++){var c=el.children[k];
            c.style.setProperty('grid-column','auto','important');c.style.setProperty('grid-row','auto','important');}}
            }catch(e){console.log('LUMINA-WB grid error '+e);}
            console.log('LUMINA-WB fixGrids iw='+window.innerWidth+' gridsSeen='+seen+' collapsed='+hit+' totalEls='+document.querySelectorAll('*').length);}
            console.log('LUMINA-WB injected on '+location.href.slice(0,80));
            fixViewport();addStyle();addColorSchemeMeta();fixImages();fixGrids();
            var n=0,iv=setInterval(function(){fixImages();if(n<5)fixGrids();if(++n>8)clearInterval(iv);},700);
            })()
        """.trimIndent().replace("\n", "")
    }
}
