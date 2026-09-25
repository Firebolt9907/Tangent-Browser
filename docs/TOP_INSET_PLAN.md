# Top safe-area injection replacement

## Objective

Replace accumulated header-specific repairs with positioning and scroll-container rules. Keep
the page background under the transparent status bar, move readable controls below it, and avoid
moving content twice or changing the site's scrolling behavior. The per-site native viewport
padding fallback remains available.

**Required appearance:** when a header is present, its background color extends upward through
the camera-cutout/status-bar area, while all header text, icons, and controls remain below it.
An empty band painted with the page background is not sufficient. This must hold while the header
is pinned during scrolling as well as at the top of the document.

## Implementation plan

1. **Model layout ownership.** Separate viewport-fixed elements, locally contained fixed elements,
   sticky elements bound to a scrollport, document-positioned controls, and viewport-sized shells.
   Resolve relationships through open shadow roots. DOM ancestry alone does not establish whether
   one element inherits another element's movement.
2. **Apply one correction per positioning chain.** Preserve existing offsets between stacked bars,
   author transforms, and safe space already supplied by a containing scrollport. Distinguish header
   content from decorative media, sidebars, and large overlay backgrounds. Preserve the existing
   flow/shell spacing and strip painting where compatible.
3. **Own edits reversibly.** Track original inline values and priorities separately from Tangent's
   applied values. Retain subsequent author edits. Repeated application and inset changes must not
   compound; disabling must release owned styles and scheduled work.
4. **Use one controller per document.** Update its configuration on reinjection. Observe relevant
   mutations and open shadow roots, coalesce updates, and bound discovery work. Avoid observer
   feedback loops and permanent full-document work on every scroll frame.
5. **Verify behavior independently.** Use the exact Kotlin-emitted JavaScript in deterministic
   fixtures. Assert visible geometry, relative gaps, no unwanted motion, author-style restoration,
   lifecycle behavior, and settled observer activity. Retain existing offline regressions and JVM
   integration contracts.

## Acceptance matrix

| Family | Required examples |
| --- | --- |
| Fixed | border/content box, positive and negative offsets, narrow controls, author transforms |
| Sticky | root scrolling, positive offsets, stacked bars, nested safe/unsafe scrollports |
| Containing blocks | transforms, individual transform properties, containment, will-change |
| Descendants | absolute content layers, nested fixed elements, independent viewport-fixed children |
| Dynamic | late mounting, class/style changes, shadow-root mutation, scroll reveal/hide |
| Lifecycle | repeated injection, increasing/decreasing/zero inset, native fallback toggle |
| Exclusions | already-safe elements, bottom bars, sidebars, full-bleed media, backdrop-only layers |
| Regression | shell bottom reachability, anchor clearance, scroll stability, top/bottom coexistence |
| Appearance | screenshot pixels match header background in cutout band at rest and while scrolled; no header content enters band |

## Scope and evidence

CSS has a small number of positioning primitives, but arbitrary combinations and site scripts
prevent a universal guarantee. Closed shadow roots and cross-origin frame contents are not generally
inspectable. Headless Chrome gives a reproducible geometry regression gate; GeckoView on-device
verification remains necessary for engine-specific layout, mobile viewport behavior, and visual QA.

The CSS positioning model defines fixed containing blocks separately from sticky scrollports:
[CSS Positioned Layout](https://www.w3.org/TR/css-position-3/). Mutation callbacks are asynchronous;
own-write suppression must account for queued records:
[MutationObserver.takeRecords](https://developer.mozilla.org/en-US/docs/Web/API/MutationObserver/takeRecords).

Implementation is split between Terra agents for the production controller, independent behavioral
fixtures, and integration/test review. Results and remaining limitations belong in the harness
documentation and implementation notes after validation.
