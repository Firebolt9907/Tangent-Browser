package com.firebolt9907.luminabrowser.ui.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Semantic image cutout for web dark mode — the *primary* keyer, with the WASM flood fill
 * ([WebDarkMode.buildImageCutoutScript] / `tools/dark-cutout-wasm`) as the offline / model-not-yet
 * fallback.
 *
 * The flood fill separates a light background from a subject by color/edge thresholds, which cannot
 * work when they are colorimetrically the same — e.g. apple.com's silver MacBook on a light-blue
 * canvas, where palm-rest pixels sit *inside* the background's own color spread. ML Kit **Subject
 * Segmentation** solves that class of image because it is semantic: it keeps the whole object
 * regardless of color similarity.
 *
 * ### Flow (native round-trip, because ML Kit can't run in the GeckoView page)
 * 1. In dynamic dark mode with the model ready, the injected driver posts each large `<img>`'s
 *    resolved URL (tagged with a per-element id) to native over the nonce-guarded `lumina-darkcut`
 *    bridge ([BRIDGE_MESSAGE]); it does **not** run the WASM worker.
 * 2. [onPayload] (from `ReaderExtractor`'s prompt delegate) hands the batch here. For each item we
 *    fetch the image natively (no CORS — a benefit over the in-page path), decode + downscale, run
 *    [SubjectSegmentation], and get the foreground (subject) bitmap.
 * 3. **Photo guard:** we only key when the region ML Kit would drop is actually *light* (so a
 *    full-bleed photo, or a subject on a dark scene, is left in true color — matching the flood
 *    fill's "keys nothing without a light border" behavior).
 * 4. We emit the subject's alpha as a small mask PNG (data URI) back through [maskApplier], which the
 *    host injects onto the matching `<img>` as a CSS `mask-image` — the exact same non-destructive
 *    application the WASM path uses, so the original image stays full-resolution.
 *
 * Everything is fail-safe: any fetch/decode/segmentation error just leaves the image untouched (the
 * per-element recolor still stands). Off unless [enabled]; keys only when [modelReady].
 */
object DarkImageSegmenter {

    const val BRIDGE_MESSAGE = "lumina-darkcut"

    private const val TAG = "DarkImageSegmenter"

    /** True while dynamic web dark mode is active (gates all work; set by the host). */
    @Volatile
    var enabled: Boolean = false

    /** True once the Subject Segmentation model is downloaded and a warmup pass has succeeded. Read by
     *  the host at inject time to decide whether the driver takes the ML path or the WASM fallback. */
    @Volatile
    var modelReady: Boolean = false
        private set

    /**
     * Injects a finished mask onto the page. Set by `LuminaRoot`; it loads
     * [WebDarkMode.buildApplyMaskScript] into the tab's live `EngineSession`, but only while [tabId]
     * is still the foreground tab (so a mask can't land on the wrong page after a tab switch).
     */
    @Volatile
    var maskApplier: ((tabId: String, id: String, maskDataUri: String, natW: Int, natH: Int) -> Unit)? = null

    /** Longest side (px) the fetched image is decoded to before segmentation — bounds cost; the mask
     *  upscales smoothly, so a modest resolution is plenty. */
    private const val MAX_DECODE_DIM = 1024

    /** Background is "light enough to darken" above this mean luma (0-255). Below it the dropped region
     *  is dark/colorful (a photo) and we leave the image alone. */
    private const val LIGHT_BG_LUMA = 135

    /** Apply only when the subject occupies a sane fraction of the image (else it's a photo/near-blank). */
    private const val MIN_SUBJECT_FRAC = 0.02f
    private const val MAX_SUBJECT_FRAC = 0.985f

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build(),
        )
    }

    /**
     * Kicks off the one-time model download and marks [modelReady] on success. ML Kit downloads the
     * subject-segmentation model via Play Services on first `process`; we warm it with a tiny bitmap
     * and retry with backoff while it lands. Safe to call more than once.
     */
    fun warmup() {
        if (modelReady) return
        scope.launch {
            val dummy = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
            repeat(6) { attempt ->
                try {
                    Tasks.await(segmenter.process(InputImage.fromBitmap(dummy, 0)))
                    modelReady = true
                    return@launch
                } catch (e: Exception) {
                    // Not downloaded yet (ML Kit initiates the download on this failed call) — back off.
                    delay(2_000L * (attempt + 1))
                }
            }
        }
    }

    /** Bridge entry: `{tabId ignored, items:[{id, src}]}` from the driver. Dispatches one job per image. */
    fun onPayload(tabId: String, json: String) {
        if (!enabled || !modelReady) return
        val items = try {
            JSONObject(json).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val src = obj.optString("src").takeIf { it.isNotEmpty() } ?: continue
            if (!src.startsWith("http", ignoreCase = true) && !src.startsWith("data:")) continue
            scope.launch { process(tabId, id, src) }
        }
    }

    private fun process(tabId: String, id: String, src: String) {
        try {
            val bmp = fetchAndDecode(src) ?: return
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)))
            val fg = result.foregroundBitmap ?: return
            val w = fg.width
            val h = fg.height
            if (w <= 0 || h <= 0) return

            val fgPixels = IntArray(w * h)
            fg.getPixels(fgPixels, 0, w, 0, 0, w, h)
            // The original (opaque) pixels, aligned to fg (same size — ML Kit returns input dimensions).
            val srcPixels = IntArray(w * h)
            if (bmp.width == w && bmp.height == h) {
                bmp.getPixels(srcPixels, 0, w, 0, 0, w, h)
            } else {
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)
            }

            // Photo guard + mask build in one pass: measure the mean luma of the dropped (background)
            // pixels and the subject fraction, and fold the subject alpha into a mask (RGB 0, alpha=fg).
            var bgLumaSum = 0L
            var bgCount = 0
            var subjectCount = 0
            val maskPixels = IntArray(w * h)
            for (i in fgPixels.indices) {
                val a = fgPixels[i] ushr 24
                if (a >= 128) {
                    subjectCount++
                } else {
                    val p = srcPixels[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    bgLumaSum += (r * 54 + g * 183 + b * 19) shr 8
                    bgCount++
                }
                maskPixels[i] = a shl 24 // black, subject alpha
            }
            val total = w * h
            val subjectFrac = subjectCount.toFloat() / total
            val bgLuma = if (bgCount > 0) (bgLumaSum / bgCount).toInt() else 0
            // Skip: nothing dropped / everything dropped, or the dropped region isn't light (a photo).
            if (subjectFrac < MIN_SUBJECT_FRAC || subjectFrac > MAX_SUBJECT_FRAC) return
            if (bgCount > 0 && bgLuma < LIGHT_BG_LUMA) return

            val mask = Bitmap.createBitmap(maskPixels, w, h, Bitmap.Config.ARGB_8888)
            val dataUri = mask.toPngDataUri() ?: return
            maskApplier?.invoke(tabId, id, dataUri, w, h)
        } catch (e: Exception) {
            Log.d(TAG, "segmentation skipped for $id: ${e.message}")
        }
    }

    private fun fetchAndDecode(src: String): Bitmap? {
        val bytes = try {
            if (src.startsWith("data:")) {
                val comma = src.indexOf(',')
                if (comma < 0) return null
                Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
            } else {
                val conn = (URL(src).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "image/*")
                }
                try {
                    if (conn.responseCode !in 200..299) return null
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            return null
        }
        // Decode bounds first, then downscale to MAX_DECODE_DIM to bound cost.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= MAX_DECODE_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun Bitmap.toPngDataUri(): String? = try {
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }
}
