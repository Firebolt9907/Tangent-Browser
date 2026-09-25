# Tangent Browser — Implementation Notes

Snapshot of the app's design and the rationale behind the choices that aren't obvious from the code. Updated as state changes; if it disagrees with the source, trust the source.

## Stack

- Android Compose UI, Material 3, edge-to-edge.
- Browser engine: Mozilla GeckoView via `org.mozilla.components` 147.0.4 (`browser-engine-gecko`, `browser-state`, `feature-session`, `feature-tabs`) plus Firefox Accounts / Sync (`service-firefox-accounts`, `browser-storage-sync`, `feature-syncedtabs`).
- Min SDK 26, target SDK 36, Kotlin 2.2.

## Components graph (`engine/Engine.kt`)

`TangentApplication.components` holds:

- `globalPreferencesStore: GlobalPreferencesStore` — initialized first so its values can be passed to `GeckoEngine` at construction time.
- `engine: GeckoEngine` — constructed with `buildDefaultSettings(globalPreferencesStore)` so stored engine settings survive process death.
- `store: BrowserStore` with `EngineMiddleware`
- `sessionUseCases: SessionUseCases`, `tabsUseCases: TabsUseCases`
- `tabRepository: GeckoTabRepository` (UI-facing tab façade)
- `tabSnapshotCache: TabSnapshotCache`
- `sitePreferencesStore: SitePreferencesStore`
- `addonController: AddonController` wrapping Gecko's `WebExtensionRuntime`
- `firefoxSyncController: FirefoxSyncController` wrapping FxA authentication plus remote-tabs sync
- `browserState: StateFlow<BrowserState>` mirrored from the store via `observeManually`
- `applyEngineSettings()` — mutates `engine.settings` from the current `globalPreferencesStore` values; called from `SettingsSheet` when any toggle changes.

Three demo tabs are seeded on first launch (Apple, NYT, Wikipedia).

## UI layout (`ui/TangentRoot.kt`)

Top to bottom in the `Box`:

1. **Adjacent tab snapshot** (only present when `offsetX != 0`) — shows during drag, translates with the same x as the corresponding ghost pill.
2. **`ContentHost`** wrapping the single `EngineView`. Only one engine view exists for the whole app — `EngineSession.render()` rebinds it per selected tab.
3. **Sticky incoming snapshot** — bridges the moment of session swap on commit so the user doesn't see a blank frame while GeckoView repaints.
4. **SafariBars** — previous-tab ghost, active pill, and a next-tab ghost when one exists. Each translates with the same offset math as its content layer, so each tab visually owns its own pill.

No status-bar scrim is drawn — the page renders directly under the system bar icons. See "Edge-to-edge & status-bar area" below.

## Tab switching (`ui/TangentRoot.kt`)

Finger-tracking drag, single source of truth `var offsetX: Float`.

- `BarPill` exposes `onDragDelta(Float)` and `onDragEnd(total, velocity)`. When `interactive = false`, the `draggable` modifier is omitted — that's how ghost pills stay non-interactive.
- `BarPill`'s `draggable` uses `startDragImmediately = false`. With `true`, the `draggable` modifier consumes the very first `ACTION_DOWN` into the drag stream and child pointer input never fires, which makes every `IconButton` (`aA`, Extensions, Reload) and the URL `Row.clickable` effectively dead. The cost of `false` is one touch-slop's worth of finger travel before a swipe registers, which is imperceptible compared to losing the chrome buttons.
- The active bar also sets `interactive = false` once the scroll-collapsed compact progress is past halfway. Compact mode is tap-to-expand only; horizontal tab switching is intentionally disabled there so a narrow domain pill does not accidentally switch tabs.
- During drag, `offsetX += delta`, clamped to one page stride in either direction. A page stride is `width + 64.dp`, which creates the Safari-style visual gap between pages during the switch.
- Because GeckoView is a `SurfaceView`, Compose transforms cannot be trusted to move the live engine pixels. While `offsetX != 0`, `TangentRoot` draws bitmap layers above the engine: a neutral background, the adjacent tab/new-tab pane, and a gesture-local current-page snapshot translated by `offsetX`. All three switch panes are clipped to a `SquircleShape` (n=5 superellipse, sampled into a path) at radius `0.8 × topChromeInsetPx` so corners scale with the device's status-bar / cutout inset and the curvature reads as iOS-style continuous corners rather than a circular fillet.
- The hot-path drag reads of `offsetX` are kept out of the main composable scope: structural conditionals (`overlayActive`, `showNewTabPane`, `adjacentDirection`) go through `derivedStateOf` so they only invalidate `TangentRoot` on sign flips; per-frame translation/alpha/scale reads live inside `graphicsLayer { … }` blocks so the snapshot system rerenders just the layer instead of recomposing. The `adjacentSnapshot` `remember` is keyed on `adjacentDirection` (sign), not raw `offsetX`, so `cache.get` does not run every frame.
- Tab-switch drags request a fresh `captureThumbnail` at `BarPill.onDragStart`, and seed the moving current-page Image from the cached `currentPageSnapshot` immediately so it has something to draw on the very first frame. The fresh capture overwrites the seed when it lands. The seed prevents a black flash while the async capture is in flight; the trade-off is that if the cached bitmap is at an older scroll position, the user briefly sees that scroll position before the fresh capture corrects it. (Earlier this was avoided entirely by waiting for the fresh capture, but the resulting black flash was worse.)
- The full overlay layer (background + adjacent + new-tab pane) renders whenever `offsetX != 0`, regardless of whether the moving current-page snapshot is ready yet. If a bitmap is missing, a clipped background pane is still drawn at the same transform so the Safari-style rounded corners do not disappear while an async capture/disk decode catches up. Earlier the layer was gated on the snapshot being non-null, which made rapid back-to-back tab switches look frozen — the new tab's post-bind capture hadn't run yet, so the gate held and `SurfaceView` translation was invisible.
- Adjacent snapshot is positioned at `offsetX - sign(offsetX) * pageStride`.
- Dragging left past the far-right tab reveals a new-tab pane that uses the same page-card geometry as normal tabs but fills it with `MaterialTheme.colorScheme.surface` (solid, not translucent). The pane is visible as soon as it enters from the right. A ghost `SafariBar` with `blankNewTabPreview = true` (showing a centered `+`) is rendered at `offsetX + pageStride` in `BottomBarLayer` (gated on `showNewTabGhost && nextTab == null`) so the user sees the `+` pill tracking alongside the new-tab page card during the drag, not just post-commit. The same `surface` color is used for the incoming new-tab pane in the commit animation and for a blank-tab card in the grid — the live GeckoView for `about:blank` is also covered by a `surface` box until a real URL is loaded, so the color stays consistent from drag through editing. During the pre-editing animation, `BarUiState.blankNewTabPreview` makes the pill hide its side buttons/tab count and render a full-pill centered `+`; once Editing mode appears, normal text input takes over. Releasing past the same distance / velocity threshold creates a real `about:blank` tab and stages Gecko selection until the spring finishes. A small accidental upward component no longer blocks creation: vertical only wins if the grid-open threshold/velocity wins before the horizontal commit path runs.
- Newly created tabs immediately put the bottom bar into Editing mode with an empty address/search field.
- On release, threshold = 120 px **or** ≥ 800 px/s in the same direction as the offset:
  - **Commit**: snap `offsetX` to 0 *immediately* and run the slide on a *separate* offset variable that drives two cosmetic layers in tandem: `leavingSnapshotBitmap` (the just-captured outgoing page) translates from the release offset to ±pageStride, and `incomingSnapshotBitmap` (the adjacent tab's bitmap) translates from `release ∓ pageStride` to 0. Both are driven by one velocity-seeded spring with `dampingRatio = dampingForVelocity(velocity)` and `stiffness = Spring.StiffnessMediumLow`. `visualSelectedTabId` advances at commit start so the URL pill and next/previous-tab math match the page the user is seeing, but the actual Gecko selection/rebind is staged in `pendingGeckoSelectTabId` until the spring settles. The active SafariBar follows `incomingSnapshotOffsetX` during the commit spring, so it stays attached to the incoming page instead of snapping to center. If a new drag or grid handoff interrupts the spring, the pending Gecko selection is flushed before the cosmetic layers are cancelled.
  - **Cancel**: animate `offsetX` to 0 with `spring(dampingRatio = dampingForVelocity(velocity), stiffness = Spring.StiffnessMedium)` seeded with the release velocity.
- The new-tab create path uses the same dual-offset commit overlay, but the incoming layer renders the empty translucent blank-tab page instead of a bitmap. Visual `about:blank` also gets a persistent translucent blank-page layer behind the editing text field, so the page does not abruptly become a plain Gecko `about:blank` background when input appears.
- Page `target=_blank` / `window.open()` requests arrive as `EngineSession.Observer.onWindowRequest(WindowRequest.Type.OPEN)`. `ContentHost` prepares the child `EngineSession`, and `TangentRoot` adds it unselected while reusing the same new-tab commit overlay: the current snapshot slides left and the incoming new-tab pane slides in from the right. The tab is selected and `WindowRequest.start()` is called after the spring finishes, so Gecko's child-window load/render work does not compete with the animation.
- Extension `WebExtensionDelegate.onNewTab` requests are bridged through `AddonController.onNewTabRequested` into the same tab-creation path. Active extension tabs animate in from the right and select after the spring; inactive extension tabs are added without selecting.
- `ContentHost` reports selected-tab paint from Gecko observer callbacks. The sticky cover clears shortly after the selected tab paints, with a 700 ms fallback so an already-loaded session that does not emit a fresh paint callback cannot leave the bitmap stuck onscreen.
- Starting a new drag cancels any in-flight switch animation and clears the sticky cover, but first flushes any pending Gecko selection so a grid handoff or rapid next swipe cannot lose the already-committed tab intent. A horizontal re-grab during the commit spring stays in tab-switch mode; it only becomes a grid handoff after actual upward movement.

Why not two live engine views? GeckoView only supports one rendered session at a time, and `engineView.render(session)` is the rebind point. Two engine views would each try to bind the same session set and instantiate huge GL state. Snapshots are the cheaper way to keep visual continuity.

## Scroll-collapsing URL bar (`ui/TangentRoot.kt`, `ui/chrome/BarExpanded.kt`)

- `ContentHost` collects `EngineView.verticalScrollDelta`; positive deltas increase `barCollapsePx`, negative deltas restore it. Deltas are accumulated and applied once per frame via `withFrameNanos` so Gecko scroll callbacks cannot start unlimited Compose/coroutine work.
- Collapse uses a hybrid preview/snap model. While scrolling below the compact threshold, the bar only previews up to ~28% collapsed using `CubicBezierEasing(0.33, 0, 0.2, 1)`, so the user sees responsive feedback without leaving the bar in awkward mid-states.
- Crossing 180 px of accumulated collapse flips the logical state to compact immediately and animates the visual bar to fully compact. Expanding back out uses hysteresis: once compact, the bar stays compact until collapse drops below 120 px, then previews expansion and settles back to expanded if scrolling pauses.
- Preview progress is written with `Animatable.snapTo`, throttled by a 0.003 epsilon so tiny scroll changes do not restart work every frame while still feeling smooth on slow scrolls. Only the discrete commit transitions animate: compact-entry, compact-exit, and settle-back-to-expanded use `Animatable.animateTo` with `spring(dampingRatio = 0.6, stiffness = Medium)` for a bouncier settle, while in-between preview updates do not animate. Width, height, corner radius, and tab-count chip alpha all interpolate from the same `collapseProgress` so they animate in lockstep. When preview settles fully back to expanded, `barCollapsePx` is reset to 0 so the next scroll starts from a clean state instead of resuming from stale accumulated collapse.
- The bar is one continuous `BarExpanded` surface, not a discrete Expanded/Compact composable switch. Width, height, corner radius, padding, side controls, and tab-count visibility interpolate from full to compact.
- Full size is screen width minus 40 dp. Compact target width is based on the displayed domain length, clamped so short domains remain tappable and long domains cannot exceed the full bar width.
- The tab-count chip fades/shrinks out before full compact. Tapping the compact domain pill expands the URL bar instead of entering text editing.
- `BottomBarLayer` owns the read of `barCollapseProgressState.value`; `TangentRoot` passes the `Animatable` down instead of reading its value directly. This limits per-frame invalidation during pill animation to the bottom bar layer instead of recomposing the full root around Gecko.
- `SafariBar` reports stable bottom inset heights instead of its animated height: 56 dp for normal/compact and 72 dp for Editing, plus 24 dp bottom padding. This avoids resizing the Gecko viewport on every collapse frame while still giving the address text field enough vertical room.
- `bottomChromeInsetPx` (measured via `onGloballyPositioned` on the `SafariBar` box) is the single source of truth for how much vertical space the pill occupies. It is passed to `ContentHost` for the Gecko viewport inset **and** to every `ModalBottomSheet` (`AddonsSheet`, `AaSheet`) as `windowInsets = WindowInsets(bottom = bottomInsetDp)`, replacing the default `navigationBars` inset. The 24 dp bottom padding already clears the gesture-nav bar, so the full pill height is the correct sheet inset — sheets stop above the pill rather than behind it.
- `ContentHost` calls `engineView.setDynamicToolbarMaxHeight(bottomChromeInsetPx)` and `engineView.setVerticalClipping(bottomChromeInsetPx)` (both **positive**) via `SideEffect`. This tells Gecko the pill occupies `bottomChromeInsetPx` pixels at the bottom, so `window.innerHeight` / `visualViewport.height` exclude the pill and `position: fixed; bottom: 0` elements land at the pill's top edge — while the GeckoView itself remains full-screen so content can scroll beneath the pill. Both calls are guarded by `> 0` to avoid calling `setDynamicToolbarMaxHeight(0)` on the initial composition before `SafariBar` is measured, which GeckoView treats as "no toolbar" and may not re-enable. Do not negate `bottomChromeInsetPx` when passing to `setVerticalClipping` — the parameter is a positive pixel height, not a negative offset.

## Snapshot cache (`state/TabSnapshotCache.kt`, `ui/content/ContentHost.kt`)

- `TabSnapshotCache` — memory LRU backed by files in `cacheDir/tab_snapshots`, capped at 24 resident entries and 256 disk entries.
- Disk keys use the page URL when available and fall back to tab id for URL-less pages. This lets seeded/restored tabs reuse screenshots after a process restart even if the runtime tab id changes.
- Capture goes through **`EngineView.captureThumbnail`**, not `EngineSession.captureThumbnail` (the latter doesn't exist — it was the first compile failure).
- `TabSnapshotCache` exposes a version `StateFlow`; Compose collects it so a newly captured bitmap can appear during a drag without waiting for unrelated UI state to change. `_version` is only incremented when the key or bitmap identity actually changes — redundant `put()` calls (same key, same bitmap reference) do not trigger recomposition.
- `get()` is non-blocking on disk: a memory hit returns synchronously, a miss returns `null` and schedules a background `BitmapFactory.decodeFile` that bumps `version` on completion, which feeds back through the existing `snapshotCacheVersion`-keyed `remember` blocks so the bitmap appears in the next composition. Negative results are tracked so repeated `get()` for an absent key doesn't keep scheduling reads. This matters because `cache.get` is called from `remember { ... }` blocks during composition (current/adjacent/predictive-back lookups, gesture-start, sticky-incoming pin) — a synchronous main-thread decode there caused per-tab-switch frame drops.
- Captures are compressed as JPEG on an IO coroutine. Before compression, bitmaps wider than 1080 px are scaled down proportionally (with OOM fallback to the original) — grid cells display at ~40% width so full-resolution storage wasted memory and encode time.
- `pruneDisk()` is throttled to at most once every 5 s. Without that, the 4–5 captures per page load × N tabs each listed and sorted the entire snapshot directory.
- Snapshot keys are versioned (`v2:` prefix) to ignore older cache files written before URL capture timing was tightened.
- `ContentHost` waits until the current URL has been idle for 600 ms, then captures again 1200 ms later for hydration/images. This covers real loads, restored tabs, and SPA routes. FCP no longer writes an early loading frame that suppresses the finished capture through deduplication.
- `SnapshotCaptureState` binds each capture to its session binding, URL, navigation generation and request sequence. Checks run after capture and after background conversion: navigation, reload, unbind and newer accepted captures invalidate stale results. Uniform compositor clear frames are rejected off main.
- Gesture captures are checked off main before replacing the moving overlay: black, near-black noise, transparent and uniform clear frames preserve the usable cache seed. One retry after 80 ms handles a briefly cleared compositor. The suspending capture is cancelled when the request/binding changes; only settled captures enter the history cache. A URL update does not replay a previous gesture request. The same quality check rejects/deletes old blank disk snapshots during decode, so they cannot seed the switcher after restart.
- Outgoing `onLoadRequest` capture is best effort while still settled. `javascript:` injections are ignored. Location-change and disposal no longer capture: their async results can already contain arriving-page pixels. Previously settled frames remain available instead.
- Disk operations run serially so JPEG writes/pruning cannot race on temporary files. A fresh put/remove invalidates any pending decode for that key, preventing disk pixels from replacing a newer memory capture.
- `ContentHost` reports accepted captures to `TangentRoot`'s immediate URL and tab maps. Back previews use only the target URL; tab-switch lookup prefers URL snapshots, with a tab fallback.
- The `DisposableEffect(engineView, engineSession, tabId)` remains the **single** rebind site; `AndroidView.update` must never render a session.
- Recent tab/url bitmaps live in heap; older retained snapshots live in the app cache partition and may be evicted by Android under storage pressure.

## Predictive back gesture (`ui/TangentRoot.kt`, `ui/content/ContentHost.kt`)

- Uses Android's system `PredictiveBackHandler`, not a custom left-edge `EngineView` touch gesture. `AndroidManifest.xml` opts into the platform back-invoked callback with `android:enableOnBackInvokedCallback="true"`.
- The handler enables as soon as the selected page reports `content.canGoBack == true`. It no longer waits for `content.history` or a previous-page screenshot, so the gesture can start immediately after Gecko exposes back availability.
- If `content.history` has a target URL, `TangentRoot` resolves a previous-page bitmap by URL only — first from `recentPageSnapshots[previousHistoryUrl]`, then `TabSnapshotCache`. It deliberately does **not** fall back to `recentTabSnapshots[selectedTabId]` because that map is keyed by tab ID and returns the most recent frame for the tab (the current page), which would make the peek show the same page the user is leaving. During a provisional load where the visible URL has advanced but history still points at the last committed page, the target is the current history entry; once the load is committed, the target is `currentIndex - 1`. If engine history has not populated yet, `TangentRoot` falls back to the last observed non-blank URL for the tab. Missing bitmap is allowed: the gesture still runs over a neutral background and commits `goBack()`.
- The previous-page bitmap, when available, stays constant size and receives only parallax translation plus a dim fade. It is drawn above the live engine view because live Gecko pixels cannot be reliably transformed by Compose.
- At gesture start, `TangentRoot` seeds the moving current-page layer from the cached current-page bitmap, then increments `currentSnapshotRequestId`; `ContentHost` captures the live engine view immediately and replaces the seed with a gesture-local bitmap at the current scroll position.
- During progress, the gesture-local current-page bitmap always dismisses toward the right, regardless of which screen edge initiated the system gesture. The previous-page parallax also keeps the same left-origin back-navigation model for both edges. The live Gecko view remains stationary underneath until commit/cancel.
- On completion, the current-page bitmap animates offscreen, `sessionUseCases.goBack` is invoked on the same tab, and the previous-page screenshot is pinned as a temporary cover. If no previous-page bitmap exists, a neutral cover is pinned instead so the old live Gecko surface cannot flash while the previous page starts loading.
- The predictive-back cover does not clear just because `selectedContent.url` changes to the target URL; Android Components can update URL state before Gecko has repainted the surface. It clears on a matching paint callback, or after the target URL is reached, loading is false, and the fallback delay expires. A hard stale-cover timeout also clears it if Gecko reports a normalized URL that never exactly matches the pinned target.
- Cancel cleanup runs in a `finally` path so an interrupted cancel animation cannot leave the screenshot layer above the live engine.
- The old `ContentHost` custom predictive-back touch callbacks still exist in the API surface but are disabled from `TangentRoot` (`canStartPredictiveBack = false`). They can be removed later if the system handler remains the only supported path.

## Custom Tab exit animation (`ui/customtab/CustomTabRoot.kt`)

External links open in `CustomTabActivity` (own task); its close has two paths — do not collapse them (see AGENTS.md "Two activities / external links" for the inline guardrail).

- **Resolved close animation** — used when the CCT caller supplied `EXTRA_EXIT_ANIMATION_BUNDLE`, or via our `R.anim.custom_tab_exit` fallback for Google-app-referrer launches (the home-screen Discover panel sends plain VIEW intents). `CustomTabRoot` registers a lowest-priority `PredictiveBackHandler` that shrinks the whole UI into an opaque rounded-corner card tracking the gesture (`closeSlide`, M3 predictive-back style) and routes the commit through `Activity.finish()`, which plays the animation via `overridePendingTransition` with a temporary `getPackageName()` override so client-APK anim resources resolve against the *client's* package (Chrome's technique; keys `android:activity.packageName`/`animEnterRes`/`animExitRes`). This intercept is required for home-screen Discover launches: their close is a cross-task transition down to the embedded "-1 screen", and the launcher animates nothing for this icon-less, recents-excluded task. **Do not** try to get the *system* predictive preview here by pre-registering the animation with `overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)` and dropping the callback — tried on Android 16/Pixel: the close then plays nothing at all (no drag preview, and the override suppresses even the canned animation). The in-app predictive drag + `finish()` animation is the working combination.
- **Plain ACTION_VIEW** (no exit bundle, non-Google referrer) — when the page has no web history `CustomTabRoot` registers **no** back callback, so Back finishes the activity and Android plays its native predictive-back animation straight back to the calling app.

## Edge-to-edge & status-bar area

- `MainActivity` calls `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT), …)`. The default no-arg overload applies a translucent scrim — that's why the bar wasn't actually transparent before.
- Engine view does **not** use `Modifier.statusBarsPadding()` — it draws under the status bar so site backgrounds reach the screen edge.
- A top CSS spacer of `WindowInsets.statusBars` height is injected into each page (`PageBottomSpacer.kt#injectViewportSpacers`) so document-flow content starts in the safe area. A JS pass also adjusts top-pinned fixed/sticky headers. Re-injected on FCP / load-complete.
- No status-bar scrim is drawn. Earlier iterations used Haze blur, then a solid translucent surface fill; both were removed. GeckoView is a `SurfaceView` so Compose cannot blur its pixels, and the solid fill ended up looking like a stripe of chrome rather than transparency. The status-bar area now shows the page directly under the system bar icons. To get true live-page blur GeckoView would need to be on a `TextureView`.

## Page-level injection (`ui/content/PageBottomSpacer.kt`)

**JavaScript URL transport:** `loadJavascriptUrl` percent-encodes the source after `javascript:`.
Gecko decodes that URL before evaluating it: raw `%360` in gradient modulo arithmetic becomes `60`,
making the entire top-inset script fail to parse before it creates a spacer. Literal percent escapes
inside strings are vulnerable too. Keep encoding in the shared transport helper, and test an actual
JavaScript URL (`tools/top-padding-harness/transportTest.js`); direct `page.evaluate` skips this step
and previously let the all-sites failure pass every layout test.

**Visual contract:** a top header's background must continue upward into the camera-cutout/status-bar
area, with the header's text, icons, and controls entirely below that area. Clearing the band by
moving the complete header down and exposing the page background is insufficient. Verify both
geometry and painted pixels, including after scrolling when the ordinary document spacer is gone.
The viewport fill is conditional: only a visible fixed/stuck header touching the safe-area edge
owns it. A static header, an unpinned sticky header, or an off-screen/hidden header must not leave
an opaque viewport band. The in-flow spacer still scrolls away normally. `pinnedFillTest.js`
checks painted pixels after scroll, hide/return transitions, inset changes and disable.

Header declarations that still match our applied inset are retained across rescans, including
while dropdowns animate. Do not restore/reapply those declarations just to measure candidates:
that restarts CSS transitions and measures intermediate heights. Drain our synchronous mutation
records after reconciliation so our own writes do not schedule another reconciliation. During
scroll/transition frames, only the small paint-owner list is checked, not the document-wide scan.
The replacement design and acceptance matrix are in [`docs/TOP_INSET_PLAN.md`](docs/TOP_INSET_PLAN.md).

`EngineSession.injectViewportSpacers(topPx, bottomPx, skipHeaderInsets)` injects:

- A `<style>` with `scroll-padding-top` / `scroll-padding-bottom` so anchor jumps don't land under the status bar or the floating bottom bar.
- A top spacer `<div>` prepended to body — pushes flow content into the safe area.
- A bottom spacer `<div>` appended to body — clears space behind the floating SafariBar.
- A fixed/sticky header pass, unless `skipHeaderInsets = true`, that walks the document and open shadow roots looking for visible, top-level, top-pinned headers near the viewport top. Fixed headers keep `top: 0` but gain `padding-top`; height is only increased explicitly for `box-sizing: border-box` elements with a non-`auto` height (the case where the added padding would otherwise compress the content area). `content-box` and `height: auto` elements just receive `padding-top` and expand naturally — previously all fixed headers got an unconditional `height: original + topPx`, which made `content-box` headers 2 × `topPx` too tall. Sticky headers get their `top` offset increased by the status-bar inset instead of getting extra height, so they stick below the system icons without changing document flow.

The top and bottom spacer `<div>`s use `display: block; visibility: hidden; pointer-events: none` — `clear: both` was removed because it is a float-clearance mechanism that is a no-op in flex/grid layouts and can unexpectedly shift content in float-based layouts.

Triggered on FCP and load-complete. `injectedForCurrentLoad` debounces, and a `suppressJavascriptLoadCallback` guard prevents the `javascript:` URL itself from re-triggering injection. The header pass retains intact insets, reconciles changed ones, chooses ancestor header candidates before nested children to avoid compounding padding in complex navs, and reruns from a `MutationObserver`, resize, and delayed timers for late-rendered site shells.

The MutationObserver drains records generated by a completed reconciliation and ignores changes confined to Tangent's paint/spacer elements. Site changes on managed headers are still checked for overwritten inset declarations. A pending-frame flag coalesces callbacks; existing listeners and delayed callbacks dispatch to the current injection closure, so inset changes and disabling cannot revive stale values.

Full scans use a 500–2000 ms adaptive cooldown, with bypasses for overwritten declarations and returning headers. Retained elements are excluded from candidate measurement and duplicate descendant offsets. Scroll and animation paint updates only check the managed header list and never reset its padding or height.

The setTimeout cascade is three timers: 1 s, 3 s, 10 s. The 250 ms timer was removed — with the 500 ms throttle gate, it was always a no-op. These guards collectively prevent the self-firing restyle loop that previously caused `captureThumbnail` to land on transitional frames and fill the snapshot cache with dark bitmaps.

## Address input (`state/GeckoTabRepository.kt`, `state/TangentViewModel.kt`)

Address submission goes through `normalizeUrl`:

- Empty input stays `about:blank`.
- Inputs with an explicit scheme (`https:`, `about:`, etc.) load as-is.
- Inputs without whitespace that look host-like (`apple.com`, `localhost`, `localhost:3000`) get `https://` prepended.
- Everything else becomes a Google search URL.

New-tab creation sets `BarMode.Editing` and clears `editingText`, so the keyboard/address field opens immediately.

Entering or tapping the editing text field selects the full current address, making replacement typing the default path.

### `about:blank` auto-dismiss

`GeckoTabRepository` tracks the last-selected tab id. `dismissBlankTabAndReturn()` removes the current tab and re-selects the previous one only if the current tab's URL is `about:blank` and the previous tab still exists in the store. Wired to:

- The system back gesture in `TangentRoot` when `canGoBack == false` and the URL is `about:blank` (a second `BackHandler` below the history-back one).
- The Editing-mode cancel callback when the underlying URL is still `about:blank` (typically a brand-new tab the user opened then bailed out of without typing).

If no previous tab exists the call is a no-op — the only tab is never killed.

### Address suggestions (`state/AddressSuggestionProvider.kt`)

`addressSuggestions(query, history, bookmarks)` (on `Dispatchers.IO`) returns up to a few
`AddressSuggestion`s for the typed query, de-duplicated by URL:

- **Bookmarks** — `BookmarksStorage.searchBookmarks` has an inline-class signature that doesn't
  resolve cleanly from Kotlin here, so we mirror `BookmarksSheet`'s proven path instead: read
  `getTree(BookmarkRoot.Mobile.id, recursive = true)` and filter the flattened items locally
  (bookmark counts are small, so this is cheap).
- **History** — `HistoryStorage.getSuggestions(query, max)`.

The view-model exposes `onSubmitText(text)` which loads a tapped suggestion through the same
`repo.loadUrl` → `normalizeUrl` path as a manual submit, so a URL loads directly and bare text
becomes a search.

### Address sheet (`ui/chrome/AddressSheet`, `ui/chrome/ImeSheetController.kt`)

While the bar is in `BarMode.Editing`, `AddressSheet` presents the suggestions as an iOS-style
sheet over a scaled-down snapshot of the page being left:

- **Backdrop** — a scrim plus the selected tab's `currentPageSnapshot` (the existing snapshot
  cache bitmap) rendered as a card that scales `1f → 0.93f` about its top edge and gains rounded
  corners as the sheet opens, leaving the page's top peeking below the status bar. Tapping the
  backdrop dismisses (calls `onEditingCancel`).
- **Sheet** — a rounded panel with a grabber and the suggestion list. The live editing pill
  (bottom bar) keeps its own `imePadding()` and so stays pinned to the keyboard top. The **sheet
  itself does NOT use `imePadding`** — it positions its resting bottom with an explicit `kbHeightPx`
  inset (captured live from `WindowInsets.ime` while not dragging) and *translates* its whole self by
  the finger travel, because it must slide fully off-screen (top edge and all), which `imePadding`
  (a grow/shrink of the bottom edge) cannot do.
- **Drag-to-dismiss (frame-locked, API 30+)** — dragging anywhere on the sheet down translates it
  1:1 with the finger (`rawDragPx`) so it slides off-screen with no fade. In parallel the keyboard is
  driven down frame-for-frame via `ImeSheetController` (`insetByUp(-dy)`), a wrapper around the
  platform `WindowInsetsAnimationController`. Verified on device, Compose's `imePadding()` **does**
  track manual IME control, so the editing pill rides the collapsing keyboard down for free and comes
  to rest as the pill once the keyboard is fully collapsed — the pill must therefore NOT also be
  translated by the descent (doing so double-counts and pushes it behind the keyboard). The backdrop
  scale/dim is driven by `f = 1 - rawDragPx/kbHeight`. On release, `rawDragPx > kbHeight/2` or a fast
  fling dismisses (sheet animates the rest of the way off, then `onEditingCancel`), else it settles
  back open; `animateToFinish(show)` rides the real keyboard to its resting state either way.
- **Below API 30** there is no manual IME control (`isSupported = false`): the sheet still slides via
  `rawDragPx` and dismiss hides the keyboard the normal way.

## Firefox add-ons (`addons/AddonController.kt`, `ui/addons/AddonsSheet.kt`, `ui/chrome/AaSheet.kt`)

Add-ons use Gecko's built-in WebExtension APIs directly instead of the larger `feature-addons` UI stack.

- `AddonController` registers a `WebExtensionDelegate` at component construction time, then mirrors installed extensions into a `StateFlow`.
- The add-ons manager lives in the **Add-ons tab of `AaSheet`** (the sheet opened by the `aA` button). There is no separate add-ons toolbar button. The tab embeds `AddonsManagerSheetContent` inline — install by AMO slug / page URL / direct `.xpi` URL, enable/disable with a switch, remove with a trash icon, and open an extension's options page via its settings icon.
- Slugs/pages are normalized to `https://addons.mozilla.org/firefox/downloads/latest/{slug}/latest.xpi`; direct `.xpi` URLs are passed through.
- Installed add-ons can be enabled, disabled, removed, and opened to their manifest-declared options/settings page from the tab. `AddonController` exposes `WebExtension.Metadata.optionsPageUrl` on each `InstalledAddon`; the manager shows a settings icon when that URL exists, and `TangentRoot` opens it in a normal selected tab.
- **`AddonsSheet` is permission-prompt-only.** Install, update, and optional permission prompts are bridged into `AddonsSheet` as a separate `ModalBottomSheet` that appears automatically above `AaSheet` when Gecko issues a permission callback. This is required because Gecko waits for those callbacks before completing installation. The prompt groups requested access into extension, website, and data-collection sections, with common WebExtension permission keys rendered as readable descriptions instead of raw manifest strings. Accepting or denying the prompt dismisses `AddonsSheet` immediately.
- AMO detail pages get a small page-level click bridge injected from `ContentHost`: clicking “Add to Firefox” navigates to the add-on's latest `.xpi`, and `.xpi` / AMO download requests are intercepted and passed to `AddonController.installFromPageRequest`. `TangentRoot` observes `AddonController.state.pendingPrompt` and opens `AddonsSheet` automatically so Gecko's install permission dialog appears regardless of whether the user has `AaSheet` open.

Known limitations:

- No curated/recommended AMO catalog is built yet.
- Browser-action/page-action toolbar surfaces are not implemented. The add-ons can run, but action popups/buttons do not have first-class UI yet.

## Firefox Sync (`sync/FirefoxSyncController.kt`, `sync/PlacesLoginsStorage.kt`, `ui/sync/SyncedTabsSheet.kt`, `ui/passwords/PasswordsSheet.kt`)

Firefox Sync supports the `tabs`, `passwords`, `creditCards`, `history`, `bookmarks`, and `addresses` engines. Passwords are stored locally using AES-256-GCM encryption backed by Android KeyStore and synced via Firefox Sync when the user is signed in.

- `TangentApplication` initializes application-services HTTP plumbing via `AppServicesInitializer` / `RustHttpConfig`.
- `FirefoxSyncController` owns `FxaAccountManager`, `RemoteTabsStorage`, and `SyncedTabsStorage`.
- The FxA OAuth flow uses Mozilla's public Android sample client id (`3c49430b43dfba77`) and redirects to `https://accounts.firefox.com/oauth/success/{clientId}`. `ContentHost` intercepts that success URL from Gecko observer callbacks (both `onLoadRequest` and `onLocationChange`), extracts `code` / `state`, and calls `FxaAccountManager.finishAuthentication`. A `@Volatile authInFlight` flag in `FirefoxSyncController` prevents `finishAuthentication` from being called twice if both callbacks fire before `stopLoading()` takes effect.
- `SyncedTabsStorage.start()` watches the `BrowserStore`, stores non-private loaded local tabs into the remote-tabs store, and syncs the tabs engine after debounced tab changes.
- The tab grid exposes a **Synced Tabs** button and a **Settings** button side-by-side. `SyncedTabsSheet` handles sign-in, sign-out, manual "Sync Now", and opening tabs from other Firefox devices.
- Authentication completion removes the temporary sign-in tab and reopens the sheet with synced tabs.
- When the sign-in URL is opened from the sheet, `closeGrid(animateBack = false)` is called immediately so the sign-in page is visible rather than hidden behind the grid. When a synced tab is opened, `closeGrid(animateBack = true)` is called so the grid closes with animation and the opened tab is visible.

### Passwords storage (`sync/PlacesLoginsStorage.kt`)

`PlacesLoginsStorage` implements `LoginsStorage` (from `concept-storage`) and `SyncableStore` (from `concept-sync`), wrapping `mozilla.appservices.logins.DatabaseLoginsStorage` from the native Rust logins crate (`org.mozilla.appservices:logins:147.0.1`):

- **Encryption**: Managed natively by the Rust logins crate (SQLite + encryption). `KeyManager.getKey()` must return the UTF-8 bytes of an appservices **JWK** from `mozilla.appservices.logins.createKey()` (a `{"kty":"oct",…}` string) — **not** 32 raw random bytes, which fail appservices' `from_utf8`→JWK parse with `InvalidKey` and silently break every encrypt/decrypt (see AGENTS.md invariant #14). The JWK is resolved eagerly and sealed under an AndroidKeyStore AES-GCM wrapping key.
- **Persistence**: The Rust `DatabaseLoginsStorage` writes to `tangent_logins.db` in the app's private database directory. All crypto and serialization happens in native code via the Rust logins crate, backed by the `libmegazord.so` native library.
- **Sync registration**: `DatabaseLoginsStorage.registerWithSyncManager()` registers the Rust store with the native sync engine. The store is also registered with `GlobalSyncableStoreProvider` in `FirefoxSyncController.init` for the `SyncEngine.Passwords` engine. `FxaAccountManager` is configured with `supportedEngines = setOf(SyncEngine.Tabs, SyncEngine.Passwords, SyncEngine.CreditCards, SyncEngine.History, SyncEngine.Bookmarks, SyncEngine.Addresses)`.
- **API**: `list()`, `get(id)`, `getByBaseDomain(domain)`, `add(LoginEntry)`, `update(id, LoginEntry)`, `addOrUpdate(LoginEntry)`, `findLoginToUpdate(LoginEntry)`, `touch(id)`, `delete(id)`, `wipeLocal()`.
- **Autofill**: `engine.settings.loginAutofillEnabled = true` is set at construction time in `buildDefaultSettings()`.

### Critical: register `RemoteTabsStorage` with `GlobalSyncableStoreProvider` before `accountManager.start()`

`FxaAccountManager` is configured with `SyncConfig(supportedEngines = setOf(SyncEngine.Tabs))`, but that only tells the sync scheduler *which* engine to run — not *where* to store data. The backing store must be registered separately via:

```kotlin
GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs to lazy { remoteTabsStorage })
```

This must be called **before** `accountManager.start()`. Without it, sync runs but writes to and reads from nowhere — `getSyncedDeviceTabs()` always returns empty.

### Critical: `syncNow` is fire-and-forget — always await completion before reading

`FxaAccountManager.syncNow()` returns as soon as it has **enqueued** a WorkManager one-shot job (`WorkManagerSyncDispatcher.syncNow` calls `WorkManager.beginUniqueWork("Immediate", KEEP, …).enqueue()` and returns immediately — it is not a suspending call that awaits the job). Calling `syncedTabsStorage.getSyncedDeviceTabs()` right after will read **stale data** because the sync worker hasn't run yet.

The correct pattern in `refreshSyncedTabs` is:
1. Register a `SyncStatusObserver` on `accountManager.syncStatusObserverRegistry` (Kotlin property, not a `getSyncStatusObserverRegistry()` method call) **before** calling `syncNow`.
2. Store the result in a `CompletableDeferred<Unit>`.
3. Call `syncNow`.
4. `withTimeout(30_000L) { syncDone.await() }` — suspends until `onIdle` or `onError` fires, with a 30 s fallback that still lets the read proceed on stale data.
5. Only then call `getSyncedDeviceTabs()`.

The observer must be registered first to avoid a race where sync completes before the observer is attached (completing immediately with stale data and leaving the deferred unresolved forever). Note that `accountManager` implements `Observable<AccountObserver>` — its `register`/`unregister` methods accept `AccountObserver` only. `SyncStatusObserver` must be registered on `accountManager.syncStatusObserverRegistry` (`ObserverRegistry<SyncStatusObserver>`), not on `accountManager` directly.

Known limitations:

- Passwords sync uses `PlacesLoginsStorage` wrapping the native Rust `DatabaseLoginsStorage` from `org.mozilla.appservices:logins:147.0.1`, which provides full Rust-backed sync engine bindings via the `libmegazord.so` native library. The `DatabaseLoginsStorage.registerWithSyncManager()` call registers the Rust sync engine for bidirectional password sync.
- Bookmarks, history, credit cards, addresses, and add-ons are not wired.
- Production distribution should use an app-specific FxA OAuth client instead of the sample client id.

### Sync triggers & why FxA push is inert

`FirefoxSyncController` keeps synced tabs fresh via three layered mechanisms — a background WorkManager
sync every 4h (`PeriodicSyncConfig(240, 5)`), a 30-min foreground poll driven by `TangentRoot`'s
lifecycle observer, and the FxA push fast-path (`initPush()`). AGENTS.md "Firefox Sync — three sync
triggers" carries the inline guardrails; this is the full analysis of why push delivers nothing on our
build and must not be "fixed" by retrying it.

- Mozilla's push server (`updates.push.services.mozilla.com`) is an FCM **bridge to Mozilla's own
  Firebase project**. A subscription for a *third-party* sender id returns
  `400 {"errno":106,"message":"User has invalid app ID <senderId>"}`. Our `google-services.json` is a
  personal project, so every autopush `subscribe()` fails and FxA never gets a push endpoint
  (`currentDevice.subscription == null`). FCM token retrieval itself succeeds — the wall is Mozilla's
  bridge, not Google.
- Desktop Firefox/Zen get instant push via a **direct WebSocket** to autopush (no FCM, no app-id
  gate). Mobile Firefox (Fenix) uses the FCM bridge but with **Mozilla's** sender id/project, which
  the bridge accepts. F-Droid forks (Fennec, Mull) ship **no** FCM and rely on polling — as we do.
- Real push on our own project needs either self-hosting an autopush bridge to our Firebase and
  pointing `PushConfig(serverHost=…)` at it, or piggybacking Mozilla's sender id (works but violates
  Mozilla's ToS, fragile).
- `initPush()` scaffolding is kept isolated and failure-swallowing so a future self-hosted autopush
  needs only a `serverHost` change. It stays inert without a real `app/google-services.json`: the
  Google Services Gradle plugin is applied conditionally (`if (file("google-services.json").exists())`),
  and `initPush()` looks up `gcm_defaultSenderId` **by name at runtime** (`resources.getIdentifier`) —
  do not add a hard `R.string.gcm_defaultSenderId` reference (breaks the no-Firebase build).
  `google-services.json` is gitignored.
- **Receipt (the real path):** incoming "Send tab" commands are pulled by `pollIncomingCommands()` →
  `deviceConstellation().pollForCommands()` after every foreground sync pass, unconditionally
  (independent of push/Firebase). A received tab raises a `received_tabs` notification
  (`sync/ReceivedTabNotifications`) rather than auto-opening; tapping it opens the URL as a normal tab.

## Build configuration (`app/build.gradle.kts`)

- `isMinifyEnabled = true`, `isShrinkResources = true` on release.
- Release signing uses the local debug keystore (intentional for now).
- **ABI filter for dev**: `ndk.abiFilters += "arm64-v8a"` unless `-PsingleAbi=false` is passed. Drops APK from ~546 MB → ~200 MB. Native libs are still unstripped — `ndk.debugSymbolLevel = "none"` would shave another ~80 MB but isn't applied yet.
- No ABI splits configured. For real distribution this should become an AAB.

## Passkeys / WebAuthn (`TangentBrowserActivity.kt`, `engine/Engine.kt`, `sync/webauthn/`)

GeckoView 120+ implements the WebAuthn spec internally — challenge/response crypto, talking to the platform authenticator, and Credential Manager calls all happen inside Gecko without host-app protocol code. The host app's only job is to give GeckoEngine an activity delegate so it can launch credential picker UI and receive the result.

- `MainActivity` implements `mozilla.components.concept.engine.activity.ActivityDelegate` and registers itself via `engine.registerActivityDelegate(this)` in `onStart`, unregistering in `onStop`.
- Gecko Components 147 exposes `ActivityDelegate.startIntentSenderForResult(intent, onResult)` rather than the older `ActivityResultHandler` / `engine.onActivityResult` bridge. `MainActivity` backs that delegate with `ActivityResultContracts.StartIntentSenderForResult`, stores the pending callback, and forwards `result.data` back to Gecko.
- On Android 14+, GeckoView calls `CredentialManager.createCredential` / `getCredential` directly; the registered delegate satisfies the activity-context requirement for those calls too.
- `androidx.credentials:1.5.0` and `credentials-play-services-auth` are explicit dependencies so CredentialManager classes are on the classpath at runtime for GeckoView's internal use (Play Services FIDO2 is the fallback on Android 8–13).

The `SecurityKey` mode deliberately bypasses Android's provider sheet. Its activity-owned YubiKit
`FidoClient` discovers generic FIDO2/CTAP authenticators over USB or NFC, handles key/PIN/touch UI,
and returns standard WebAuthn response JSON through Tangent's existing isolated extension bridge.
The bridge supplies the original browser-owned client-data hash and rewrites `clientDataJSON` in the
page-owned response, just as it does for Credential Manager. A roaming key reports UVPAA false; it
must never be advertised as a platform authenticator merely to pass a site's capability gate.

Passkeys are stored by the selected authenticator and are independent of Firefox Sync's logins
database. Hardware-key private material never enters Tangent or Bitwarden.

## Reader mode (`ui/reader/`, `ui/chrome/AaSheet.kt`, `ui/content/ContentHost.kt`)

Reader mode extracts article content from the live page via JavaScript and renders it in a clean native Compose view. The **same extraction heuristic** (article-element scoring → per-site include/strip rules → heading/paragraph/blockquote/list/table/figure/inline-math block extraction) exists in two forms with **two transports**; keep them behaviourally in step:

### Two transports (Port primary, prompt fallback)

1. **Content-script Port (primary).** The built-in reader extension (`assets/extensions/reader/`, content script `extractor.js`, native-messaging port `TangentReader`) runs the extractor in an **isolated world** and returns the article over a `Port` — never `window.prompt`. This is the *only* path that works on pages served with a CSP `sandbox` directive that omits `allow-modals` (which silently no-ops `prompt()` — e.g. TechCrunch). `ReaderExtension` (see `ui/reader/`) installs it once from `Engine.init` (mirrors `WebDarkModeExtension`) and owns the shared `ReaderPortHandler`. `ContentHost` calls `ReaderExtension.registerFor(session, tabId)` to route a session's port to its tab; `ReaderPortHandler.onPortConnected` pushes the compact `ReaderRules` over the port (`{action:"config", rules}`), and `onPortMessage` routes `{type:"article"}` → `ReaderManager.setReaderEligible`. `extractor.js` auto-extracts at `document_end` (+ a bounded re-extract as late content lands) and on `{action:"extract"}`.
2. **`javascript:` prompt bridge (fallback).** `ReaderExtractor.extractReaderContent(engineSession, tabId)` injects a `javascript:` URL that walks the DOM and calls `window.prompt("tangent-reader", JSON.stringify(articleData))`. Used only when the extension isn't installed yet or a session has no live port. `ReaderExtractor.registerPromptDelegate` wraps `GeckoSession.promptDelegate` with `ReaderPromptDelegate`, which intercepts `TextPrompt` where `prompt.message == "tangent-reader"` (data in `prompt.defaultValue`); guard `if (oldDelegate is ReaderPromptDelegate) return` prevents double-wrapping. `ReaderPromptDelegate` also carries all the *other* `tangent-*` bridge probes, so it is registered regardless of transport.

Both `triggerInitialReaderCheck` (per-load, in `ContentHost`) and `toggleReader` (manual "Show Reader") try `ReaderExtension.requestExtract(session)` first and fall back to `extractReaderContent` only when it returns false.

### Architecture

- `ReaderManager` (singleton) holds four `StateFlow`s: `activeReaderTabs`, `eligibleReaderTabs`, `tabArticles`, `pendingActivationTabs`. All keyed by tab id.
- `ContentHost` calls `registerPromptDelegate` **and** `ReaderExtension.registerFor` inside `DisposableEffect(engineView, engineSession, tabId)` after `engineView.render(session)`.

### Critical: `prompt.message` not `prompt.title`

`window.prompt(message, defaultValue)` maps to `TextPrompt.message` (first argument) and `TextPrompt.defaultValue` (second argument). There is no `TextPrompt.title` field for this purpose. Checking `prompt.title` silently discards every extraction result.

### Activation flow

1. User taps "Show Reader" in `AaSheet` (Page tab).
2. If already eligible → `readerManager.setReaderActive(tabId, true)` — immediate.
3. If not yet eligible → `readerManager.requestReaderActivation(tabId)` (adds to `pendingActivationTabs`) then `ReaderExtractor.extractReaderContent(engineSession, tabId)`.
4. When the `javascript:` prompt fires, `ReaderPromptDelegate` calls `ReaderManager.setReaderEligible(tabId, article)`, which also auto-activates any pending tab.
5. `TangentRoot` observes `activeReaderTabs` and `tabArticles` — when both have an entry for the selected tab, `ReaderView` overlays `ContentHost`.

### JS injection guard

`extractReaderContent` loads a `javascript:` URL via `engineSession.loadUrl(..., ALLOW_JAVASCRIPT_URL | LOAD_FLAGS_REPLACE_HISTORY)`. `ContentHost` sets `suppressJavascriptLoadCallback = true` before calling it (same guard used by the viewport spacer injection) so the JS URL load does not re-trigger extraction or spacer injection in `onLoadingStateChange` / `onFirstContentfulPaint`. Reader state (`ReaderManager.clearReaderState`) is cleared in `onLocationChange` only for non-`javascript:` URLs.

## What's intentionally not built

- **Profile / tab persistence**. `BrowserState` is in-memory only; local open tabs are published to Firefox Sync while signed in, but the selected/open local tab set still is not restored on cold start.
- **Browser-action / page-action surfaces** for extensions.
- **Multi-window**. Activity is single-window only.
- **Per-session engine settings** (e.g. per-tab JavaScript toggle). `applyEngineSettings()` mutates the shared `GeckoEngine.settings` object; changes apply to subsequent page loads across all tabs, not retroactively to already-loaded sessions.

## Downloads (`MainActivity.kt`, `engine/Engine.kt`, `ui/downloads/DownloadsSheet.kt`)

File downloads are captured by mozilla-components and handed off to the **platform**
`android.app.DownloadManager`:

- `TangentBrowserComponents.downloadsUseCases = DownloadsUseCases(store, application)`.
- `MainActivity.downloadsFeature` is a `DownloadsFeature` wired with `AndroidDownloadManager`,
  started in `onStart` / stopped in `onStop`. It observes the selected tab's `content.download`,
  consumes it, and enqueues into the system DownloadManager (OS owns the notification, storage,
  and persistence). No `fragmentManager` is given, so there is no pre-download confirm dialog —
  downloads start immediately. On API ≤ 28 it requests `WRITE_EXTERNAL_STORAGE` via
  `onNeedToRequestPermissions` → `REQUEST_CODE_DOWNLOAD_PERMISSIONS`, resolved in
  `onRequestPermissionsResult` → `downloadsFeature.onPermissionsResult`.

The in-app **Downloads list** (`DownloadsSheet`) does not read `BrowserStore.state.downloads`
(no `DownloadMiddleware`/`DownloadStorage` is installed, so that map stays empty with the
AndroidDownloadManager path). Instead it queries the platform `DownloadManager.query()` directly —
that API only returns downloads owned by this app, and the OS keeps them across process death,
so it is the source of truth that matches what AndroidDownloadManager enqueued.

- Reached from a **Downloads button** in the tab-grid toolbar (alongside Synced Tabs, Passwords,
  History, Bookmarks, Settings).
- Rows show file name, status (Pending / Downloading / Paused / Failed / size + time when done),
  and a live `LinearProgressIndicator` for active downloads. The sheet polls `queryDownloads`
  every 1.2 s **only while** an active (running/pending/paused) download exists, then stops.
- Tapping a completed row opens the file via `ACTION_VIEW` on
  `DownloadManager.getUriForDownloadedFile(id)` with `FLAG_GRANT_READ_URI_PERMISSION`.
- Trailing trash icon calls `DownloadManager.remove(id)` (removes entry + file); "Clear all"
  removes every entry.

## GeckoView background color

`GeckoEngineView.asView().setBackgroundColor()` does not propagate to Gecko's compositor in this version of mozilla-components — the method hits the Android `FrameLayout` wrapper rather than the `GeckoView` itself. The loading-state grey is instead covered by a Compose `Box` overlay rendered above `ContentHost` in the `Box` Z-stack. `geckoHasPainted: Boolean` gates it: while `!geckoHasPainted && stickyIncomingTabId == null && stickyBackCoverUrl == null`, a `Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)` box covers the engine.

`geckoHasPainted` is computed on each `actualSelectedTabId` change: it starts `true` if the incoming tab has a cached snapshot (was previously rendered) **or** if `content.loading == false` — either condition means Gecko has already painted content and won't fire a new paint callback after rebind. Without this pre-set, switching to any already-loaded tab would leave the overlay up indefinitely since `onTabPainted` only fires for new loads. For genuinely cold tabs (no snapshot, currently loading), it starts `false` and flips to `true` when `onTabPainted` fires.

The sticky-cover system is excluded from the gate so the two layers don't double up — the sticky covers already handle tab-switch and back-gesture transitions; this layer only fires for cold-start and new-tab loads where no sticky cover is active.

## Known follow-ups

- Strip native debug symbols (`debugSymbolLevel = "none"`) for production-shape release builds.
- Investigate moving GeckoEngineView to TextureView for working backdrop blur (would let the top status-bar fill go back to a real blur).

## Tab grid view (`ui/grid/Grid.kt`, `ui/TangentRoot.kt`, `ui/chrome/BarPill.kt`)

The 2D tab grid is a swipe-up-on-pill gesture continuous with the existing horizontal swipe-to-switch. `BarPill` runs a single `pointerInput { awaitEachGesture { … } }` block that does *not* axis-lock — every move dispatches a 2D `Offset` delta and release dispatches `(totalDelta, velocity)` as `Offset` pairs. The pre-slop down is *not* consumed, so child `IconButton`s and the URL `Row.clickable` keep firing for taps. The horizontal and vertical components of the same finger motion drive the carousel `offsetX` and the grid progress simultaneously — like Safari on iOS, where you can blend the two in a single drag and the release decides which gesture committed.

State in `TangentRoot`:

- `gridProgressState: Animatable<Float>` — used only for spring animations (open/close/cancel). Never snapTo'd during drag.
- `gridDragProgress: Float` (plain `mutableFloatStateOf`) — the live drag progress written directly on the hot delta path with zero coroutine overhead. The render reads `if (gridGestureActive) gridDragProgress else gridProgressState.value` so the two sources are never mixed.
- `gridDragPx`, `gridGestureActive`, `gridOpen` — finger tracking + committed states.
- `gridHandoff{Selected,Leaving}TabId/OffsetX/TopPadPx` — only set during a mid-switch handoff (grid grabbed while a horizontal-commit spring is still running). When null, the grid derives every tab's browse position dynamically from the carousel layout.
- `gridEngaged = gridGestureActive || gridOpen || gridProgressState.value > 0f || gridCommitFromProgress > 0f || gridCancelFromProgress > 0f || gridWaitingForAfterClose` — gates the existing horizontal-drag overlay rendering and BackHandler routing. The commit/cancel progress terms keep the overlay alive during the frame immediately after finger-up, before the coroutine-driven `Animatable` has produced its first value. `gridWaitingForAfterClose` stays true from `closeGrid(animateBack=true)` until `afterClose()` completes — at `progress=0` the selected tab card sits at fullscreen position with scale=1, covering the live Gecko view during the gap between animation-end and the deferred tab selection.
- `leavingSnapshotTabId` / `incomingSnapshotTabId` were added to the existing horizontal-commit overlay so the grid can hand off mid-flight: when `onPillDragStart` fires with `leavingSnapshotJob != null`, it cancels the spring, freezes both X offsets + top-pad insets into the handoff state, clears the leaving/incoming bitmaps and sticky covers, and lets the grid take over rendering. Both visible tabs continue from their current X positions and animate to their grid cells in lockstep with the grid progress spring.

`TabGridOverlay` renders **every** tab simultaneously, each at its true carousel position. For an N-tab strip with the (new) selected tab at `newSelIdx` and a `carouselShift` (= `offsetX` during a 2D drag, or `gridHandoffSelectedOffsetX` during a mid-switch handoff), tab `i`'s `browseTranslationX = (i - newSelIdx) * pageStride + carouselShift`. Off-screen tabs (e.g. four strides away) are at `±4 * pageStride` translation at progress=0 — they really are off-screen, not stacked at the center. As progress→1, each tab's X lerps from its carousel slot to its grid cell, so far-away tabs slide in from the side rather than popping out from the center. Each cell's transform pipeline mirrors the existing tab-switch overlay layout exactly: `padding(top = browseTopPadPx)` *before* `graphicsLayer { transformOrigin = TopLeft; translationX = lerp(browseX, cellLeft, xProgress); translationY = lerp(scrollOffsetPx, cellTop, p); scaleX = scaleY = lerp(1, cellW/W, p) }` *before* `clip(SquircleShape)`. The `translationY` lower bound is `scrollState.value` (not 0) because `cellTop` is in scroll-content coordinates; visual y = `translationY − scrollOffset`, so lerping from `scrollOffset` ensures the card lands at visual y = 0 (fullscreen) when progress reaches 0 — without this, cells past the first two rows closed above the top of the screen. This means at progress=0 a tab whose carousel position happens to coincide with the visible viewport is pixel-equivalent to the existing browse-layer snapshot — so the gesture starts with no visual seam. The grid background is `(progress * 20).coerceIn(0, 1)` so it is fully opaque within the first ~5% of drag, preventing GeckoView from showing through the squircle-clipped corners during the ramp-up.

The corner radius passed to `clip(SquircleShape)` interpolates from `browseCornerDp` (= `topChromeInsetPx * 0.8 * 2`, the same value used by the horizontal-switch overlay's `switchPaneShape`) to 22 dp as progress goes 0→1. At progress=0 the grid cell's clip is pixel-identical to the browse-layer clip, so the frame where the horizontal overlay gives way to the grid overlay has no corner-radius pop. `switchCornerRadiusDp` is computed once above all three overlay blocks (horizontal, commit animation, grid) and shared so the three layers are always in sync.

The selected tab's snapshot is `tabSwitchCurrentSnapshot ?: snapshotFor(tab.id, tab.content.url)` — same source the horizontal switch uses, so a gesture-fresh capture (matching current scroll position) is preferred over the cached entry when one is available. `tabSwitchCurrentSnapshot` is cleared (`resetTabSwitchSnapshot()`) when a different tab is tapped in the grid so the close animation shows the tapped tab's own cached snapshot instead of the previously-viewed tab's gesture capture. Other cells also use `snapshotFor(...)`, so tab-id in-memory snapshots cover rapid grid/tab interactions before disk cache decode catches up. Blank/about:blank tabs intentionally ignore snapshots and render as the same empty translucent white page used by the new-tab carousel pane, so they appear in the grid as normal tab cards with the blank-tab visual instead of placeholder text or stale captures.

`gridTabs` (the `List<GridTab>` passed to `TabGridOverlay`) is wrapped in `remember { derivedStateOf { … } }`. All state it reads on the hot path — `gridGestureActive`, `gridDragProgress`, `gridDragPx`, `offsetX`, `gridHandoffSelectedTabId`, `gridHandoffSelectedOffsetX`, `gridCommitFromPositions` — must be read **inside** the `derivedStateOf` lambda so Compose tracks them as snapshot dependencies and the lambda re-runs on every drag frame. Capturing these as plain-float or plain-value locals outside the lambda and then using those captures inside the lambda is not tracked by `derivedStateOf` and causes positions to freeze mid-gesture or commit positions to remain `null` even after they are set.

Unified gesture handlers in `TangentRoot`:

- `onPillDragStart`: if a horizontal-commit spring is mid-flight, freeze its leaving/incoming offsets into the handoff slots and flush the pending Gecko selection before cancelling the cosmetic spring. It seeds `gridDragProgress` from `gridProgressState.value`, but does not mark `gridGestureActive` until upward movement actually arrives; this keeps quick repeated horizontal swipes from being mistaken for grid handoffs. If a close/cancel animation was interrupted, it also seeds `gridDragProgress` from `gridProgressState.value` so the re-grabbed card continues from its current visual position without a pop. Otherwise it preps the existing horizontal-switch capture (cached `currentPageSnapshot` seed, fresh `requestTabSwitchSnapshot()`). The `gridXProgressState.snapTo(0f)` coroutine that previously ran here is removed — `xProgress` is derived as `0f` whenever `gridGestureActive` is true, so x is always correct with no async work.
- `onPillDragDelta(Offset)`: feed `delta.x` into the existing `applyHorizontalDelta` — guarded by `tabSwitchingEnabled || gridGestureActive`. During grid gesture, `applyHorizontalDelta` skips the first/last-tab boundary clamps so the user can wiggle the card freely in both directions (the left boundary that blocked the first tab from moving right is removed when `gridGestureActive`). `delta.y` (negated, clamped to ≥0) accumulates into `gridDragPx`; `gridDragProgress` is written directly (`gridDragPx / travelPx`) with no coroutine launch. Marking `gridGestureActive = true` once `gridDragPx > 0` is what flips `gridEngaged`, hides the existing horizontal overlays, and reveals the grid.
- `onPillDragEnd(totalDelta, velocity)`: if `gridDragPx ≥ 50%` of grid travel *or* upward velocity `≥ 1000 px/s`, **vertical wins**: snap `offsetX` to 0 (cancel any horizontal carousel motion — Safari behavior), capture the finger-release pose, and `openGrid(fromProgress = gridDragProgress, initialVelocityProgressPerSec = upwardVelocity / travelPx)`. Else: run the existing horizontal `commitHorizontalRelease(velocity.x)` logic; if any grid progress accumulated, capture the same release pose and animate from that exact drag position back to fullscreen via `gridCancelFromProgress`. Tab creation is allowed in this horizontal-wins branch even if `gridGestureActive` briefly became true from a small upward component.

`openGrid` and `springGridProgressTo` both accept `fromProgress: Float` and `snapTo` it inside the coroutine before calling `animateTo`, so the spring starts exactly where the finger lifted rather than from a stale zero. Spring parameters: `openGrid` uses `dampingRatio=0.75, stiffness=400` (snappy open with a slight elastic overshoot at the end); `springGridProgressTo` uses `dampingRatio=1.0, stiffness=220` (critically damped cancel, paced to match); `closeGrid(animateBack=true)` uses `tween(300ms, CubicBezierEasing(0,0,0.2,1))` — a fast ease-out (decelerate) curve with no spring bounce, matching the iOS/Material close-to-fullscreen feel. The actual Gecko session selection is deferred to `afterClose()` to avoid CPU spikes competing with the animation.

**Initial scroll position**: `TabGridOverlay` pre-positions the scroll while the grid is fully closed so the selected tab is visible when it next opens. A `LaunchedEffect(gridIsClosed, selectedTabId)` fires when `progress < 0.001 && !isGesture && commitFromProgress == 0 && cancelFromProgress == 0`, calling `scrollState.scrollTo((cell.top − topMarginPx).coerceAtLeast(0))` for the selected tab's row. Doing this while the grid is closed (progress ≈ 0) is safe: visual y = `ty − scrollState.value = scrollState.value − scrollState.value = 0`, so there is no visible jump. Setting scroll mid-gesture (progress > 0) would shift the `ty = lerp(scrollState.value, cell.top, progress)` base value and cause a visible upward teleport, so the effect is explicitly gated on the closed condition. The `scrollEnabled` gate remains unchanged — user touch-scroll is still disabled until `progress ≥ 0.999`.

Domain pills (`pillAlpha` in `TabGridOverlay`) are suppressed entirely while `isGesture=true` (finger still down) and only fade in once the finger lifts and the commit spring begins. This prevents the labels from being visible during the swipe-up drag — they appear as part of the settle animation, not as a side-effect of partial progress.

A **new-tab "+" cell** is always appended at `index = tabs.size` in `TabGridOverlay`. It uses the same `gridCellRect` position as if it were an extra tab, fades in with `pillAlpha` (same gate as domain pills — suppressed during the gesture, visible after), and is always placed at its grid position with no browse-layer lerp. `totalItems = tabs.size + 1` is used for the row-count / content-height calculation so it correctly reserves space for the extra cell.

Tapping the "+" cell transitions directly to the new blank tab without a carousel slide animation:
1. `gridNewTabSelected = true` is set in `TangentRoot`, which shifts `newSelIdx` to `tabsList.size` in the `gridTabs` computation. This makes all real tabs' `browseTranslationX` negative (they lerp off-screen left during the grid close). `gridNewTabSelected` is added to the `gridTabs remember` keys so that `derivedStateOf` captures the updated `newSelIdx`.
2. `TabGridOverlay` receives `newTabSelected = true`, which suppresses the fixed-position "+" cell and instead renders an **animated** "+" cell using the standard lerp: `scale = lerp(1, cellScale, progress)`, `tx = lerp(0, cell.left, xProgress)`, `ty = lerp(scrollOffset, cell.top, progress)`. At progress=0 (grid fully closed) the cell is at fullscreen; at progress=1 it is at its grid slot. This mirrors exactly how a real selected tab expands to fullscreen.
3. `closeGrid(animateBack = true)` runs the 300 ms ease-out close animation. In `afterClose`, `gridNewTabSelected = false`, `viewModel.onCreateTabFromEdge(selectTab = false)` creates the new tab, `stickyIncomingTabId` is set to cover GeckoView, and `commitPendingGeckoSelection()` selects the tab with `editBlankTab = true` (bar goes to Editing). All state changes in `afterClose` are in the same coroutine continuation as `gridWaitingForAfterClose = false`, so Compose batches `gridEngaged = false` with the new-tab setup atomically.

The existing horizontal-drag overlay block is gated on `!gridEngaged` so the grid takes over rendering when active. The commit overlay's bitmap nulling in the handoff path means its render condition is naturally false during/after the handoff. `pillInteractive = !barCollapseCompact && !gridOpen` is passed separately from `tabSwitchingEnabled` because the latter flips false the moment `gridGestureActive` is set — if it gated the pill's `pointerInput`, the gesture would tear down its own listener mid-flight. Child controls use a separate `controlsEnabled = !gridEngaged` flag, threaded through `SafariBar`, `BarExpanded`, and `BarEditing`, so URL taps/text-field focus/buttons are disabled during all-tabs gestures/animations while the pill drag recognizer remains alive to receive release/cancel.

Tapping a cell clears the handoff pins, calls `tabRepository.selectTab` if changed, snaps `offsetX` to 0 (so the tapped tab's carousel slot equals its full-screen rect at progress=0), and triggers `closeGrid`. The lerp lands on that tab.

`BackHandler` for `gridOpen` calls `closeGrid(animateBack = true)`; the existing history-back and `about:blank` handlers are gated on `!gridOpen` so the system back button doesn't double-fire.

## Velocity-keyed tab switch damping (`ui/TangentRoot.kt`)

Both the commit and cancel springs in `onDragEnd` pull their `dampingRatio` from a shared top-level helper `dampingForVelocity(velocity)`. Slow swipes (`|v| < 400 px/s`) get a critically damped settle (1.0), the average band (400–1500 px/s) interpolates linearly from 1.0 down to 0.88 for a barely-perceptible bounce, and fast swipes (`>= 1500 px/s`) snap to 0.82 for a slightly noticeable overshoot. Stiffness stays fixed: `Spring.StiffnessMediumLow` for commit, `Spring.StiffnessMedium` for cancel. The new-tab pop spring is unchanged.

## Per-site preferences & appearance sheet (`state/SitePreferencesStore.kt`, `ui/chrome/AaSheet.kt`)

`SitePreferencesStore` is a `SharedPreferences`-backed store keyed by host (derived via `compactHost`/`hostFromUrl`, which strips `www.`). It exposes `get(host)` / `set(host, prefs)` and a `SharedFlow<String> updates` that emits the host whenever its prefs change. The `aA` button in the bottom bar is wired separately from the URL field — `BarExpanded` takes both `onUrlTap` and `onAaTap`, threaded through `SafariBar` and `BottomBarLayer`. Tapping `aA` opens `AaSheet`, a `ModalBottomSheet` with a `PrimaryTabRow` across the top.

### Page tab

Contains per-site controls. `about:blank` / empty hosts open the sheet but the per-site controls no-op.

- **Zoom** — +/-/reset row (10% steps, clamped 50%–200%).
- **Add top padding for this site** — per-site override switch for the viewport top-padding fix.
- **Request desktop site** — per-host switch persisted in `SitePreferences.desktopMode`. Toggling it
  calls `engineSession.toggleDesktopMode(enabled, reload = true)` for immediate effect. A
  `LaunchedEffect(selectedEngineSession, currentHost, …)` in `TangentRoot` re-applies the host's saved
  preference (`reload = false`) on tab-switch / cross-host navigation / restored tabs, so the desktop
  UA follows the site rather than the session.

For real URLs, the tab also exposes:

- **Share** — fires `Intent.ACTION_SEND` (`text/plain`) wrapped in `createChooser` so the system share sheet appears. Dismisses `AaSheet` on tap.
- **Open in [browser]** — launches `Intent.ACTION_VIEW` targeting the user's stored preferred browser package. Falls back to the system chooser via a bare `ACTION_VIEW` on `ActivityNotFoundException`. The button label resolves the package to a human-readable app name via `PackageManager.getApplicationLabel`; shows "Open in browser" when no preference is set. When the stored preference is `""` (empty string), it means the user explicitly chose "Default browser" and no `setPackage` is applied, so the OS routes to the system default.
- **Change** — opens an `AlertDialog` listing browsers available on the device. The list is built by querying `PackageManager.queryIntentActivities` with an HTTPS probe intent and **`flags=0`** (not `MATCH_DEFAULT_ONLY` — that flag returns only the current system default, so every other installed browser is invisible), then **filtering out Tangent's own package** (`com.firebolt9907.tangentbrowser`) so the app does not appear as its own fallback. Hardcoded well-known entries (Default browser, Chrome, Firefox, Firefox Nightly) are pinned at the top of the list; each is included if its package appears in the query results *or* if `PackageManager.getApplicationInfo` confirms it is installed (the second check covers packages that are installed but don't surface via `queryIntentActivities` on some Android versions). "Default browser" (empty package) is always present. Any remaining dynamically discovered browsers follow in alphabetical order. If the user has picked a browser before, a **Recent** section (up to 5 entries) appears above a divider so prior choices are one tap away. Tapping any entry writes its package name (or `""` for Default) to `GlobalPreferencesStore`, adds it to the recent list, and immediately updates the button label.

### Add-ons tab

Embeds `AddonsManagerSheetContent` (from `ui/addons/AddonsSheet.kt`) inline — no nested `ModalBottomSheet`. Shows the install field, the installed-addon list, and relays the `onOpenAddonSettings` callback back to `TangentRoot` to open an extension's options page in a new tab. `AaSheet` receives `AddonController` directly and collects its state via `collectAsStateWithLifecycle` inside the tab content. The `initialTab: Int` parameter lets `TangentRoot` open the sheet directly to the Add-ons tab if needed.

## Global preferences (`state/GlobalPreferencesStore.kt`)

`GlobalPreferencesStore` holds app-wide (not per-host) user preferences, backed by the `tangent_global_prefs` `SharedPreferences` file. Keys:

- `preferredBrowserPackage` — a nullable string. `null` = no preference set; `""` (empty string) = user explicitly chose "Default browser" (no package constraint on the intent); any other value is a package name.
- `recentBrowserPackages` — a pipe-separated list of up to 5 package strings the user has previously selected from the Change picker (most-recent-first). `""` in the list represents "Default browser". Managed via `recordRecentBrowser(pkg: String)`, which prepends and caps at 5.
- `trackingProtection: Boolean` (default `true`) — Enhanced Tracking Protection; maps to `TrackingProtectionPolicy.recommended()` vs `none()`.
- `blockPopUps: Boolean` (default `true`) — maps to `javaScriptCanOpenWindowsAutomatically = false` in `GeckoEngine.settings`.
- `javaScriptEnabled: Boolean` (default `true`) — maps to `GeckoEngine.settings.javascriptEnabled`.
- `httpsOnly: Boolean` (default `false`) — maps to `Engine.HttpsOnlyMode.ENABLED` vs `DISABLED`.
- `globalPrivacyControl: Boolean` (default `false`) — maps to `GeckoEngine.settings.globalPrivacyControlEnabled`.

No reactive flow is needed because browser-package values are only read when `AaSheet` opens. Engine settings are read at startup (see Engine section). Constructed once in `TangentBrowserComponents` **before** the engine so `buildDefaultSettings` can read stored values at engine construction time.

## Settings sheet (`ui/settings/SettingsSheet.kt`)

`SettingsSheet` is a `ModalBottomSheet` accessed from the **Settings button** in the tab grid (displayed alongside the Synced Tabs button, fading in with the same `gridProgress`-keyed alpha). It presents two sections:

- **Privacy & Security** — Enhanced Tracking Protection, Global Privacy Control, HTTPS-Only Mode.
- **Content** — Block Pop-ups, JavaScript.

Each toggle writes to `GlobalPreferencesStore` and immediately calls `TangentBrowserComponents.applyEngineSettings()`, which mutates `engine.settings` in place so the change takes effect for subsequent navigations without an app restart. The engine settings are also applied at startup via `buildDefaultSettings(globalPreferencesStore)` passed to the `GeckoEngine` constructor, so stored preferences survive process death.

## Per-site top padding & zoom (`ui/content/ContentHost.kt`, `ui/content/PageBottomSpacer.kt`)

`TangentRoot` derives `currentSitePrefs` from the active host through the store, and `sitePrefsVersion` bumps on every `sitePrefsStore.updates` emission (not just the current host) so prefs changes for inactive tabs are reactive too. `ContentHost` accepts `topPaddingFixActive` and `pageZoomFactor`. When `topPaddingFixActive` is true the `EngineView` is wrapped with `Modifier.padding(top = topChromeInsetPx.toDp())` so it does not draw under the status bar, and `injectViewportSpacers` is called with `topPx = 0` and `skipHeaderInsets = true` — the JS still installs bottom spacing but does not add a second document-flow top spacer or run the fixed/sticky header walker. This makes the aA per-site switch a native viewport fallback for sites whose own header/layout scripts fight the injected safe-area edits. `injectPageZoom(factor)` sets `document.documentElement.style.zoom` (or empty string for 1.0) via a `javascript:` URL, called on FCP, load-complete, and through a `LaunchedEffect(pageZoomFactor)` so live changes from the sheet apply without a navigation.

The top padding is bound to *each rendered view*, not just the live engine. A `topPadDpFor(url)` helper in `TangentRoot` resolves any URL's host through the store and returns the inset in dp. Every transition snapshot (`adjacentSnapshot`, `movingCurrentSnapshot`, `incomingSnapshotBitmap`, `leavingSnapshotBitmap`, `stickyIncomingSnapshot`) applies `Modifier.padding(top = pad)` *before* its `graphicsLayer { translationX = … }` so the bitmap occupies the same lower region during a swipe that the live `EngineView` would, with `clip(switchPaneShape)` rounding the corners of that smaller area. The commit-time padding amounts are captured into `leavingSnapshotTopPadPx` / `incomingSnapshotTopPadPx` / `stickyIncomingTopPadPx` so the post-release slide uses the leaving and incoming tabs' actual settings even if the user has since navigated. Without this, tabs with the fix on showed a vertically-stretched or top-aligned bitmap at the start of every drag — the snapshot was captured at `screenH − topChromeInsetPx` but rendered at full screen height.

## Web dark mode (`ui/content/WebDarkMode.kt`, `engine/Engine.kt`, `ui/content/ContentHost.kt`)

"Dark mode for every website" — DarkReader-level coverage at a fraction of DarkReader *dynamic*
mode's cost. DarkReader dynamic parses every stylesheet, computes a dark equivalent for every declared
color, analyzes background-image pixels, and re-runs that on every DOM mutation via a `MutationObserver`
— high quality, but measurable jank and steady CPU/memory. We split the problem into a **native tier**
and a **GPU-composited fallback tier** so most sites cost nothing and the rest cost one filter pass:

- **Tier 1 — native `prefers-color-scheme`.** `applyEngineSettings` sets
  `engine.settings.preferredColorScheme` (MC, runtime-settable, no restart). Only the explicit **On**
  trigger forces `Dark`; **Auto** and **Off** stay `System` — so under Auto, Gecko already flips the
  `prefers-color-scheme: dark` media query whenever the device is dark, and any site with a real dark
  theme renders it authored-quality for free. No injection.
- **Tier 2 — filter-invert fallback (`WebDarkMode.buildApplyScript`).** For pages that stay *light*
  under Tier 1, one class on `<html>` applies `filter: invert(1) hue-rotate(180deg) contrast(0.9)`
  (the `contrast(0.9)` softens pure black/white to DarkReader-comfortable grays). Media
  (`img`/`video`/`canvas`/`iframe`/inline-bg) is re-inverted by a static CSS selector with the
  *inverse* `contrast(1.1111)` so photos net ≈ `contrast(1.0)` and stay crisp. "Light vs dark" is one
  `getComputedStyle` luminance read (`pageIsDark`); a directly-viewed image/video/PDF (`isMediaDoc`,
  via `document.contentType`) is excluded so it isn't turned into a negative. Newly-added nodes need no
  JS — the selector engine keeps them correct.

**Opt-in aggressive sub-mode** (`webDarkModeAggressive`, off by default) closes the one gap the cheap
tier leaves — class-based (non-inline) CSS background images inverting into negatives — far more
cheaply than DarkReader dynamic: a bounded (≤4000-element) `getComputedStyle` walk *tags* elements with
a `url(...)` background (`[data-tangent-dark-bg]`, added to the re-invert selector) plus a rAF-batched
`MutationObserver` that tags only newly-added subtrees. It tags once instead of recomputing every color
on every mutation; gradients are left alone; the observer is disconnected on removal.

**Wiring.** Injected like `SiteTint` — per-load in `ContentHost.injectSpacer` (behind
`suppressJavascriptLoadCallback`) and via a `LaunchedEffect(webDarkModeEnabled, webDarkModeAggressive)`
for live toggles/tab-swaps. The global trigger is `WebDarkModeTrigger {Off, On, Auto}` (migrates the
legacy boolean); `Auto` resolves against `isSystemInDarkTheme()` in the UI layer. A per-site override
`SiteDarkMode {Default, On, Off}` (in `SitePreferences`, aA → Page Settings) resolves over the global
value, so a site that looks wrong inverted can be excluded (or one force-darkened). The effective
boolean feeds `ContentHost`'s existing `webDarkModeEnabled` param at both call sites (`TangentRoot`,
`CustomTabRoot`). Controls: Settings picker (Off/On/Match system) + aggressive sub-toggle, aA quick
toggle, aA Page-Settings per-site override. Pure script builders are JVM-tested (`WebDarkMode*Test`).

**No white flash — the `document_start` pre-paint extension** (`assets/extensions/darkmode/`,
`WebDarkModeExtension.kt`). Tier 2 injects at first-contentful-paint / load-complete — *after* a
light-only page has already painted white — so those pages flashed white before darkening, and a
`javascript:` URL has no earlier hook. A built-in WebExtension content script (`darkmode.js`,
`run_at: document_start`, `<all_urls>`) applies the **same** filter class/id/contrast as Tier 2
*before* the first paint, so a light page's first frame is already dark. It then re-decides on the
first `requestAnimationFrame` (which fires **before** that frame's paint, by which point
render-blocking CSS — hence a site's own dark theme under Tier 1 — has applied) and *removes* the
invert for pages that turn out genuinely dark or are media documents, so those never flash *light*
either; `getComputedStyle` reports real colors regardless of the CSS filter, so `pageIsDark` still
works with the filter on. Reusing the Tier-2 class/id means the native FCP injection takes over
seamlessly (no double-invert); the dynamic tier strips the pre-paint filter first (so a page isn't
both inverted *and* recolored). Installed **once** at startup (`TangentBrowserComponents.init`) and
enabled/disabled via `enable/disableWebExtension` to track the **global** resolved state
(`TangentRoot` `LaunchedEffect(webDarkModeActive)`) — the content script matches every page, so it
must only run while dark mode is globally on; it costs nothing when off. A per-site-Off page (dark
mode globally on) still gets the pre-paint invert, so `injectSpacer` strips it per-load with a
lightweight `removeWebDarkModePrePaint` (gated on `webDarkModeGloballyActive && !webDarkModeEnabled`,
so dark-mode-off users pay no per-load teardown). `WebDarkModeScriptTest` pins the content script to
`document_start` and cross-checks its class/id/contrast against `buildApplyScript` so they can't drift.

## Tried and reverted

- **Walk header descendants and shift abs-positioned `top` by `topPx`** in `PageBottomSpacer.kt` to fix apple.com globalnav children landing under the status bar. Each pass added one `style.setProperty` per abs descendant; the existing `MutationObserver` on `documentElement{attributeFilter:['class','style','slot']}` re-fired on every one of those mutations and re-scheduled `applyHeaderInsets` via RAF, putting the page in a continuous restyle loop. `engineView.captureThumbnail` landed on transitional frames and `TabSnapshotCache` filled with empty/dark bitmaps, so every tab switch showed a uniform dark cover. The current header pass avoids this by editing only selected top-level fixed/sticky header candidates and leaving nested absolute-positioned descendants alone.
- **`LaunchedEffect` calling `engineView.render(session)` at +16 ms / +120 ms after a tab change**, intended to nudge the SurfaceView when Gecko didn't repaint after rebinding. It raced with the existing `captureThumbnail` calls in the same composable — re-binding the session resets the compositor, so captures landed on cleared frames. Same bitmap-corruption symptom as above. If retried, the render-poke must not run anywhere near the capture pipeline.
- **`onPaintStatusReset` → `notifyPainted`** to clear the sticky cover earlier. The callback fires when the surface is *cleared*, not when new content paints, so it cleared the cover prematurely.
- **Sticky cover fallback bumped 700 → 1500 ms.** Made the cover-with-no-snapshot phase visibly long for any tab not yet captured (cold-start NYT/Wikipedia). Kept at 700 ms.
- **Haze top-scrim blur.** Looked correct in mocks but never blurred live page pixels because GeckoView is a `SurfaceView` outside Compose's draw pass. Briefly replaced with a solid translucent surface fill, which read as a flat chrome stripe rather than transparency, so that was also dropped — the status-bar area is now bare. The `dev.chrisbanes.haze` dependency and all `hazeSource`/`hazeEffect` plumbing were removed.
- **Waiting for the in-gesture fresh `captureThumbnail` before drawing the moving tab-switch snapshot.** Avoided the stale-scroll flash from cache-seeded bitmaps but produced a visible black flash on the very first frames of every drag (opaque overlay covered the live engine before the async capture landed). Now the moving layer is cache-seeded again and the fresh capture overwrites it on arrival — black flash beat stale flash on the user-perceptibility scale.
- **`GeckoEngineView.asView().setBackgroundColor()`** to override the grey loading background. Reaches only the Android `FrameLayout` wrapper, not Gecko's compositor. No visible effect. Replaced with a Compose overlay gated on `geckoHasPainted` (see "GeckoView background color" above).
- **Pre-setting the sticky incoming cover immediately on grid-cell tap** (before `closeGrid`) to prevent a flash between animation-end and deferred tab selection. Removed once `gridWaitingForAfterClose` made it redundant — the grid overlay at `progress=0` already covers the engine, and the sticky cover set in `afterClose()` is the correct hand-off point.
- **`closeGrid` with `spring(dampingRatio=0.80, stiffness=120)`** for the `animateBack` path. Too slow (~600 ms) and the slight bounce conflicted with the ease-out feel of a "select and close" action. Replaced with `tween(300ms, CubicBezierEasing(0,0,0.2,1))`.


## Stable Zen Spaces sync (Zen 1.22.1b / Spaces engine v3)

The integration reads the `spaces` collection using the existing FxA account and Sync 1.5
client. The verified upstream sources are
[ZenSpacesSync.sys.mjs](https://github.com/zen-browser/desktop/blob/1.22.1b/src/zen/sync/ZenSpacesSync.sys.mjs)
and [ZenSpacesSyncModel.sys.mjs](https://github.com/zen-browser/desktop/blob/1.22.1b/src/zen/sync/ZenSpacesSyncModel.sys.mjs).
Stable records use `{id, kind, data}`, replacing the Twilight `{id, type, ...fields}` shape.
`ZenStableRecords` handles spaces, GUID-based containers, tabs, folders, splits, and layout records;
the old parser is retained only for collections with no v3 records.

The layout record orders spaces and Essentials; space/folder `children` arrays and split `tabs`
arrays order the pin list. Folder/split trees are flattened for mobile, retaining folder names.
Traversal guards cycles, duplicate references, missing records, and cross-space references; remaining
valid pins still appear when a concurrent desktop update leaves the child list incomplete. Stable
pins use `staticLabel` when supplied, and only HTTP(S) URLs can be opened. Container GUIDs are display
metadata; desktop numeric container IDs are not portable to Android. All Essentials are available in
every remote-space card: Zen's optional container-specific visibility preference is not part of this
collection. This does not recreate desktop cookie containers on Android.

Both Synced Tabs and the overview's Spaces gesture use a near-full-screen space browser with
named space chips and separate Essentials, Pinned, and Tabs sections. When Zen spaces exist, Synced
Tabs hides the Firefox-compatible device list (the Firefox sync engines still run). Account/sharing
controls and Send Tab live in the header menu. Tapping a remote item opens an independent local tab;
remote desktop spaces remain read-only, except for the dedicated phone mirror described below. Zen 1.22.1b adds `pinned: false` records for normal tabs when
`zen.spaces-sync.normal-tabs` is enabled on desktop (Settings → Sync → Sync your Spaces across devices).
These records include their owning `workspaceUuid`, so Tangent shows unpinned tabs from every space,
independently of Firefox's current-space synced-tabs projection. Missing `pinned` defaults to true
for compatibility with 1.22b; Essentials are always pinned. Normal-tab records can remain on the
server after the desktop option is turned off: upstream deliberately holds them rather than sending
tombstones. Tangent displays the server snapshot until a later sync updates/deletes those records. Folder
and split membership is readable, but live-folder providers and split-pane layouts are not recreated.

Refresh uses existing startup/resume, manual, and foreground sync paths. Full collection reads follow
server pagination; refresh failures retain the last successful snapshot and show a retry message in
Synced Tabs. Sign-out clears the persisted snapshot and replaces the credential-caching client;
an account generation guard prevents a late response from restoring signed-out data. Refreshes are
serialized. `ZenStableRecordsTest` covers stable fixtures, ordering, persistence, missing/cyclic
references, deletions, and unsafe URLs; `ZenWorkspacesSyncTest` retains crypto and legacy coverage.

Device verification: sign into the same Mozilla account on Zen and Tangent, enable Spaces in Zen's
sync settings, enable normal-tab syncing and open both pinned and unpinned tabs in multiple spaces (including folder/split members), sync Zen, then open Tangent's Synced
Tabs and tap Sync Now. Verify space order, Essentials, opening pins, desktop changes after refresh,
and signing out/restarting. JVM fixtures cannot establish real-account interoperability by themselves.


### Dedicated phone space

`ZenPhoneSpace` projects the phone's normal HTTP(S) tabs into unpinned v3 records in a per-install
space named `Tangent · <device model>`. Private tabs and Custom Tab sessions are excluded. The
`zenPhoneSpaceEnabled` preference defaults on for signed-in accounts; writes require an existing
version-3 Spaces registration in `meta/global`. Tangent never creates or rewrites that metadata,
`crypto/keys`, or the shared `layout` record. Zen should have normal-tab syncing enabled to receive
these unpinned records. Sharing can be paused in Spaces → menu → Sync settings, leaving the existing
desktop copy in place.

The publisher owns only its random space UUID and namespaced tab record IDs (`tg-<space UUID>-<hash>`).
Tab IDs now persist in `tangent_tabs.json` and are reused on restore (with duplicate-ID protection),
so relaunching does not recreate every desktop tab. Existing files without IDs migrate on their next
save. Different installations have separate namespaces, and Mozilla accounts have separate storage.
Changes update tab URL/title, ordering changes update the space children, and closed phone tabs get
encrypted tombstones. Desktop space names/themes/icons and non-phone children survive updates.
Tabs removed from the phone but moved to another space on desktop are not tombstoned there.
The dedicated phone space reconciles tab additions and explicit deletion tombstones in both
directions. `ZenPhoneMerge` imports desktop-created records as normal unselected Personal tabs and
retains their remote identity, so duplicate URLs stay distinct and retries do not duplicate tabs.
Account UID + phone-space UUID scope a persisted identity/URL checkpoint. A missing linked local
tab is a local close, not a new desktop addition. Desktop tombstones close matching phone tabs
unless their URL changed locally since the last agreed checkpoint (local navigation wins).
Missing records/children are not deletion evidence: desktop publication and collection resets can
temporarily omit records. Other spaces and Essentials never import through this path; private and
non-web tabs are excluded. Desktop URL edits to existing tabs are not pulled by this merge.
Imported records keep their desktop pin/folder/container metadata; moving one out of this space
ends write ownership of that record. Inbound changes are persisted before checkpointing identities;
storage errors fail the sync rather than recording an import that has not been saved. Each upload
still uses the server's conditional-write precondition, and checkpoints advance only after every
write succeeds. The foreground poll, background baseline, and command-receipt paths are unchanged.
Opening a tab manually from the read-only desktop-space viewer still creates an independent tab.

The foreground loop compares the public tab projection every three seconds and makes no requests
when unchanged. A final publish on background is best effort; resume and manual/periodic refresh
retry. Network failures back off, and the transport honors server backoff. Each encrypted PUT has
`X-If-Unmodified-Since` from the last fetched BSO (including tombstones); HTTP 412 aborts the pass
instead of overwriting a concurrent desktop change. Partial uploads are re-read and diffed on retry.
Sign-out/account-generation guards stop further writes to an old account. All normal Firefox sync
triggers and Send Tab command polling remain in place. Desktop visibility depends on Zen's sync
schedule; successful phone upload does not guarantee instant desktop receipt.

`ZenPhoneSpaceTest` checks privacy, stable IDs, update/deletion scope, duplicate URLs, space appearance,
and v3 reader roundtrips. `ZenSpaceTransportTest` exercises synthetic HTTP responses to verify the
real encrypted PUT, conflict precondition, unsupported-version refusal, and backoff behavior.
No test uses real browsing data or account credentials.


### Phone-space favicons

Phone-tab records embed `data:image/png;base64,…` in `data.icon`, as accepted by Zen's
`syncableIconUrl`/`setIcon` path. `ZenPhoneFavicons` prefers the loaded Gecko bitmap, then uses
Tangent's existing `BrowserIcons` loader (ignoring generated placeholders). Encoding runs off the
main thread, preserves aspect ratio within 64×64 pixels, caps PNG data at 24 KiB, and caches by page
URL/bitmap revision. Fallback fetches have four-way concurrency and a five-second total budget;
missing favicons never block tab publication indefinitely. The icon and URL snapshots are captured
together before any network suspension. Icon-only changes trigger the next publisher pass.

An unloaded/restored phone tab retains the existing synced icon only when its URL is unchanged.
Navigation clears that fallback to avoid carrying a previous site's identity onto a different page.
`hasStaticIcon` stays false so Zen can refresh a live tab's favicon normally; these are site favicons,
not user-pinned custom icons. Icon bytes travel inside the same encrypted Sync record as the tab.

### Tab recency and Zen ordering

TangentRoot waits for 15 seconds of continuous foreground viewing before promoting a tab to the
end of BrowserStore's oldest-first list (the top of Tangent's stack). Changing tabs, opening the
overview, or pausing the activity cancels the dwell. Promotion waits until gestures and snapshot
transitions are hidden and only moves the tab; it never selects it again or rebinds the engine.
Background additions and page updates do not promote tabs. The shared store order is persisted and
used by every overview mode and the pill switcher. ZenPhoneSpace reverses the public projection at
publication, putting the most recently viewed tab first in the space children; linked desktop root
tabs participate, while folder placement and unrelated desktop children are preserved.
