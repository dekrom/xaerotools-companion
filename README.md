# XaeroTools Companion

[![CI](https://github.com/dekrom/xaerotools-companion/actions/workflows/ci.yml/badge.svg)](https://github.com/dekrom/xaerotools-companion/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/dekrom/xaerotools-companion)](https://github.com/dekrom/xaerotools-companion/releases/latest)
[![License: CC0](https://img.shields.io/badge/license-CC0-lightgrey.svg)](LICENSE)

**Meteor Client addon that feeds a self-hosted
[XaeroTools](https://github.com/dekrom/xaerotools) server.**

- **Position ping** — your position, dimension and heading about once a
  second; everyone watching the server's web map sees a live marker.
- **Map upload** — watches `xaero/world-map/` (and `config/xaero/world-map/`)
  and uploads every freshly-mapped region once its file settles. The server
  keeps a verbatim per-player backup **and** tile-merges everyone's uploads
  into one shared map. `.xt sync` uploads the whole local map once — the
  initial backup; the watcher keeps it current afterwards. Cave layers stay
  local unless you flip `upload-caves` on.
- **Live preview** — a coarse color sketch of the chunks you're currently
  seeing, drawn on the shared map before the game even saves them; the real
  region upload replaces it.

Nothing else changes: the game keeps writing its own local map exactly as
before, the addon only ever reads it.

Everything lives in the **XaeroTools tab** in Meteor's top bar (next to
Config), not in a module: settings persist with the rest of Meteor's config
(`meteor-client/xaerotools.nbt`), and the tab shows live queue/sent status
plus one-click **Sync all maps**.

## Install

You need Fabric with [Meteor Client](https://meteorclient.com/) and Xaero's
World Map installed — XaeroPlus on top works great. Without Xaero's there is
nothing to upload, though the live position marker still works.

**Download ONE jar — match your Minecraft version** — from the
[latest release](https://github.com/dekrom/xaerotools-companion/releases/latest):

| Minecraft | File |
|---|---|
| 1.21.4 | `xaerotools-companion-0.3.0+1.21.4.jar` |
| 1.21.5 | `xaerotools-companion-0.3.0+1.21.5.jar` |
| 1.21.6 | `xaerotools-companion-0.3.0+1.21.6.jar` |
| 1.21.7 | `xaerotools-companion-0.3.0+1.21.7.jar` |
| 1.21.8 | `xaerotools-companion-0.3.0+1.21.8.jar` |
| 1.21.9 or 1.21.10 | `xaerotools-companion-0.3.0+1.21.10.jar` |
| 1.21.11 | `xaerotools-companion-0.3.0+1.21.11.jar` |
| 26.1.x | `xaerotools-companion-0.3.0+26.1.2.jar` |
| 26.2.x | `xaerotools-companion-0.3.0+26.2.jar` |

Drop it into your `mods` folder and start the game (or build it yourself,
below). Works with any XaeroTools release 0.2 or newer.

## Setup

Server on the **same machine** as the game: just `xaerotools serve …`, then
flip **enabled** in the XaeroTools tab (or `.xt on`) — the defaults
(`http://127.0.0.1:45746`, empty `token`) work as-is; loopback clients need
no token.

For a **remote** server:

1. On the server box: `xaerotools serve …`, then generate a token for your
   account name — in the web map's **Share panel**, or with
   `xaerotools tokens generate <YourAccountName>` (token shown once).
2. In the XaeroTools tab: set `server-url`, paste the `token`, flip
   **enabled**. `player-name` stays empty unless the token was generated for
   a different name than the current session. Several accounts sharing one
   config can each get a `NAME=TOKEN` line in `account-tokens` instead.

Either way: `.xt sync` for the first full upload, `.xt status` to watch it
drain, `.xt ping` to test the position path, `.xt on`/`.xt off` to toggle.

The server contract (endpoints, folder rules, rate limits, security notes) is
[`docs/INGEST.md`](https://github.com/dekrom/xaerotools/blob/main/docs/INGEST.md)
in the main repo. Plain HTTP: share beyond a trusted LAN only over a VPN
(Tailscale) or a TLS reverse proxy.

## Build

Multi-version via [Stonecutter](https://stonecutter.kikugie.dev/) — one jar
per Meteor release from 1.21.4 through 26.2 (the 1.21.10 jar also covers
1.21.9, which never got its own Meteor build):

```bash
./gradlew buildAndCollect          # every version → build/libs/<mod version>/
./gradlew :1.21.4:build            # just one version → versions/1.21.4/build/libs/
```

`./gradlew "Set active project to 1.21.4"` switches which version the IDE
sees; `"Reset active project"` returns to 26.2. Gradle fetches a matching JDK
toolchain automatically if you don't have one.

## License

CC0 — based on
[meteor-addon-template](https://github.com/MeteorDevelopment/meteor-addon-template).
