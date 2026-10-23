# End-to-end bot tests

A headless Minecraft 26.3 client that plays through McServerAuth's dialogs on a real Paper server. It does not render or move; it only speaks enough of the protocol to log in, read dialogs, click their buttons with typed input, run commands, and read system chat.

## Run

From the repository root, after running `scripts/start-test-server.ps1` (for `run/paper.jar` and the EULA) and `scripts/start-mailpit.ps1` (for Mailpit) once:

```powershell
python dev\bot\run_tests.py                  # build, then every mode
python dev\bot\run_tests.py smtp nosmtp      # selected modes
python dev\bot\run_tests.py --skip-build     # use the jar already in build/libs
```

The exit code is 0 only when every check passes. Server output goes to `run-bot/bot-server.log`.

The runner copies `run/paper.jar` and `run/eula.txt` into `run-bot/` (git-ignored) and starts the server there on `127.0.0.1:37481`, a flat world with a small view distance. If Mailpit is not running, the runner starts it and stops it at the end. Every mode gets a server started with an **empty plugin data folder** and its own `config.yml`. The server password and player password are new random values on each run and are never printed. The server log does record them, because the runner sets the server password from the console.

## Modes

| Mode | Config | Covers |
|---|---|---|
| `smtp` | Mailpit SMTP, secret input log on | optional email at registration, `/email` (every error path), forgot password with and without email, full reset by email, Thai + emoji passwords, secret input log contents |
| `nosmtp` | no SMTP | password-only registration, `/email` and reset refused, `/authadmin reset` then registering again |
| `legacy` | no SMTP, `ip-ban.failures: 8` | wrong server password, `/changepassword`, second login with an online name, wrong letter case, per-connection attempt limit, IP ban |
| `badfrom` | `from` without an address | lockdown at startup |

## Files

- `mcbot.py`: the client. `Bot.connect()`, `wait_dialog()` (returns the dialog's text), `click(action, **fields)`, `command(text)`, `pump(until)`.
- `scenarios.py`: one function per mode. Each check is a `ctx.check(name, ok, detail)`.
- `run_tests.py`: builds, prepares `run-bot/`, starts and stops the server and Mailpit, and prints the summary.

## When Minecraft updates

Packet ids and the protocol number are hard-coded in `mcbot.py`. Regenerate them from the new server jar:

```powershell
java -DbundlerMainClass=net.minecraft.data.Main -jar run\paper.jar --reports --output <dir>
```

Read `<dir>/reports/packets.json` for the ids, and `protocol_version` in the jar's `version.json` for `PROTOCOL`.

Dialog text is found by scanning the dialog packet for printable strings. That is enough for the English defaults in `messages.yml`. A translated `messages.yml` needs different strings in the checks.
