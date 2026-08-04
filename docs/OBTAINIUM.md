# Installing & updating Tangent Browser with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs Android apps straight from a project's
release feed and checks for updates itself. Tangent's feed is its **GitHub Releases** page: every
tag matching `v*` builds a signed `arm64-v8a` APK and attaches it to a release
(`.github/workflows/release.yml`).

Repository URL used throughout this doc — if you host the repo under a different name, change it
here, in `obtainium.json`, in `scripts/obtainium_link.py`, and regenerate the README link:

```
https://github.com/Firebolt9907/TangentBrowser
```

## For users

1. Install Obtainium (F-Droid, or its own GitHub releases).
2. **Add App** → paste the repository URL above → **Add**.
3. Obtainium reads the latest release, installs the APK, and polls for later tags.

### One-tap install link

Obtainium accepts an entire app config in a deep link — `obtainium://app/<url-encoded JSON>` — so
tapping it on a phone opens Obtainium with the source, name and settings already filled in; the user
only confirms. Custom schemes aren't clickable in GitHub Markdown or most messengers, so the link is
wrapped in Obtainium's own https redirect, which bounces to the app (and, if Obtainium isn't
installed, just shows its page):

```
https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/<url-encoded JSON>
```

The rendered badge/link lives in the [README](../README.md). Regenerate it after any repo rename or
app-id change — the URL embeds both:

```sh
python3 scripts/obtainium_link.py                       # uses the default repo URL
python3 scripts/obtainium_link.py https://github.com/you/YourFork
```

The JSON keys (`id`, `url`, `author`, `name`, `preferredApkIndex`, `additionalSettings` — itself a
JSON *string* — and `overrideSource`) mirror what Obtainium's own "share app" action emits; don't
hand-edit the encoded blob, re-run the script.

Alternatively import [`obtainium.json`](obtainium.json) (Obtainium → **Import/Export** → *Import from
file*), which sets up the same source.

Notes:
- **arm64-v8a only.** The published APK excludes 32-bit and x86 ABIs. Practically every phone from
  the last several years is arm64; an emulator likely is not.
- **minSdk 26** (Android 8.0).
- The APK is ~250 MB (GeckoView ships a full browser engine — `libxul.so` alone is 150 MB), so the
  first install and each update are a sizeable download.
- The first Obtainium install must be a fresh install if you previously side-loaded a locally built
  APK — the debug-keystore build and the release build are signed with different keys, and Android
  refuses to update across signing keys. Uninstall the old one first (it will lose local data).

## For the maintainer — one-time setup

### 1. Create the upload keystore

This key identifies the app forever. Regenerating it means every existing install has to be
uninstalled and re-installed by hand, so back the file up somewhere durable (password manager,
encrypted backup) — **not** in the repo.

```sh
keytool -genkeypair -v \
  -keystore tangent-upload.jks \
  -alias tangent \
  -keyalg RSA -keysize 4096 -validity 10000
```

### 2. Add the repository secrets

`gh secret set …` from the repo root, or Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `TANGENT_KEYSTORE_BASE64` | `base64 -i tangent-upload.jks` (macOS) / `base64 -w0` (Linux) |
| `TANGENT_KEYSTORE_PASSWORD` | keystore password |
| `TANGENT_KEY_ALIAS` | `tangent` |
| `TANGENT_KEY_PASSWORD` | key password (same as the store password unless you set another) |

```sh
gh secret set TANGENT_KEYSTORE_BASE64 < <(base64 -i tangent-upload.jks)
gh secret set TANGENT_KEYSTORE_PASSWORD
gh secret set TANGENT_KEY_ALIAS
gh secret set TANGENT_KEY_PASSWORD
```

The workflow fails fast if `TANGENT_KEYSTORE_BASE64` is missing, and runs `apksigner verify
--print-certs` on the built APK before publishing — an unsigned or wrongly signed APK never reaches
a release.

### 3. Cut a release

```sh
git tag v1.0.0 && git push origin v1.0.0
```

or **Actions → Release → Run workflow** and type `1.0.0` (that path creates the tag for you).

Versioning rules baked into the workflow:
- The tag must be `vMAJOR.MINOR.PATCH`; `versionName` is the tag without the `v`.
- `versionCode` is derived as `MAJOR*10000 + MINOR*100 + PATCH`, so **every release must bump at
  least the patch number** — Android rejects an update whose `versionCode` did not increase, and
  Obtainium would report the update as perpetually available.
- `MINOR` and `PATCH` are capped at 99 by that encoding.

`versionCode`/`versionName` are injected via `TANGENT_VERSION_CODE`/`TANGENT_VERSION_NAME` (also
accepted as `-PtangentVersionCode=` / `-PtangentVersionName=`); a plain local build still reports
`1.0`.

### Building a distribution APK locally

```sh
TANGENT_VERSION_NAME=1.0.0 TANGENT_VERSION_CODE=10000 \
TANGENT_KEYSTORE_FILE=$PWD/tangent-upload.jks \
TANGENT_KEYSTORE_PASSWORD=… TANGENT_KEY_ALIAS=tangent TANGENT_KEY_PASSWORD=… \
./gradlew :app:assembleRelease
```

Without `TANGENT_KEYSTORE_FILE` the release build falls back to the local debug keystore (the
existing dev behaviour) — fine for testing, **never** for something you publish.
