package com.firebolt9907.tangentbrowser.ui.content

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal const val MaxImageTransferBytes = 20 * 1024 * 1024

/** Enforce the limit on actual bytes, including chunked responses and dishonest Content-Length. */
internal fun readBoundedImage(input: InputStream, limit: Int = MaxImageTransferBytes): ByteArray? {
    require(limit in 1..MaxImageTransferBytes)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            if (output.size() == limit) return null
            output.write(single)
        } else {
            if (count > limit - output.size()) return null
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray().takeIf { it.isNotEmpty() }
}

internal fun imageTransferMime(raw: String?): String? = raw?.substringBefore(';')?.trim()?.lowercase()
    ?.takeIf { Regex("image/[a-z0-9.+-]+").matches(it) }

/** A previously granted URI must never be overwritten with another image's bytes. */
internal fun sharedImageName(bytes: ByteArray): String = "img_" +
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Bound OCR bitmap allocation independently of the compressed download size. */
internal fun ocrImageSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0) return null
    var sample = 1
    while (((width.toLong() + sample - 1) / sample) *
        ((height.toLong() + sample - 1) / sample) > 4_194_304L
    ) sample *= 2
    return sample
}
