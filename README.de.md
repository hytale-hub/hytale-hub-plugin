# HytaleHub Plugin

[English](README.md) | **Deutsch**

Offizielles **Hytale-Hub.de** Integrationsplugin für Hytale Server.

> Ausführliche Anleitungen (Einrichtung, Token, Spieler verknüpfen, FAQ): <https://www.hytale-hub.de/docs/plugin>
> Downloads: [Releases](../../releases)

## Features

### 1. Verified Live (Heartbeat)
Sendet alle 30 Sekunden Server-Status-Updates an die Hytale-Hub API.
- Zeigt deinen Server als "Online" auf der Website
- Aktualisiert Spielerzahlen in Echtzeit
- HMAC-SHA256 signierte Anfragen

### 2. Votifier (Vote-Empfang)
Empfängt Votes von Hytale-Hub und vergibt Belohnungen.

**Drei Modi verfügbar:**
| Modus | Beschreibung | Empfohlen für |
|-------|-------------|----------------|
| `pull` | Plugin fragt API alle 15s nach Votes | Shared Hosting, Firewall-Umgebungen (Standard) |
| `tcp` | Klassischer Votifier v2 TCP Server | Self-hosted mit offenen Ports |
| `http` | HTTP Endpoint via Nitrado:WebServer | Server mit WebServer Plugin |

### 3. Reward-System

**Zwei Arten von Belohnungen:**

| Typ | Beschreibung | Sicherheit |
|-----|-------------|------------|
| **Item-Rewards** | Items im `/rewards` Container-UI | Sicher - kein Verlust bei vollem Inventar |
| **Command-Rewards** | Server-Commands (Coins, XP, etc.) | Direkte Ausführung |

### 4. Account-Linking
Spieler können ihren Hytale-Account mit der Website verknüpfen für Direct-Voting via `/vote`.

---

## Installation

### Voraussetzungen
- Hytale Server 0.5 bis 0.7 (im Manifest als `>=0.5.0 <0.8.0` angegeben; API-Aufrufe geprüft gegen 0.5.6, 0.6.8, 0.7.0 und 0.7.4, Laufzeittest auf 0.7.0 und 0.7.4). Auf neueren Servern lädt das Plugin trotzdem, der Server schreibt nur eine Versionswarnung ins Log.
- Java 25+

### Schnellstart
1. `HytaleHub-0.2.3.jar` in den `mods/` Ordner kopieren
2. Server starten (erzeugt Standard-Konfiguration)
3. Server stoppen
4. `mods/HytaleHub_HytaleHubPlugin/config.json` bearbeiten
5. `token` mit deinem API-Token von Hytale-Hub ersetzen
6. Server neu starten

---

## Konfiguration

**Pfad:** `mods/HytaleHub_HytaleHubPlugin/config.json`

```json
{
  "token": "DEIN_API_TOKEN",
  "features": {
    "heartbeat": true,
    "votifier": true,
    "voteCommand": true,
    "voteReminder": true,
    "reminderIntervalMinutes": 120
  },
  "votifier": {
    "mode": "pull",
    "port": 8192
  },
  "rewards": {
    "items": [
      { "itemId": "Ore_Gold", "quantity": 3 },
      { "itemId": "Diamond", "quantity": 1 }
    ],
    "commands": [
      "currency add %player% 100"
    ],
    "description": "3x Gold, 1x Diamant, 100 Coins"
  },
  "broadcast": {
    "enabled": true,
    "message": "%player% hat gevotet und erhaelt: %rewards%"
  },
  "messages": {
    "voteSuccess": "",
    "voteReminder": "Du hast heute noch nicht gevotet! /vote",
    "alreadyVoted": "Du hast heute bereits gevotet!"
  },
  "debug": false
}
```

### Konfigurations-Referenz

#### `token` (erforderlich)
Dein API-Token von Hytale-Hub.de. Findest du im Server-Dashboard.

#### `features`
| Option | Typ | Standard | Beschreibung |
|--------|-----|----------|-------------|
| `heartbeat` | boolean | `true` | Verified Live Status aktivieren |
| `votifier` | boolean | `true` | Vote-Empfang aktivieren |
| `voteCommand` | boolean | `true` | `/vote` Command aktivieren |
| `voteReminder` | boolean | `true` | Vote-Erinnerungen aktivieren |
| `reminderIntervalMinutes` | int | `120` | Erinnerungs-Intervall (min. 10 Min.) |

#### `votifier`
| Option | Typ | Standard | Beschreibung |
|--------|-----|----------|-------------|
| `mode` | string | `"pull"` | `pull`, `tcp`, oder `http` |
| `port` | int | `8192` | TCP Port (nur für `tcp` Modus) |

#### `rewards`
| Option | Typ | Beschreibung |
|--------|-----|-------------|
| `items` | Array | Item-Rewards (werden in `/rewards` Container gelegt) |
| `commands` | Array | Command-Rewards (direkt ausgeführt) |
| `description` | string | Beschreibung für Broadcast |

**Item-Reward Format:**
```json
{ "itemId": "Diamond", "quantity": 5 }
```

#### `broadcast`
| Option | Typ | Standard | Beschreibung |
|--------|-----|----------|-------------|
| `enabled` | boolean | `true` | Server-weite Ankündigung |
| `message` | string | siehe oben | Broadcast-Nachricht |

#### `messages`
| Option | Beschreibung |
|--------|-------------|
| `voteSuccess` | Nachricht nach erfolgreichem Vote (leer = keine) |
| `voteReminder` | Erinnerungs-Nachricht |
| `alreadyVoted` | Nachricht wenn bereits gevotet |

### Platzhalter

| Platzhalter | Verwendbar in | Beschreibung |
|-------------|---------------|-------------|
| `%player%` | commands, broadcast.message | Spielername |
| `%from%` | commands, broadcast.message | Service-Name (z.B. "HytaleHub") |
| `%rewards%` | broadcast.message | Reward-Beschreibung aus `rewards.description` |

### Nachrichtenfarben

Das Plugin verwendet folgende Farbcodes (Hex):

| Farbe | Hex | Verwendung |
|-------|-----|------------|
| Lila | `#a855f7` | Prefix `[Hytale-Hub]` |
| Grün | `#22c55e` | Erfolgs-Nachrichten |
| Gelb | `#fbbf24` | Warnungen, Erinnerungen |
| Blau | `#58a6ff` | Hervorgehobene Commands |
| Rot | `#ef4444` | Fehler |
| Grau | `#888888` | Info-Nachrichten |

---

## Commands

| Command | Beschreibung | Wer |
|---------|-------------|-----|
| `/vote` | Voten (verknüpfter Account) bzw. Verknüpfungs-Code erhalten | alle |
| `/rewards` | Item-Rewards Container öffnen | alle |
| `/rules` | Serverregeln anzeigen | alle |
| `/commands` | Befehlsliste anzeigen | alle |
| `/testvote <player>` | Vote simulieren, alle Rewards vergeben | Admins |
| `/resetrules [player]` | Spieler müssen die Regeln erneut akzeptieren | Admins |

### /vote Flow
1. Prüft ob Spieler mit Website verknüpft ist
2. **Nicht verknüpft:** Zeigt Link zum Verknüpfen
3. **Verknüpft:** Direkter Vote + sofortige Rewards

### /rewards Flow
1. Öffnet Container-UI (wie Chest)
2. Spieler zieht Items ins Inventar (Drag & Drop)
3. Nicht abgeholte Items bleiben gespeichert
4. Read-Only: Spieler kann keine eigenen Items hineinlegen

### /testvote Flow
1. Admin führt `/testvote Spielername` aus
2. Simuliert einen echten Vote von "HytaleHub"
3. Alle Rewards werden vergeben (Items + Commands)
4. Broadcast wird gesendet (wenn aktiviert)

---

## Automatische Events

### Bei Spieler-Join
Wenn ein Spieler dem Server beitritt:

1. **Pending Votes verarbeiten**
   - Votes die empfangen wurden während der Spieler offline war
   - Commands werden ausgeführt, Item-Rewards zu `/rewards` hinzugefügt

2. **Pending Rewards Benachrichtigung**
   - Spieler wird informiert wenn er Items zum Abholen hat
   - Zeigt Anzahl der ausstehenden Rewards

3. **Vote-Reminder starten**
   - Wenn Spieler heute noch nicht gevotet hat
   - Erste Erinnerung: 60 Sekunden nach Join
   - Weitere Erinnerungen: Alle X Minuten (konfigurierbar)

### Vote-Reminder System

**Aktivierung:** `features.voteReminder: true`

**Timing:**
| Event | Zeit |
|-------|------|
| Erste Erinnerung | 60 Sekunden nach Join |
| Weitere Erinnerungen | Alle `reminderIntervalMinutes` (Standard: 120 Min) |

**Stopp-Bedingungen:**
- Spieler hat gevotet
- Spieler hat den Server verlassen
- Neuer Kalendertag (Reset)

**Minimum-Intervall:** 10 Minuten (Spam-Schutz)

---

## Votifier Modi

### Pull-Modus (Empfohlen)
**Funktioniert überall ohne Port-Konfiguration.**

```json
"votifier": {
  "mode": "pull"
}
```

- Plugin fragt alle 15 Sekunden die API nach neuen Votes
- Keine Firewall-Konfiguration nötig
- Ideal für Shared Hosting

### TCP-Modus
**Für selbst-gehostete Server mit offenen Ports.**

```json
"votifier": {
  "mode": "tcp",
  "port": 8192
}
```

**Protokoll:** Votifier v2 (HMAC-basiert)
1. Server sendet: `VOTIFIER 2 <challenge>`
2. Client sendet: `{"payload": "<json>", "signature": "<base64>"}` (als JSON-Zeile oder mit v2-Binärrahmen). Die Payload muss die Challenge dieser Verbindung enthalten.
3. Server antwortet: `{"status":"ok"}` oder `{"status":"error","cause":"..."}`

**Sicherheits-Features:**
- Challenge-Token pro Verbindung (Replay-Schutz)
- HMAC-SHA256 Signatur-Validierung
- Constant-Time Signatur-Vergleich
- Nur signierte Nachrichten; unsignierte Formate werden abgelehnt
- Höchstens 8 KB pro Nachricht, 5 Sekunden pro Verbindung, 4 gleichzeitige Verbindungen pro IP
- Der Platzhalter-Token aus der Standard-Config wird nie als Schlüssel akzeptiert

Firewall-Regeln:
```bash
# Linux (ufw)
sudo ufw allow 8192/tcp

# Linux (iptables)
sudo iptables -A INPUT -p tcp --dport 8192 -j ACCEPT

# Windows PowerShell (Admin)
New-NetFirewallRule -DisplayName "HytaleHub Votifier" -Direction Inbound -Protocol TCP -LocalPort 8192 -Action Allow
```

### HTTP-Modus
**Für Server mit Nitrado:WebServer Plugin.**

```json
"votifier": {
  "mode": "http"
}
```

- Erfordert installiertes `Nitrado:WebServer` Plugin
- Endpoint: `/HytaleHub/HytaleHubPlugin/vote`

---

## Reward-System Details

### Item-Rewards (Sicher)

Items werden im `/rewards` Container gespeichert:
- **Kein Verlust** bei vollem Inventar
- Spieler holt Items ab wann er will
- Persistent über Server-Neustarts

**Anti-Abuse Schutz:**
- Container ist **Read-Only** (nur Entnahme möglich)
- Spieler kann keine eigenen Items hineinlegen
- Verhindert Duplizierung

### Command-Rewards (Sofort)

Commands werden direkt ausgeführt:
```json
"commands": [
  "currency add %player% 100",
  "xp give %player% 500"
]
```

**Hinweis:** Wenn der Spieler offline ist, werden Commands beim nächsten Join ausgeführt.

---

## Datei-Struktur

```
mods/HytaleHub_HytaleHubPlugin/
├── config.json           # Hauptkonfiguration
├── pending_votes.json    # Votes für Offline-Spieler
└── pending_rewards.json  # Item-Rewards zum Abholen
```

### pending_votes.json

Speichert Votes für Spieler die offline waren:

```json
{
  "pendingVotes": {
    "playername": [
      {
        "vote": {
          "serviceName": "HytaleHub",
          "username": "PlayerName",
          "address": "127.0.0.1",
          "timestamp": 1706285123
        },
        "receivedAt": 1706285123000
      }
    ]
  },
  "lastVoteTimes": {
    "playername": 1706285123000
  }
}
```

### pending_rewards.json

Speichert Item-Rewards zum Abholen via `/rewards`:

```json
{
  "pendingRewards": {
    "playername": [
      {
        "itemId": "Diamond",
        "quantity": 3,
        "addedAt": 1706285123000,
        "source": "vote"
      }
    ]
  }
}
```

**Automatisches Speichern:** Beide Dateien werden automatisch bei Änderungen gespeichert.

---

## Troubleshooting

### Votes kommen nicht an (Pull-Modus)

1. **Token prüfen**
   - `token` in config.json korrekt?
   - Keine Leerzeichen?

2. **Logs prüfen**
   ```
   [HytaleHub] Votifier Pull service started
   [HytaleHub] Poll check: 0 pending votes
   ```

3. **Debug aktivieren**
   ```json
   "debug": true
   ```

### Votes kommen nicht an (TCP-Modus)

1. **Port prüfen**
   ```bash
   # Port erreichbar?
   nc -zv deine-server-ip 8192
   ```

2. **Firewall prüfen**
   - Port 8192 geöffnet?

3. **Logs prüfen**
   ```
   [HytaleHub] Votifier TCP server started on port 8192
   ```

### Item-Rewards erscheinen nicht

1. **Config prüfen**
   - `items` Array vorhanden?
   ```json
   "rewards": {
     "items": [
       { "itemId": "Diamond", "quantity": 1 }
     ]
   }
   ```

2. **Item-IDs prüfen**
   - Gültige Hytale Item-IDs verwenden
   - z.B. `Ore_Gold`, `Diamond`, `Weapon_Sword_Mithril`

### Heartbeat-Fehler

1. **Token prüfen**
   - Korrekter API-Token?

2. **Netzwerk prüfen**
   - Server kann HTTPS-Verbindungen herstellen?

3. **Logs prüfen**
   ```
   [HytaleHub] Heartbeat service started
   [HytaleHub] Heartbeat sent: 200 OK
   ```

---

## Technische Details

### Heartbeat (Verified Live)

**Gesendete Daten:**
```json
{
  "playersOnline": 5,
  "playersMax": 100,
  "ts": 1706285123
}
```

| Feld | Typ | Beschreibung |
|------|-----|-------------|
| `playersOnline` | int | Aktuelle Spieleranzahl |
| `playersMax` | int/null | Maximale Spieleranzahl (wenn verfügbar) |
| `ts` | long | Unix Timestamp |

**Authentifizierung:** Bearer Token im `Authorization` Header

### Debug-Modus

Aktiviere mit `"debug": true` in der Config.

**Was wird geloggt:**
- Item-Reward Verarbeitung (welche Items hinzugefügt werden)
- Pending Rewards Zähler
- API-Anfragen und Antworten (ohne Token!)

**Beispiel Debug-Output:**
```
[DEBUG] addItemRewardsToPending called for PlayerName
[DEBUG] itemRewards count: 2
[DEBUG] Added reward: Diamond x1
[DEBUG] Total pending rewards for PlayerName: 2
```

### Hardcoded Konstanten

| Konstante | Wert | Beschreibung |
|-----------|------|-------------|
| `BASE_URL` | `https://www.hytale-hub.de` | API Basis-URL |
| `HEARTBEAT_INTERVAL` | 30s | Heartbeat-Frequenz |
| `PULL_INTERVAL` | 15s | Vote-Poll-Frequenz |
| `HTTP_TIMEOUT` | 5000ms | API Request Timeout |
| `VOTE_REMINDER_INITIAL_DELAY` | 60s | Erste Erinnerung nach Join |

### API Endpoints

| Endpoint | Methode | Beschreibung |
|----------|---------|-------------|
| `/api/private/status/ingest` | POST | Heartbeat Status-Update |
| `/api/private/votes/pending` | GET | Ausstehende Votes abrufen (Pull-Modus) |
| `/api/votes/direct` | POST | Direkter Vote (Account-Linking) |
| `/api/link/game/check` | POST | Account-Verknüpfung prüfen |
| `/api/link/game/generate` | POST | Verknüpfungs-Link generieren |

### Sicherheit

- **Tokens werden niemals geloggt** (auch nicht im Debug-Modus)
- HMAC-SHA256 Signatur für alle API-Requests
- Constant-Time Vergleich bei Token-Validierung
- Challenge-basierter Replay-Schutz (TCP-Modus)
- TCP: höchstens 4 gleichzeitige Verbindungen pro IP, 5 Sekunden pro Verbindung, begrenzte Warteschlange

### Vote-Deduplizierung

Verhindert doppelte Vote-Verarbeitung:
- Fingerprint: `username + serviceName + timestamp`
- Zeit-Fenster: 5 Minuten
- In-Memory Speicherung (kein Persist über Restart)

### Persistenz

- `pending_votes.json` - Votes für Offline-Spieler
- `pending_rewards.json` - Item-Rewards zum Abholen
- Automatisches Speichern bei Änderungen
- Laden beim Server-Start

---

## Build

```bash
# Projekt bauen
gradle build

# JAR: build/libs/HytaleHub-0.2.3.jar
```

### Entwicklungs-Voraussetzungen
- Java 25 JDK
- Gradle (aufrufen mit `gradle`, nicht `./gradlew`)
- HytaleServer.jar im libs/ Ordner (von deinem eigenen Hytale-Server kopieren; wird nicht mit dem Repo verteilt)

### API-Prüfung gegen Server-Versionen

Die Hytale-Server-API ändert sich zwischen Versionen. Nach dem Bauen:

```bash
javac -d tools/bin tools/ApiCheck.java
java -cp tools/bin ApiCheck <klassen-ordner> <Server-x.y.z.jar>
```

---

## Mitmachen und Support

- **Fehler & Ideen:** [GitHub Issues](../../issues), Pull Requests sind willkommen. Wer einen KI-Coding-Agenten nutzt, liest zuerst [AGENTS.md](AGENTS.md).
- **Website:** https://www.hytale-hub.de
- **Discord:** [Hytale-Hub Discord](https://discord.gg/DYQ6Ec57bu)

---

## Changelog

### v0.2.3
- Fix: Der TCP-Modus hat jeden Vote von hytale-hub.de abgelehnt ("Missing serviceName"). Die signierte Nachricht wird jetzt mit und ohne v2-Binärrahmen angenommen.
- TCP-Modus gehärtet: unsignierte Formate werden abgelehnt, 8 KB pro Nachricht, 5 Sekunden pro Verbindung, 4 Verbindungen pro IP, begrenzte Warteschlange. Langsame oder massenhafte Verbindungen blockieren keine echten Votes mehr.
- Der Platzhalter-Token aus der Standard-Config gilt nie als Schlüssel; das Plugin warnt beim Start, bis ein echter Token eingetragen ist.
- HTTP-Modus: Anfragen höchstens 8 KB.
- Manifest gibt den unterstützten Server-Bereich an: `>=0.5.0 <0.8.0`.
- Der Timer-Thread der Regelseite wird beim Beenden gestoppt (kein Rest-Thread nach `/plugin`-Neuladen).

### v0.2.2
- Fix: Kam ein Vote, während `/rewards` offen war, gingen dessen Items beim nächsten Abholen verloren.
- Fix: Offline-Votes wurden verarbeitet, bevor der Spieler in der Welt war; Reward-Befehle konnten ins Leere laufen.
- Fix: Speicherdateien werden sicher geschrieben; eine kaputte Datei wird beiseitegelegt (`*.corrupt-<zeit>`), statt das Plugin zu stoppen.
- Fix: Im Pull-Modus werden Vote-IDs gemerkt, doppelte Zustellungen ignoriert.
- Fix: "Alle abholen" konnte nach einem Fehler Items doppelt geben; unbekannte Item-IDs werden abgelehnt.
- Spielernamen werden geprüft, bevor sie in Konsolenbefehle gehen. Fehlgeschlagene Reward-Befehle stehen im Log.
- `config.example.json` entspricht dem echten Config-Format.

### v0.2.1
- Danke an **KaGurke** für die Meldung der Fehler auf neueren Hytale-Servern. Daraus sind die Fixes in 0.2.1 bis 0.2.3 entstanden.
- Fix: `NoSuchMethodError` auf neueren Servern bei `/vote`-Broadcast, `/rewards` und Regeln-Kick (geänderte Server-API).
- Fix: Befehle funktionieren auf Servern ab 0.6 wieder für normale Spieler (`requireNoPermission`).
- Belohnungen werden vor Chat und Broadcast vergeben; jeder Schritt schlägt einzeln fehl.
- Open Source (MIT), neues Prüfwerkzeug `tools/ApiCheck.java`.

### v0.2.0
- Neues `/rewards` Container-UI System
- Item-Rewards mit Anti-Abuse Schutz
- Account-Linking für Direct-Voting
- Vote-Reminder System
- Vereinfachte Konfiguration (nur noch `token`)

### v0.1.0
- Initiale Version
- Verified Live Heartbeat
- Votifier (TCP/HTTP/Pull)
- Basis Reward-System

---

## Lizenz

MIT - siehe [LICENSE](LICENSE). Hytale-Hub.de ist ein Community-Projekt und steht in keiner Verbindung zu Hypixel Studios. Hytale ist eine Marke von Hypixel Studios.
