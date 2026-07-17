# `addons/` — Firefox add-on installation

`AddonController` (the runtime/install logic) plus `AddonInstallUrls.kt` (the pure, unit-testable AMO/`.xpi` URL parsing, split out so the controller holds no URL string-munging). Thin wrapper over Gecko's built-in `WebExtensionRuntime` — the app deliberately does *not* depend on the larger `feature-addons` UI stack.

## Responsibilities

- **Mirror installed extensions** into `state: StateFlow<AddonsUiState>` so the UI can list them. Registers a `WebExtensionDelegate` at construction time; every install/uninstall/enable/disable event re-publishes.
- **Resolve install inputs** (`toAddonInstallUrl`):
  - AMO slug (`ublock-origin`) → `https://addons.mozilla.org/firefox/downloads/latest/{slug}/latest.xpi`.
  - AMO add-on page URL → extract slug, same rewrite.
  - Direct `.xpi` URL → pass through.
- **Bridge permission prompts to Compose.** Gecko blocks installation waiting for the prompt response. `AddonController` translates each prompt callback into an `AddonPermissionPrompt` placed on `state.pendingPrompt`; `respondToPrompt(id, accepted)` resolves it.
- **Intercept AMO page download requests.** `installFromPageRequest(url)` is called from `ui/content/AmoAddonInstaller.kt`'s page-level JS bridge when an `.xpi` or AMO `/downloads/latest/` URL is requested.

## Public API

```kotlin
class AddonController(runtime: WebExtensionRuntime, scope: CoroutineScope)

val state: StateFlow<AddonsUiState>

fun refresh()
fun install(input: String)                    // user-typed
fun installFromPageRequest(url: String): Boolean  // AMO bridge
fun setEnabled(addonId: String, enabled: Boolean)
fun uninstall(addonId: String)
fun respondToPrompt(promptId: Long, accepted: Boolean)
fun clearMessage()
```

## State shape

```kotlin
data class AddonsUiState(
    addons: List<InstalledAddon>,
    pendingPrompt: AddonPermissionPrompt?,
    isBusy: Boolean,
    message: String?,
    isError: Boolean,
)

data class InstalledAddon(id, name, description, version, isEnabled)
data class AddonPermissionPrompt(id, title, permissions, origins, dataCollectionPermissions)
```

Status / error messages live on the state too — `setBusy / setStatus / setError` are the only three internal mutators. `isError` flips the bottom-sheet message color.

## Known gaps (deliberate)

- No curated/recommended AMO catalog.
- No browser-action / page-action toolbar surfaces (popups/buttons).
- Extension-requested new tabs are bridged through `AddonController.onNewTabRequested`; active requests select and animate the new tab from the right, inactive requests are added in the background.

## When to touch this package

- Adding a new add-on prompt type → extend `WebExtensionDelegate` overrides + add a sibling `AddonPermissionPrompt` variant.
- Implementing browser actions → this is where the runtime hooks live; UI goes in `ui/addons/` or alongside the bar.
- Changing install-URL parsing → `toAddonInstallUrl` / `isAddonDownloadUrl` are the only places that matter.
