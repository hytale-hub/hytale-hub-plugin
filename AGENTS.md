# AGENTS.md

Guidance for AI coding agents (and humans in a hurry) working on this repository.

## What this is

A server plugin for Hytale (Java 25). It connects a game server to <https://www.hytale-hub.de>:
live status heartbeat, vote delivery (Votifier: pull, tcp, http), reward handout, account linking, rules screen.
User documentation: <https://www.hytale-hub.de/docs/plugin> and [README.md](README.md) (German: [README.de.md](README.de.md)).

## Build

```bash
gradle build                   # use `gradle`, not ./gradlew; output: build/libs/HytaleHub-<version>.jar
```

- Needs a Java 25 JDK and the `HytaleServer.jar` of a Hytale server in `libs/` (compile-only, never commit it).
- No tests exist. Verification is the API check below plus a run on a real server.

## The one rule that matters: server API compatibility

The Hytale server API changes between releases. A changed return type or parameter list still compiles against the old
jar but throws `NoSuchMethodError` / `AbstractMethodError` at runtime on the new server, and the plugin silently loses a
feature. Past breaks: `Universe.getPlayers()` (`List` to `Collection`), `Player.sendMessage` removed,
`ContainerWindow.onClose0` got parameters, `PacketHandler.disconnect(String)` became `disconnect(Message)`,
`AbstractCommand.canGeneratePermission()` removed (commands then require a permission nobody has).

Before every change that touches server API, and before every release:

1. Compile against the newest server jar you can get.
2. Run the checker against each supported server jar (at least the oldest and newest):
   ```bash
   javac -d tools/bin tools/ApiCheck.java
   java -cp tools/bin ApiCheck build/classes/java/main /path/to/HytaleServer.jar
   ```
   It must print `0 failing`. Any `MISSING` line is a runtime crash waiting to happen.
3. If possible, start a server with the jar in `mods/` and run `/vote`, `/rewards`, `/rules`, `/testvote <player>` with a player online.

Do not guess server API. Read the class from the server jar (`javap -p -cp HytaleServer.jar <class>`).

## Layout

```
src/main/java/de/hytalehub/
  HytaleHubPlugin.java     entry point, wiring, lifecycle
  command/                 /vote /rewards /rules /commands /testvote /resetrules
  handler/VoteHandler      vote processing: rewards first, then messages and broadcast
  service/                 heartbeat, vote polling, reminders, pending votes/rewards, rules acceptance
  votifier/, http/         TCP Votifier v2 server, HTTP servlet (Nitrado WebServer)
  ui/                      custom UI pages and the reward container window
  config/, model/          config.json / rules_config.json mapping, data classes
src/main/resources/        manifest.json, config.example.json, Common/UI/Custom/*.ui
tools/ApiCheck.java        server API signature checker
```

## Conventions and pitfalls

- A vote must never lose its reward because a message failed. Keep reward steps independent and catch `Throwable`
  (not only `Exception`) around each step, because linkage errors are `Error`s.
- Pending rewards: never write back a list the UI held on to (`replaceRewards`). Remove what was handed out with
  `PendingRewardsService.removeReward`, right after the item is in the inventory. Votes add rewards at any time.
- Data files (`pending_*.json`, `accepted_rules.json`) go through `service/JsonFiles`: atomic write, corrupt file moved
  aside. Services are `synchronized`; poll, TCP, HTTP and world threads all touch them.
- Offline votes are processed on `PlayerReadyEvent` (player is in a world), not on `PlayerConnectEvent`. Ready fires again
  on every world change, so the join flow is guarded once per session.
- Pull mode: vote `id`s are remembered (`markVoteIdSeen`) so a repeated delivery never pays twice.
- Player names go into console commands. Only `[A-Za-z0-9_]{1,32}` is accepted (`VoteHandler.isValidUsername`).
- Item IDs are checked against `Item.getAssetMap()` (`model/RewardItems`); an unknown id would otherwise become an empty item.
- TCP Votifier: hytale-hub.de sends `{"payload","signature"}` as one JSON line without v2 magic. Both that and v2
  binary framing go through `verifyWrapper` (HMAC first, then challenge). Keep the limits in `VotifierServer`
  (8 KB, 5 s deadline, 4 per IP, bounded queue): the website gives a vote 3 s, a blocked worker loses it.
- The placeholder token (`DEIN_API_TOKEN` / `YOUR_API_TOKEN`) is public and must never verify anything:
  use `HytaleHubConfig.isTokenConfigured`.
- `manifest.json` `ServerVersion` is a range (`>=0.5.0 <0.8.0`). Widen it only after ApiCheck and a run on the new
  server version. The server only warns outside the range, it does not refuse to load.
- Never log the API token. Tokens only travel as `Authorization: Bearer`.
- `.ui` files: ASCII only, double quotes only, every selector id must exist in the markup. The client rejects the whole
  page otherwise.
- Commands: do not rely on automatic permission generation. Call `requireNoPermission()` for player commands.
- Plugin id and data folder: `mods/HytaleHub_HytaleHubPlugin/`. Keep file names stable (`config.json`, `pending_votes.json`,
  `pending_rewards.json`), servers depend on them.
- The API base URL and intervals are constants in the services. Endpoints used: `/api/private/status/ingest`,
  `/api/private/votes/pending`, `/api/votes/direct`, `/api/link/game/check`, `/api/link/game/generate`.
- Bump the version in `build.gradle.kts` and `src/main/resources/manifest.json` together and add a changelog entry to
  both README files.

## Out of scope

No Hytale server code, decompiled sources or game assets belong in this repository.
