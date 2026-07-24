package com.firebolt9907.tangentbrowser.ui.content

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Copies/shares the *actual image bytes* (not just its URL) for the image long-press menu.
 *
 * Android's clipboard and share sheet both take a `content://` URI, so we first materialise the
 * image into `cacheDir/shared/` and hand out a [FileProvider] URI (authority
 * `${applicationId}.fileprovider`, declared in the manifest → `res/xml/provider_paths.xml`). Both
 * `http(s):` and inline `data:` image URLs are supported. Network work runs off the main thread;
 * callers should invoke from a coroutine.
 */
object ImageClipboard {

    /** Downloads/decodes [imageUrl] into a shared cache file and returns a FileProvider URI, or null. */
    private suspend fun materialize(context: Context, imageUrl: String): Pair<Uri, String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val (bytes, mime) = when {
                    imageUrl.startsWith("data:", ignoreCase = true) -> decodeDataUrl(imageUrl)
                    else -> download(imageUrl)
                } ?: return@runCatching null

                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                // Reuse a stable filename per URL so repeated copies don't fill the cache.
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val file = File(dir, "img_${imageUrl.hashCode().toUInt()}.$ext")
                file.writeBytes(bytes)

                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                uri to mime
            }.getOrNull()
        }

    private fun download(imageUrl: String): Pair<ByteArray, String>? {
        val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val mime = conn.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) null else bytes to mime
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeDataUrl(dataUrl: String): Pair<ByteArray, String>? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val header = dataUrl.substring(5, comma) // strip "data:"
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        if (!header.contains("base64")) return null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        return if (bytes.isEmpty()) null else bytes to mime
    }

    /** Puts the image on the system clipboard as an image clip. Returns true on success. */
    suspend fun copy(context: Context, imageUrl: String): Boolean {
        val (uri, _) = materialize(context, imageUrl) ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        // newUri infers the image MIME type from the resolver (our FileProvider file extension).
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
        return true
    }

    /**
     * Shares the *actual image* (not its URL) through the system share sheet, so it can be sent as a
     * picture into a chat/doc. Materializes to a FileProvider URI and fires `ACTION_SEND` with the
     * image MIME. Returns true if a chooser was launched.
     */
    suspend fun shareImage(context: Context, imageUrl: String): Boolean {
        val (uri, mime) = materialize(context, imageUrl) ?: return false
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, null).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    /** Fetches [imageUrl]'s raw bytes (http(s) or inline data:), or null. */
    private fun rawBytes(imageUrl: String): ByteArray? =
        when {
            imageUrl.startsWith("data:", ignoreCase = true) -> decodeDataUrl(imageUrl)
            else -> download(imageUrl)
        }?.first

    /** Result of an on-device OCR pass over an image. */
    sealed interface OcrResult {
        data class Text(val text: String) : OcrResult
        object Empty : OcrResult
        object Failed : OcrResult
    }

    /**
     * Extracts visible text from [imageUrl] using ML Kit's **on-device** text recognizer (no network,
     * no permission — the model is bundled/downloaded once by Play Services) and copies it to the
     * clipboard. Returns the outcome so the caller can toast appropriately.
     */
    suspend fun copyTextFromImage(context: Context, imageUrl: String): OcrResult {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                rawBytes(imageUrl)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull()
        } ?: return OcrResult.Failed

        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS,
        )
        val text = try {
            val input = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            awaitOcr(recognizer, input)
        } catch (t: Throwable) {
            return OcrResult.Failed
        } finally {
            recognizer.close()
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return OcrResult.Empty
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return OcrResult.Failed
        clipboard.setPrimaryClip(ClipData.newPlainText("Image text", trimmed))
        return OcrResult.Text(trimmed)
    }

    /** Bridges ML Kit's Task to a coroutine without pulling in kotlinx-coroutines-play-services. */
    private suspend fun awaitOcr(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        input: com.google.mlkit.vision.common.InputImage,
    ): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        recognizer.process(input)
            .addOnSuccessListener { if (cont.isActive) cont.resumeWith(Result.success(it.text)) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    }

    /**
     * True when [saveToPhotos] is usable without a storage permission — i.e. Android 10+ (Q), where
     * MediaStore inserts with `RELATIVE_PATH` need no `WRITE_EXTERNAL_STORAGE`. Below Q we keep the
     * action hidden rather than request a legacy permission.
     */
    val canSaveToPhotos: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    /**
     * Saves the image into the shared **Pictures/Tangent** gallery album via MediaStore, so it
     * shows up in Photos/Gallery (unlike "Save image", which lands in Downloads). Q+ only — no storage
     * permission needed. Returns true on success.
     */
    suspend fun saveToPhotos(context: Context, imageUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            // Direct SDK_INT guard (not the property) so lint recognizes the Q-only MediaStore APIs below.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@withContext false
            runCatching {
                val (bytes, mime) = when {
                    imageUrl.startsWith("data:", ignoreCase = true) -> decodeDataUrl(imageUrl)
                    else -> download(imageUrl)
                } ?: return@runCatching false

                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val name = "IMG_${System.currentTimeMillis()}.$ext"
                val resolver = context.contentResolver
                val collection = android.provider.MediaStore.Images.Media
                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/Tangent",
                    )
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run { resolver.delete(uri, null, null); return@runCatching false }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }.getOrDefault(false)
        }
}
