# Update notification — design

Yeedar cannot update itself. A Fabric mod is a jar in a folder, and a mod that
downloads and swaps its own jar is a large, fragile feature that also asks
people to trust it with write access to their mods directory. That is not worth
building.

But the situation today is worse than it needs to be. Someone can run 1.2.0 for
months and get no signal at all that 1.6.0 exists, because the only way to find
out is to visit the Releases page, and nobody visits the Releases page. The bug
reports that follow are about problems already fixed.

This adds the smallest thing that helps: tell people a newer version exists, and
link them to it.

## Scope

Notification only. No downloading, no installing, no jar replacement, and no
refusing to run when out of date. The mod says a newer version exists; the
human does the rest.

## Where "latest" comes from

The GitHub Releases API:

```
GET https://api.github.com/repos/agilekevin/yeedar/releases/latest
```

Unauthenticated. `tag_name` gives `v1.7.0` (strip the leading `v`), `html_url`
gives the page to link.

Routing this through the YeetVis API was the alternative and is worse on three
counts. It would not work before login, and YeetVis login is restricted to
authorized members — an un-logged-in user still wants to know their jar is old,
arguably more than anyone. It would need the latest version kept in sync
server-side, a second place to remember on every release and therefore a second
place to get it wrong. And it would couple a mod-local concern to a service
deploy for no gain.

The release flow already pushes a `vX.Y.Z` tag as the last step of shipping, so
this source is correct by construction with nothing new to maintain.

`/releases/latest` excludes prereleases, which is the behavior we want without
asking for it.

### Two API details that will otherwise cost an afternoon

GitHub rejects requests with no `User-Agent` header with a 403. Send one.

Unauthenticated calls are limited to 60/hr per IP. One call per game launch is
nowhere near that, but people behind a shared NAT could collectively approach
it, so a 403 must be treated as "no answer" rather than as an error worth
surfacing.

## Knowing our own version

```java
FabricLoader.getInstance().getModContainer("yeedar")
        .map(c -> c.getMetadata().getVersion().getFriendlyString())
```

`build.gradle` expands `${version}` into `fabric.mod.json` from `mod_version` in
`gradle.properties`, so this is authoritative at runtime. Do not add a version
constant in Java — a second copy is a second thing to forget on release, and the
failure is silent.

## Comparison

Compare numerically, segment by segment, not as strings. `1.10.0` is newer than
`1.9.0` and a lexical compare gets that backwards — which would go unnoticed
until the first double-digit minor.

Notify only when latest is strictly greater. A local dev build ahead of the
newest release stays silent rather than announcing a downgrade.

Anything unparseable on either side means no notification. A malformed tag is
not worth a wrong message.

## When it checks, and when it speaks

**Check** once per game launch, asynchronously on the existing
`java.net.http.HttpClient` in `YeetVisClient`, following the `sendAsync` pattern
already used there. Every failure is silent — no chat, no stack trace. A GitHub
outage must produce exactly nothing. The check is the least important thing the
mod does and must never be the loudest.

**Speak** on `ClientPlayConnectionEvents.JOIN` (fabric-api, already a
dependency). Chat cannot be written during `onInitializeClient`, so the join
event is the first moment there is anywhere to write to.

**Once per version, per install.** `YeedarConfig` gains
`lastNotifiedVersion`; the message is shown only when the newest release differs
from it, and the field is written as soon as it is shown. You hear about 1.7.0
exactly once, ever.

The reasoning is that this is a Civ server and people relog constantly. A
message that reappears every session is one people train themselves not to read,
which fails at the only job it has. `/yeedar status` covers anyone who saw it
and forgot.

The message links the release page via `ClickEvent.OPEN_URL`. If you are going
to tell someone to update, making them go find the page is a small rudeness.

The check is asynchronous and the first join can beat it, especially on a slow
connection. So the result is held for the session and the join hook reads
whatever is known at the time: if nothing has resolved yet, that join is silent
and the next one picks it up. Holding the join to wait on a network call would
be the wrong trade for a message this unimportant.

## Configuration

Two new fields on `YeedarConfig`, which is a Gson-serialized POJO at
`<configdir>/yeedar.json`:

- `updateCheckEnabled` — default `true`. Turns the check off entirely, network
  call included.
- `lastNotifiedVersion` — default `""`.

Gson leaves field initializers alone for keys missing from the JSON, so existing
configs pick up the defaults with no migration.

## `/yeedar status`

Status currently does not show the mod version at all, which is its own small
gap — it is the first thing anyone asks for in a bug report. Add a line:

```
Version: 1.6.0
Version: 1.6.0 (1.7.0 available)
```

The suffix appears only when a newer release is known from this session's check.
This is the on-demand surface, so the join message never has to be the only
chance to see it.

## Units

1. **`VersionCompare`** — pure. Parse and compare two version strings, return
   whether the second is strictly newer. No network, no Minecraft, no config.
2. **`UpdateChecker`** — fetch, parse `tag_name`/`html_url`, hold the result for
   the session. One network call, all failures swallowed.
3. **The join hook** — decide whether to speak, format the message, write
   `lastNotifiedVersion`.

## Testing

`VersionCompare` is where the bugs live and it is pure, so it carries the tests:

- `1.7.0` newer than `1.6.0`.
- `1.10.0` newer than `1.9.0` — the lexical trap.
- `2.0.0` newer than `1.99.99`.
- Equal versions are not newer.
- Older is not newer, so a dev build ahead of the release stays quiet.
- A `v` prefix on either side is tolerated.
- Unparseable input on either side yields "not newer" rather than throwing.
- Differing segment counts (`1.6` vs `1.6.0`) compare as equal.

For `UpdateChecker`, cover that a non-200, malformed JSON, and a thrown
exception all resolve to "no update known" rather than propagating.

## Out of scope

**Auto-update.** Stated above; it is the reason this document is short.

**Minimum-supported-version enforcement** — the server refusing old clients, or
warning that a feature needs a newer jar. That is a real idea and a different
one: it needs a YeetVis endpoint, a policy about what "unsupported" does, and a
plan for people who cannot update immediately. Not smuggled in here.
