# `ui/groups/` + `ui/containers/` — Tab groups and containers UI

The two management sheets over the group/container state layer (see the *Tab groups* and *Containers*
sections of [`state.md`](state.md)). Both are hosted from `ui/LuminaOverlaySheets.kt` and opened from
the tab-overview overflow. They look similar but back two very different things:

- a **tab group** is only an organizational *label* over open tabs;
- a **container** is a real, isolated cookie/storage jar (a Firefox contextual identity).

Keep that distinction visible in the UI — they must not read as the same feature.

## `ui/groups/TabGroupsSheet.kt`
The tab-groups manager, over `TabGroupsRepository`. Lists each group as a card
(`GroupCard`) with its member tabs (`TabRow`), an "add current/other tabs to group" affordance
(`AddToGroupButton`), and per-group actions (`GroupOverflow` → rename / recolor / delete via
`GroupEditDialog`). `TabGroupColor.swatch()` lives here — the concrete `Color` per enum entry — so the
state enum stays Compose-free. Empty groups still render (they're user-managed and survive their tabs
closing).

## `ui/containers/ContainersSheet.kt`
The containers manager, over `ContainerRepository`. Lists each container as a row (`ContainerRow`) and
creates new ones (`ContainerCreateDialog`, choosing a Firefox container color + icon).
`ContainerState.Color.swatch()` maps Firefox's container colors to concrete swatches. Because a
container is real isolation, the UI should make its cookie-jar separation legible (a tab opened in a
container carries that container's identity), unlike the label-only groups sheet.

## When to touch this package

- Group card layout / membership actions → `TabGroupsSheet`; the persistence + auto-drop-on-close
  logic is in `TabGroupsRepository` (`state/`), not here.
- Container creation / color+icon choices → `ContainersSheet`; the registry persistence and store
  re-registration are in `ContainerRepository`.
- Both sheets are wired in `ui/LuminaOverlaySheets.kt` and reached from the overflow in
  `ui/GridOverviewControls.kt`.
- Keep each color→swatch mapping in the UI layer so the `state/` enums stay Compose-free.
