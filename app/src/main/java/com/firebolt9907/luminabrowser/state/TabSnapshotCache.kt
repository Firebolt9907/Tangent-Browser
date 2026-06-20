package com.firebolt9907.luminabrowser.state

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
    private val maxEntries: Int = 16,
) {
    private val directory = File(cacheDir, "tab_snapshots")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val memoryCache = object : LinkedHashMap<String, Bitmap>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>): Boolean {
            return size > maxEntries
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
        memoryCache[key] = bitmap
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
            writeToDisk(key, bitmap)
            if (shouldPrune) pruneDisk()
        }
    }

    private fun scheduleDiskLoad(key: String) {
        if (!pendingDiskLoads.add(key)) return
        ioScope.launch {
            val bitmap = runCatching {
                BitmapFactory.decodeFile(fileForKey(key).absolutePath)
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
