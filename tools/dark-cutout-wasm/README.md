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
regenerates `app/src/main/java/com/firebolt9907/luminabrowser/ui/content/DarkCutoutAssets.kt`
(base64 wasm + base64 worker + flattened driver). Commit `DarkCutoutAssets.kt` together with the
sources here — it's the shipped artifact.

Requires `rustup target add wasm32-unknown-unknown` and Node.

## Validate against a real site (headless Chrome, page CSP enforced)

```
node harness-worker.js https://www.apple.com out.png   # full worker pipeline, the shipping bytes
node kernel.test.js                                     # kernel-only unit tests
```

Files: `src/lib.rs` kernel · `worker.js` off-thread runner · `driver.js` page glue ·
`gen.js`/`build.sh` codegen · `harness-worker.js`/`kernel.test.js` validation · `*.png`/`*.b64`/
`driver.flat.js` are generated scratch (gitignored).
