# Ignore utility-entity names

## Problem

Some Minecraft client mods (notably FreeCam) spawn a phantom player entity named
`FreeCam` when active. The Yeedar proximity tracker treats this entity like any
other nearby player and emits enter/leave events for it, polluting tracking data
with a "player" that is really the local user's own utility.

## Goal

Suppress tracking for a configurable set of utility/phantom entity names,
defaulting to `FreeCam`.

## Design

### Config (`YeedarConfig.java`)

- New field: `private List<String> ignoredNames = new ArrayList<>(List.of("FreeCam"));`
  - Serialized to/from `yeedar.json` alongside the existing fields. Existing
    config files gain the field on next save; users can extend the list by hand.
- New method `boolean isIgnored(String name)`:
  - Case-insensitive exact match against `ignoredNames`
    (mirrors `FriendlyTracker.isFriendly`'s lowercasing).
  - Null-safe: if a hand-edited config sets `ignoredNames` to null (Gson can
    deserialize a missing/null field to null), treat it as empty.
- New getter `getIgnoredNames()` for completeness/consistency.

### Detection loop (`PlayerTracker.java`)

In the proximity loop (currently lines 55–61), after reading the player name:

```java
String name = player.getName().getString();
if (YeedarConfig.getInstance().isIgnored(name)) continue;
currentPlayers.add(name);
```

Because an ignored name never enters `currentPlayers`, it never enters
`trackedPlayers`, so no enter or leave event is ever emitted for it. No
special-casing is needed anywhere else.

### Out of scope

- `NamelayerListener` (passive chat detection) parses real chat lines of the
  form `[group] player: message`. A freecam phantom never produces chat, so it
  cannot reach this path — no change needed.
- No new in-game commands. Editing `yeedar.json` is consistent with how
  `detectionRange` and `apiBaseUrl` are managed today.

## Matching semantics

- Exact match, case-insensitive. Not prefix or substring.

## Testing

- The repository currently has no test sources. Verification is by building
  (`./gradlew build`) to confirm compilation, plus logic walkthrough. A unit
  test for `isIgnored` was considered but deferred (would be the first test in
  the repo) per user direction.
