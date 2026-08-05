package com.firebolt9907.tangentbrowser.state

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A file the page handed us as a `blob:` URL and that we copied into the app cache.
 *
 * A `blob:` URL is not an address — it is a per-document handle into the *creating page's* memory.
 * It dies with the document that made it, which is why a restored tab pointing at one loads nothing
 * and why the raw URL (`blob:https://site.com/9f2c…-uuid`) is meaningless in the address bar. Once
 * the bytes are here, the file outlives the document, the tab and the process.
 */
data class BlobFile(
    /** Stable id; also the cache file's base name. */
    val id: String,
    /** The `blob:` URL the bytes were captured from — the key a live/restored tab is matched on. */
    val blobUrl: String,
    /** Best-effort save/display name, always carrying an extension. */
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** The page that produced the blob, kept for provenance in the UI. */
    val pageUrl: String,
    val capturedAtMs: Long,
    /** Absolute path of the cached copy. */
    val path: String,
) {
    /** The URL a tab loads to render the cached copy after the original blob URL has died. */
    val fileUrl: String get() = "file://$path"

    /** Human type label for this file — "PDF file", "Image", … */
    val typeLabel: String get() = blobTypeLabel(mimeType, fileName)
}

// ---- Pure helpers (JVM-testable; no Android deps) ---------------------------------------------

/** True for a `blob:` URL — the opaque, document-scoped handle described on [BlobFile]. */
fun isBlobUrl(url: String?): Boolean = url != null && url.startsWith("blob:", ignoreCase = true)

/**
 * Lowercase file extension implied by [url]'s path, or "" when it has none. Blob URLs usually end
 * in a UUID, but a page that built one from a real filename (`…/report.pdf`) tells us the type
 * before a single byte has been captured — which is what lets the address bar say "PDF file"
 * immediately rather than after the copy finishes.
 */
fun urlExtension(url: String?): String {
    if (url.isNullOrBlank()) return ""
    val path = url.substringBefore('?').substringBefore('#')
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    val ext = name.substring(dot + 1).lowercase()
    return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else ""
}

/**
 * Human label for a file of [mimeType] / [fileName] — what the address bar shows in place of the
 * unreadable blob URL. Deliberately a *type* ("PDF file"), not the filename: blobs are usually
 * generated and named after a UUID, so the type is the only part that means anything to the reader.
 */
fun blobTypeLabel(mimeType: String?, fileName: String?): String {
    val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
    val ext = urlExtension(fileName)
    byExtension(ext)?.let { return it }
    byMime(mime)?.let { return it }
    return "File"
}

private fun byExtension(ext: String): String? = when (ext) {
    "" -> null
    "pdf" -> "PDF file"
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg" -> "Image"
    "mp4", "webm", "mkv", "mov", "m4v", "avi" -> "Video"
    "mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus" -> "Audio"
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz" -> "Archive"
    "csv", "tsv" -> "CSV file"
    "xls", "xlsx", "ods" -> "Spreadsheet"
    "doc", "docx", "odt", "rtf" -> "Document"
    "ppt", "pptx", "odp" -> "Slideshow"
    "epub" -> "eBook"
    "apk" -> "App package"
    "json" -> "JSON file"
    "xml" -> "XML file"
    "htm", "html" -> "Web page"
    "txt", "log", "md" -> "Text file"
    "ics" -> "Calendar file"
    "vcf" -> "Contact card"
    else -> null
}

private fun byMime(mime: String): String? = when {
    mime.isEmpty() -> null
    mime == "application/pdf" -> "PDF file"
    mime.startsWith("image/") -> "Image"
    mime.startsWith("video/") -> "Video"
    mime.startsWith("audio/") -> "Audio"
    mime == "text/csv" -> "CSV file"
    mime == "application/json" -> "JSON file"
    mime == "text/html" -> "Web page"
    mime.startsWith("text/") -> "Text file"
    mime == "application/zip" || mime.contains("compressed") || mime.contains("tar") -> "Archive"
    mime.contains("spreadsheet") || mime.contains("excel") -> "Spreadsheet"
    mime.contains("presentation") || mime.contains("powerpoint") -> "Slideshow"
    mime.contains("word") || mime == "application/rtf" -> "Document"
    mime == "application/epub+zip" -> "eBook"
    mime == "application/vnd.android.package-archive" -> "App package"
    else -> null
}

/** Conventional extension for [mimeType], used to name a capture the page didn't name itself. */
fun extensionForMime(mimeType: String?): String {
    val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
    return when {
        mime == "application/pdf" -> "pdf"
        mime == "image/jpeg" -> "jpg"
        mime == "image/svg+xml" -> "svg"
        mime == "text/csv" -> "csv"
        mime == "text/plain" -> "txt"
        mime == "text/html" -> "html"
        mime == "application/json" -> "json"
        mime == "application/zip" -> "zip"
        mime == "application/vnd.android.package-archive" -> "apk"
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ->
            mime.substringAfter('/').takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) } ?: "bin"
        else -> "bin"
    }
}

/** Strip anything that can't safely be a filename, and bound the length. */
fun sanitizeFileName(name: String?, fallbackExt: String): String {
    val cleaned = name.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ \\-()\\[\\]]"), "_")
        .trim()
        .trim('.')
        .take(80)
    if (cleaned.isEmpty()) return "file.$fallbackExt"
    return if (urlExtension(cleaned).isEmpty()) "$cleaned.$fallbackExt" else cleaned
}

// ---- The store --------------------------------------------------------------------------------

/**
 * Cache of files captured from `blob:` URLs, plus the index that survives a restart.
 *
 * Why it exists: a tab showing a blob URL is showing something the user usually wants to keep — a
 * generated PDF, an export, a rendered receipt — but the URL is worthless the moment the document
 * goes away. [com.firebolt9907.tangentbrowser.ui.content.BlobCapture] streams the bytes out of the
 * page into `cacheDir/blobs/` and registers them here; from then on:
 *
 *  - the address bar shows [BlobFile.typeLabel] instead of the UUID URL ([blobAddressLabel]);
 *  - a restored tab loads [BlobFile.fileUrl] instead of the dead blob URL ([restoreUrl]);
 *  - the "Save to Downloads" banner copies the cached file out ([saveToDownloads]).
 *
 * The cache is bounded ([MAX_TOTAL_BYTES] / [MAX_FILES], oldest evicted first) because it lives in
 * `cacheDir` — the OS may clear it under storage pressure, and a captured file is a convenience
 * copy, never the user's only copy of anything.
 */
object BlobFileStore {

    private const val DIR = "blobs"
    private const val INDEX = "index.json"

    /** Refuse to copy anything larger; a multi-hundred-MB blob is not worth a cache eviction storm. */
    const val MAX_CAPTURE_BYTES = 96L * 1024 * 1024

    private const val MAX_TOTAL_BYTES = 192L * 1024 * 1024
    private const val MAX_FILES = 40

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()

    /** Captures in flight, by capture id. Bytes are streamed straight to disk as chunks arrive. */
    private val pending = HashMap<String, Pending>()

    private val _files = MutableStateFlow<List<BlobFile>>(emptyList())

    /** Captured files, newest first. Collected by the UI so a finished capture reveals its banner. */
    val files: StateFlow<List<BlobFile>> = _files.asStateFlow()

    private class Pending(
        val id: String,
        val blobUrl: String,
        val fileName: String,
        val mimeType: String,
        val pageUrl: String,
        val target: File,
        val out: FileOutputStream,
        var written: Long = 0,
    )

    /** Called once from the components graph, before any page can navigate to a blob URL. */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        synchronized(lock) { _files.value = readIndex() }
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        val d = File(ctx.cacheDir, DIR)
        if (!d.exists() && !d.mkdirs()) return null
        return d
    }

    // ---- Lookup -------------------------------------------------------------------------------

    /** The captured file for [url], matching either the original blob URL or the cached `file://`. */
    fun lookup(url: String?): BlobFile? {
        if (url.isNullOrBlank()) return null
        // compactHost() runs this for every tab on every store emission, so ordinary web URLs must
        // cost a prefix check and nothing more.
        if (!isBlobUrl(url) && !url.startsWith("file:")) return null
        val all = _files.value
        all.firstOrNull { it.blobUrl == url }?.let { return it }
        if (!url.startsWith("file:")) return null
        val path = url.removePrefix("file://").substringBefore('?').substringBefore('#')
        return all.firstOrNull { it.path == path }
    }

    /**
     * The address-bar label for [url], or null when it isn't a blob (or a restored blob copy) and
     * the caller should fall back to the host. Falls back to the URL's own extension so a blob whose
     * bytes are still streaming — or whose capture failed outright — still reads "PDF file" rather
     * than a UUID.
     */
    fun blobAddressLabel(url: String?): String? {
        if (url.isNullOrBlank()) return null
        lookup(url)?.let { return it.typeLabel }
        if (!isBlobUrl(url)) return null
        return blobTypeLabel(null, url)
    }

    /**
     * What a restored tab should load in place of persisted [url]: the cached copy when [url] is a
     * blob URL we captured, otherwise null (the caller keeps the URL as-is). A blob URL with no
     * capture stays as-is deliberately — it fails to load and shows the page's own error, which is
     * honest, rather than being silently rewritten to something else.
     */
    fun restoreUrl(url: String?): String? {
        if (!isBlobUrl(url)) return null
        val file = lookup(url) ?: return null
        return if (File(file.path).exists()) file.fileUrl else null
    }

    // ---- Capture ------------------------------------------------------------------------------

    /**
     * Open a capture for [blobUrl]. Returns the capture id to pass to [appendChunk]/[finishCapture],
     * or null when the blob is too large, already captured, or the cache is unavailable.
     */
    fun beginCapture(
        blobUrl: String,
        mimeType: String,
        fileName: String?,
        pageUrl: String,
        expectedSize: Long,
    ): String? {
        if (!isBlobUrl(blobUrl)) return null
        if (expectedSize > MAX_CAPTURE_BYTES) return null
        if (lookup(blobUrl) != null) return null
        val dir = dir() ?: return null
        val id = java.util.UUID.randomUUID().toString()
        val ext = urlExtension(fileName).ifEmpty { urlExtension(blobUrl) }.ifEmpty { extensionForMime(mimeType) }
        val name = sanitizeFileName(fileName, ext)
        val target = File(dir, "$id.${urlExtension(name).ifEmpty { ext }}")
        val out = runCatching { FileOutputStream(target) }.getOrNull() ?: return null
        synchronized(lock) {
            pending[id] = Pending(id, blobUrl, name, mimeType, pageUrl, target, out)
        }
        return id
    }

    /** Append one base64 chunk to an open capture. False means "stop" — the page should give up. */
    fun appendChunk(captureId: String, base64: String): Boolean {
        val p = synchronized(lock) { pending[captureId] } ?: return false
        val bytes = runCatching {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        }.getOrNull() ?: run { abortCapture(captureId); return false }
        if (p.written + bytes.size > MAX_CAPTURE_BYTES) {
            abortCapture(captureId)
            return false
        }
        return runCatching {
            p.out.write(bytes)
            p.written += bytes.size
            true
        }.getOrElse {
            abortCapture(captureId)
            false
        }
    }

    /** Close an open capture and publish it. Returns the registered file, or null on failure. */
    fun finishCapture(captureId: String): BlobFile? {
        val p = synchronized(lock) { pending.remove(captureId) } ?: return null
        runCatching { p.out.close() }
        if (p.written <= 0) {
            p.target.delete()
            return null
        }
        val file = BlobFile(
            id = p.id,
            blobUrl = p.blobUrl,
            fileName = p.fileName,
            mimeType = p.mimeType,
            sizeBytes = p.written,
            pageUrl = p.pageUrl,
            capturedAtMs = System.currentTimeMillis(),
            path = p.target.absolutePath,
        )
        synchronized(lock) {
            val merged = (listOf(file) + _files.value.filterNot { it.blobUrl == file.blobUrl })
                .sortedByDescending { it.capturedAtMs }
            _files.value = prune(merged)
            writeIndex(_files.value)
        }
        return file
    }

    /** Abandon an open capture and delete its partial file. */
    fun abortCapture(captureId: String) {
        val p = synchronized(lock) { pending.remove(captureId) } ?: return
        runCatching { p.out.close() }
        p.target.delete()
    }

    /** Forget [file] and delete its cached copy. */
    fun remove(file: BlobFile) {
        synchronized(lock) {
            File(file.path).delete()
            _files.value = _files.value.filterNot { it.id == file.id }
            writeIndex(_files.value)
        }
    }

    /** Drop entries past the size/count budget, and any whose file has been cleared by the OS. */
    private fun prune(list: List<BlobFile>): List<BlobFile> {
        val kept = ArrayList<BlobFile>(list.size)
        var total = 0L
        for (f in list) {
            val onDisk = File(f.path)
            if (!onDisk.exists()) continue
            if (kept.size >= MAX_FILES || total + f.sizeBytes > MAX_TOTAL_BYTES) {
                onDisk.delete()
                continue
            }
            kept.add(f)
            total += f.sizeBytes
        }
        return kept
    }

    // ---- Saving out ---------------------------------------------------------------------------

    /**
     * Copy [file] into the user's Downloads. On API 29+ this goes through MediaStore (no storage
     * permission, and the file shows up in Files / the Downloads app); below that it is a direct
     * write to the public Downloads directory, which needs `WRITE_EXTERNAL_STORAGE` — already
     * declared (maxSdkVersion 28) for the same reason the download manager needs it.
     *
     * Returns the name it was saved under, or null if the copy failed.
     */
    fun saveToDownloads(context: Context, file: BlobFile): String? {
        val source = File(file.path)
        if (!source.exists()) return null
        val mime = file.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return null
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            file.fileName
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            runCatching {
                downloads.mkdirs()
                val target = uniqueFile(downloads, file.fileName)
                source.inputStream().use { input -> FileOutputStream(target).use(input::copyTo) }
                registerLegacyDownload(context, target, mime)
                target.name
            }.getOrNull()
        }
    }

    /** `report.pdf` → `report (2).pdf` when the name is taken, so a save never silently overwrites. */
    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 2
        while (candidate.exists() && n < 100) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }

    /** Pre-Q only: publish the copy to the system download list so it shows up in Downloads UIs. */
    @Suppress("DEPRECATION")
    private fun registerLegacyDownload(context: Context, target: File, mime: String) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            dm?.addCompletedDownload(target.name, target.name, true, mime, target.absolutePath, target.length(), true)
        }
    }

    // ---- Index --------------------------------------------------------------------------------

    private fun readIndex(): List<BlobFile> {
        val dir = dir() ?: return emptyList()
        val index = File(dir, INDEX)
        if (!index.exists()) return emptyList()
        val json = runCatching { index.readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<BlobFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (path.isEmpty() || !File(path).exists()) continue
            out += BlobFile(
                id = o.optString("id"),
                blobUrl = o.optString("blobUrl"),
                fileName = o.optString("fileName"),
                mimeType = o.optString("mimeType"),
                sizeBytes = o.optLong("sizeBytes"),
                pageUrl = o.optString("pageUrl"),
                capturedAtMs = o.optLong("capturedAtMs"),
                path = path,
            )
        }
        return prune(out.sortedByDescending { it.capturedAtMs })
    }

    private fun writeIndex(list: List<BlobFile>) {
        val dir = dir() ?: return
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("blobUrl", f.blobUrl)
                    .put("fileName", f.fileName)
                    .put("mimeType", f.mimeType)
                    .put("sizeBytes", f.sizeBytes)
                    .put("pageUrl", f.pageUrl)
                    .put("capturedAtMs", f.capturedAtMs)
                    .put("path", f.path),
            )
        }
        runCatching { File(dir, INDEX).writeText(arr.toString()) }
    }
}
