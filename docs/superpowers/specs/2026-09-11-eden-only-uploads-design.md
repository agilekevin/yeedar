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

Nothing. No chat line on joining another server, no refusal message when a
gated command does nothing. Uploads simply do not happen.

The gate does log one line to the game log per refusal so the behaviour is
diagnosable from `latest.log` without being visible in game.

The cost, accepted knowingly: a player who runs `/yeedar launch` on another
server gets no feedback at all. `/yeedar status` is unchanged and does not
report which server it thinks it is on.

## Structure

New `com.yeedar.net.EdenServer`, two pieces:

- `isEdenAddress(String)` — pure, no Minecraft types, unit tested. All the
  parsing lives here: null, blank, port, case, trailing dot, near-misses.
- `connected()` — the live check. Reads `MinecraftClient.getCurrentServerEntry()`
  and rejects singleplayer, then defers to `isEdenAddress`.

Splitting them is the point: the matching rule is the part that can be wrong
in an interesting way, and it is the part that can be tested without a running
game.

## Testing

`EdenServerTest` covers `isEdenAddress` against the address forms a real
client produces and the near-misses that must not pass:

- `play.edenmc.world`, with `:25565`, uppercased, trailing dot — all true
- null, empty, blank — false
- `edenmc.world`, `mc.edenmc.world`, `notplay.edenmc.world`,
  `play.edenmc.world.example.com`, `localhost` — all false

`connected()` needs a running client and is not unit tested.
