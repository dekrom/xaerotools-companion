# XaeroTools Companion

[![CI](https://github.com/dekrom/xaerotools-companion/actions/workflows/ci.yml/badge.svg)](https://github.com/dekrom/xaerotools-companion/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/dekrom/xaerotools-companion)](https://github.com/dekrom/xaerotools-companion/releases/latest)
[![License: CC0](https://img.shields.io/badge/license-CC0-lightgrey.svg)](LICENSE)

**Meteor Client addon that feeds a self-hosted
[XaeroTools](https://github.com/dekrom/xaerotools) server.**

- **Position ping** — your position, dimension and heading about once a
  second; everyone watching the server's web map sees a live marker.
- **Map upload** — uploads freshly-mapped regions as you play. It tracks the
  regions around you (and the ones you were near in the last ten minutes, so
  the save Xaero makes behind you still counts) and uploads each one once its
  file stops changing. Cost does not grow with the size of your map: a 300 GB
  archive is tracked with the same few dozen file checks as a small one. The server
  keeps a verbatim per-player backup **and** tile-merges everyone's uploads
  into one shared map. `.xt sync` uploads the whole local map once — the
  initial backup; the watcher keeps it current afterwards. Cave layers stay
  local unless you flip `upload-caves` on.
- **Live preview** — a coarse color sketch of the chunks you're currently
  seeing, drawn on the shared map before the game even saves them; the real
  region upload replaces it.
- **Highlight sync** — if you run XaeroPlus, the chunks it finds (new chunks
  by either detection and their inverses, old/modern chunks, portals, old
  biomes, breadcrumb trails) go up as rows every few seconds, so the shared
  map shows the whole group's finds. On by default, remote servers only —
  see [Setup](#highlight-sync-xaeroplus-only).

Nothing else changes: the game keeps writing its own local map exactly as
before, the addon only ever reads it.

Everything lives in the **XaeroTools tab** in Meteor's top bar (next to
Config), not in a module: settings persist with the rest of Meteor's config
(`meteor-client/xaerotools.nbt`), and the tab shows live queue/sent status
plus one-click **Sync all maps**.

## Install

### 1. The other mods first

All of it has to be for the **same Minecraft version** — mixing versions is
the usual reason the tab never shows up. In this order:

1. **[Fabric Loader](https://fabricmc.net/use/installer/)** for your Minecraft
   version — run the installer, pick the version, install. It is not a jar you
   copy; the installer does it.
2. **[Meteor Client](https://meteorclient.com/)** for that same version — its
   jar goes in the `mods` folder. This addon does nothing without it.
3. **[Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)** for that
   version, in `mods` too — it draws the map that gets uploaded. Without it the
   live position marker still works, but there is nothing to upload.
   [XaeroPlus](https://github.com/rfresh2/XaeroPlus) on top is optional and
   works great — it is what **highlight sync** reads.

Where the `mods` folder lives:

| Launcher | Path |
|---|---|
| Vanilla launcher (Windows) | `%APPDATA%\.minecraft\mods` |
| Vanilla launcher (macOS) | `~/Library/Application Support/minecraft/mods` |
| Vanilla launcher (Linux) | `~/.minecraft/mods` |
| CurseForge / Prism / MultiMC / Modrinth App | that instance's own `mods` folder |

Paste the path into Windows Explorer, or press Cmd+Shift+G in Finder.

### 2. This addon

**Download exactly ONE jar — the one matching your Minecraft version** — from
the [latest release](https://github.com/dekrom/xaerotools-companion/releases/latest).

Every jar there is named `xaerotools-companion-<release>+<Minecraft>.jar`. The
release number is the same for all of them; pick by the part after the `+`:

| Minecraft | The jar ending in |
|---|---|
| 1.21.4 | `+1.21.4.jar` |
| 1.21.5 | `+1.21.5.jar` |
| 1.21.6 | `+1.21.6.jar` |
| 1.21.7 | `+1.21.7.jar` |
| 1.21.8 | `+1.21.8.jar` |
| 1.21.9 or 1.21.10 | `+1.21.10.jar` |
| 1.21.11 | `+1.21.11.jar` |
| 26.1.x | `+26.1.2.jar` |
| 26.2.x | `+26.2.jar` |

Drop it in `mods` next to the others and start the game. That is the whole
install — no build step and no JDK, the release jars are ready to run.
`SHA256SUMS.txt` on the release page verifies your download. Works with any
XaeroTools server release 0.2 or newer. (Or build it yourself — see below.)

Everything lives in the **XaeroTools tab** in Meteor's top bar (next to
Config), not in a module: settings persist with the rest of Meteor's config
(`meteor-client/xaerotools.nbt`), and the tab shows live queue/sent status
plus one-click **Sync all maps**.

## Setup

### The server is on this same PC

Run `xaerotools` normally, then open Meteor's GUI (**Right Shift**), go to the
**XaeroTools** tab and flip **enabled** (or type `.xt on`). The defaults
(`http://127.0.0.1:45746`, empty `token`) already work — **loopback clients
need no token**.

### The server is someone else's PC

1. **The host** starts the server so it accepts connections:

   ```bash
   xaerotools serve --lan --password pick-a-password
   ```

   and mints you a token, on their machine, with your account name:

   ```bash
   xaerotools tokens generate YourAccountName
   ```

   > The web map's **Share panel** can mint tokens too, but only on an
   > unprotected local server — `--lan` deliberately disables token, merge and
   > map-root management in the browser. When sharing, the host uses the
   > command above.

2. **You**, in the XaeroTools tab under **Connection**:

   | Setting | Value |
   |---|---|
   | `server-url` | the host's address, e.g. `http://192.0.2.42:45746` |
   | `token` | the token they sent you |
   | `player-name` | leave empty (uses your account name) — set it only if the token was minted for a different spelling |

   Then flip **enabled**.

   Several accounts sharing one game install can instead put one `NAME=TOKEN`
   line per account into `account-tokens`; the entry matching the logged-in
   account wins.

### Highlight sync (XaeroPlus only)

If XaeroPlus is installed, `highlight-sync` under **Highlights** is **on by
default**. Every few seconds it uploads the chunks XaeroPlus has found — new
chunks by either detection and their inverses, old/modern chunks, portals, old
biomes, breadcrumb trails — to the server in `server-url`, which keeps its own
database of them, so the shared map shows the whole group's finds. Rows travel,
never your databases, and only modules you have enabled produce anything.

**Remote servers only.** A server on this machine already reads those databases
from disk, so it refuses the upload rather than keep a second copy. Turn the
whole thing off with `highlight-sync` in the XaeroTools tab.

### Then, either way

```
.xt sync      # upload the map you already have — the initial backup
.xt status    # connection + queue draining
```

The watcher keeps everything current afterwards.

| Command | Does |
|---|---|
| `.xt on` / `.xt off` | turn the live link on or off |
| `.xt status` | connection, queue length, what has been sent |
| `.xt sync [world]` | full upload, all worlds or just one |
| `.xt ping` | send one position now — tests the connection |

### If it is not working

| Symptom | Fix |
|---|---|
| No XaeroTools tab in Meteor | The jar does not match your Minecraft version, or Meteor itself is missing. Check the game's mod list. |
| `.xt status` not connected | `server-url` needs `http://` and the port. |
| Connection refused | Host is not running, started without `--lan`, or the firewall blocked it (Windows: allow **Private networks**). |
| `401 unauthorized` | Token wrong, revoked, or minted for a different account name. |
| Position works, no terrain | Xaero's World Map missing, or the game has not saved that area yet — run `.xt sync`. |

The server contract (endpoints, folder rules, rate limits, security notes) is
[`docs/INGEST.md`](https://github.com/dekrom/xaerotools/blob/main/docs/INGEST.md)
in the main repo. Plain HTTP: share beyond a trusted LAN only over a VPN
(Tailscale) or a TLS reverse proxy.

## Build

Multi-version via [Stonecutter](https://stonecutter.kikugie.dev/) — one jar
per Meteor release from 1.21.4 through 26.2 (the 1.21.10 jar also covers
1.21.9, which never got its own Meteor build).

You need a JDK 21 or newer on `PATH` (or `JAVA_HOME`) just to start Gradle;
Gradle then downloads the exact compile toolchain each version wants — 26.1
and newer build on Java 25.

```bash
./gradlew buildAndCollect          # every version → build/libs/<mod version>/
./gradlew :1.21.4:build            # just one version → versions/1.21.4/build/libs/
```

On Windows, `gradlew.bat` in place of `./gradlew` (`gradlew.bat buildAndCollect`).

`./gradlew "Set active project to 1.21.4"` switches which version the IDE
sees; `"Reset active project"` returns to 26.2.

## License

CC0 — based on
[meteor-addon-template](https://github.com/MeteorDevelopment/meteor-addon-template).
