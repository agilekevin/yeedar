# Yeedar

A client-side [Fabric](https://fabricmc.net/) mod for Minecraft that tracks nearby
players on the **EdenMc** server and reports their movements to a companion web
service ([YeetVis](https://yeetvis-api.onrender.com)) for live visualization.

Yeedar runs entirely on your own client — no server-side installation is required.
It watches for players entering and leaving your detection range, marks known
allies as "friendly" using your Namelayer groups, and forwards sightings to the
backend so they can be plotted on a map. It's especially useful for keeping tabs
on who's around when you're out in the field, far from snitch coverage.

> **Note:** Yeedar only observes information your Minecraft client can already
> see (players within render/entity range). It does not give you wallhacks,
> X-ray, or any view your client doesn't normally receive.

## Requirements

| Requirement | Version |
|-------------|---------|
| Minecraft | 1.21.8 |
| Java | 21 or newer |
| [Fabric Loader](https://fabricmc.net/use/installer/) | 0.16.0+ |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.136.1+ |

## Installation

1. Install the [Fabric Loader](https://fabricmc.net/use/installer/) for
   Minecraft 1.21.8.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and drop the `.jar`
   into your `mods` folder.
3. Download the latest `yeedar-x.y.z.jar` from the
   [Releases page](../../releases) (or from Modrinth) and drop it into the same
   `mods` folder.
4. Launch Minecraft with the Fabric profile.

Your `mods` folder is typically:

- **Windows:** `%appdata%\.minecraft\mods`
- **macOS:** `~/Library/Application Support/minecraft/mods`
- **Linux:** `~/.minecraft/mods`

## Getting started

All commands are typed in the in-game chat and start with `/yeedar`.

1. **Point Yeedar at the backend** (a default is already set, so this is only
   needed if you use a custom server):

   ```
   /yeedar api https://yeetvis-api.onrender.com
   ```

2. **Log in with Discord.** This opens your browser to authorize Yeedar and
   automatically stores your access token. Login is restricted to authorized
   members of your group — only they can connect:

   ```
   /yeedar login
   ```

3. **Check that everything is connected:**

   ```
   /yeedar status
   ```

That's it — once logged in, tracking runs automatically while you play.

## Commands

| Command | Description |
|---------|-------------|
| `/yeedar login` | Open Discord login in your browser and connect your account. |
| `/yeedar logout` | Clear your stored token and username. |
| `/yeedar token <token>` | Set your access token manually (instead of `login`). |
| `/yeedar api <url>` | Set the backend API base URL. |
| `/yeedar range <blocks>` | Set the detection range in blocks (1–512, default 128). |
| `/yeedar toggle` | Turn player tracking on or off. |
| `/yeedar status` | Show tracking state, login, API URL, range, and tracked count. |
| `/yeedar list` | List players currently in range and their positions. |
| `/yeedar jalist` | Read the open JukeAlert `/jalist` window and upload snitch timers. |

## Snitch maintenance

Snitches expire — JukeAlert gives each one a dormancy timer (it stops alerting)
and then a cull timer (it is removed). `/jalist` shows those timers, but only
inside a GUI, one page at a time.

Run `/yeedar jalist`. It issues the JukeAlert `/jalist` command itself, then
reads every page of the window that opens — clicking through the pagination
itself — and uploads the timers to YeetVis, where they appear on the
dashboard's **Snitch Maintenance** layer coloured by how soon each snitch
expires.

Leave the window alone while it runs; it drives itself and takes a few seconds
per dozen pages. If jalist is already open, `/yeedar jalist` scans that window
instead of reopening it.

Yeedar does **not** touch a `/jalist` window you opened yourself — nothing
moves unless you ask for a scan.

Only information the window already shows you is read, and only snitches you
have access to are ever uploaded. Nothing is deleted server-side by a scan: a
snitch missing from your jalist may simply be one you cannot see.

## Friendly (ally) tracking

Yeedar can mark players from your [Namelayer](https://www.namelayer.net/) groups
as **friendly** so they're distinguished from strangers on the map. It learns
your allies two ways, automatically:

- **Group member lists** — run a Namelayer list command such as
  `/nllm <group>` and Yeedar captures the member list it prints, syncing those
  names as friendlies. You'll see a `[Yeedar] Synced N members from <group>`
  confirmation.
- **Group chat** — when a group member speaks in group chat
  (`[group] player: message`), Yeedar records that player as a friendly.

This list refreshes periodically while you play.

## Configuration

Settings are saved to `config/yeedar.json` in your Minecraft directory and are
managed through the commands above. You normally won't need to edit the file by
hand, but it looks like this:

```json
{
  "apiBaseUrl": "https://yeetvis-api.onrender.com",
  "token": "",
  "username": "",
  "detectionRange": 128.0,
  "trackingEnabled": true,
  "ignoredNames": ["FreeCam"]
}
```

- **`detectionRange`** — how far (in blocks) to detect other players.
- **`trackingEnabled`** — whether sightings are reported (toggle with
  `/yeedar toggle`).
- **`ignoredNames`** — player names to never track. Useful for ignoring utility
  entities such as `FreeCam`. Matching is case-insensitive.

## License

Yeedar is released under the MIT License.
