# mc-server-auth — Design

Date: 2026-10-17
Status: approved in chat, pending spec review

## Goal

A Paper plugin for Minecraft 26.3 that protects an offline-mode server (players
join with TLauncher or other non-premium launchers) with two password layers:

1. **Server password** — one shared password that every player must know to get in.
2. **Personal password** — set by each player on their first join, required on every
   later join, so nobody can play under someone else's username.

Both prompts appear as native Minecraft dialogs **during the configuration phase**,
before the player enters the world. A player who fails never sees the world.

No client mod is needed. The plugin runs only on the server.

## Non-goals

- Premium (Mojang) account verification.
- Multi-server / proxy (Velocity, BungeeCord) support.
- Email-based password recovery. Recovery is an admin reset.
- Masked password input. Minecraft dialog text inputs do not mask characters; this is
  a game limitation and is documented for players.

## Known limitations (to tell server owners and players)

- Offline mode disables protocol encryption, so passwords travel in plaintext over the
  network. Players must not reuse a password from any other account.
- Dialog input is visible on screen while typing.

## Environment

- Paper 26.3 (confirmed available on the PaperMC download API on 2026-10-17).
- Java 25 (installed locally: OpenJDK 25.0.3).
- Gradle (Kotlin DSL), depends on `paper-api` 26.3.
- Test server runs locally in `run/` (git-ignored) on port **34127**
  (random high port, per the owner's port rule). Recorded in `README.md`.

## Login flow

```
Player connects
  │
  ├─ Pre-login check (before configuration):
  │    - IP temporarily banned?            → disconnect
  │    - Same name already online?         → disconnect the NEW connection
  │      (offline mode would otherwise let an impostor kick the real player)
  │    - Name differs from a registered name only by letter case?
  │                                         → disconnect, tell them the exact casing
  │
  ▼
Configuration phase (player is not in the world yet)
  │
  ├─ Session remember enabled AND (name, IP) authenticated within window?
  │                                         → allow, skip dialogs
  │
  ├─ Dialog 1: server password
  │     wrong → count failure; under limit → show dialog again; at limit → disconnect
  │
  ├─ Account exists?
  │     no  → Dialog 2a: new password + confirm
  │            rules: 6–64 chars, both fields equal, not equal to the server password
  │            invalid → show 2a again with the reason
  │            valid   → store hash, continue
  │     yes → Dialog 2b: personal password
  │            wrong → count failure; same limit rules as dialog 1
  │
  ▼
Configuration completes → player enters the world
```

Every dialog wait has a timeout (default 60 s, whole flow). Timeout, closing the
dialog, or pressing a cancel button disconnects the player with a message.

Failures from dialog 1 and dialog 2b share one per-connection counter.

## Components

Each unit has one job and can be tested on its own. Package root:
`dev.tongt.mcserverauth`.

| Unit | Responsibility | Depends on |
|---|---|---|
| `AuthConfig` | Load and validate `config.yml` into an immutable object. | Bukkit config API |
| `Messages` | Load `messages.yml`; return player-facing text (MiniMessage) by key with placeholders. | Adventure |
| `PasswordHasher` | `hash(String)` and `verify(String, hash)` using BCrypt (cost 12). Shaded library. | jBCrypt (or equivalent) |
| `AccountStore` | SQLite access: find account, create, update password, delete (reset), read/write the server password hash. Keyed by lowercase name; keeps the original-case name. Runs off the main thread. | sqlite-jdbc (bundled with Paper) |
| `AttemptLimiter` | Per-connection failure count, per-IP failure window, temporary IP bans. In-memory. | Clock (injectable for tests) |
| `SessionCache` | Remembers `(name, IP) → authenticated at`; feature C, **off by default**. In-memory, lost on restart. | Clock |
| `LoginFlow` | Drives the dialogs in the configuration phase following the flow above. Holds no storage logic itself. | all of the above, Paper dialog API |
| `PreLoginGuard` | The pre-login checks (ban, duplicate online name, case mismatch). | `AccountStore`, `AttemptLimiter` |
| `ChangePasswordCommand` | `/changepassword` (feature A): opens a dialog with current, new, confirm. | `LoginFlow` dialog builders, `AccountStore`, `PasswordHasher` |
| `AuthAdminCommand` | `/authadmin reset <name>` and `/authadmin setserverpassword` (feature B). | `AccountStore`, `PasswordHasher` |
| `McServerAuthPlugin` | Wires everything, registers listeners and commands, closes the database on disable. | everything |

### Paper API used

- `AsyncPlayerConnectionConfigureEvent` — runs during configuration; the handler
  blocks (async thread) on a future that completes when the flow finishes.
- Dialog API (`io.papermc.paper.dialog`) with text inputs and action buttons;
  responses arrive through `PlayerCustomClickEvent` and are matched to the waiting
  connection.
- `AsyncPlayerPreLoginEvent` for `PreLoginGuard`.

Exact signatures are verified against the 26.3 javadoc while writing the plan.

## Data

SQLite file: `plugins/McServerAuth/accounts.db`

```sql
CREATE TABLE accounts (
  name_lower    TEXT PRIMARY KEY,
  name_display  TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at    INTEGER NOT NULL,   -- epoch millis
  last_login_at INTEGER,
  last_ip       TEXT
);

CREATE TABLE settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- settings row: ('server_password_hash', <bcrypt hash>)
```

Plaintext passwords are never stored or logged.

### Server password bootstrap

- First start with no `server_password_hash`: the plugin generates a random
  12-character password, stores its hash, and prints the plaintext **once** to the
  console with a notice to change it.
- `/authadmin setserverpassword` run by a player opens a dialog (password + confirm),
  so the password never appears in a command line or log.
- From the console: `authadmin setserverpassword <password>` is accepted, with a
  warning that the console log records it.
- Changing the server password does not affect players already online.

## Commands and permissions

| Command | Who | Permission | Notes |
|---|---|---|---|
| `/changepassword` | player | `mcserverauth.changepassword` (default: true) | dialog: current, new, confirm |
| `/authadmin reset <name>` | admin / console | `mcserverauth.admin` (default: op) | deletes the account; next join goes to dialog 2a. Kicks the player if online. Clears their session cache entry. |
| `/authadmin setserverpassword` | admin / console | `mcserverauth.admin` | see bootstrap section |

## Config (`config.yml`)

```yaml
max-attempts-per-connection: 3
ip-ban:
  failures: 5          # failures from one IP ...
  window-minutes: 10   # ... within this window
  ban-minutes: 10      # result in a temporary ban
login-timeout-seconds: 60
password:
  min-length: 6
  max-length: 64
session-remember:
  enabled: false       # feature C, off by default
  minutes: 5
```

## Player-facing text

All player-facing text is **English** and lives in `messages.yml` (MiniMessage
format), not in code. Keys cover: dialog titles, input labels, button labels, each
error reason, each disconnect reason, command feedback.

## Error handling

- Database error during the flow → disconnect with a generic "login unavailable,
  try again" message; full error to the server log.
- Plugin cannot open the database at startup → log the error and disable the plugin
  **and** stop the server from accepting players (fail closed, never fail open).
- Unknown or stale dialog response (no waiting connection) → ignored.
- Player disconnects mid-flow → the waiting future is completed and cleaned up.

## Testing

Automated (JUnit 5, no running server):

- `PasswordHasher`: hash differs from input, verify true/false, two hashes of the
  same password differ.
- `AccountStore` against a temp SQLite file: create, find (case-insensitive), update,
  reset, server password read/write.
- `AttemptLimiter` with a fake clock: per-connection limit, IP window, ban expiry.
- `SessionCache` with a fake clock: disabled returns nothing, enabled expires.
- `AuthConfig` validation rejects bad values.

Manual, on the local Paper 26.3 test server (port 34127) with an offline client:

1. New player: server password → set password → enters world.
2. Returning player: both passwords correct → enters world.
3. Wrong server password 3 times → disconnected; repeated → IP banned temporarily.
4. Wrong personal password → disconnected; never sees the world.
5. Same name joins while the real player is online → new connection refused.
6. `Steve` registered, `steve` joins → refused with casing hint.
7. Timeout and closing the dialog → disconnected.
8. `/changepassword` works; old password no longer accepted.
9. `/authadmin reset` → player is asked to set a new password next time.
10. `/authadmin setserverpassword` → new password required for new joins.
11. Session remember on: reconnect within 5 min from the same IP skips dialogs.
