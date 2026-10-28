# HytaleHub Plugin

**English** | [Deutsch](README.de.md)

The official server plugin for [Hytale-Hub.de](https://www.hytale-hub.de), the German Hytale server list.
It connects your server to the site: **verified live status**, **vote delivery with in-game rewards**, **account linking** and an optional **server rules screen**.

> Full guides (setup, token, linking players, FAQ): <https://www.hytale-hub.de/docs/plugin>
> Releases: see the [Releases](../../releases) page. Current version: **0.2.3**

---

## Features

| Feature | What it does |
|---|---|
| **Verified live status** | Sends player counts to Hytale-Hub every 30 s. Your server shows as verified and online. |
| **Votifier** | Receives votes from Hytale-Hub and hands out rewards. Three delivery modes: `pull`, `tcp`, `http`. |
| **Rewards** | Item rewards via the `/rewards` container (nothing is lost when the inventory is full) and command rewards (coins, XP, ...). |
| **Account linking** | Players link their Hytale account with their Hytale-Hub account and can vote in-game with `/vote`. |
| **Rules screen** | Optional full-screen rules page on first join (`/rules`, `/commands`). |

## Requirements

- A Hytale server, version 0.5 to 0.7 (declared as `>=0.5.0 <0.8.0` in the manifest). API calls verified against 0.5.6, 0.6.8, 0.7.0 and 0.7.4; run-tested on 0.7.0 and 0.7.4. On a newer server the plugin still loads, the server only logs a version warning.
- Java 25+ (the one your server already uses)
- An API token from your server dashboard on Hytale-Hub.de

## Installation

1. Download `HytaleHub-<version>.jar` from the [Releases](../../releases) page.
2. Put it into your server's `mods/` folder and start the server once. The default config is created.
3. Stop the server, open `mods/HytaleHub_HytaleHubPlugin/config.json` and set `token` to your token.
4. Start the server again.

If anything does not work, check the console for lines starting with `[HytaleHub]` (see [Troubleshooting](#troubleshooting)).

## Configuration

`mods/HytaleHub_HytaleHubPlugin/config.json`

```json
{
  "token": "YOUR_API_TOKEN",
  "features": {
    "heartbeat": true,
    "votifier": true,
    "voteCommand": true,
    "voteReminder": true,
    "reminderIntervalMinutes": 120
  },
  "votifier": { "mode": "pull", "port": 8192 },
  "rewards": {
    "items": [
      { "itemId": "Ore_Gold", "quantity": 3 },
      { "itemId": "Diamond", "quantity": 1 }
    ],
    "commands": ["currency add %player% 100"],
    "description": "3x Gold, 1x Diamond, 100 coins"
  },
  "broadcast": { "enabled": true, "message": "%player% voted and receives: %rewards%" },
  "messages": {
    "voteSuccess": "",
    "voteReminder": "You have not voted today yet! /vote",
    "alreadyVoted": "You already voted today!"
  },
  "debug": false
}
```

| Key | Default | Description |
|---|---|---|
| `token` | - | **Required.** API token from your Hytale-Hub server dashboard. Never share it. |
| `features.heartbeat` | `true` | Send live status. |
| `features.votifier` | `true` | Receive votes. |
| `features.voteCommand` | `true` | Enable `/vote`. |
| `features.voteReminder` | `true` | Remind players who have not voted today. |
| `features.reminderIntervalMinutes` | `120` | Reminder interval (minimum 10). |
| `votifier.mode` | `"pull"` | `pull`, `tcp` or `http` (see below). |
| `votifier.port` | `8192` | Port for `tcp` mode only. |
| `rewards.items` | `[]` | Item rewards, claimed by the player via `/rewards`. |
| `rewards.commands` | `[]` | Console commands run immediately on a vote. |
| `rewards.description` | - | Text for the `%rewards%` placeholder. |
| `broadcast.enabled` | `true` | Announce votes to all players. |
| `broadcast.message` | see above | Announcement text. |
| `messages.*` | see above | Texts shown to players. An empty text sends nothing. |
| `debug` | `false` | Verbose logging (tokens are never logged). |

**Placeholders:** `%player%` (player name, in commands and broadcast), `%from%` (vote source, in commands and broadcast), `%rewards%` (broadcast only).

## Commands

| Command | Description | Who |
|---|---|---|
| `/vote` | Vote (linked accounts) or get the link code to connect your account | everyone |
| `/rewards` | Open the reward container and take your items | everyone |
| `/rules` | Show the server rules | everyone |
| `/commands` | Show the command list | everyone |
| `/testvote <player>` | Simulate a vote and hand out all rewards | admins |
| `/resetrules [player]` | Make players accept the rules again | admins |

## Votifier modes

| Mode | Use when | How it works |
|---|---|---|
| `pull` (default) | Almost always. Works behind firewalls and on rented hosting. | The plugin asks the API for new votes every 15 s. No open port needed. |
| `tcp` | Self-hosted with a free port. | Votifier v2 server: only signed messages with the challenge of the connection (HMAC-SHA256). Limits: 8 KB per message, 5 s per connection, 4 connections per IP. Open the port in your firewall. |
| `http` | The `Nitrado:WebServer` plugin is installed. | Votes arrive at `/HytaleHub/HytaleHubPlugin/vote`. |

## Rewards

- **Item rewards** land in the `/rewards` container. The container is take-only, so players cannot put items in, and nothing is lost when the inventory is full. Unclaimed items survive restarts.
- **Command rewards** run as console commands at once. Votes for offline players are saved and processed at their next join, once the player is in the world.
- Only player names made of letters, digits and underscores are accepted, because they are inserted into console commands.
- Unknown item IDs in `rewards.items` are skipped with a warning in the log instead of handing out an empty item.
- Rewards are handed out **before** chat messages and the broadcast. A failure in a message can never block a reward.

## Troubleshooting

| Symptom | Check |
|---|---|
| Server not shown as verified | `token` correct and without spaces? Log should show `Heartbeat service started`. A `401` means the token is wrong or disabled. |
| Votes do not arrive (`pull`) | `[HytaleHub] [Poll #n] HTTP 401` means wrong token. Set `"debug": true` for details. |
| Votes do not arrive (`tcp`) | Port open and reachable from outside? (`nc -zv your-ip 8192`) |
| No item rewards | `rewards.items` present and item IDs valid? Players claim with `/rewards`. |
| `NoSuchMethodError` in the log | The server API changed. Update to the newest plugin release and open an issue if it persists. |
| Player gets no `/vote` or `/rewards` | Update to 0.2.1. Servers from 0.6 on require a permission for every command; older plugin versions did not set it. |

## Build

You need a Java 25 JDK and the `HytaleServer.jar` of your own server in `libs/` (it is not part of this repository).

```bash
gradle build            # output: build/libs/HytaleHub-<version>.jar
```

Use `gradle`, not `./gradlew`.

### API compatibility check

The Hytale server API changes between versions, and a changed return type breaks a plugin at runtime
(`NoSuchMethodError`) although it still compiles against the old jar. `tools/ApiCheck.java` compares every
server API call in the compiled plugin with a server jar:

```bash
javac -d tools/bin tools/ApiCheck.java
java -cp tools/bin ApiCheck build/classes/java/main /path/to/HytaleServer.jar
```

It prints each call that does not exist with the same signature and exits with code 1. Run it against every
server version you support before a release.

## Contributing

Issues and pull requests are welcome. Please run the API check before you open a PR. If you use an AI coding agent, read [AGENTS.md](AGENTS.md) first.

## Links

- Website: <https://www.hytale-hub.de>
- Plugin documentation: <https://www.hytale-hub.de/docs/plugin>
- Discord: <https://discord.gg/DYQ6Ec57bu>

## Changelog

### 0.2.3
- Fix: TCP mode rejected every vote from hytale-hub.de ("Missing serviceName"). The signed message is now accepted with or without v2 binary framing.
- TCP mode hardened: unsigned formats are rejected, 8 KB message limit, 5 s per connection, 4 connections per IP, bounded queue. Slow or flooding clients can no longer block real votes.
- The placeholder token from the default config is never accepted as a signing key; the plugin warns at startup until a real token is set.
- HTTP mode: request body limited to 8 KB.
- Manifest declares the supported server range `>=0.5.0 <0.8.0`.
- The rules page timer thread is stopped on shutdown (no leftover thread after `/plugin` reload).

### 0.2.2
- Fix: a vote arriving while `/rewards` was open was lost on the next claim.
- Fix: offline votes were processed before the player was in the world; reward commands could miss them.
- Fix: data files are written atomically; a corrupt file is moved aside (`*.corrupt-<time>`) instead of stopping the plugin.
- Fix: pull mode remembers vote IDs and ignores repeated deliveries.
- Fix: "Claim all" could hand out items twice after an error; unknown item IDs are rejected.
- Player names are validated before they go into console commands. Failed reward commands are logged.
- `config.example.json` matches the real config format.

### 0.2.1
- Thanks to **KaGurke** for reporting the errors on newer Hytale servers. That report started the fixes in 0.2.1 to 0.2.3.
- Fix: `NoSuchMethodError` on newer servers in `/vote` broadcast (`Universe.getPlayers`), `/rewards` (`Player.sendMessage`, `ContainerWindow.onClose0`) and the rules kick (`PacketHandler.disconnect`).
- Fix: commands work again for normal players on servers 0.6+ (`requireNoPermission`).
- Rewards are granted before chat and broadcast; every step fails on its own.
- Open source (MIT). New `tools/ApiCheck.java`.

### 0.2.0
- New `/rewards` container UI, item rewards with abuse protection, account linking, vote reminders, simpler config.

### 0.1.0
- First version: verified live heartbeat, Votifier (TCP/HTTP/pull), basic rewards.

## License

[MIT](LICENSE). Hytale-Hub.de is a community project and is not affiliated with Hypixel Studios. Hytale is a trademark of Hypixel Studios.
