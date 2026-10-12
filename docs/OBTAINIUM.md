# Installing and updating Tangent Browser with Obtainium

Release feed: **https://github.com/Firebolt9907/LuminaBrowser/releases**.

## On your phone

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases).
2. Add `https://github.com/Firebolt9907/LuminaBrowser` as a GitHub source.
3. Enable **Include prereleases** to receive builds from `codex/beta`.
4. Enable Obtainium's background update checks and, if desired, automatic installation in its settings.
   Android may require permission to install apps and confirmation for an update; Obtainium controls
   this, rather than the browser itself.

The [README badge](../README.md) and [import file](obtainium.json) configure the beta feed and select
only `arm64-v8a.apk` assets. Stable-only users can turn prereleases off. Android 8.0 or newer and an
arm64 phone are required.

**This repository is private.** Obtainium needs authenticated GitHub access to private releases.
Configure a personal GitHub token in Obtainium's GitHub source settings on your phone, scoped to this
repository with read access to its contents. Do not put that token in this repository or share it in
chat. Alternatively, the owner can make the repository public if that is intentional.

Android accepts an update only when the signing key matches the installed APK. The new permanent
release key will differ from an earlier locally built APK: back up any important browser data before
the first migration. A reinstall may be necessary once; subsequent releases retain the same key.
Never uninstall the existing browser until its data is backed up and the new release is available.

## One-time signing setup

A permanent keystore has been generated outside the checkout during setup. Keep its backup securely;
losing or regenerating the key breaks updates for existing installs. Never commit the keystore,
passwords, or their Base64 representations.

Add these **repository Actions secrets** in
[Settings → Secrets and variables → Actions](https://github.com/Firebolt9907/LuminaBrowser/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `TANGENT_KEYSTORE_BASE64` | Base64 encoding of the complete permanent keystore file |
| `TANGENT_KEYSTORE_PASSWORD` | Keystore password |
| `TANGENT_KEY_ALIAS` | `tangent` |
| `TANGENT_KEY_PASSWORD` | Key password (same as the generated keystore password) |

The signing backup includes files named after those four secrets, ready to copy into GitHub's secure
secret editor. When repository secret-management access is available to `gh`, they can instead be
uploaded without printing their values:

```sh
gh secret set TANGENT_KEYSTORE_BASE64 --repo Firebolt9907/LuminaBrowser < TANGENT_KEYSTORE_BASE64.txt
gh secret set TANGENT_KEYSTORE_PASSWORD --repo Firebolt9907/LuminaBrowser < TANGENT_KEYSTORE_PASSWORD.txt
gh secret set TANGENT_KEY_ALIAS --repo Firebolt9907/LuminaBrowser < TANGENT_KEY_ALIAS.txt
gh secret set TANGENT_KEY_PASSWORD --repo Firebolt9907/LuminaBrowser < TANGENT_KEY_PASSWORD.txt
```

The workflow checks all four secrets before building, requires explicit upload signing, and checks
the APK's signing certificate against the configured keystore before publication. A missing or invalid
key fails the job; it does not fall back to a disposable debug key.

## Publishing updates

The [Release workflow](../.github/workflows/release.yml) publishes:

- **Beta:** every push to `codex/beta` creates a prerelease named `beta-RUN_NUMBER`.
- **Stable:** pushing `vMAJOR.MINOR.PATCH` creates a stable release, for example `v1.0.0`.
- **Manual:** Actions → Release → Run workflow; leave the version empty for a beta, or enter a new
  stable version such as `1.0.0`. Select the branch containing the changes to release.

Each run tests the app and builds a signed arm64 release APK with JDK 21, Gradle's pinned wrapper,
Android API 37.0, and build-tools 36.0.0. Publication happens only after those checks and signature
verification pass. Releases contain the APK and `SHA256SUMS`. Existing published APKs are never
replaced by a rerun: use a new push/version for an update.

`versionName` matches the release tag (without `v` for stable releases). Both feeds share one
increasing Android `versionCode`: **1,000,000 + the Release workflow's run number**. This lets a phone
move between stable and beta builds without resetting its version counter. An optional repository
variable `TANGENT_VERSION_CODE_BASE` can raise the baseline if an existing install has a higher code.
Never lower that value. If the workflow is deleted/recreated, raise the baseline before publishing.
The maximum supported Android version code is 2,100,000,000.

After adding the secrets, rerun any first build that failed because they were absent. If Actions are
disabled, enable them in the repository settings. The default branch is `codex/beta`, so manual runs
are available there too.

## Local distribution build

Supply the same permanent key securely through the `TANGENT_KEYSTORE_FILE`,
`TANGENT_KEYSTORE_PASSWORD`, `TANGENT_KEY_ALIAS`, and `TANGENT_KEY_PASSWORD` environment variables,
plus `TANGENT_VERSION_NAME` and an increasing `TANGENT_VERSION_CODE`:

```sh
TANGENT_REQUIRE_RELEASE_SIGNING=true ./gradlew :app:assembleRelease
```

In the cloud environment, first source `/workspace/.lumina-setup/env.sh` and pass
`-Dorg.gradle.java.home="$JAVA_HOME"`; the tracked Gradle configuration contains a Mac-specific Java
path. Firebase is optional and release builds do not need `google-services.json`.

Regenerate the one-tap link after changing the repository URL or application ID:

```sh
python3 scripts/obtainium_link.py
```
