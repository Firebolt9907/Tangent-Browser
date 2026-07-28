# Zen Space Color Sync — companion Sine mod

A tiny [Sine](https://github.com/CosmoCreeper/Sine) mod for **Zen Browser** that carries the active
**space accent colour** from the browser chrome into web pages, so in-page UI can match your chrome.

It exists to feed the **Tangent Reader** desktop extension (`extension/`): with this mod installed,
the reader sheet tints its links and paints a thin top edge in your current space's colour. Without
it, the reader looks exactly as before — the mod is purely additive.

## Why a mod is needed

Zen's per-space colour lives **only in the browser chrome** — the `--zen-primary-color` CSS variable
on the chrome root and the `zen.theme.accent-color` preference. It is never exposed to web content or
to the WebExtension `theme` API, and chrome CSS can't cross into content documents. So a **privileged
chrome script** is the only thing that can move the value across the chrome→content boundary.

The official Zen Mods store is **CSS-only** and can't do this. **Sine** runs mod JavaScript in the
chrome context (`Services`, `gBrowser`, …), which is exactly what's required.

## What it does

- Reads the active space's accent (`--zen-primary-color`, falling back to the `zen.theme.accent-color`
  pref).
- Sets it as `--zen-space-color` on each page's `document.documentElement` via a small per-`<browser>`
  frame script.
- Re-pushes on **tab switch**, **page load/navigation**, and whenever Zen rewrites the accent
  (**space switch** / gradient edit — caught with a `MutationObserver` on the chrome root's `style`)
  or the accent pref changes.

The page side is then **pure CSS**: `--zen-space-color` is an inherited custom property, so it flows
through the reader overlay's shadow DOM untouched (`:host { all: initial }` does **not** reset custom
properties). See the `var(--zen-space-color, …)` usages in `extension/src/reader.css`.

```
chrome: --zen-primary-color ──(this mod)──▶ page root: --zen-space-color ──(CSS inherit)──▶ reader shadow DOM
```

## Files

| file | role |
|---|---|
| `theme.json` | Sine manifest — registers the chrome script for `browser.xhtml`. |
| `zen-space-color-bridge.uc.mjs` | the chrome→content bridge. |
| `preferences.json` | one toggle (`extensions.zen-space-color.enabled`, default on). |

## Install

1. Install **Sine** (see its README) in Zen.
2. In Sine's marketplace, add this mod by its GitHub folder, **or** copy this `zen-mod/` folder into
   your Sine mods directory.
3. Enable it. No restart needed. Open any article and toggle the Tangent Reader.

## Caveats

- **Zen-only.** It reads Zen internals (`--zen-primary-color`, `gBrowser`); on other Firefox forks it
  simply does nothing and the reader falls back to its default blue.
- Depends on Zen internals that can shift across Zen versions — the frame-script transport is stable,
  but the accent source (`--zen-primary-color`) is the part most likely to need a bump.
- Uses the classic `messageManager.loadFrameScript` transport (still supported in current Zen); if a
  future Zen drops it, port the bridge to a `JSWindowActor`.
