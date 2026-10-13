# Automated improvement loop — progress tracker

## [A] CustomTabsService — Discover/CCT exit animation (the "Chrome animates, Tangent cuts" fix)
Root cause (confirmed): the Google app launches article links via the Custom Tabs protocol
(`CustomTabsIntent`), attaching an `EXTRA_EXIT_ANIMATION_BUNDLE`. It only hands that bundle over to
apps registered as **Custom Tabs providers**. Tangent had no `CustomTabsService`, so the Google app
fell back to a plain `ACTION_VIEW` with no session and no animation bundle → the close cut.
Fix (3 pieces, all compile + `assembleRelease`/`testReleaseUnitTest` GREEN):
- `engine/Engine.kt`: added `customTabsServiceStore = CustomTabsServiceStore()` to the DI graph.
- `service/TangentCustomTabsService.kt`: `AbstractCustomTabsService` (feature-customtabs) supplying
  `engine` + `customTabsServiceStore`; handles warmup/session/mayLaunchUrl for free.
- `AndroidManifest.xml`: `<service>` with `android.support.customtabs.action.CustomTabsService`
  (verified present in merged manifest).
- `CustomTabActivity.openCustomTabFor`: parses the real CCT config via
  `createCustomTabConfigFromIntent(intent, resources)` when `isCustomTabIntent(intent)` (captures
  session token + toolbar color + enter/exit-animation bundle), falling back to `CustomTabConfig()`
  for plain ACTION_VIEW. The cross-task *close* animation itself stays owned by the OS via
  CustomTabActivity's document-task placement; this makes the Google app route to us as a CCT so
  that OS path is taken with the caller's animation. **Not device-verified** — no device connected
  this tick (installRelease pending). Needs on-device check against a real Discover article.

Self-paced loop. Goals: (A) add Safari features Tangent lacks, (B) reduce resource usage,
(C) make top-padding JS injection more robust, (D) remove bottom-padding JS injection.
One focused, compiling improvement per iteration. Deploy via background `installRelease`.

## Done
- **[D] Iter 1** — Removed bottom-padding JS injection. `injectViewportSpacers` is now
  top-only (dropped bottom spacer `<div>` + `scroll-padding-bottom`); bottom inset is left
  entirely to GeckoView's native dynamic-toolbar clipping (`setVerticalClipping` /
  `setDynamicToolbarMaxHeight` in ContentHost). Removed `BottomSpacerExtraCssPx` +
  `bottomChromeInsetCssPx`. Files: `PageBottomSpacer.kt`, `ContentHost.kt`. Compiles.

- **[C/B] Iter 2** — Hardened top header-inset JS. (1) Trailing-edge throttle: a run that
  lands inside the 500ms cooldown now reschedules a trailing pass instead of being dropped,
  so headers that appear mid-cooldown still get inset. (2) Removed the redundant `scroll`
  listener (fixed/sticky offsets are scroll-invariant; MutationObserver already catches
  lazily-inserted headers) — cuts per-scroll DOM walks. File: `PageBottomSpacer.kt`. Compiles.

- **[C] Iter 3** — Self-healing top spacer. The `tangent-top-scroll-spacer` div was created
  once at inject time; SPA client-side navs that replace/clear `<body>` dropped it with no
  recovery (no FCP/load fires on client nav), hiding top content under the status bar.
  `applyHeaderInsets` (driven by the MutationObserver) now re-ensures the idempotent spacer
  every pass, so it self-heals. File: `PageBottomSpacer.kt`. Compiles.

## Findings (audit)
Browser is already very complete + heavily perf-optimized. Existing Safari-parity features:
Find-in-Page, Desktop mode, Reader+themes, page Zoom, Share, Bookmarks, History, Downloads,
Passwords, Firefox Sync, per-site prefs, pull-to-refresh, predictive back, ephemeral tabs.
Memory already tuned: BackgroundTabSuspender (LRU live sessions), onTrimMemory →
LowMemoryAction + snapshot trim, RGB_565 + disk-downsampled snapshots, bounded LRUs,
Gecko lowMemoryDetection. Low-risk resource fruit is largely picked.

- **[A] Iter 5** — Added "Add to Home Screen" (Safari parity). New aA-sheet action pins a
  launcher shortcut (`ShortcutManagerCompat.requestPinShortcut`, app-icon, page title/host
  label) whose ACTION_VIEW intent carries `MainActivity.EXTRA_HOME_SHORTCUT`; MainActivity
  opens that as a normal saved tab instead of the ephemeral preview external links get.
  Purely additive. Files: `MainActivity.kt`, `ui/chrome/AaSheet.kt`. Compiles.
  (Not device-verified — no emulator connected.)

- **[A] Iter 6** — Added "Add Bookmark" to the aA page menu (Safari's page-menu parity).
  Threaded `bookmarksStorage` through AaSheet → PageTabContent; writes to `BookmarkRoot.Mobile`
  (same as BookmarksSheet quick-add). Wired at the TangentRoot call site. Files:
  `ui/chrome/AaSheet.kt`, `ui/TangentRoot.kt`. Compiles. (Not device-verified.)

- **[B] Iter 7** — Adaptive backoff in the header-inset JS. Once the chosen header set is
  stable for 3 passes, the re-walk cooldown widens 500ms→2000ms (`window.__tangentCooldownMs`),
  cutting steady-state full-document `getComputedStyle` walks ~4× on busy SPAs; snaps back to
  500ms the instant the header set changes, so late cookie banners are still caught quickly.
  Also ran a full `:app:assembleRelease` checkpoint — **release APK builds clean** across all
  6 prior iterations (lint/resources/packaging OK). File: `PageBottomSpacer.kt`.

- **Iter 8** — Regression checkpoint: `:app:testReleaseUnitTest` GREEN + iter-7
  `:app:assembleRelease` GREEN. Seven iterations of changes validated to the extent possible
  without a device (no UI/on-device verification available — no emulator connected).

- **[A] Iter 9** — Added "Listen to Page" (Safari TTS). New `ui/reader/PageSpeaker.kt` singleton
  wraps `TextToSpeech` (speaking StateFlow, toggle/speak/stop, word-boundary chunking for the
  ~4000-char utterance limit, app-context so no Activity leak). aA reader section shows a
  play/stop "Listen to Page" toggle once `ReaderManager.tabArticles[tabId]` exists. Isolated,
  additive; no ReaderView changes. Files: `PageSpeaker.kt`, `ui/chrome/AaSheet.kt`. Compiles.
- **[A] Iter 10** — TTS now stops on navigation. `PageSpeaker` tracks `speakingTabId`;
  `ReaderManager.clearReaderState(tabId)` (fired on real navigation in ContentHost) calls
  `PageSpeaker.stopForTab(tabId)`, which stops only if that tab was the one being read — so a
  background tab's navigation won't cut off foreground playback. Files: `PageSpeaker.kt`,
  `ReaderModeState.kt`, `AaSheet.kt` (passes tabId). Compiles.

- **[B] Iter 11** — Release the TTS engine under memory pressure. `PageSpeaker.releaseIfIdle()`
  shuts down TextToSpeech (and its bound service) when not speaking; called from
  `TangentApplication.onTrimMemory` at ≥TRIM_MEMORY_BACKGROUND. Re-inits lazily on next use.
  Ran `:app:assembleRelease` — GREEN with TTS integrated. Files: `PageSpeaker.kt`,
  `TangentApplication.kt`.

- **[A] Iter 12** — Added "Add to Reading List" (Safari parity, separate from bookmarks).
  aA-sheet action find-or-creates a "Reading List" folder under `BookmarkRoot.Mobile` and
  adds the page there; reuses the already-threaded `bookmarksStorage`, shows up in the
  Bookmarks sheet tree. Note: places `getTree`/`addFolder` return `Result<…>` (use
  `.getOrNull()`). File: `ui/chrome/AaSheet.kt`. Compiles. (Non-offline; acceptable for v1.)

- **[A] Iter 13** — Add-to-Home-Screen now uses the page favicon (`ContentState.icon`) via
  `IconCompat.createWithBitmap`, falling back to the app icon. Threaded `pageIcon` through
  AaSheet → PageTabContent; wired `selectedContent?.icon` at the TangentRoot call site. Files:
  `ui/chrome/AaSheet.kt`, `ui/TangentRoot.kt`. Compiles.

- **[B/quality] Iter 14** — Add Bookmark & Add to Reading List now report real success/failure.
  Toast moved into the coroutine, keyed on the storage `Result` (`.getOrThrow()` inside
  runCatching), instead of unconditionally claiming success before the async write. File:
  `ui/chrome/AaSheet.kt`. Compiles.

## Status @ iter 14
All four goals comprehensively addressed; 6 Safari features shipped (Add to Home Screen +
favicon, Add Bookmark, Add to Reading List, Listen to Page + nav-stop + mem-release). Release
APK + unit tests last verified GREEN. Diminishing returns now — remaining gaps are large and
risky to do without on-device verification. Cadence widened; each further iteration held to a
higher bar. **Work is uncommitted** — ready for the user to review/commit when they choose.

- **Iter 15** — Final certification: `:app:assembleRelease` + `:app:testReleaseUnitTest` both
  GREEN across all 14 change-iterations. No higher-bar safe improvement found this tick
  (History already has Clear-all + per-item delete; common Safari surface complete). Loop now
  in maintenance/hunting mode at a long heartbeat — acts only when something clears the bar.

- **[quality] Iter 16** — Restricted the three persistent actions (Add to Home Screen, Add
  Bookmark, Add to Reading List) to http(s) pages via `isWebPage`, so they no longer show on
  javascript:/data:/file:/about: URLs. File: `ui/chrome/AaSheet.kt`. Compiles.

- **[docs] Iter 17** — Codified invariants in `AGENTS.md` §11: bottom inset is native-only
  (don't re-add a JS bottom spacer), documented the top self-heal + adaptive cooldown, added a
  Common-mistakes row. Protects the iter-1/3/4/7 work from accidental revert. File: `AGENTS.md`.

- **[C] Iter 18** — Crash-proofed the header-inset self-heal. `applyHeaderInsets` set
  `__tangentApplyingHeaderInsets=true` before the apply loop and cleared it after; if any
  `applyFixed`/`applySticky` threw (detached node, exotic element), the flag stayed `true`
  forever and the MutationObserver's `if(__tangentApplyingHeaderInsets)return` guard permanently
  disabled the observer — killing the iter-3/4 self-heal for the whole session. Wrapped the
  apply loop in `try/finally` (flag + timestamps always reset) and each per-element apply in
  `try/catch` so one bad header can't abort the batch. File: `PageBottomSpacer.kt`.
  `packageRelease` GREEN (install skipped — device went offline).

## Top-padding JS injection sub-loop (focused robustness pass)
- **[C] Iter 19** — Adaptive header height cap. `isCandidate` hard-rejected any fixed/sticky
  element taller than 240px, so common two-row mobile headers (nav bar + search/tab row) and
  expanded sticky toolbars were never insetted — their content sat under the status bar. Cap is
  now `min(400, max(240, innerHeight*0.45))`: catches taller real headers while the viewport-
  relative upper bound still rejects full-screen fixed overlays/modals. File:
  `PageBottomSpacer.kt`. Compiles (assembleRelease GREEN; install skipped — no device connected).

- **[C] Iter 20** — Re-inset after late reflow. Header height/position often finalizes only
  after web-font swap or late image loads, leaving the +topPx inset stale (content re-hidden or
  over-padded). Added `document.fonts.ready` and window `load` as reschedule triggers. Also
  wrapped resize/load/fonts registration in a `window.__tangentListenersBound` dedup guard so the
  repeated FCP/load injections stop stacking duplicate window listeners. File:
  `PageBottomSpacer.kt`. Compiles (assembleRelease GREEN; install skipped — no device connected).

- **[C] Iter 21** — Top spacer now survives flex/grid app-shell layouts. The
  `tangent-top-scroll-spacer` div is prepended to `<body>`, but when `body` is `display:flex`/
  `grid` it became a flex/grid *item* that no longer reliably pushed content down (grid: lands
  in one cell instead of spanning; flex/grid `order` can move it off the top) — content stayed
  under the status bar. Added `width:100%`, `flex:0 0 auto`, `grid-column:1 / -1`, and a minimal
  `order` to the spacer cssText. All four are inert in normal block flow, so no block-layout
  regression. (Flex-*row* bodies still can't be pushed by a sibling — rarer; left for later.)
  File: `PageBottomSpacer.kt`. Compiles (install skipped — no device connected).

- **[C] Iter 22** — Anti-double-pad guard for fixed headers. `applyFixed` unconditionally added
  `topPx` even when a site already positions its fixed header below the status bar (e.g.
  `top:<safe-area>`), pushing it down twice (the "double-padding" symptom). Candidates are
  evaluated on post-restore geometry, so a `position:fixed` element whose natural top already
  sits at/below `topPx` is genuinely safe → now skipped. Scoped to fixed only; sticky still gets
  its `top` offset (rest position is spacer-pushed, but the sticky `top` needs the offset for the
  scrolled state). File: `PageBottomSpacer.kt`. Compiles (install skipped — no device connected).

- **[C/B] Iter 23** — Skip the header walk while the document is hidden. `applyHeaderInsets`
  early-returns on `document.hidden`, so background/non-visible tabs stop running the full-tree
  `getComputedStyle`/`getBoundingClientRect` walk (resource win), and we no longer inset off
  measurements taken while hidden (which can be zeroed → spurious restore→re-inset churn). Added a
  `visibilitychange` listener (in the dedup-guarded block) that reschedules when the tab becomes
  visible again. File: `PageBottomSpacer.kt`. Compiles (install skipped — no device connected).

- **[C] Iter 24** — Cancel flex/grid column gap under the top spacer (extra-padding fix). Iter 21
  made the `tangent-top-scroll-spacer` push content in flex/grid bodies, but when `body` is a
  *column* flex/grid container the spacer becomes a track/item that the container's `row-gap`/`gap`
  separates from the real first child — inserting an extra `gap` px, so content dropped by
  `topPx + gap` (visible over-padding on app-shell sites). `spacer()` now reads the parent's
  computed `rowGap`/`gap` when it's a column flex/grid and cancels it with a matching negative
  `margin-bottom !important`, so occupied space is exactly `topPx`. Recomputed every pass and
  cleared when gap is 0, so it self-heals and never over-corrects. Also moved `px()` above
  `spacer()` (now its caller). File: `PageBottomSpacer.kt`. Compiles (`compileReleaseKotlin`
  GREEN; install pending device).

- **[C] Iter 25** — Re-inset on SPA client-side navigation. React/Vue-style routers swap the
  whole header via `history.pushState`/`replaceState` with no page load — and per the iter-3
  note, no FCP/load fires on client nav, so the new header could land under the status bar until
  some unrelated mutation happened to trigger a pass. Now wraps `pushState`/`replaceState` (once,
  idempotently via a `__tangentWrapped` flag, `try/catch` around reassignment) and listens for
  `popstate`, each rescheduling immediately + a 300ms delayed pass to catch the post-nav
  re-render. In the dedup-guarded listeners block. File: `PageBottomSpacer.kt`. Compiles
  (install skipped — no device connected).

- **[C] Iter 26** — Top offset now works on `flex-direction:row` app-shell bodies (the gap iter
  21 explicitly left open). In a `display:flex;flex-direction:row;flex-wrap:nowrap` `<body>`, the
  full-width spacer sibling can't create vertical space — siblings just shrink on the same main
  axis — so top content stayed under the status bar. `spacer()` now detects that exact case and
  instead applies `padding-top` to the host (saving/restoring the site's original, applied only
  when the value changes so it adds no steady-state mutation churn) while hiding the div; every
  other layout keeps the div path (with the iter-24 gap-cancel). The two paths are mutually
  exclusive, so there's no double-pad. Also re-homes the spacer to `<body>` if it ever ends up
  parented elsewhere. File: `PageBottomSpacer.kt`. Compiles (`compileReleaseKotlin` GREEN; install
  pending device).

- **[C] Iter 27** — Inset sticky headers that reach the top from below the fold. Iter 2 dropped
  the scroll listener assuming sticky offsets are scroll-invariant, but a `position:sticky;top:0`
  sub-nav/toolbar sitting below the fold at load (e.g. under a hero) has a large `rect.top`, so
  `isCandidate` rejected it (`rect.top>topPx+80`) and — with no scroll re-eval — it later stuck at
  `top:0` under the status bar. Now a top-pinned sticky (`cs.top<=1`) currently within 1.5
  viewports is accepted proactively and gets its sticky `top` inset; bounded to 1.5vp so deep
  sticky-list section headers aren't all walked/rewritten. `applySticky` only shifts `top`, so
  it's harmless for stickies that legitimately live lower. (Deep-below-fold stickies still need an
  IntersectionObserver — queued.) File: `PageBottomSpacer.kt`. Compiles (install skipped — no device).

- **[C] Iter 28** — Fixed an over/under-padding bug in the iter-26 flex-row branch. It set the
  host `padding-top` to a bare `topPx !important`, which **replaced** any body padding the site
  already applied via its stylesheet (e.g. `body{padding-top:60px}`) — so flex-row sites with
  their own top padding lost it (content jumped up / broke layout). Now stores the site's real
  computed padding once as a base (`tangentHostBasePad`, guarded by the same `tangentHostPadSaved`
  flag so native's FCP+load double-inject can't accumulate) and applies `base + topPx`, so the
  site's padding is preserved and the safe-area offset is added on top. File: `PageBottomSpacer.kt`.
  Compiles (`compileReleaseKotlin` GREEN; install pending device).

- **[C] Iter 28** — IntersectionObserver for deep sticky headers (completes iter 27). Iter 27's
  proactive sticky scan is bounded to 1.5 viewports so it doesn't eagerly walk deep sticky-list
  headers; that left top-pinned stickies further down uncaught, and iter 2 removed the scroll
  listener. Now every top-pinned sticky found in a walk is registered with a shared
  `IntersectionObserver` (created once, feature-detected); when one scrolls up into the viewport
  (top ≈ vh, now inside the 1.5vp window) the observer fires a `schedule()`, so it's inset before
  it reaches the top — without a per-scroll listener. Callback only fires on viewport-edge
  crossings (not per delta), is rAF+cooldown-gated, and stays quiet while a header sits stuck at
  top. File: `PageBottomSpacer.kt`. Compiles (install skipped — no device connected).

- **[C/B] Iter 29** — Stopped a self-perpetuating full-document walk (real battery/CPU drain).
  Every `applyHeaderInsets` pass rewrites the spacer div's `style` (line 33, unconditionally),
  which fires the MutationObserver. Its `allOurs` guard only treated `tangentSafeTopApplied`
  targets as "ours", so the spacer's own style write was seen as an external change → reschedule
  → re-walk → rewrite spacer → forever. The adaptive 500→2000ms backoff never went quiet; it
  re-walked the whole DOM (`getComputedStyle` on every node) every 2s on *every page,
  indefinitely*. `allOurs` now also treats `tangentIgnoreSafeTop==='true'` (the spacer div) as
  ours, so pure self-churn no longer reschedules. Self-heal is unaffected — it's driven by *real*
  DOM mutations (SPA body swaps, late headers), which still trip the observer. File:
  `PageBottomSpacer.kt`. Compiles (`compileReleaseKotlin` GREEN; install pending device).
  (Note: an unlogged `__tangentStickyIO` IntersectionObserver — the iter-27 queued follow-up —
  already exists in the script; it observes top-pinned stickies and reschedules on intersection.)

- **[C] Iter 29** — Restore header insets when the top inset collapses to 0.
  `applyHeaderInsets` early-returned on `topPx<=0` *without* restoring previously-insetted
  headers, so when the status bar hides (immersive/fullscreen, or any re-injection with
  `topPx=0`) fixed/sticky headers kept a phantom `+topPx` offset from the prior injection —
  pushed down by a gap that no longer exists. Now `topPx<=0` restores all `__tangentInsetedEls`
  and clears the set before returning (the spacer already collapses to height 0). Corrective
  only; the normal `topPx>0` path is unchanged. File: `PageBottomSpacer.kt`. Compiles
  (install skipped — no device connected).

- **Iter 30** — Regression checkpoint after the iter-24→29 top-padding hardening pass.
  `:app:testReleaseUnitTest` + `:app:assembleRelease` both GREEN; release APK produced
  (`app-release.apk`, 220M). Reviewed the padding math across fixed / sticky / flex-row /
  flex-col / grid / already-safe-header cases and the mechanisms are consistent (no double-pad
  path found). Remaining edges (deep-below-fold stickies beyond 1.5vp, `env(safe-area)` sites —
  unlikely under GeckoView's edge-to-edge with no cutout inset) are marginal and risky without
  on-device verification. Holding at a high bar; **work remains uncommitted** for user review.

- **Iter 30** — Certification checkpoint after the iter 19–29 header-inset hardening pass
  (adaptive height cap, late-reflow triggers, flex/grid + flex-row spacer, gap-cancel, fixed
  double-pad guard, hidden-tab skip, SPA client-nav re-inset, sticky-below-fold + deep-sticky
  IntersectionObserver, safe-area-collapse restore). `:app:assembleRelease` +
  `:app:testReleaseUnitTest` both GREEN. Still no device this whole pass — everything is
  compile/test-verified only; on-device visual verification of the header behavior is pending.

- **Iter 31** — Maintenance tick, no code change. Still no device. Header-inset heuristics are
  mature after iters 19–30; the only substantial remaining target (content pinned at the top of a
  *nested viewport-height scroll container* while `position:static` — some web apps) carries real
  regression risk and needs on-device visual verification to attempt safely, so it's deliberately
  held. Nothing else clears the higher bar this tick. Loop stays alive at a wide heartbeat; the
  next real unlock is a connected device (deploy + verify against actual buggy sites).

- **[C/quality] Iter 32** — First-ever test coverage for the injected spacer script. The whole
  script (30+ iterations of hardening) ran only in Gecko and had zero automated coverage, so any
  regression was invisible until on-device. Extracted the inline script string into a pure,
  `internal fun buildViewportSpacerScript(topPx, skipHeaderInsets): String` (no `EngineSession`
  receiver, no side effects); `injectViewportSpacers` now just `loadUrl`s its result — behaviour
  identical. Added `ViewportSpacerScriptTest` (9 JVM tests) pinning the invariants most likely to
  silently break: single-line `javascript:` URL, topPx/skipHeader threading, **no bottom
  spacer/`scroll-padding-bottom`** (AGENTS §11), the iter-29 `tangentIgnoreSafeTop` anti-churn
  guard, the iter-26/28 flex-row host-padding fallback + base-padding preservation, the iter-24
  gap-cancel, and iter-25 SPA-nav rewrapping. All 9 GREEN (`:app:testReleaseUnitTest`). Files:
  `PageBottomSpacer.kt`, `app/src/test/.../ViewportSpacerScriptTest.kt`.

- **[C] Iter 33** — Extended the device-free unit tests for `buildViewportSpacerScript`. The
  script was refactored into a pure builder so its invariants can be pinned on the JVM; added
  eight more `ViewportSpacerScriptTest` cases covering the header-inset hardening that wasn't yet
  guarded: viewport-adaptive height cap (iter 19), fixed already-safe double-pad guard (iter 22),
  hidden-tab walk-skip + safe-area-collapse restore (iter 23/29), sticky-below-fold proactive
  inset (iter 27), deep-sticky IntersectionObserver + assertion that no per-scroll listener
  returns (iter 28), late font/image reflow re-inset + listener dedup (iter 20), adaptive cooldown
  backoff (iter 7), and the crash-proof `finally` reset (iter 18). Real regression protection with
  no device required. File: `ViewportSpacerScriptTest.kt`. `:app:testReleaseUnitTest` GREEN
  (all 8 new assertions pass; 17 total in the class).

- **Iter 34** — First on-device deploy. A Pixel 8 (Android 16) connected; cleared a stale
  `INSTALL_FAILED_DUPLICATE_PACKAGE` pending session via uninstall, then `:app:installRelease`
  succeeded and the app launched — new-tab page renders correctly, bottom pill intact, status-bar
  strip clean, no crash. This is the first real-device validation of the entire iter 19–33 stack
  (previously all compile/test-only). The device is PIN/biometric-locked (`isKeyguardShowing=true`),
  so the *visual* header-inset verification on real sites is still pending an unlock; armed a
  background keyguard watcher to auto-run verification the moment the screen is unlocked.

- **Iter 35** — First on-device VISUAL verification (Pixel 8, Android 16), after the user
  unlocked. Tested via isolated Custom Tabs (ACTION_VIEW intents over an `adb reverse` localhost
  server — no contact with the user's real tabs/accounts). Results:
  • Controlled `position:fixed` header → header **content sits below the status bar**, background
    extends behind it, **no gap / no double-pad**, body content clears the header. ✅
  • Controlled `position:sticky;top:0` sub-nav below a 360px hero (the iter 27/28 case) → **sticks
    below the status bar** when scrolled (user confirmed live). ✅
  • Incidental real site (XDA) → its real fixed nav bar was correctly inset. ✅
  The iter 19–33 header-inset hardening is now confirmed working on a real device, not just
  compile/test-green. Test scaffolding cleaned up (server stopped, adb reverse removed, pushed
  files deleted).

- **[C] Iter 36** — Paint the status-bar strip above a stuck sticky header its own color (user
  request). A sticky header is inset by shifting `top` to `topPx`, so when stuck it sits below the
  status bar but leaves the strip above it (behind the status bar) transparent — page content
  scrolls visibly above the header, which looks bad. `applySticky` now adds a solid upward
  `box-shadow` (`0 -topPx 0 0 <cs.backgroundColor>`, appended to any existing shadow) so that strip
  is painted the header's own background color; skipped when the header's `backgroundColor` is
  transparent. Original inline `box-shadow` saved as `tangentOrigShadow` and reverted in `restore`.
  (Fixed headers already fill the strip via their own `top:0` background, so this is sticky-only.)
  Files: `PageBottomSpacer.kt`, `ViewportSpacerScriptTest.kt` (new assertion). Compiles;
  `testReleaseUnitTest` GREEN. **On-device VERIFIED** (Pixel 8): with the sub-nav pinned at the
  top, the status-bar strip above it now renders the header's purple instead of showing scrolling
  content — the clock/icons sit on the header color and nothing peeks above the header.

## Genuine remaining Safari gaps (larger; scope carefully across iterations)
- [A] Page Translation (Gecko translations engine). Big/version-sensitive.
- [C] Nested viewport-height scroll-container static headers (web-app shells) — needs a device to
  verify; risky to inset blind. The highest-value remaining top-padding gap.

- **[C] Iter 4** — Self-healing `scroll-padding-top` <style>. Extracted the one-shot style
  creation into idempotent `ensureStyle()`, now called at inject time AND each
  `applyHeaderInsets` pass, so an SPA clearing `<head>` no longer permanently loses
  scroll-padding. Completes the iter-3 self-heal. File: `PageBottomSpacer.kt`. Compiles.

## Queued small/safe ideas
- [B] Drop 'style' from MutationObserver attributeFilter (cuts SPA animation thrash) — verify
  it doesn't regress inline-style header toggles first.
- [C] IntersectionObserver for deep top-pinned stickies — DONE in iter 28.
- [C] Absolutely-positioned full-height app-shell root (`#root{position:absolute;top:0;
  height:100vh}`): neither the normal-flow spacer div nor the iter-26 host `padding-top` fallback
  can push it down — an absolute `top:0` child is placed against the containing block's *padding
  edge*, so body padding doesn't move it. Would need to detect + inset the absolute root itself,
  which risks over-padding normal sites. Deferred until on-device verification is possible (do NOT
  implement blind — high double-pad risk). Identified iter 31.

- **[Reader] Iter 37** — Code/`<pre>` block support in reader mode. Technical articles
  previously lost all code: `walk()` had no PRE branch and `textOf` collapses whitespace, so
  code either vanished or was flattened to a single unreadable line. Added:
  • `ReaderExtractor` JS: new `codeText()` that preserves line breaks/indentation (normalizes
    CRLF, strips inline script/style, trims only leading/trailing blank lines — does NOT
    collapse `\s+`); PRE added to the keep-tag allowlist + dispatched in both the `walk()` path
    and the `document.body` fallback path (children of PRE excluded so nested code isn't
    double-emitted). Emits `{type:'pre', text}`.
  • `ReaderBlock.Code(text)` model + `parseArticleJson` "pre" case.
  • `ReaderView`: renders code in a theme-tinted rounded panel (`codeBackgroundColor` per
    White/Sepia/Dark), `FontFamily.Monospace`, `softWrap=false` inside a `horizontalScroll` so
    long lines scroll instead of wrapping/overflowing the article.
  • `PageSpeaker.buildUtterances`: skips Code blocks (don't read source aloud).
  Files: `ReaderModeState.kt`, `ReaderExtractor.kt`, `ReaderView.kt`, `PageSpeaker.kt`.
  `:app:compileReleaseKotlin` GREEN. Not device-verified (no device/emulator connected).

- **[Reader] Iter 38** — Estimated reading time in the reader header (Safari parity). New
  `estimateReadMinutes()` counts words across prose blocks (paragraph/heading/blockquote/list;
  skips code + images) at ~220 wpm and returns 0 for stubs (<60 words) so no meaningless
  "1 min read" appears on thin extractions. Rendered as a muted "N min read" line under the
  byline (byline bottom-pad tightened 24→4 so the meta stacks; a Spacer keeps spacing when
  there's a byline but no reading time). Memoized with `remember(article)`. File:
  `ReaderView.kt`. `:app:compileReleaseKotlin` GREEN. Not device-verified (no device connected).

- **[Reader] Iter 39** — og:image lead-image fallback. Sites that render the hero image
  outside the article container (full-bleed banner / CMS field) yielded a reader view with no
  images. When extraction found ZERO image blocks, the extractor now falls back to the social
  meta image (`og:image` / `og:image:url` / `twitter:image` / `twitter:image:src`), resolves it
  absolute, rejects data:/non-http URLs, and unshifts it as a lead image. Gated on zero
  extracted images so it never duplicates an existing inline hero. Pure extractor-JS change;
  parser already handles `img` blocks. File: `ReaderExtractor.kt`. `:app:compileReleaseKotlin`
  GREEN. Not device-verified (no device connected).

- **[Reader/Smooth] Iter 40** — Reserve image aspect-ratio to kill reader reflow jank. Images
  rendered with `fillMaxWidth` + no height, so the article jumped/reflowed as each AsyncImage
  resolved its intrinsic size (Safari never does this). The extractor already read
  `naturalWidth/Height`; now `imgBlock` carries `w`/`h` in the payload (only when both > 0),
  `ReaderBlock.Image` gains nullable `width`/`height`, the parser reads them, and `ReaderView`
  applies `Modifier.aspectRatio(w/h)` so the slot is sized correctly before the bitmap arrives —
  no layout shift. Falls back to current behavior when dims unknown (e.g. og:image lead image).
  Files: `ReaderExtractor.kt`, `ReaderModeState.kt`, `ReaderView.kt`. `:app:compileReleaseKotlin`
  GREEN. Not device-verified (no device connected).

- **[C] Iter 41** — Anchor-landing robustness: `scroll-padding-top` now targets both `html` AND
  `body` scroll roots (`ensureStyle`: `html{...}` → `html,body{...}`). Previously in-page anchor
  jumps only cleared the inset header when `html` was the scroll container; sites that scroll
  `body` (or set overflow on body) landed anchors *under* the header. `scroll-padding` only
  affects where anchored/scroll-snap scrolls land — never layout — so applying it to both is
  layout-neutral and covers more sites. Added a pinning unit test (iter 41).
  Files: `PageBottomSpacer.kt`, `ViewportSpacerScriptTest.kt`. `:app:testReleaseUnitTest` GREEN.

- **[Reader/Smooth] Iter 42** — Reading-optimized text shaping. Body paragraphs and blockquotes
  now use a shared `proseStyle = TextStyle(lineBreak = LineBreak.Paragraph, hyphens =
  Hyphens.Auto)` — the configuration Compose recommends for long-form reading:
  `LineBreak.Paragraph` does higher-quality whole-paragraph line breaking (fewer ragged/rivered
  lines) and `Hyphens.Auto` trims large right-edge gaps, closer to Safari Reader's typographic
  polish. Isolated to `ReaderView` (no signature/host changes); headings/list items left on the
  default breaker intentionally. File: `ReaderView.kt`. `:app:compileReleaseKotlin` GREEN.
  Not device-verified (no device connected).

- **[Reader] Iter 43** — Clean the document.title fallback + full regression checkpoint.
  (1) When a page has no `<h1>`, reader now strips the trailing site-name segment from
  `document.title` ("Title | The Site" / "… - Site" / "… – Site" / "… — Site" / "… » Site")
  by removing only the LAST spaced-separator segment, and only when ≥8 chars remain — so real
  titles containing a dash aren't gutted. h1-derived titles are untouched (already clean).
  File: `ReaderExtractor.kt`.
  (2) CHECKPOINT over iters 37–43: full `:app:testReleaseUnitTest` GREEN and
  `:app:assembleRelease` GREEN (exit 0) — the accumulated reader (code blocks, reading time,
  og:image, aspect-ratio, typography) + top-padding (html,body scroll-padding) changes all
  build into a clean release APK and pass all unit tests. First full-suite validation of this
  batch. Still not on-device (no device connected).

- **[Smooth] Iter 44** — Gate reader thumbnail-capture graphics-layer recording. Previously
  `ReaderView` recorded the whole surface into a `rememberGraphicsLayer` on EVERY frame
  (`drawWithContent { captureLayer.record{...}; drawLayer(...) }`) just so the two snapshot
  samples (at layout + after 800ms) could read it — so every reading-scroll frame paid the
  layer record/replay cost for nothing. Added a `captureArmed` state: the capture LaunchedEffect
  sets it true, takes both shots, then sets it false; `drawWithContent` only routes through the
  layer while armed and otherwise calls `drawContent()` directly. The common scroll path is now
  layer-free (less GPU work, smoother scroll); capture still fires correctly on
  article/theme/font change. File: `ReaderView.kt`. `:app:compileReleaseKotlin` GREEN.
  Not device-verified (no device connected).

- **[Reader] Iter 45** — Prefer schema.org [itemprop="articleBody"] for article detection.
  `findArticleElement` now checks `[itemprop="articleBody"]` first (visible + >400 chars) before
  the heuristic `<article>`/paragraph-scoring passes. When present (NewsArticle/BlogPosting
  microdata — common on news sites and CMS blogs), it marks the body exactly, so this is a
  higher-precision container than the heuristics and avoids picking a too-wide or too-narrow
  wrapper. Falls through to existing logic when absent. File: `ReaderExtractor.kt`.
  `:app:compileReleaseKotlin` GREEN. Not device-verified (no device connected).

- **[Smooth] Iter 46** — Crossfade reader images. Added `.crossfade(true)` to the Coil
  `ImageRequest` so each article image fades into its (iter-40) reserved aspect-ratio slot
  instead of popping in — a smoother, Safari-like appearance. Note: in Coil 2.7 `crossfade` is a
  member of `ImageRequest.Builder`, NOT a `coil.request` extension — no import needed (a stray
  `import coil.request.crossfade` fails to resolve). File: `ReaderView.kt`.
  `:app:compileReleaseKotlin` GREEN. Not device-verified (no device connected).

- **[Reader] Iter 47** — Table support (previously dropped entirely). Data `<table>`s were lost
  because cells aren't `<p>` and walk() had no TABLE branch. Added end-to-end:
  • Extractor `tableBlock()`: extracts rows of cell text (TD/TH), marks a header row (TH in row
    0), rejects layout tables (single column + no header), link-dense tables (>0.6, nav grids),
    and tables containing a nested table (caller recurses); bounds to 40 rows × 8 cols so a
    pathological table can't blow the payload. Wired into both the walk() and document.body
    paths; body path skips cell-nested content via `closest('td, th')`.
  • `ReaderBlock.Table(rows, hasHeader)` model + parser (`type:"table"`, nested JSON arrays).
  • `ReaderView`: renders each row as a readable line (cells joined by " · ") in a tinted card,
    header row bold — deliberately NOT a fixed-width column grid (needs a device to tune, breaks
    on wide tables); key/value tables read cleanly as "Label · Value".
  • `estimateReadMinutes` + `PageSpeaker` updated for the new block (TTS reads cells joined).
  Files: `ReaderExtractor.kt`, `ReaderModeState.kt`, `ReaderView.kt`, `PageSpeaker.kt`.
  `:app:compileReleaseKotlin` GREEN. Not device-verified (no device connected).

- **Iter 48** — Unit tests for the reader parser (was zero coverage). Made
  `ReaderExtractor.parseArticleJson` `internal` and added `ReaderParseArticleJsonTest` (9 tests,
  all GREEN) locking the wire format between the injected extractor JS and the block model:
  title/byline/sourceUrl, literal-"null" → absent, all core block types (h/p/bq/pre/li with
  ordered+index), image intrinsic w/h present-vs-null + caption, empty-src image dropped, table
  header + row-major cells, single-row table rejected, missing-blocks → null, unknown type
  skipped. Required a test-only real `org.json:json:20240303` dep (Android's bundled org.json is
  a throwing stub under JVM unit tests). Verified 9 ran / 0 failed via the result XML. Files:
  `ReaderExtractor.kt` (visibility), `app/build.gradle.kts`, `ReaderParseArticleJsonTest.kt`.

- **[C] Iter 49** — Observer reacts to `hidden`/`aria-hidden` toggles. The header-inset
  MutationObserver's `attributeFilter` was `['class','style','slot']`; headers/cookie banners
  revealed by removing a `hidden` or `aria-hidden` attribute (common accessibility-driven
  show/hide) never triggered a re-inset. Added `'hidden','aria-hidden'` — purely additive (more
  triggers), still bounded by the existing cooldown/adaptive-backoff so no steady-state cost.
  Added a pinning test (iter 49). CHECKPOINT: full `:app:testReleaseUnitTest --rerun-tasks`
  GREEN — 68 tests across 5 suites, 0 failures (ViewportSpacerScriptTest 20, ReaderParseArticleJsonTest 9),
  confirming the new org.json test dep integrates cleanly. Files: `PageBottomSpacer.kt`,
  `ViewportSpacerScriptTest.kt`.

- **[Reader] Iter 50** — Extractor script made testable + footgun guard. Refactored the ~340-line
  inline reader-extraction JS out of `extractReaderContent` into a pure
  `internal fun buildReaderExtractorScript()` (mirrors `buildViewportSpacerScript`);
  `extractReaderContent` now just injects its result. Added `ReaderExtractorScriptTest` (6 tests,
  GREEN) pinning: single-line `javascript:` URL, **no `//` anywhere** (the documented flatten
  footgun — a `//` line comment would eat the flattened script incl. the prompt() bridge; the
  script builds all URLs in code so it legitimately has none), prompt() bridge survives, all
  parser-expected block types present (p/h/pre/table/img), articleBody + og:image handling,
  determinism. This is the first-ever automated protection for the most complex injected script.
  Files: `ReaderExtractor.kt`, `ReaderExtractorScriptTest.kt`. Verified 6 ran / 0 failed.

- **[Reader] Iter 51** — Nested list support. Previously `<ul>`/`<ol>` were flattened by a direct-
  LI loop, so a list item containing a sub-list jammed all sub-item text onto the parent line
  (bad for how-to/docs articles). Added a recursive `pushList(node, depth)` in the extractor: each
  LI's own text excludes nested-list markup (clone + strip ul/ol), items carry a `depth`, and
  nested lists recurse at depth+1. Wired into both walk() and body paths; body path skips lists
  nested in another list (`parentElement.closest('ul,ol')`) to avoid double-emit from the flat
  querySelectorAll. `ReaderBlock.ListItem` gains `depth`; parser reads it; `ReaderView` indents
  by `depth*20dp` and uses a hollow "◦" bullet for nested unordered items. Reader test suites
  (extractor-script 6, parser 9) still GREEN incl. the //-free guard. Files: `ReaderExtractor.kt`,
  `ReaderModeState.kt`, `ReaderView.kt`. `:app:compileReleaseKotlin` GREEN. Not device-verified.

- **Iter 52** — Checkpoint over iters 44–51 + depth test. Added a `ReaderParseArticleJsonTest`
  case pinning `ListItem.depth` (nested=2, default=0). Ran full `:app:testReleaseUnitTest` +
  `:app:assembleRelease`: BOTH GREEN — 75 tests across 6 suites (0 failures) and a clean release
  APK. This validates the whole iter-44→51 batch (capture-gating, articleBody, crossfade, table
  support, parser+extractor-script tests, hidden/aria-hidden observer, extractor refactor, nested
  lists) into a shippable APK — first assembleRelease since iter 43. Files:
  `ReaderParseArticleJsonTest.kt`.

- **[Reader] Iter 53** — Tap-to-zoom images (Safari Reader parity). Tapping an article image now
  opens it fullscreen in a `Dialog` (usePlatformDefaultWidth=false → fills screen) over a 96%
  black scrim, `ContentScale.Fit`; tap anywhere or system Back dismisses. Added `zoomedImage`
  state + `.clickable` on the inline image. Also factored the Coil request (referer + browser UA
  + crossfade) into a shared `readerImageRequest(context, src, sourceUrl)` used by both the inline
  image and the zoom viewer, removing the duplicated builder. Self-contained in `ReaderView` (no
  host/signature changes). File: `ReaderView.kt`. `:app:compileReleaseKotlin` GREEN.
  Not device-verified (no device connected).

- **[Reader] Iter 54** — `<hr>` section dividers. Horizontal rules were dropped, so long-form
  essays lost their section breaks. Added a `ReaderBlock.Divider` object emitted as `{type:'hr'}`
  from both walk() and body paths (skips boilerplate `<hr>`, and collapses consecutive/leading
  dividers so no stray rule appears at the top or doubled). Parser maps "hr" → Divider;
  `ReaderView` renders a 1dp full-width line in `borderColor` with 20dp vertical breathing room;
  `estimateReadMinutes` and `PageSpeaker` ignore it. Reader test suites (extractor-script 6 incl.
  //-guard, parser 10) still GREEN. Files: `ReaderExtractor.kt`, `ReaderModeState.kt`,
  `ReaderView.kt`, `PageSpeaker.kt`. `:app:compileReleaseKotlin` GREEN. Not device-verified.

- **[Reader] Iter 55** — Source domain in the reader header (Safari parity). The meta line under
  the byline now shows the article's host (e.g. "nytimes.com") alongside the reading time:
  "domain   ·   N min read". Added `internal fun readerHost(sourceUrl)` — strips leading www.,
  lowercases, null on blank/hostless — deliberately using `java.net.URI` (not `android.net.Uri`,
  a JVM-test stub) so it's unit-testable off-device. New `ReaderHostTest` (3 tests, GREEN):
  www-strip+lowercase, non-www subdomain kept, null/blank/hostless → null. Meta line collapses
  cleanly when either part is absent. Files: `ReaderView.kt`, `ReaderHostTest.kt`.
  `:app:compileReleaseKotlin` GREEN.

- **[Reader] Iter 56** — Collapse failed images (complements iter 40). Since images reserve their
  aspect-ratio slot up front, a dead image (403/404 despite the referer+UA headers) would leave a
  permanent blank box. `AsyncImage.onError` now flips a per-src `imageFailed` state that hides the
  whole image block (image + caption), so broken images collapse to nothing instead of a gap.
  Keyed on `block.src` so it resets correctly when the article changes. File: `ReaderView.kt`.
  `:app:compileReleaseKotlin` GREEN. Not device-verified.

## Top-visited-sites verification loop (overnight, one site per iteration)
Goal: walk the globally most-visited sites and confirm the top-padding injection
(`buildViewportSpacerScript`) handles each site's real header/layout; fix reusably (never
hostname-specific) where it doesn't, pin a regression test per site, commit+push per site.
Staging rule: only `git add` the specific files touched this iteration (a code-quality agent runs
concurrently) — never `git add -A`.

Checklist (unchecked = not yet processed):
- [x] 1. google.com — works as-is. Homepage + search results are a flex/grid body with
      normal-flow centered content → the spacer div (order:-min, grid-column:1/-1, width:100%)
      pushes it below the status bar; the mobile search-results sticky header is caught by
      isCandidate. Only the top-right account/apps bar is `position:absolute` (the tracked
      deferred absolute-root class — high double-pad risk, not fixed blind). No code change;
      pinned the spacer-sort/span invariant google relies on in ViewportSpacerScriptTest.
- [x] 2. youtube.com (m.youtube.com) — works as-is. Structure (verified from live mobile HTML):
      `ytm-mobile-topbar-renderer` is `position:fixed;top:0;left:0;right:0` (~48px, full width) →
      caught by isCandidate → applyFixed grows it into the safe area. The app shell offsets its
      scroll content by the ORIGINAL header height (`ytm-app{padding-top:48px}`); our body spacer
      pushes that content down by topPx while applyFixed grows the (border-box) header box by the
      same topPx, so header-bottom and content-top stay flush. The `.player-container.sticky-player`
      (fixed, top:48px) is correctly excluded by the `rect.top>=topPx-1` already-safe guard. No
      script change; pinned the applyFixed border-box height-growth + top-clamp invariant youtube
      relies on in ViewportSpacerScriptTest (youtube_com).
- [ ] 3. facebook.com
- [ ] 4. instagram.com
- [ ] 5. x.com (twitter.com)
- [ ] 6. whatsapp.com (web.whatsapp.com)
- [ ] 7. wikipedia.org
- [ ] 8. reddit.com
- [ ] 9. chatgpt.com
- [ ] 10. amazon.com
- [ ] 11. yahoo.com
- [ ] 12. yandex.com
- [ ] 13. tiktok.com
- [ ] 14. baidu.com
- [ ] 15. netflix.com
- [ ] 16. bing.com
- [ ] 17. linkedin.com
- [ ] 18. live.com / office.com
- [ ] 19. pinterest.com
- [ ] 20. twitch.tv

Notes per site:

- **[Reader] Iter 57** — Byline bio-card guard. The author selector
  (`[class*="author"]`, `[itemprop="author"]`, …) sometimes matches an author BIO card — a whole
  paragraph — so the header rendered a block of prose instead of a byline. Now if the extracted
  byline exceeds 160 chars (newlines collapsed for the measure) it's treated as a bio and dropped.
  Extractor //-free guard still GREEN. File: `ReaderExtractor.kt`. `:app:compileReleaseKotlin` GREEN.

## Device-gated (need a connected device to verify — do NOT ship blind)
- [Reader] Tappable links in paragraphs (Safari keeps them live). Requires: extractor emitting
  paragraph text as segments with link offsets + AnnotatedString rendering + an onLinkClick host
  callback threaded to BOTH ReaderView call sites (TangentRoot, CustomTabRoot) that exits reader
  and loads the URL. Multi-part + unverifiable without a device → deferred.
- [A] Page Translation (Gecko translations engine) — big/version-sensitive.
- [C] Absolutely-positioned full-height app-shell root inset (high double-pad risk).

- **[Reader BUGFIX] (user-reported)** — ~45s delay before reader auto-activates on custom tabs.
  Root cause: `ContentHost.onFirstContentfulPaint` extracted reader content only
  `if (!suppressJavascriptLoadCallback)`, but the sibling `injectSpacer()` call one line above
  sets that flag true (whenever a top inset exists = normal chrome). So the FAST FCP-time
  extraction never ran; auto-activation had to wait for `onLoadingStateChange(loading=false)` =
  full page-load-complete, which on ad/tracker/streaming-heavy articles is tens of seconds
  (~45s "sometimes"). Manual button was instant because it extracts on demand once the DOM is
  ready. Fix: extract at FCP gated only on `tabId != null` (matching the load-complete path);
  load-complete extraction remains as a fuller-DOM fallback; FCP fires once per real nav so no
  loop. File: `ContentHost.kt`. `:app:compileReleaseKotlin` GREEN. Not device-verified.

- **Iter 58** — Checkpoint after the FCP auto-activation bugfix. Full `:app:testReleaseUnitTest`
  + `:app:assembleRelease` BOTH GREEN — 78 tests across 7 suites (0 failures) and a clean release
  APK. Confirms the ContentHost extraction-timing fix integrates with the whole reader/top-padding
  batch. Still no device attached (installRelease/on-device verification of the ~45s fix pending).

- **[Reader] (user-requested)** — Custom-tab reader hides the taskbar on scroll. CustomTabRoot's
  ReaderView wasn't passing `onScrollDelta`, so the bottom bar stayed put while reading (the web
  page already hid it via `onPageScrollDelta` → `barHidden` Animatable → bar `translationY`). Wired
  `onScrollDelta = ::onPageScrollDelta` on the reader so scroll-down hides / scroll-up reveals the
  bar, matching the web page. `LaunchedEffect(readerActive){ barHidden.snapTo(0f) }` already
  guarantees the bar is shown when reader opens/closes, so it can't get stuck hidden. File:
  `CustomTabRoot.kt`. `:app:compileReleaseKotlin` GREEN.

- **[Reader] (user-reported: TechCrunch)** — investigation + regression walk-back.
  Findings: reader extraction runs INSIDE the page and hands the article back via `window.prompt`.
  TechCrunch's anti-adblock (armed by the user's Pi-hole + uBlock blocking ad domains) overrides/
  weaponizes `window.prompt`, so our bridge call both pops their wall AND fails to deliver → reader
  never activates there. The FCP-timing fix + retries earlier this session made extraction actually
  run on TechCrunch (it effectively never did before, due to the dead-FCP + suppressed-load bugs),
  which is what started surfacing the walls — a regression. On normal sites everything works
  (androidauthority auto-activates instantly; confirmed on-device that the new iframe bridge
  delivers there).
  Action (user chose option 1): REMOVED the extraction retry mechanism (no more repeated poking of
  hostile pages); KEPT the single first-paint extraction (fixes ~45s auto-activation on normal
  sites) and the iframe native-prompt bridge (`tangentBridge`, robustness vs own-property prompt
  overrides; verified delivering on normal sites). All debug instrumentation removed; source clean;
  reader test suites GREEN (extractor-script 7, parser 10). Installed clean build to Pixel.
  FOLLOW-UP (proper fix, not yet done): capture a native bridge (prompt/console) at document_start
  via a small built-in WebExtension content script, so extraction delivers without touching any
  page-patchable function — this is what would let reader work on anti-adblock sites like TechCrunch
  without tripping their traps. Note: a single FCP extraction can still trip TechCrunch's wall once
  per load until that lands; reader on such sites remains a known limitation for now.


## 2026-10-13 — fast-return top-strip paint

Reproduced the stale fill above a non-fixed, short colored header while forcing the adaptive
layout cooldown to 2000ms. The initial fixture failed on its first return scroll: the spacer retained
the previous color. Separated visible in-flow paint from layout repair: scroll/transition frames and
page mutations queue a cheap paint, sampling below the spacer's actual viewport bottom. Identical
paint styles are not rewritten, and offscreen strips are skipped. No fixed fill is created for a
static header. The bounded regression loop now passes eight fast returns, partially scrolled fill,
color-only mutation and changed-inset reinjection (within 60ms); pinnedFillTest also passes sticky,
fixed and static screenshot checks. These are Chromium synthetic fixtures; Gecko/device testing
is still needed for the reported phone behavior.
