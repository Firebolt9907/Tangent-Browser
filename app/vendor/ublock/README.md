# Bundled uBlock Origin

Upstream: Raymond Hill and contributors, https://github.com/gorhill/uBlock
Pinned release: 1.75.0
Package: https://github.com/gorhill/uBlock/releases/download/1.75.0/uBlock0_1.75.0.firefox.signed.xpi
SHA-256: 5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287
Corresponding upstream source: https://github.com/gorhill/uBlock/tree/1.75.0
License: GPL-3.0-or-later; LICENSE.txt and third-party notices remain in the generated extension.

Cookie-list snapshots retrieved 2026-09-30:
- easylist-cookies.txt: https://ublockorigin.github.io/uAssets/thirdparties/easylist-cookies.txt
- annoyances-cookies.txt: https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt

`prepareBundledUblock` in app/build.gradle.kts expands the pinned archive into build/generated/ublockAssets,
removes META-INF signing metadata, and modifies only assets/assets.json: enables fanboy-cookiemonster
and ublock-cookies-easylist by default, appending the bundled snapshots as offline fallbacks. All original
remote filter-update URLs remain. No upstream JavaScript is modified. Builds require no downloads.
Do not edit generated files. To update the pin, replace the upstream archive and its checksum/version
in the Gradle task, refresh these snapshots, then run the defaults harness and build checks.

Gecko built-ins cannot use ordinary add-on updates. The opt-in automatic-update switch installs
Mozilla's signed AMO package as a profile overlay with the SAME ID. Do not uninstall the built-in first:
that deletes user data. Startup respects profile copies instead of replacing them with the APK version.
Saved filter selections and custom rules survive; new permissions use the existing prompts. Tangent
blocks removal of this managed add-on in either form. Disabling automatic checks retains the installed
version. Filter-list automatic updates remain controlled independently by uBO's dashboard.

Verify: ./gradlew :app:prepareBundledUblock && node tools/ubo-defaults-harness.js
