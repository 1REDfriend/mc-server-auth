# mc-server-auth — Design

Date: 2026-10-17
Status: approved in chat, pending spec review
Revision 2: added email verification and self-service password reset by emailed code.
Revision 3 (security review): join-time safety net, dialog waits keyed by connection,
hard deadline per connection, code dialog buttons, burned/expired codes return to the
previous dialog, slow-email handling, atomic per-email limit.

## Goal

A Paper plugin for Minecraft 26.3 that protects an offline-mode server (players
join with TLauncher or other non-premium launchers) with two password layers:

1. **Server password** — one shared password that every player must know to get in.
2. **Personal password** — set by each player on their first join, required on every
   later join, so nobody can play under someone else's username.

Each player also registers a **verified email address**. A player who forgets
their personal password can reset it themselves with a 6-digit code sent to that
email.

All prompts appear as native Minecraft dialogs **during the configuration phase**,
before the player enters the world. A player who fails never sees the world.

No client mod is needed. The plugin runs only on the server.

## Non-goals

- Premium (Mojang) account verification.
- Multi-server / proxy (Velocity, BungeeCord) support.
- Reset **links** in emails. That would need a public web server, port forwarding and
  HTTPS. The emailed code is typed into an in-game dialog instead.
- Changing the registered email from in-game. A player who loses access to their
  email asks an admin for `/authadmin reset`, then registers again.
- Masked password input. Minecraft dialog text inputs do not mask characters; this is
  a game limitation and is documented for players.

## Known limitations (to tell server owners and players)

- Offline mode disables protocol encryption, so passwords and codes travel in
  plaintext over the network. Players must not reuse a password from any other account.
- Dialog input is visible on screen while typing.

## Environment

- Paper 26.3 (confirmed available on the PaperMC download API on 2026-10-17).
- Java 25 (installed locally: OpenJDK 25.0.3).
- Gradle (Kotlin DSL), depends on `paper-api` 26.3.
- Test server runs locally in `run/` (git-ignored) on port **34127**.
- Local fake mail server for testing: Mailpit (catches all mail, sends nothing out):
  SMTP on **41853**, web inbox on **41854**.
- Ports follow the owner's random-high-port rule and are recorded in `README.md`.

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
  │     wrong → count failure; under limit → show again; at limit → disconnect
  │
  ├─ Account exists?
  │
  │   no ─► Dialog 2a: register
  │           fields: new password, confirm password, email
  │           rules: password 6–64 chars, both equal, not equal to the server
  │                  password; email valid format, ≤ 254 chars, used by fewer
  │                  than `max-accounts-per-email` accounts
  │           invalid → show 2a again with the reason
  │           valid   → send verification code to the email
  │         Dialog 2a-verify: "Code sent to t***@gmail.com"
  │           buttons: Confirm, Resend (after cooldown), Back, Cancel
  │           correct code → store account (password hash + email), continue
  │                          (the per-email limit is re-checked atomically with the
  │                          insert; over the limit → back to 2a with the reason)
  │           wrong code   → stay on 2a-verify (see Codes for the attempt counter)
  │           code burned or expired → back to 2a with "That code is no longer valid"
  │           Back → 2a; Cancel → disconnect
  │
  │   yes ─► Dialog 2b: personal password
  │           buttons: Log in, Forgot password?, Cancel
  │           wrong → count failure; same limit rules as dialog 1
  │           Forgot password? → reset flow below
  │
  ▼
Configuration completes → player enters the world
  │
  └─ Join-time safety net (PlayerJoinEvent, lowest priority): the player must hold a
     "passed" token recorded when the dialogs succeeded, at most 30 s old. It is
     consumed on join. A join without a fresh token is kicked, so nobody reaches the
     world without passing the flow (for example after dropping mid-flow).
```

A player who is already in the world and is sent back to the configuration phase
(reconfiguration) skips the dialogs. That needs both: the player joined through the
flow above (tracked from join to quit) and is online right now.

### Reset flow (from dialog 2b)

```
Forgot password?
  │
  ▼
Forgot menu: Send me a code, I already have a code, Back
  │
  ├─ Send me a code, but the reset cooldown for this account or IP is active?
  │      → stay in the Forgot menu showing "A code was sent recently, try again in
  │        N seconds"
  │
  ├─ Send reset code to the account's email
  │
  ▼
Dialog R1: "Code sent to t***@gmail.com"  — enter the 6-digit code
  │   buttons: Confirm, Resend, Back, Cancel
  │   wrong → stay on R1 (code attempt counter)
  │   code burned or expired → back to dialog 2b with "That code is no longer valid"
  │   Back → dialog 2b; Cancel → disconnect
  ▼
Dialog R2: new password + confirm (same rules as 2a)
  │
  ▼
Store new hash, clear the account's session-cache entries → player enters the world
  (if an admin deleted the account meanwhile → disconnect with the account-reset
  message instead)
```

The reset does not need the player to stay connected: if they disconnect, the code
stays valid until it expires, and on the next join (after the server password) they
can choose **Forgot password?** → **I already have a code** to go straight to R1.

### Timeouts

- Normal dialogs: 60 s by default (`login-timeout-seconds`), counted from the start
  of the connection and restarted after each code dialog.
- While a dialog that waits for an emailed code is open (2a-verify, R1): 10 minutes
  (`code-wait-timeout-minutes`).
- Hard deadline per connection: start + code wait + 2 × login timeout (12 minutes
  with the defaults). Every dialog waits at most until then, so Resend / Back loops
  cannot keep a connection in the configuration phase forever.
- Timeout, closing the dialog, or Cancel → disconnect with a message.

Failures of the server password and the personal password share one
per-connection counter. Wrong codes have their own counter (see Codes).

## Verification and reset codes

- 6 random digits from `SecureRandom`.
- Stored only as a hash (SHA-256 with a per-code salt is enough: the code is short-
  lived and attempt-limited), never logged.
- Valid for 15 minutes, single use.
- 5 wrong attempts burn the code; the player must request a new one.
- At most one live code per account and purpose (`REGISTER`, `RESET`). A new code
  replaces the old one.
- Kept in memory; a server restart invalidates all codes (acceptable).

### Send limits (anti-spam)

| Purpose | Per account / pending registration | Per IP |
|---|---|---|
| Register (resend) | 60 s between sends, max 3 per connection | 5 per hour |
| Reset | 1 per 15 min | 3 per hour |

Someone abusing **Forgot password?** on another player's name only causes a code to be
sent to the real owner's inbox; the limits stop that from becoming spam.

### Email masking

Shown as first character + `***` + `@` + full domain, e.g. `t***@gmail.com`. The full
address is never shown in game or in command output except to admins
(`/authadmin info <name>`).

## Components

Each unit has one job and can be tested on its own. Package root:
`dev.tongt.mcserverauth`.

| Unit | Responsibility | Depends on |
|---|---|---|
| `AuthConfig` | Load and validate `config.yml` into an immutable object. | Bukkit config API |
| `Messages` | Load `messages.yml`; return player-facing text (MiniMessage) and email subject/body templates by key with placeholders. | Adventure |
| `PasswordHasher` | `hash(String)` and `verify(String, hash)` using BCrypt (cost 12). Shaded library. | jBCrypt (or equivalent) |
| `AccountStore` | SQLite access: find account, create, update password, delete (reset), count accounts per email, read/write the server password hash. Keyed by lowercase name; keeps the original-case name. Runs off the main thread. | sqlite-jdbc (bundled with Paper) |
| `AttemptLimiter` | Per-connection failure count, per-IP failure window, temporary IP bans. In-memory. | Clock (injectable for tests) |
| `SessionCache` | Remembers `(name, IP) → authenticated at`; feature C, **off by default**. In-memory. | Clock |
| `EmailAddress` | Validate format and length; normalize (trim, lowercase domain); produce the masked form. Pure, no I/O. | — |
| `CodeService` | Issue, verify and burn codes per (account, purpose); enforce expiry, attempts and send limits. In-memory. | Clock, `SecureRandom` |
| `MailSender` | Send one plain-text email over SMTP (STARTTLS or SSL) on a background thread; returns a future. Interface + SMTP implementation, so tests use a fake. | Jakarta Mail (Eclipse Angus), shaded |
| `LoginFlow` | Drives the dialogs in the configuration phase following the flows above. Holds no storage or mail logic itself. | all of the above, Paper dialog API |
| `PreLoginGuard` | The pre-login checks (ban, duplicate online name, case mismatch). Asks `LoginFlow.isNameBusy`, which is thread-safe: names in the world, in the flow, or just passed and on the way in. | `AccountStore`, `AttemptLimiter`, `LoginFlow` |
| `ChangePasswordCommand` | `/changepassword` (feature A): dialog with current, new, confirm. | dialog builders, `AccountStore`, `PasswordHasher` |
| `AuthAdminCommand` | `/authadmin reset <name>`, `/authadmin setserverpassword`, `/authadmin info <name>` (feature B). | `AccountStore`, `PasswordHasher` |
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
  email         TEXT NOT NULL,      -- verified before the row is created
  created_at    INTEGER NOT NULL,   -- epoch millis
  last_login_at INTEGER,
  last_ip       TEXT
);
CREATE INDEX accounts_email ON accounts(email);

CREATE TABLE settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- settings row: ('server_password_hash', <bcrypt hash>)
```

An account row is created only after the email is verified, so every stored email
is verified. Plaintext passwords and codes are never stored or logged.

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
| `/authadmin reset <name>` | admin / console | `mcserverauth.admin` (default: op) | deletes the account (password and email); next join registers again. Kicks the player if online. Clears their session-cache entries and live codes. |
| `/authadmin setserverpassword` | admin / console | `mcserverauth.admin` | see bootstrap section |
| `/authadmin info <name>` | admin / console | `mcserverauth.admin` | shows display name, full email, created / last login time, last IP |

## Config (`config.yml`)

```yaml
max-attempts-per-connection: 3
ip-ban:
  failures: 5          # failures from one IP ...
  window-minutes: 10   # ... within this window
  ban-minutes: 10      # result in a temporary ban
login-timeout-seconds: 60
code-wait-timeout-minutes: 10
password:
  min-length: 6
  max-length: 64
session-remember:
  enabled: false       # feature C, off by default
  minutes: 5
email:
  max-accounts-per-email: 3
  code-valid-minutes: 15
  code-max-attempts: 5
  smtp:
    host: ""           # e.g. smtp.gmail.com
    port: 587
    security: starttls # starttls | ssl | none (none only for the local test mail server)
    username: ""
    password: ""       # app password; never commit a real one
    from: ""           # e.g. "My Server <noreply@example.com>"
```

### When SMTP is not configured

The plugin refuses new registrations (after the server password, a new player is
disconnected with "Registration is unavailable. Contact an admin.") and logs a
startup warning. Existing players can still log in;
**Forgot password?** shows "Password reset is unavailable, contact an admin".
It never lets a player register without a verified email.

If sending fails at runtime (SMTP error), the player sees "Could not send the email,
try again later", the failure goes to the server log, and the send does **not** count
against the player's send limits.

If the SMTP server has not answered within 30 s, the plugin stops waiting but the email
may still arrive. The code stays valid, the send **does** count against the limits, and
the code dialog opens with "The email is taking a while. Check your inbox in a minute;
you can also press Resend later."

## Player-facing text

All player-facing text is **English** and lives in `messages.yml` (MiniMessage
format), not in code. Keys cover: dialog titles, input labels, button labels, each
error reason, each disconnect reason, command feedback, and the subject and body of
the two emails (verification, reset). Email bodies are plain text and include the
code, its validity time, the player name, and a line saying "If this wasn't you,
ignore this email."

## Error handling

- Database error during the flow → disconnect with a generic "login unavailable,
  try again" message; full error to the server log.
- Plugin cannot open the database, or `config.yml` is invalid, at startup → log the
  error and switch to lockdown: the plugin stays enabled with only a listener that
  refuses every login (fail closed, never fail open; disabling the plugin would let
  everyone in).
- SMTP errors → see "When SMTP is not configured".
- Unknown or stale dialog response (no waiting connection) → ignored.
- Player disconnects mid-flow → the waiting future is completed and cleaned up;
  live codes stay valid until they expire.
- Dialog waits during the configuration phase are keyed by the connection object, not
  the UUID. In offline mode a second connection with the same name has the same UUID;
  keying by connection stops a refused duplicate from answering or cancelling the real
  player's dialog. In-game dialogs (`/changepassword`, `/authadmin`) are keyed by UUID.
- Any unexpected error or `Error` in the configuration handler → log it and disconnect
  with "login unavailable"; the handler never lets an exception through.
- Plugin disable → every waiting dialog is completed as closed.

## Testing

Automated (JUnit 5, no running server):

- `PasswordHasher`: hash differs from input, verify true/false, two hashes of the
  same password differ.
- `AccountStore` against a temp SQLite file: create, find (case-insensitive), update,
  reset, count per email, server password read/write.
- `AttemptLimiter` with a fake clock: per-connection limit, IP window, ban expiry.
- `SessionCache` with a fake clock: disabled returns nothing, enabled expires.
- `EmailAddress`: valid / invalid formats, length limit, normalization, masking.
- `CodeService` with a fake clock: correct code accepted once, wrong attempts burn
  the code, expiry, new code replaces old, send-limit cooldowns per account and IP.
- `AuthConfig` validation rejects bad values.

Manual, on the local Paper 26.3 test server (port 34127) with an offline client and
the local fake mail server (inbox on port 41854):

1. New player: server password → password + email → code from inbox → enters world.
2. Wrong verification code 5 times → code burned; Resend works after cooldown.
3. Returning player: both passwords correct → enters world.
4. Wrong server password 3 times → disconnected; repeated → IP banned temporarily.
5. Wrong personal password → disconnected; never sees the world.
6. Forgot password → code from inbox → new password → enters world; old password
   rejected next time.
7. Forgot password, disconnect, rejoin → "I already have a code" → reset works.
8. Forgot password twice within 15 min → second request refused with wait time.
9. Same name joins while the real player is online → new connection refused.
10. `Steve` registered, `steve` joins → refused with casing hint.
11. Timeout and closing the dialog → disconnected.
12. `/changepassword` works; old password no longer accepted.
13. `/authadmin reset` → player registers again next time (new email allowed).
14. `/authadmin setserverpassword` → new password required for new joins.
15. Session remember on: reconnect within 5 min from the same IP skips dialogs.
16. SMTP blank in config → registration refused with message, existing login works.
