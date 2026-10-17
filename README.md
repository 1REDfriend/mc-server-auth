# mc-server-auth

Paper 26.3 plugin for offline-mode servers (TLauncher and similar). Before a player enters the world they must:

1. enter the shared **server password**, then
2. register (personal password + verified email) on their first visit, or enter their **personal password** after that.

A player who forgets their password can reset it with a 6-digit code sent to their email.

Design: `docs/superpowers/specs/2026-10-17-mc-server-auth-design.md`

## Ports used in development

| Port  | What                                  |
|-------|---------------------------------------|
| 34127 | Local Paper test server (127.0.0.1)   |
| 41853 | Mailpit SMTP (fake mail server)       |
| 41854 | Mailpit web inbox                     |

## Build

```powershell
.\gradlew.bat build
```
The plugin jar is `build\libs\mc-server-auth-0.1.0.jar`. It bundles bcrypt (relocated) and Jakarta Mail, so the
server needs no internet access to load it.

## Run the local test server

```powershell
.\scripts\start-mailpit.ps1
.\gradlew.bat build
.\scripts\start-test-server.ps1
```
The first run downloads Paper and stops until you accept the Minecraft EULA in `run\eula.txt`.
Connect with an offline launcher to `127.0.0.1:34127`. Emails appear at http://127.0.0.1:41854.
The generated server password is printed once in the server console.

## Install on a real server

1. Put the jar in `plugins/`, set `online-mode=false`, start once.
2. Copy the generated server password from the console, or set your own with `/authadmin setserverpassword`.
3. Fill `email.smtp` in `plugins/McServerAuth/config.yml` (for Gmail: `smtp.gmail.com`, port 587, `starttls`, an App Password). Without SMTP, new players cannot register.
4. Restart.

> **Warning:** if McServerAuth is not enabled (check `/plugins` after every server or plugin update), the offline-mode
> server is open to anyone under any name. If you are unsure, keep `white-list=true` as a backstop.

## Commands

| Command | Permission | Default |
|---|---|---|
| `/changepassword` | `mcserverauth.changepassword` | everyone |
| `/authadmin reset <name>` | `mcserverauth.admin` | op |
| `/authadmin setserverpassword` (players: opens a dialog) / `setserverpassword <password>` (console only) | `mcserverauth.admin` | op |
| `/authadmin info <name>` | `mcserverauth.admin` | op |

## Tell your players

- Offline mode does not encrypt traffic. **Do not reuse a password from any other account.**
- Password fields show what you type; check nobody is watching your screen.
