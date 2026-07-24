package com.firebolt9907.tangentbrowser.state

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Memory LRU of page thumbnails backed by JPEG files in `cacheDir/tab_snapshots`.
 * Keyed by URL (or tab id for URL-less pages). [version] increments on every change so
 * Compose can recompose when a fresh capture arrives. Thread-safe.
 */
class TabSnapshotCache(
    cacheDir: File,
    // Disk LRU size. Must comfortably exceed both the in-memory cap below AND the number of tabs a
    // user realistically keeps open — the all-tabs grid renders one thumbnail per tab from disk, and
    // `pruneDisk` deletes every JPEG beyond this many. A too-small value (this was 16, i.e. *below*
    // the memory cap) permanently strands older tabs' thumbnails: the file is gone, the disk decode
    // misses, and the key is parked in `missedDiskKeys` so it is never retried. Each entry is a
    // ≤1080px-wide JPEG (~100–250 KB), so a few hundred is only tens of MB on disk.
    private val maxEntries: Int = 256,
    // Resident bitmaps dominate the app's heap, so the in-memory LRU is capped well below the disk
    // LRU. Evicted entries reload transparently from disk on the next [get]. Keep enough for the
    // foreground tab plus its immediate neighbours (used as predictive-back / tab-switch covers).
    private val maxMemoryEntries: Int = 24,
) {
    private val directory = File(cacheDir, "tab_snapshots")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val memoryCache = object : LinkedHashMap<String, Bitmap>(maxMemoryEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>): Boolean {
            return size > maxMemoryEntries
        }
    }
    private val pendingDiskLoads = HashSet<String>()
    private val missedDiskKeys = HashSet<String>()
    private var lastPruneAtMs = 0L
    private val pruneIntervalMs = 5_000L
    // Track the last (key, bitmap-identity) that triggered a version bump to avoid redundant invalidations.
    private var lastVersionBumpKey: String? = null
    private var lastVersionBumpBitmap: Bitmap? = null

    /**
     * Memory hit returns synchronously. Miss returns null and schedules a background
     * disk decode; on success it lands in memory and bumps [version].
     */
    @Synchronized
    fun get(id: String, url: String = ""): Bitmap? {
        val key = snapshotKey(id, url)
        memoryCache[key]?.let { return it }
        if (key !in missedDiskKeys) scheduleDiskLoad(key)
        return null
    }

    @Synchronized
    fun put(id: String, bitmap: Bitmap, url: String = "") {
        val key = snapshotKey(id, url)
        // captureThumbnail yields a full-screen ARGB_8888 bitmap (~10 MB). Web captures are opaque,
        // so store the resident copy as RGB_565 to halve heap use; the caller keeps its own ARGB
        // reference for the immediate cover, which is GC'd once the transition ends.
        val stored = bitmap.toResidentConfig()
        memoryCache[key] = stored
        missedDiskKeys.remove(key)
        // Only bump version when the bitmap is a new instance for this key, to avoid
        // invalidating all snapshot-keyed `remember` blocks on redundant puts.
        if (key != lastVersionBumpKey || bitmap !== lastVersionBumpBitmap) {
            lastVersionBumpKey = key
            lastVersionBumpBitmap = bitmap
            _version.value = _version.value + 1
        }
        val shouldPrune = (System.currentTimeMillis() - lastPruneAtMs) > pruneIntervalMs
        if (shouldPrune) lastPruneAtMs = System.currentTimeMillis()
        ioScope.launch {
            writeToDisk(key, stored)
            if (shouldPrune) pruneDisk()
        }
    }

    /** A 565 copy for in-memory/disk storage; falls back to the original if the copy fails (OOM). */
    private fun Bitmap.toResidentConfig(): Bitmap {
        if (config == Bitmap.Config.RGB_565) return this
        return try {
            copy(Bitmap.Config.RGB_565, false) ?: this
        } catch (_: OutOfMemoryError) {
            this
        }
    }

    private fun scheduleDiskLoad(key: String) {
        if (!pendingDiskLoads.add(key)) return
        ioScope.launch {
            val bitmap = runCatching {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(fileForKey(key).absolutePath, opts)
            }.getOrNull()
            synchronized(this@TabSnapshotCache) {
                pendingDiskLoads.remove(key)
                if (bitmap != null) {
                    memoryCache[key] = bitmap
                    _version.value = _version.value + 1
                } else {
                    missedDiskKeys.add(key)
                }
            }
        }
    }

    @Synchronized
    fun remove(id: String, url: String = "") {
        val key = snapshotKey(id, url)
        if (memoryCache.remove(key) != null) {
            _version.value = _version.value + 1
        }
        missedDiskKeys.add(key)
        ioScope.launch {
            fileForKey(key).delete()
        }
    }

    /**
     * Release in-memory thumbnails on system memory pressure. Every cached bitmap has already been
     * (or is being) written to disk, so a later [get] transparently reloads it via the disk-decode
     * path — we only give up RAM, not the snapshots themselves. Called from
     * [com.firebolt9907.tangentbrowser.TangentApplication.onTrimMemory].
     *
     * At moderate pressure we keep the most-recently-used entry (typically the foreground tab, whose
     * thumbnail would otherwise flash on the next grid open); at higher pressure we drop everything.
     */
    @Synchronized
    fun trimMemory(level: Int) {
        if (memoryCache.isEmpty()) return
        // At/above BACKGROUND the process is in the kill zone, so drop everything; for lighter
        // signals (e.g. UI_HIDDEN) keep the most-recently-used entry — usually the foreground tab,
        // whose cover would otherwise flash on the next tab switch. LinkedHashMap is access-ordered,
        // so the last key is the MRU.
        val dropAll = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        val survivor = if (dropAll) null else memoryCache.keys.lastOrNull()
        val removed = memoryCache.keys.retainAll(setOfNotNull(survivor))
        if (removed) _version.value = _version.value + 1
    }

    private fun writeToDisk(key: String, bitmap: Bitmap) {
        directory.mkdirs()
        val target = fileForKey(key)
        val tmp = File(directory, "${target.name}.tmp")
        // Downsample large bitmaps before writing: grid cells display at ~40% of screen width,
        // so anything wider than 1080px is wasteful on disk and during decoding.
        val toWrite: Bitmap = if (bitmap.width > 1080) {
            try {
                val scaleFactor = 1080f / bitmap.width
                val scaledHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
                bitmap.scale(1080, scaledHeight)
            } catch (_: OutOfMemoryError) {
                bitmap // fall back to original on OOM
            }
        } else {
            bitmap
        }
        val isScaled = toWrite !== bitmap
        runCatching {
            tmp.outputStream().use { out ->
                toWrite.compress(Bitmap.CompressFormat.JPEG, 84, out)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            tmp.delete()
        }
        // Recycle the scaled copy — the original stays in memoryCache.
        if (isScaled) toWrite.recycle()
    }

    private fun pruneDisk() {
        val all = directory.listFiles() ?: return
        // Sweep orphaned temp files left behind by a write interrupted between compress and
        // rename (e.g. a process kill); pruneDisk's jpg filter would otherwise never reach them.
        all.filter { it.name.endsWith(".jpg.tmp") }.forEach(File::delete)
        all.filter { it.extension == "jpg" }
            .sortedByDescending(File::lastModified)
            .drop(maxEntries)
            .forEach(File::delete)
    }

    private fun fileForKey(key: String): File =
        File(directory, "${key.sha256()}.jpg")

    private fun snapshotKey(id: String, url: String): String {
        val stableUrl = url.takeUnless { it.isBlank() || it == "about:blank" }
        return "v2:${stableUrl ?: "tab:$id"}"
    }
}

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
