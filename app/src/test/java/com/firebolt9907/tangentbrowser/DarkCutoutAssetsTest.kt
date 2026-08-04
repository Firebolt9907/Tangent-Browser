package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.DarkCutoutAssets
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Makes the one unreadable thing we ship auditable without a Rust toolchain.
 *
 * [DarkCutoutAssets.WASM_B64] is ~7 KB of base64 in a source file — exactly the shape a hostile
 * payload would take, and nobody can eyeball it. It is generated from `tools/dark-cutout-wasm/`
 * (`src/lib.rs`, a `no_std` crate with zero dependencies), but a reader has no way to know that from
 * the Kotlin side, so this test re-derives the load-bearing facts straight from the shipped bytes:
 *
 *  - the sha256 matches [EXPECTED_WASM_SHA256], pinned here so any change to the shipped kernel
 *    shows up as a reviewed one-line diff rather than 7 KB of churn nobody reads;
 *  - the module declares **no imports**, so it has no host function to call and can touch nothing
 *    but its own linear memory — a pure compute kernel, structurally incapable of I/O;
 *  - its exports are exactly the keyer API `worker.js` drives, nothing more.
 *
 * The worker and driver blobs are checked against the tracked sources byte for byte, which is the
 * whole provenance story for those two.
 *
 * Deeper checks live next to the sources: `./build.sh --check` recompiles `src/lib.rs` into a
 * scratch dir and fails on any drift (the build is byte-reproducible), and `./extract-wasm.sh`
 * decodes the blob for `wasm2wat`/`wasm-objdump`.
 */
class DarkCutoutAssetsTest {

    private companion object {
        /**
         * sha256 of `tools/dark-cutout-wasm/src/lib.rs` compiled to wasm32-unknown-unknown/release.
         * Update ONLY together with a real kernel change — `./build.sh` prints the new value.
         */
        const val EXPECTED_WASM_SHA256 = "1133d19294ba41d965a283ccd4b5d5256f04deb4e527c100b6d2bccc11aa460d"

        /** The keyer API in `src/lib.rs`, plus the memory/heap symbols the toolchain emits. */
        val EXPECTED_EXPORTS = setOf(
            "memory",
            "blur_alpha",
            "capacity",
            "cutout",
            "defringe",
            "pixels_ptr",
            "smooth_rgb",
            "to_alpha_mask",
            "__data_end",
            "__heap_base",
        )
    }

    private val wasm: ByteArray by lazy { Base64.getDecoder().decode(DarkCutoutAssets.WASM_B64) }

    /** `:app:test` runs from the module dir; tolerate the repo root too (matches the other asset tests). */
    private fun toolFile(name: String): File {
        for (p in listOf("../tools/dark-cutout-wasm/$name", "tools/dark-cutout-wasm/$name")) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("tools/dark-cutout-wasm/$name not found from ${File(".").absolutePath}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Minimal section walk. Returns section ids in order, and the names in the export section (id 7). */
    private fun parseWasm(buf: ByteArray): Pair<List<Int>, List<String>> {
        var i = 8 // magic (4) + version (4)
        val sections = mutableListOf<Int>()
        val exports = mutableListOf<String>()

        fun varuint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = buf[i++].toInt() and 0xff
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        while (i < buf.size) {
            val id = buf[i++].toInt() and 0xff
            val len = varuint()
            sections += id
            if (id == 7) {
                val save = i
                repeat(varuint()) {
                    val nameLen = varuint()
                    exports += String(buf, i, nameLen, Charsets.UTF_8)
                    i += nameLen + 1 // name + export kind
                    varuint() // index
                }
                i = save
            }
            i += len
        }
        return sections to exports
    }

    @Test
    fun `wasm blob is a wasm module of the expected version`() {
        assertTrue("blob is too small to be a module", wasm.size > 64)
        assertEquals(
            "wasm magic header",
            listOf(0x00, 0x61, 0x73, 0x6d),
            wasm.take(4).map { it.toInt() and 0xff },
        )
        assertEquals(
            "wasm binary format version",
            listOf(0x01, 0x00, 0x00, 0x00),
            wasm.drop(4).take(4).map { it.toInt() and 0xff },
        )
    }

    @Test
    fun `wasm blob is the reviewed build of src lib rs`() {
        assertEquals(
            "The shipped kernel changed. If that was intentional, rebuild with " +
                "tools/dark-cutout-wasm/build.sh and update EXPECTED_WASM_SHA256; if it was not, the " +
                "blob in DarkCutoutAssets.kt was edited by something other than gen.js.",
            EXPECTED_WASM_SHA256,
            sha256(wasm),
        )
    }

    @Test
    fun `the sha256 the source file advertises matches the bytes it carries`() {
        // The header comment and WASM_SHA256 are what a reader checks by hand — they must not be
        // able to drift away from the blob they describe.
        assertEquals(sha256(wasm), DarkCutoutAssets.WASM_SHA256)
    }

    @Test
    fun `wasm kernel declares no imports so it cannot reach the host`() {
        val (sections, _) = parseWasm(wasm)
        assertFalse(
            "The kernel gained an import section: it can now call host functions, and is no longer " +
                "a pure compute kernel. Nothing in the cutout design needs this — review src/lib.rs.",
            sections.contains(2),
        )
    }

    @Test
    fun `wasm kernel exports exactly the keyer API`() {
        val (_, exports) = parseWasm(wasm)
        assertEquals(
            "unexpected export surface on the cutout kernel",
            EXPECTED_EXPORTS,
            exports.toSet(),
        )
    }

    @Test
    fun `worker blob is the tracked worker js`() {
        assertEquals(
            "WORKER_B64 is not tools/dark-cutout-wasm/worker.js — regenerate with build.sh",
            toolFile("worker.js").readText(),
            String(Base64.getDecoder().decode(DarkCutoutAssets.WORKER_B64), Charsets.UTF_8),
        )
    }

    @Test
    fun `driver blob is the tracked driver js flattened`() {
        // gen.js flattens driver.js to one line (it ships inside a `javascript:` URL).
        val flattened = toolFile("driver.js").readText()
            .replace(Regex("\\r?\\n"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        assertEquals(
            "DRIVER_JS is not tools/dark-cutout-wasm/driver.js — regenerate with build.sh",
            flattened,
            DarkCutoutAssets.DRIVER_JS,
        )
    }
}
