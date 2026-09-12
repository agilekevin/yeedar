# Upload only from EdenMC

## Problem

Yeedar uploads whatever it sees to YeetVis from whatever server it is on. The
mod is written for EdenMC — the snitch parsing, the group names, the map the
data lands on — but nothing checks. Join a friend's survival server with the
mod installed and it happily reports that server's players, and its terrain,
into the Eden map. Nobody asked for that data and it silently corrupts the map
it lands on.

## Decision

Gate every outbound report on being connected to EdenMC.

**The test is an exact host match on `play.edenmc.world`.** Case-insensitive,
port stripped, a trailing FQDN dot stripped. Everything else — singleplayer,
LAN, realms, another server, a different Eden subdomain — is not Eden.

The narrow rule is deliberate. A suffix match would be more forgiving of Eden
changing subdomains, but the failure it protects against (uploads stop) is
visible and recoverable, while the failure the narrow rule protects against
(uploads go somewhere they should not) is neither.

## What is gated

Anything that reports or acts on world state:

| Path | Where the gate goes |
|---|---|
| Player sightings | `PlayerTracker.tick`, beside `isTrackingEnabled()` |
| Terrain chunks | `TerrainCapture.tick`, beside `isMappingEnabled()` |
| Snitch scans | `YeetVisClient.uploadJalist` |
| Terrain batches | `YeetVisClient.uploadTerrain` |
| Sightings (backstop) | `YeetVisClient.sendPlayerEvent` |
| `/yeedar launch` | `LaunchClient.launch` |

Not gated, because they carry no world data and are wanted from anywhere:
`/yeedar login`, `logout`, `token`, `api`, the friendly-list refresh,
`fetchDefaultGroups`, and the update check.

### Why the tick loops and not just the HTTP layer

Gating only inside `YeetVisClient` would leave `TerrainCapture` sampling
chunks on a non-Eden server, buffering them, handing them to
`TerrainUploader`, and taking a `failed()` on every batch — driving
`UploadBackoff` to its ceiling on work that was never going to be sent. The
sampler has to stop at the source. The HTTP-layer guards stay as backstops so
a future upload path cannot forget the rule.

## What the player sees

One line each time they join a multiplayer server that is not Eden:

> **[Yeedar]** Not uploading from this server.
> Yeedar only reports from play.edenmc.world — no sightings, snitches or
> terrain are being sent from here.

`/yeedar launch` answers too, because the player typed it and a command that
silently does nothing reads as a broken mod:

> **Not on EdenMC.** Yeedar only launches on play.edenmc.world.

Nothing is said per sighting or per upload, and nothing at all in singleplayer
or on a LAN world, where no one is waiting for their world to reach Eden's map.

**Once per join.** The notice is armed by the join event and fires a single
time per connection, so the countdown is the whole of the "once" — no flag
is kept that could fall out of step with the connection it describes. Rejoin
and you are told again, which is the point: it is a fact about where you are,
not an announcement about the session.

It is printed 60 ticks after the join event, reusing the delay the update
notice already needed: sending straight from the join races the server's own
join spam and scrolls away unread.

The upload paths still log a line to the game log per refusal, so behaviour is
diagnosable from `latest.log`.

The cost, accepted knowingly: `/yeedar status` does not report which server it
thinks it is on.

## Structure

New `com.yeedar.net.EdenServer`, three pieces:

- `isEdenAddress(String)` — pure, no Minecraft types, unit tested. All the
  parsing lives here: null, blank, port, case, trailing dot, near-misses.
- `connected()` — the live check. Reads `MinecraftClient.getCurrentServerEntry()`
  and rejects singleplayer, LAN and realms, then defers to `isEdenAddress`.
- `onRemoteServer()` — on any multiplayer server, Eden or not. It separates
  "somewhere uploads could have been expected" from singleplayer and LAN,
  which is the difference between a useful notice and a nag.

New `com.yeedar.net.OffEdenNotice.shouldNotify(onRemoteServer, onEden)` — the
notice rule, also free of Minecraft types, following the shape
`UpdateNotifier` already set: the predicate is pure and the caller owns the
message and the timing.

Splitting the rules from the lookups is the point: they are the parts that can
be wrong in an interesting way, and the parts that can be tested without a
running game.

## Testing

`EdenServerTest` covers `isEdenAddress` against the address forms a real
client produces and the near-misses that must not pass:

- `play.edenmc.world`, with `:25565`, uppercased, trailing dot — all true
- null, empty, blank — false
- `edenmc.world`, `mc.edenmc.world`, `notplay.edenmc.world`,
  `play.edenmc.world.example.com`, `localhost` — all false

`OffEdenNoticeTest` covers the notice rule: a non-Eden server earns it, Eden
does not, and a local world does not even when its stale server entry reads as
Eden.

`connected()` and `onRemoteServer()` need a running client and are not unit
tested.
