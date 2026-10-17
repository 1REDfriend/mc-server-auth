# mc-server-auth — Design

Date: 2026-10-17
Status: approved in chat, pending spec review
Revision 2: added email verification and self-service password reset by emailed code.
Revision 3 (security review): join-time safety net, dialog waits keyed by connection,
hard deadline per connection, code dialog buttons, burned/expired codes return to the
previous dialog, slow-email handling, atomic per-email limit.
Revision 4 (final review): libraries shaded into the jar, per-IP connection cap and
short server-password timeout against name lockout, wrong codes count against the IP
and only the requesting IP can burn a code, queued mail is withdrawn instead of
cancelled, cheap checks before bcrypt with a submit cap, dialog click nonces, memory
pruning, config upper bounds, session-remember caveats.
Revision 5 (review fixes): foreign wrong-code guesses are capped per code, IPv6 limits
group by /64, a timeout or cancel at the login dialog or Forgot menu costs the IP a
failure, and the session cache ignores logins that started before a `clear()`.

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
  │    - IP already has 2 connections in the login flow?
  │                                         → disconnect ("Too many connections from
  │                                           your address"); re-checked atomically when
  │                                           the configuration phase starts
  │    - Same name already online?         → disconnect the NEW connection
  │      (offline mode would otherwise let an impostor kick the real player)
  │    - Name differs from a registered name only by letter case?
  │                                         → disconnect, tell them the exact casing
  │
  ▼
Configuration phase (player is not in the world yet)
  │
  ├─ Session remember enabled AND (name, IP) authenticated within window?
  │                                         → allow, skip BOTH passwords
  │
  ├─ Dialog 1: server password (each display waits at most min(login timeout, 30 s))
  │     wrong → count failure; under limit → show again; at limit → disconnect
  │     timeout, Cancel, close or disconnect → one IP failure (not a per-connection
  │       failure), then disconnect. A connection idling here holds the name, so
  │       repeating that costs the attacker's IP a ban.
  │
  ├─ Account exists?
  │
  │   no ─► Dialog 2a: register
  │           fields: new password, confirm password, email
  │           rules: password 6–64 chars, both equal, not equal to the server
  │                  password; email valid format, ≤ 254 chars, used by fewer
  │                  than `max-accounts-per-email` accounts
  │           checks run cheapest first: password length / match, email format,
  │           per-email count, code cooldown and per-connection send limit; only then
  │           the bcrypt compare with the server password. The new password is hashed
  │           only after the code was sent. At most 10 submits of 2a per connection,
  │           then disconnect ("Too many wrong attempts").
  │           invalid → show 2a again with the reason
  │           valid   → send verification code to the email
  │         Dialog 2a-verify: "Code sent to t***@gmail.com"
  │           buttons: Confirm, Resend (after cooldown), Back, Cancel
  │           correct code → store account (password hash + email), continue
  │                          (the per-email limit is re-checked atomically with the
  │                          insert; over the limit → back to 2a with the reason)
  │           wrong code   → stay on 2a-verify; counts one IP failure (an IP ban
  │                          disconnects); see Codes for the attempt counter
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
flow above (tracked from join to quit) and is online right now (the plugin's own
thread-safe set of names in the world; no Bukkit player lookup off the main thread).

Before a returning player's login succeeds (personal password or reset), the account
is read again; if an admin deleted it meanwhile, the player is disconnected with the
account-reset message.

On join the name is marked online before the passed token is consumed, so the name is
never briefly free. Any error in the join check kicks the player.

### Session remember (off by default)

When enabled, a reconnect of the same name from the same IP within
`session-remember.minutes` skips **both** the server password and the personal
password. Anyone else on that IP (shared Wi-Fi, carrier NAT) can join as that name in
that window. The cache would otherwise survive a server-password change, so changing
the server password clears every remembered login. A personal password change or reset
clears that name's entries; a restart clears all (in memory only).

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
  │   wrong → stay on R1 (code attempt counter); counts one IP failure
  │   code burned or expired → back to dialog 2b with "That code is no longer valid"
  │   Back → dialog 2b; Cancel → disconnect
  ▼
Dialog R2: new password + confirm (same rules as 2a; length / match first, the
  │   bcrypt compare with the server password last; at most 10 submits per
  │   connection, then disconnect)
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
- The server-password dialog waits at most min(`login-timeout-seconds`, 30 s) per
  display (and never past the normal deadline).
- Hard deadline per connection: start + code wait + 2 × login timeout (12 minutes
  with the defaults). Every dialog waits at most until then, so Resend / Back loops
  cannot keep a connection in the configuration phase forever. The wait for an email
  send (up to 30 s) is not capped by it, so a connection can outlast the hard
  deadline by at most 30 s per send in progress.
- Timeout, closing the dialog, or Cancel → disconnect with a message.

Failures of the server password and the personal password share one
per-connection counter, kept by `LoginFlow` per connection (`max-attempts-per-connection`).
Every failure, every wrong code, and a timeout, close or cancel of the server-password
dialog, of dialog 2b (login) or of the Forgot menu also counts against the IP in
`AttemptLimiter` (`ip-ban`), so idling there cannot hold a name for free. Wrong codes do not count
toward the per-connection password counter; they have their own per-code counter
(see Codes).

At most 2 connections from one IP can be in the login flow at once. A third person behind
the same NAT waits briefly until a slot frees. Proxies (forwarded client addresses) are out
of scope: the limits use the address Paper reports.

All IP-keyed limits (`AttemptLimiter`, the in-flight cap, code send limits and the
requester-IP check) use `IpKey`: IPv4 as-is, IPv6 grouped by its /64 prefix, so rotating
addresses inside one subscriber's /64 does not evade them.

## Verification and reset codes

- 6 random digits from `SecureRandom`.
- Stored only as a hash (SHA-256 with a per-code salt is enough: the code is short-
  lived and attempt-limited), never logged.
- Valid for 15 minutes, single use.
- 5 wrong attempts (`code-max-attempts`, at most 10) burn the code; the player must
  request a new one. Only wrong attempts from the IP that requested the code count
  toward this: from any other IP a wrong code is just "wrong" and never burns it, so
  someone who knows the server password cannot burn a victim's reset code and lock
  them out with the 15-minute reset cooldown. Every wrong code still counts against
  the typing IP's limiter.
- Wrong guesses from other IPs are counted per code (`foreignWrong`). At
  `3 * code-max-attempts` the code becomes requester-only: any other IP gets "wrong"
  without the hash being checked, even for the correct code, so an attacker with many
  IPs cannot brute-force the 6 digits. The requesting IP can still use the code.
- At most one live code per account and purpose (`REGISTER`, `RESET`). A new code
  replaces the old one.
- Kept in memory; a server restart invalidates all codes (acceptable). Each issue
  sweeps expired codes, finished cooldowns, stale undo records and empty per-IP send
  windows.

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
| `PasswordHasher` | `hash(String)` and `verify(String, hash)` using BCrypt (cost 12). | `at.favre.lib:bcrypt`, shaded and relocated to `dev.tongt.mcserverauth.lib.favre` |
| `AccountStore` | SQLite access: find account, create, update password, delete (reset), count accounts per email, read/write the server password hash. Keyed by lowercase name; keeps the original-case name. Runs off the main thread. | sqlite-jdbc (bundled with Paper) |
| `AttemptLimiter` | Per-IP failure window and temporary IP bans. In-memory; prunes empty windows and expired bans on each failure. (The per-connection count lives in `LoginFlow`.) | Clock (injectable for tests) |
| `SessionCache` | Remembers `(name, IP) → authenticated at`; feature C, **off by default**. In-memory. `clear()` on server-password change bumps a generation; `remember` only stores if the generation captured after the server password was verified is still current, so a login in flight during a change is not remembered. | Clock |
| `EmailAddress` | Validate format and length; normalize (trim, lowercase domain); produce the masked form. Pure, no I/O. | — |
| `CodeService` | Issue, verify and burn codes per (account, purpose); enforce expiry, attempts and send limits. In-memory. | Clock, `SecureRandom` |
| `MailSender` | Send one plain-text email over SMTP (STARTTLS or SSL) on a background thread (3 threads, queue of 50; a full queue fails the send). Returns a `Sending` handle: a future plus `withdraw()`, which stops a still-queued email from ever being sent. Interface + SMTP implementation. | Jakarta Mail (Eclipse Angus), shaded, not relocated |
| `LoginFlow` | Drives the dialogs in the configuration phase following the flows above. Holds the per-connection state (failure count, sends, submits) and the per-IP in-flight count; no storage or mail logic itself. | all of the above, Paper dialog API |
| `DialogFactory` / `DialogWaiter` | Build dialogs as templates that take a per-display nonce; show one and block until its answer. | Paper dialog API |
| `PreLoginGuard` | The pre-login checks (ban, connections per IP, duplicate online name, case mismatch). Asks `LoginFlow.isNameBusy`, which is thread-safe: names in the world, in the flow, or just passed and on the way in. | `AccountStore`, `AttemptLimiter`, `LoginFlow` |
| `ChangePasswordCommand` | `/changepassword` (feature A): dialog with current, new, confirm. | dialog builders, `AccountStore`, `PasswordHasher` |
| `AuthAdminCommand` | `/authadmin reset <name>`, `/authadmin setserverpassword`, `/authadmin info <name>` (feature B). | `AccountStore`, `PasswordHasher` |
| `McServerAuthPlugin` | Wires everything, registers listeners and commands, closes the database on disable. | everything |

### Paper API used

- `AsyncPlayerConnectionConfigureEvent` — runs during configuration; the handler
  blocks (async thread) on a future that completes when the flow finishes.
- Dialog API (`io.papermc.paper.dialog`) with text inputs and action buttons;
  responses arrive through `PlayerCustomClickEvent` and are matched to the waiting
  connection. Each displayed dialog gets a fresh nonce; button click ids are
  `mcserverauth:<action>.<nonce>`, and a click whose nonce is not the one of the
  dialog currently awaited (a late click on a replaced dialog) is ignored.
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
CREATE INDEX accounts_email ON accounts(email COLLATE NOCASE);  -- per-email count is case-insensitive

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
- `/authadmin setserverpassword` run by a player always opens a dialog (password +
  confirm), so the password never appears in a command line or log. If the player
  typed arguments anyway, they are ignored and the player is told they may be in the
  server log.
- From the console only: `authadmin setserverpassword <password>` is accepted, with a
  warning that the console log records it.
- A player can have only one of these dialogs (`/changepassword`,
  `/authadmin setserverpassword`) open at a time; running either again while one is
  open is refused with "Finish or cancel the open dialog first."
- Changing the server password does not affect players already online. It clears
  every session-remember entry, so the next join of every player asks for it.

## Commands and permissions

| Command | Who | Permission | Notes |
|---|---|---|---|
| `/changepassword` | player | `mcserverauth.changepassword` (default: true) | dialog: current, new, confirm |
| `/authadmin reset <name>` | admin / console | `mcserverauth.admin` (default: op) | deletes the account (password and email); next join registers again. Kicks the player if online. Clears their session-cache entries and live codes. |
| `/authadmin setserverpassword [password]` | admin / console | `mcserverauth.admin` | players get a dialog; `[password]` is console-only (see bootstrap section) |
| `/authadmin info <name>` | admin / console | `mcserverauth.admin` | shows display name, full email, created / last login time, last IP |

## Config (`config.yml`)

```yaml
max-attempts-per-connection: 3   # 1-20
ip-ban:
  failures: 5          # failures from one IP ...
  window-minutes: 10   # ... within this window
  ban-minutes: 10      # result in a temporary ban
login-timeout-seconds: 60
code-wait-timeout-minutes: 10
password:
  min-length: 6
  max-length: 64
session-remember:      # skips BOTH passwords for this name from the same IP
  enabled: false       # feature C, off by default
  minutes: 5
email:
  max-accounts-per-email: 3
  code-valid-minutes: 15
  code-max-attempts: 5 # 1-10; only wrong codes from the requesting IP count
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

If the email has not been sent within 30 s, the plugin stops waiting and looks at
whether sending had started:

- Still queued (all mail threads busy): the email is withdrawn and will never be sent.
  This is handled like an SMTP error: the code and cooldown are refunded and the
  player sees "Could not send the email".
- Already started: the email may still arrive. The code stays valid, the send **does**
  count against the limits, and the code dialog opens with "The email is taking a
  while. Check your inbox in a minute; you can also press Resend later."

A full mail queue (50 waiting) fails the send immediately, handled like an SMTP error.

## Packaging

bcrypt (`at.favre.lib:bcrypt` with `at.favre.lib:bytes`) and Jakarta Mail
(`org.eclipse.angus:angus-mail` with Jakarta Activation) are shaded into the plugin jar
with the Shadow Gradle plugin; there is no `libraries:` section in `plugin.yml`. A
runtime library download that failed would leave the plugin disabled and the
offline-mode server open. bcrypt is relocated to `dev.tongt.mcserverauth.lib.favre`.
Jakarta Mail and Angus are **not** relocated: they find their providers through
`META-INF/javamail.*`, `META-INF/mailcap` and `META-INF/services/*` resources keyed by
class name, and Paper ships no `jakarta.mail`. Service files are merged. The mail
threads use the plugin class loader as context class loader. `build` produces only
`build/libs/mc-server-auth-<version>.jar` (the shaded jar; the plain jar is disabled).
The README tells owners to check `/plugins` after every update and keep the whitelist
as a backstop, because a plugin that is not enabled leaves the server open.

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
- Any unexpected failure during startup (unreadable or syntactically invalid
  `config.yml`, invalid values, database errors, or any other exception) → unregister
  every listener the plugin added, log the error, and switch to lockdown: the plugin
  stays enabled with only a listener (priority HIGHEST) that refuses every login (fail
  closed, never fail open; an exception escaping `onEnable` makes Paper disable the
  plugin and drop its listeners, which would let everyone in). `config.yml` is parsed
  directly rather than through `getConfig()`, which would log a syntax error and
  silently use defaults. Loading `messages.yml` never fails: an unreadable file falls
  back to the bundled one, and keys missing from an older file fall back to the
  bundled English text.
- Unexpected runtime errors in the command dialogs and async admin tasks → logged at
  SEVERE, the dialog is closed, and the sender sees "Something went wrong".
- SMTP errors → see "When SMTP is not configured".
- Unknown or stale dialog response (no waiting connection, or a nonce that is not the
  currently awaited dialog's) → ignored.
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
- `AttemptLimiter` with a fake clock: IP window, ban expiry, pruning of stale
  addresses.
- `SessionCache` with a fake clock: disabled returns nothing, enabled expires,
  `clear()` forgets everyone, a stale generation is not remembered.
- `IpKey`: IPv4 unchanged, IPv6 in one /64 equal, different /64 differ.
- `EmailAddress`: valid / invalid formats, length limit, normalization, masking.
- `CodeService` with a fake clock: correct code accepted once, wrong attempts burn
  the code, expiry, new code replaces old, send-limit cooldowns per account and IP,
  wrong attempts from another IP never burn a code, the foreign-guess cap makes a code requester-only, `cooldownRemaining` issues
  nothing, expired state is pruned.
- `SmtpMailSender`: bad recipients refused, unreachable server fails, a full queue
  fails the send, a queued send can be withdrawn and never runs.
- `AuthConfig` validation rejects bad values, including the upper bounds of
  `code-max-attempts` and `max-attempts-per-connection`.

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
17. Three connections from one IP at once → the third is refused.
18. Idle on the server-password dialog → disconnected after 30 s.
19. `jar tf` of the built jar lists the relocated bcrypt classes, `jakarta/mail`
    classes and `META-INF/javamail.*`; the plugin enables on a server without internet.
