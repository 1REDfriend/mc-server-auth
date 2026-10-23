# McServerAuth: Login Plugin for Offline-Mode Minecraft Paper Servers

[![Paper 26.3](https://img.shields.io/badge/Paper-26.3-blue)](https://papermc.io/)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**McServerAuth** is a free, open-source **login and registration plugin for offline-mode (cracked) Minecraft servers** running **Paper 26.3**. Players who join with **TLauncher** or any other non-premium launcher must pass **two passwords before they ever see the world**:

1. A shared **server password**, so only people you invited can get in.
2. A **personal password** per player, so nobody can play under someone else's username.

Players can add an optional **email address**, which lets them **reset a forgotten password by themselves** with a 6-digit code. Everything happens in **native Minecraft dialog windows**. Players type nothing in chat, and no client mod is needed.

> Looking for an **AuthMe alternative** with a server-wide password, popup login screens, and email password recovery? That's what McServerAuth is for.

---

## Table of contents

- [Features](#features)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Email setup (Gmail SMTP)](#email-setup-gmail-smtp)
- [Configuration](#configuration)
- [Secret input log](#secret-input-log)
- [Commands and permissions](#commands-and-permissions)
- [Security](#security)
- [FAQ](#faq)
- [Building from source](#building-from-source)
- [Local test server](#local-test-server)
- [Support](#support)
- [License](#license)

## Features

- **Login before the world loads.** The password dialogs appear while the player is still connecting, during the configuration phase. Players who fail are never placed in the world: they can't move, chat, or look around.
- **Two-layer protection.** A shared server password plus a personal password for every player.
- **Popup dialog UI.** Uses Minecraft's built-in dialog screens (text boxes and buttons), not `/login` chat commands.
- **Optional, verified email.** A player who enters an email at registration confirms it with a code; a player who leaves it blank registers right away.
- **Self-service password reset.** For accounts with an email, "Forgot password?" sends a 6-digit code. Players can add or change their email later with `/email`; accounts without one are reset by an admin. Codes expire after 15 minutes, work only once, and are rate-limited.
- **Brute-force protection.** Per-connection attempt limits, temporary IP bans (IPv6 grouped by /64), and connection and timeout limits.
- **Username takeover protection.** A second login with a name that is already online is refused, so the real player isn't kicked. Names that differ only in letter case (`Steve` vs `steve`) can't be registered twice.
- **Secure storage.** Passwords are hashed with **BCrypt**; codes are salted and hashed. Nothing sensitive is stored in plain text or logged, unless you turn on the [secret input log](#secret-input-log) for debugging.
- **Fail-closed design.** If the config is invalid or the database can't open, the plugin refuses every login instead of letting players in unprotected.
- **Optional "remember me".** Skip the dialogs on a quick reconnect from the same IP. Off by default.
- **Fully customizable text.** Every message, button, and email lives in `messages.yml`.
- **Zero dependencies to install.** Ships as one jar with BCrypt and Jakarta Mail bundled. SQLite storage needs no database server.

## How it works

```
Player connects (not in the world yet)
   │
   ▼
[Dialog] Server password ──── wrong ───► counted → too many → disconnected / IP banned
   │ correct
   ▼
New player?
   ├─ yes ─► [Dialog] Choose password (+ optional email) ─► email given? [Dialog] Enter 6-digit code ─► account created
   └─ no ──► [Dialog] Personal password ─── Forgot password? ─► code by email ─► choose new password
   │
   ▼
Player enters the world
```

## Requirements

| | Version |
|---|---|
| Server software | [Paper](https://papermc.io/downloads/paper) **26.3** |
| Java | **25** |
| `server.properties` | `online-mode=false` (the plugin is built for offline-mode servers) |
| Email (optional, for self-service reset) | Any SMTP account, e.g. Gmail with an App Password |

## Installation

1. **Download or build** `mc-server-auth-0.2.0-beta.1.jar` (see [Releases](../../releases), or [build from source](#building-from-source)).
2. Put the jar in your server's `plugins/` folder.
3. Make sure `online-mode=false` is set in `server.properties`.
4. Start the server once. The console prints a **randomly generated server password** a single time:
   ```
   [McServerAuth] No server password was set. Generated one: ...
   ```
   Copy it, or set your own with `/authadmin setserverpassword`.
5. Optional: fill in the `email.smtp` section of `plugins/McServerAuth/config.yml` (see [Email setup](#email-setup-gmail-smtp)).
6. Restart the server, then check that `McServerAuth` shows up green in `/plugins`.

> ⚠️ **Important:** on an offline-mode server, if McServerAuth isn't enabled, anyone can join under any name. After every server or plugin update, run `/plugins` and make sure it's enabled. If you're unsure, keep `white-list=true` as a backstop.

## Email setup (Gmail SMTP)

Email is optional. With SMTP set up, players may add an email at registration and reset a forgotten password themselves. Gmail works well for small servers (roughly 500 emails per day).

1. Use a Gmail account just for the server (recommended), and turn on **2-Step Verification**.
2. Create an **App Password** at <https://myaccount.google.com/apppasswords>. You'll get a 16-character code.
3. Put it in `plugins/McServerAuth/config.yml`:

```yaml
email:
  smtp:
    host: "smtp.gmail.com"
    port: 587
    security: starttls
    username: "your.server@gmail.com"
    password: "your-16-char-app-password"
    from: "My Server <your.server@gmail.com>"
```

Any other SMTP provider (Brevo, Mailgun, your host's mail server, …) works the same way; change `host`, `port`, `username`, and `password`.

If `host` is left empty, the registration dialog has no email field, players register with a password only, and a forgotten password is reset by an admin with `/authadmin reset <name>`. A `from` that holds no email address (for example just a server name) is rejected at startup.

> Sending email directly without an SMTP login isn't supported: most home internet providers block outbound port 25, and Gmail rejects or spam-folders mail from residential IPs anyway.

## Configuration

`plugins/McServerAuth/config.yml`. Restart the server after editing.

| Setting | Default | Meaning |
|---|---|---|
| `max-attempts-per-connection` | `3` | Wrong passwords allowed before the player is disconnected (1–20) |
| `ip-ban.failures` | `5` | Failures from one IP that trigger a temporary ban |
| `ip-ban.window-minutes` | `10` | Time window for counting those failures |
| `ip-ban.ban-minutes` | `10` | How long the ban lasts |
| `login-timeout-seconds` | `60` | Time to answer a dialog (the server-password dialog is capped at 30 s) |
| `code-wait-timeout-minutes` | `10` | Time to check your inbox and type the emailed code |
| `password.min-length` / `max-length` | `6` / `64` | Personal password length |
| `session-remember.enabled` | `false` | Skip dialogs on reconnect from the same IP. Anyone sharing that IP could join as that name, so leave it off unless you trust your network |
| `session-remember.minutes` | `5` | How long a login is remembered |
| `email.max-accounts-per-email` | `3` | Accounts allowed per email address |
| `email.code-valid-minutes` | `15` | How long an emailed code works |
| `email.code-max-attempts` | `5` | Wrong codes before a code is invalidated (1–10) |
| `email.smtp.*` | empty | SMTP server used to send codes |
| `secret-input-log.enabled` | `false` | Record every dialog input, passwords included (see [Secret input log](#secret-input-log)) |
| `secret-input-log.keep-days` | `7` | Days of secret input log files to keep |

An invalid value or broken YAML puts the plugin in **lockdown**: all logins are refused, and the console says what's wrong.

All player-facing text, including dialog titles, buttons, errors, kick messages, and both emails, is in `plugins/McServerAuth/messages.yml` ([MiniMessage](https://docs.advntr.dev/minimessage/format) format), so you can reword or translate everything. Missing keys fall back to the English defaults.

## Secret input log

A debugging aid for finding an input that breaks the server. When `secret-input-log.enabled` is `true`, every click on a McServerAuth dialog is written, together with everything the player typed into it, exactly as their client sent it, to `plugins/McServerAuth/secret-input-logs/secret-input-YYYY-MM-DD.log` (one file per UTC day). Each line is written to disk before the plugin handles the input, so the input that crashed the server is still there afterwards.

> ⚠️ **These files contain your players' real passwords**, both the server password and personal passwords. Text is base64-encoded so a line can't be read at a glance, but base64 is **not encryption**: anyone who can open the files can decode every password. Players often reuse passwords on other sites. Turn the log on only while you hunt a bug, then turn it off and delete the folder. Never share these files.

One line per input:

```
2026-10-23T08:00:00Z phase=configuration ip=127.0.0.1 name=<base64> id=<base64> payload=<base64>
```

`phase` is `configuration` (login dialogs) or `game` (`/changepassword`, `/email`, `/authadmin setserverpassword`). `id` is the button that was clicked, and `payload` holds every input field the client sent, extra ones included. To read a value:

```bash
echo '<base64>' | base64 -d
```

On Windows PowerShell: `[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('<base64>'))`

## Commands and permissions

| Command | Permission | Default | Description |
|---|---|---|---|
| `/changepassword` | `mcserverauth.changepassword` | everyone | Change your own password (opens a dialog) |
| `/email` | `mcserverauth.email` | everyone | Add or change your email (asks for your password, then a code sent to the new address). Needs SMTP |
| `/authadmin setserverpassword` | `mcserverauth.admin` | op | Change the shared server password. In game it opens a dialog; from the console, use `authadmin setserverpassword <password>` |
| `/authadmin reset <name>` | `mcserverauth.admin` | op | Delete an account so the player registers again (e.g. they lost access to their email) |
| `/authadmin info <name>` | `mcserverauth.admin` | op | Show an account's email, creation date, last login, and last IP |

## Security

Tell your players:

- **Offline mode doesn't encrypt traffic.** Passwords travel unencrypted between the player and the server. **Never reuse a password from another account.**
- **Password fields show what you type**, because Minecraft dialogs don't mask input. Make sure nobody is watching your screen.

What the plugin does:

- BCrypt password hashes (cost 12); salted SHA-256 for short-lived email codes.
- Failed logins, wrong codes, and abandoned dialogs all count toward a temporary IP ban.
- At most 2 connections per IP can be logging in at the same time, so nobody can lock a name by holding it open.
- A reset code can't be "burned" by an attacker from another IP to lock out the real owner.
- Email codes are sent to exactly one validated address (no header or recipient injection).
- Any unexpected error at startup or during login ends in a refusal, never in letting the player in.

## FAQ

**Does this work with TLauncher and other cracked launchers?**
Yes. That's the main use case. The server runs with `online-mode=false`, and every player, premium or not, goes through the login dialogs.

**Do players need to install a mod?**
No. Dialogs are a built-in Minecraft feature. The plugin runs only on the server.

**Which Minecraft versions are supported?**
Paper 26.3 (Minecraft 26.3) on Java 25.

**Does it work with Velocity or BungeeCord?**
Not yet. It's designed for a single Paper server.

**How is this different from AuthMe?**
McServerAuth adds a shared server password layer, uses popup dialogs instead of `/login` and `/register` commands, and authenticates players *before* they spawn in the world.

**A player forgot their password and has no email (or lost access to it). What now?**
An admin runs `/authadmin reset <name>`; the player registers again on the next join.

**I got "Too many wrong attempts from your address".**
That's the temporary IP ban (10 minutes by default). Wait, or restart the server to clear it.

**Where is the data stored?**
In `plugins/McServerAuth/accounts.db` (SQLite). Back it up like any other server data.

## Building from source

Requires JDK 25. The Gradle wrapper is included.

```bash
./gradlew build
```

On Windows use `.\gradlew.bat build`. The plugin jar is `build/libs/mc-server-auth-0.2.0-beta.1.jar` (BCrypt relocated, Jakarta Mail bundled). `build` also runs the unit tests.

## Local test server

Windows PowerShell scripts set up a throwaway test environment on your machine:

```powershell
.\scripts\start-mailpit.ps1       # fake mail server; catches every email locally
.\gradlew.bat build
.\scripts\start-test-server.ps1   # downloads Paper 26.3, installs the plugin, starts it
```

- The first run of `start-test-server.ps1` stops until you read and accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) by creating `run\eula.txt` with `eula=true`.
- `start-mailpit.ps1` needs no Docker: it downloads the official Mailpit Windows build (checksum-verified) into `tools\mailpit`.
- Connect your launcher to `127.0.0.1:34127`. Codes "sent" by email appear at <http://127.0.0.1:41854>.

| Port | Used for |
|---|---|
| 34127 | Local Paper test server (127.0.0.1 only) |
| 41853 | Mailpit SMTP |
| 41854 | Mailpit web inbox |
| 37481 | End-to-end bot test server (127.0.0.1 only) |

### End-to-end tests

`dev/bot/` holds a headless Minecraft client that joins a throwaway Paper server, clicks through the dialogs, and checks the results in the dialogs, chat, `accounts.db`, the Mailpit inbox, and the secret input log. It needs Python 3.10+ and nothing else; run the two scripts above once first so `run\paper.jar`, the EULA, and Mailpit exist.

```powershell
python dev\bot\run_tests.py            # builds the plugin, then runs every mode
python dev\bot\run_tests.py legacy     # one mode: smtp, nosmtp, legacy, badfrom
```

It runs from its own `run-bot\` folder on port 37481, so it never touches the manual test server. See [`dev/bot/README.md`](dev/bot/README.md).

Design notes and the implementation plan are in [`docs/superpowers/`](docs/superpowers/).

## Support

If McServerAuth keeps impostors off your offline-mode server, you can [buy me a coffee](https://buymeacoffee.com/tongtongkok).

<a href="https://buymeacoffee.com/tongtongkok"><img width="200" height="200" alt="Buy Me a Coffee QR code" src="https://github.com/user-attachments/assets/4034910a-9019-498d-a3e7-85d96b789e5e" /></a>

## License

[MIT](LICENSE). Free to use, modify, and share.

---

*Keywords: Minecraft login plugin, Minecraft auth plugin, offline mode server security, cracked server login, TLauncher server password, Paper 26.3 plugin, AuthMe alternative, Minecraft register plugin, email password reset, Minecraft dialog login.*
