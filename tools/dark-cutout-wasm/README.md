# dark-cutout-wasm

The in-page **image cutout** kernel for web dark mode (dynamic / "Adaptive recoloring" mode).

The dynamic recolor in `ui/content/WebDarkMode.kt` darkens element background-colors but never touches
images — right for photos, wrong for sections whose background is a full-bleed **image** with a light
color baked in (apple.com's product heroes), which stay glaring-light on the dark page. This kernel
keys out each image's light, **edge-connected** background so those sections go dark while the subject
(and any live heading over them) stays readable; true photos with no light border are left alone.

## Pipeline

`src/lib.rs` (Rust → `wasm32-unknown-unknown`, `no_std`, ~2 KB) does an edge-aware flood fill from the
image borders: seed opaque border pixels brighter than `l_seed`, grow across low-contrast steps
(the smooth background gradient) but stop at the subject's outline (`step_max`) and never below
`l_floor`. Filled pixels become transparent. `worker.js` runs it off the main thread (transferred
`ImageBitmap` in, PNG blob out); `driver.js` is the page-context glue that finds images, feeds the
worker, and swaps the result in — fail-safe on any CSP/worker/WASM/CORS failure.

## Build

```
./build.sh
```

Compiles the kernel, runs `kernel.test.js` (deterministic synthetic-buffer assertions), and
regenerates `app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/DarkCutoutAssets.kt`
(base64 wasm + base64 worker + flattened driver). Commit `DarkCutoutAssets.kt` together with the
sources here — it's the shipped artifact.

Requires `rustup target add wasm32-unknown-unknown` and Node.

## Auditing the shipped blob

`DarkCutoutAssets.WASM_B64` is ~7 KB of base64 sitting in a Kotlin source file — the one thing we
ship that a reviewer cannot read, and the exact shape a hostile payload would take. Everything below
exists so nobody has to take it on faith.

Two properties make it cheap to verify. The crate is `no_std` with **zero dependencies** (there is no
transitive supply chain to audit — `Cargo.toml` has no `[dependencies]` at all), and the build is
**byte-reproducible**: a clean rebuild of unchanged sources yields the identical 5,345 bytes, so a
hash comparison is a real check rather than a formality.

Three levels, cheapest first:

```
./gradlew :app:test --tests "*DarkCutoutAssetsTest"   # 1. no toolchain needed
./extract-wasm.sh && wasm2wat dark_cutout.wasm        # 2. read the module as text
./build.sh --check                                    # 3. rebuild from src/lib.rs, fail on drift
```

1. **`DarkCutoutAssetsTest`** re-derives the facts from the shipped bytes: the sha256 matches the
   value pinned in the test, the module declares **no import section** — so it has no host function
   to call and can touch nothing outside its own linear memory, making I/O structurally impossible —
   and its exports are exactly the keyer API. It also proves `WORKER_B64` and `DRIVER_JS` are the
   `worker.js` / `driver.js` in this directory, byte for byte.
2. **`extract-wasm.sh`** decodes the committed base64 back to a real `.wasm` (and re-prints both
   hashes) so you can run `wasm2wat` / `wasm-objdump -x` on it. ~5 KB with no imports, short enough
   to read end to end.
3. **`./build.sh --check`** recompiles `src/lib.rs` into a throwaway target dir — nothing stale can
   be reused — and fails if `DarkCutoutAssets.kt` differs by so much as a byte from what those
   sources generate. This is the link between the Rust you can read and the base64 you cannot.

`gen.js` also refuses outright to emit a kernel that has grown an import section, so the "pure
compute" property can't be lost silently in a regeneration.

Why base64 at all, rather than a binary asset: the driver ships as a `javascript:` URL, so the kernel
has to survive as a JS string literal, and page-world script cannot reach the APK's `assets/` to load
a binary itself. It is a text channel end to end (see the header of `DarkCutoutAssets.kt`).

## Validate against a real site (headless Chrome, page CSP enforced)

```
node harness-worker.js https://www.apple.com out.png   # full worker pipeline, the shipping bytes
node kernel.test.js                                     # kernel-only unit tests
```

Files: `src/lib.rs` kernel · `worker.js` off-thread runner · `driver.js` page glue ·
`gen.js`/`build.sh` codegen · `harness-worker.js`/`kernel.test.js` validation ·
`extract-wasm.sh` audit helper · `*.png`/`*.b64`/`*.wasm`/`driver.flat.js` are generated scratch
(gitignored).
