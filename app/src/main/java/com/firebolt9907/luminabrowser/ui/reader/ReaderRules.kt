package com.firebolt9907.luminabrowser.ui.reader

import android.content.Context
import android.util.Log
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Runtime, per-site reader overrides — the "filters and includes" layer over the generic extractor.
 *
 * The heuristic in [ReaderExtractor] handles most articles, but some sites need a nudge:
 *  - `include` — CSS selector(s) for the real article container. The first visible match becomes
 *    the content root, bypassing the heuristic. Fixes pages that fail to trigger reader mode at all,
 *    extract only images and no text, or where the heuristic grabs a too-broad wrapper.
 *  - `strip`   — CSS selector(s) whose subtrees are excluded before extraction. Fixes pages that
 *    pull in promos / related-content / navigation text from outside the article body.
 *
 * Rules are DATA, not code: they are interpreted by the fixed extractor, which can only *select* and
 * *drop* DOM. A bad or hostile rule can therefore at worst yield a poor extraction — never run
 * arbitrary code.
 *
 * ## Trust model: bundled floor, signed remote upgrade
 *
 * There are two sources, and the code never trusts the remote one unless it is cryptographically
 * verified:
 *  - **Bundled floor** — `assets/reader_rules.json`, a compile-time snapshot of the live config. It
 *    ships inside the (APK-signed) app and went through review, so it is always trusted. This is the
 *    fallback whenever the remote copy is unavailable, unverified, or the update toggle is off.
 *  - **Signed remote upgrade** — [refreshIfDue] bulk-fetches the whole ruleset from [REMOTE_URL] plus
 *    its detached signature from [REMOTE_SIG_URL], verifies an **Ed25519** signature over the exact
 *    payload bytes against [PUBLIC_KEY_B64] (embedded in the APK), and only then caches and applies
 *    it — and only if its `version` is greater than the loaded one (anti-rollback).
 *
 * Why the signature and not just HTTPS: TLS authenticates the transport to GitHub, not the bytes.
 * Anyone who can write to the repo (a stolen token, a bad maintainer, the update agent's own
 * credentials) could otherwise serve a ruleset to every user. With the signature, repo write-access
 * is not enough — an attacker also needs the offline private key.
 *
 * [cached] therefore resolves to:
 *  - toggle **off** → the bundled floor (no network, no remote data);
 *  - toggle **on** → the verified remote ruleset if one is loaded, else the bundled floor.
 *
 * If verification "silently" fails at runtime (bad/absent signature, wrong key, tampered file), the
 * remote payload is discarded and never becomes active — extraction keeps using the bundled floor
 * (or the last remote copy that *did* verify). The remote channel is inert until [PUBLIC_KEY_B64] is
 * set to a real key and the published file is signed with the matching private key.
 *
 * Bundled default drives behaviour until [init] runs (once, at app start); until then (and in unit
 * tests) [cached] returns "[]".
 */
object ReaderRules {
    private const val TAG = "ReaderRules"
    private const val ASSET = "reader_rules.json"
    private const val REMOTE_FILE = "reader_rules_remote.json"
    private const val REMOTE_SIG_FILE = "reader_rules_remote.sig"

    /**
     * Public GitHub raw URL of the rules file. **Point this at your own repo.** Fetched in full
     * (bulk), so the path is identical for every user and never leaks browsing history.
     */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/firebolt9907/lumina-reader-rules/main/reader_rules.json"

    /** Detached signature (base64 of the 64-byte Ed25519 signature over the payload bytes). */
    const val REMOTE_SIG_URL = "$REMOTE_URL.sig"

    /**
     * Base64 of the 32-byte Ed25519 **public** key whose private half signs the published rules.
     * Empty by default, which makes every remote payload fail verification (bundled floor is used).
     * Generate a keypair offline, sign reader_rules.json to produce reader_rules.json.sig, and paste
     * the public key here. Keep the private key offline — never in the repo or CI.
     */
    const val PUBLIC_KEY_B64 = ""

    private const val MIN_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000 /* 12h */
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_SIG_BYTES = 4 * 1024
    private const val NET_TIMEOUT_MS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Compile-time floor from the bundled asset — always trusted. */
    @Volatile
    private var bundledJson: String = "[]"

    /** Active ruleset: the verified remote upgrade if loaded, else [bundledJson]. */
    @Volatile
    private var activeJson: String = "[]"

    @Volatile
    private var loadedVersion: Int = -1

    /**
     * User toggle ([GlobalPreferencesStore.readerSiteRules]). Off → [cached] returns the bundled floor
     * and [refreshIfDue] performs no network I/O (the update opt-out). Mirrored at app start and on
     * every change.
     */
    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Compact JSON array string, safe to inline verbatim into the extractor script. Never null. */
    fun cached(): String = if (enabled) activeJson else bundledJson

    /**
     * Load the active ruleset. The bundled asset is the floor; a cached remote file is used only if
     * its signature re-verifies here (guarding against on-disk tampering) and its version is at least
     * the bundled one. Cheap and synchronous; call once at app start before the first page loads.
     */
    fun init(context: Context) {
        val bundled = readAsset(context)?.let { parseOrNull(it) }
        bundledJson = bundled?.compactJson ?: "[]"

        val remoteBody = readLocal(context, REMOTE_FILE)
        val remoteSig = readLocal(context, REMOTE_SIG_FILE)
        val remote = remoteBody
            ?.takeIf { remoteSig != null && verifyEd25519(it.toByteArray(Charsets.UTF_8), remoteSig, PUBLIC_KEY_B64) }
            ?.let { parseOrNull(it) }

        val useRemote = remote != null && (bundled == null || remote.version >= bundled.version)
        val chosen = if (useRemote) remote else bundled
        activeJson = chosen?.compactJson ?: bundledJson
        loadedVersion = chosen?.version ?: -1
    }

    /**
     * Kick off a throttled background refresh from [REMOTE_URL]. No-ops when the toggle is off or the
     * last check was under [MIN_CHECK_INTERVAL_MS] ago (unless [force]). Never blocks the caller and
     * never throws — any failure (network, bad signature, older version) leaves the active ruleset in
     * place.
     */
    fun refreshIfDue(context: Context, prefs: GlobalPreferencesStore, force: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.readerRulesLastCheckMs < MIN_CHECK_INTERVAL_MS) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching { fetchAndApply(appContext, prefs) }
                .onFailure { Log.d(TAG, "reader rules refresh failed: ${it.message}") }
        }
    }

    private fun fetchAndApply(context: Context, prefs: GlobalPreferencesStore) {
        prefs.readerRulesLastCheckMs = System.currentTimeMillis()
        val body = httpGet(REMOTE_URL, MAX_PAYLOAD_BYTES) ?: return
        val sig = httpGet(REMOTE_SIG_URL, MAX_SIG_BYTES)?.trim()

        /* Trust gate: the payload must carry a valid Ed25519 signature from our offline key. A missing,
           malformed, or wrong signature is discarded here and never applied — the active ruleset stays
           on the bundled floor (or the last copy that verified). */
        if (sig == null || !verifyEd25519(body.toByteArray(Charsets.UTF_8), sig, PUBLIC_KEY_B64)) {
            Log.d(TAG, "remote reader rules failed signature verification; keeping current rules")
            return
        }

        val parsed = parseOrNull(body) ?: return
        /* Anti-rollback: only ever move forward. */
        if (parsed.version <= loadedVersion) return

        runCatching {
            File(context.filesDir, REMOTE_FILE).writeText(body)
            File(context.filesDir, REMOTE_SIG_FILE).writeText(sig)
        }
        activeJson = parsed.compactJson
        loadedVersion = parsed.version
        Log.d(TAG, "applied verified remote reader rules v${parsed.version}")
    }

    /**
     * Verify a detached Ed25519 signature ([signatureB64]) over [payload] using [publicKeyB64] (raw
     * 32-byte key). Returns false — never throws — on any bad/blank input, so an unverifiable payload
     * is simply not trusted. Visible for unit tests.
     */
    internal fun verifyEd25519(payload: ByteArray, signatureB64: String, publicKeyB64: String): Boolean {
        if (publicKeyB64.isBlank() || signatureB64.isBlank()) return false
        return runCatching {
            val pub = Base64.getDecoder().decode(publicKeyB64.trim())
            val sig = Base64.getDecoder().decode(signatureB64.trim())
            if (pub.size != 32 || sig.size != 64) return false
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(pub, 0))
            signer.update(payload, 0, payload.size)
            signer.verifySignature(sig)
        }.getOrDefault(false)
    }

    private fun httpGet(urlStr: String, maxBytes: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = NET_TIMEOUT_MS
            readTimeout = NET_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val r = input.read(chunk)
                    if (r < 0) break
                    total += r
                    if (total > maxBytes) return null
                    out.write(chunk, 0, r)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readAsset(context: Context): String? =
        runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()

    private fun readLocal(context: Context, name: String): String? =
        runCatching { File(context.filesDir, name).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun parseOrNull(raw: String): Ruleset? = runCatching { parseRuleset(raw) }.getOrNull()

    internal data class Ruleset(val version: Int, val compactJson: String)

    /** Compact rules array only — the shape inlined into the script. Kept for existing callers/tests. */
    internal fun parse(raw: String): String = parseRuleset(raw).compactJson

    /**
     * Parse and sanitize a ruleset document into its `version` and the compact rules array the script
     * consumes. Visible for unit tests. Every selector is trimmed and rejected if it contains a
     * newline or "//": the extractor script is flattened to a single line before injection, where a
     * "//" would comment out the remainder (see [ReaderExtractor.buildReaderExtractorScript]). A rule
     * with no usable `match` is dropped. Throws on malformed JSON (callers wrap in [parseOrNull]).
     */
    internal fun parseRuleset(raw: String): Ruleset {
        val root = JSONObject(raw)
        val version = root.optInt("version", 0)
        val rulesIn = root.optJSONArray("rules") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until rulesIn.length()) {
            val ruleIn = rulesIn.optJSONObject(i) ?: continue
            val match = cleanSelectors(ruleIn.opt("match"))
            if (match.isEmpty()) continue
            val obj = JSONObject()
            obj.put("match", JSONArray(match))
            val include = cleanSelectors(ruleIn.opt("include"))
            if (include.isNotEmpty()) obj.put("include", JSONArray(include))
            val strip = cleanSelectors(ruleIn.opt("strip"))
            if (strip.isNotEmpty()) obj.put("strip", JSONArray(strip))
            out.put(obj)
        }
        return Ruleset(version, out.toString())
    }

    /** Coerce a JSON string or string[] into a list of non-empty, flatten-safe entries. */
    private fun cleanSelectors(value: Any?): List<String> {
        val items = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it, "").takeIf { s -> s.isNotEmpty() } }
            is String -> listOf(value)
            else -> emptyList()
        }
        return items
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("//") && !it.contains('\n') && !it.contains('\r') }
    }
}
