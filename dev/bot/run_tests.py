"""Runs the end-to-end scenarios against a throwaway Paper server. Usage:

    python dev/bot/run_tests.py                 # build, then every mode
    python dev/bot/run_tests.py smtp legacy     # only these modes
    python dev/bot/run_tests.py --skip-build

Needs run/paper.jar and run/eula.txt (eula=true), both made by scripts/start-test-server.ps1, and Mailpit
(tools/mailpit, made by scripts/start-mailpit.ps1, or already running). The server runs from run-bot/ on
127.0.0.1:37481 so it never touches the manual test server in run/. Each mode starts the server with an empty
plugin data folder and its own config.yml, then stops it.
"""
import os
import re
import secrets
import shutil
import socket
import string
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scenarios  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "run"
BOT_RUN = ROOT / "run-bot"
PORT = 37481
MAILPIT_SMTP = 41853
MAILPIT_API = "http://127.0.0.1:41854/api/v1"
WINDOWS = os.name == "nt"


def config(**changes):
    """The bundled config.yml with some values replaced. Keys are dotted paths of existing lines."""
    lines = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8").splitlines()
    stack = []
    for i, line in enumerate(lines):
        m = re.match(r"^( *)([a-z-]+):(.*)$", line)
        if not m:
            continue
        depth = len(m.group(1)) // 2
        stack[depth:] = [m.group(2)]
        path = ".".join(stack)
        if path in changes:
            lines[i] = f"{m.group(1)}{m.group(2)}: {changes.pop(path)}"
    for path, value in changes.items():  # new sections, e.g. for an older bundled config
        head, _, leaf = path.rpartition(".")
        lines.append(f"{head}:\n  {leaf}: {value}" if head else f"{leaf}: {value}")
    return "\n".join(lines) + "\n"


MAILPIT_SMTP_CONFIG = {
    "email.smtp.host": '"127.0.0.1"',
    "email.smtp.port": str(MAILPIT_SMTP),
    "email.smtp.security": "none",
    "email.smtp.from": '"Bot Test <noreply@mc-server-auth.test>"',
}
MODES = {
    "smtp": config(**MAILPIT_SMTP_CONFIG, **{"secret-input-log.enabled": "true"}),
    "nosmtp": config(),
    "legacy": config(**{"ip-ban.failures": "8"}),
    "badfrom": config(**{**MAILPIT_SMTP_CONFIG, "email.smtp.from": '"LAB306 - TOP CRAFT Server"'}),
}


def listening(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def random_password(n):
    return "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(n))


class Server:
    def __init__(self):
        self.out = []
        self.proc = None

    def start(self):
        log = open(BOT_RUN / "bot-server.log", "a", encoding="utf-8")
        self.proc = subprocess.Popen(["java", "-Xms512M", "-Xmx1G", "-jar", "paper.jar", "--nogui"], cwd=BOT_RUN,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding="utf-8", errors="replace")

        def pump():
            for line in self.proc.stdout:
                self.out.append(line)
                log.write(line)
                log.flush()
        threading.Thread(target=pump, daemon=True).start()
        if not self.wait_for(r"Done \(", 180):
            raise RuntimeError("server did not start; see run-bot/bot-server.log")

    def text(self):
        return "".join(self.out)

    def wait_for(self, pattern, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if re.search(pattern, self.text()):
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(0.5)
        return False

    def console(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.console("stop")
                self.proc.wait(90)
            except Exception:
                self.proc.kill()
                self.proc.wait()


def prepare():
    if not (RUN / "paper.jar").exists():
        sys.exit("run/paper.jar is missing: run scripts/start-test-server.ps1 once first.")
    eula = RUN / "eula.txt"
    if not (eula.exists() and "eula=true" in eula.read_text()):
        sys.exit("Accept the Minecraft EULA first: run scripts/start-test-server.ps1 and follow its instructions.")
    if listening(PORT):
        sys.exit(f"Port {PORT} is busy; is another bot run still going?")
    BOT_RUN.mkdir(exist_ok=True)
    shutil.copy2(RUN / "paper.jar", BOT_RUN / "paper.jar")
    shutil.copy2(eula, BOT_RUN / "eula.txt")
    (BOT_RUN / "server.properties").write_text("\n".join([
        "online-mode=false", "white-list=false", f"server-port={PORT}", "server-ip=127.0.0.1",
        "motd=mc-server-auth bot tests", "level-type=minecraft\\:flat", "generate-structures=false",
        "view-distance=2", "simulation-distance=2", "spawn-protection=0"]) + "\n")
    jars = list((ROOT / "build/libs").glob("mc-server-auth-*.jar"))
    if len(jars) != 1:
        sys.exit(f"Expected one mc-server-auth-*.jar in build/libs, found {len(jars)}.")
    (BOT_RUN / "plugins").mkdir(exist_ok=True)
    shutil.copy2(jars[0], BOT_RUN / "plugins/McServerAuth.jar")


def start_mailpit():
    """Returns the Mailpit process this run started, or None if one was already running."""
    if listening(MAILPIT_SMTP):
        return None
    exe = shutil.which("mailpit") or str(ROOT / "tools/mailpit" / ("mailpit.exe" if WINDOWS else "mailpit"))
    if not Path(exe).exists():
        sys.exit("Mailpit not found: run scripts/start-mailpit.ps1 once, or put mailpit on PATH.")
    proc = subprocess.Popen([exe, "--smtp", f"127.0.0.1:{MAILPIT_SMTP}", "--listen", "127.0.0.1:41854"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(20):
        if listening(MAILPIT_SMTP):
            return proc
        time.sleep(0.5)
    proc.kill()
    sys.exit("Mailpit did not start.")


def run_mode(mode, server_pw, player_pw):
    print(f"\n== {mode} ==")
    data = BOT_RUN / "plugins/McServerAuth"
    shutil.rmtree(data, ignore_errors=True)
    data.mkdir(parents=True)
    (data / "config.yml").write_text(MODES[mode], encoding="utf-8")
    server = Server()
    ctx = None
    try:
        server.start()
        if mode != "badfrom":
            server.console(f"authadmin setserverpassword {server_pw}")
            if not server.wait_for(r"Server password changed", 20):
                raise RuntimeError("could not set the server password")
        ctx = scenarios.Ctx(data=data, port=PORT, server_pw=server_pw, player_pw=player_pw, mailpit=MAILPIT_API,
                            console=server.console, server_log=server.text)
        getattr(scenarios, mode)(ctx)
    except Exception as e:  # a crash in a scenario is a failed check, not a crashed runner
        if ctx is None:
            ctx = scenarios.Ctx(data=data, port=PORT, server_pw="", player_pw="", mailpit=MAILPIT_API,
                                console=lambda s: None, server_log=server.text)
        ctx.check(f"{mode} ran to the end", False, repr(e))
    finally:
        server.stop()
    errors = [l.strip() for l in server.out if "[McServerAuth]" in l and ("SEVERE" in l or "ERROR" in l)]
    if mode != "badfrom":
        ctx.check(f"{mode}: no plugin errors in the server log", not errors, errors[:3])
    return ctx.results


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    modes = args or list(MODES)
    unknown = [m for m in modes if m not in MODES]
    if unknown:
        sys.exit(f"Unknown mode(s): {', '.join(unknown)}. Modes: {', '.join(MODES)}")
    if "--skip-build" not in sys.argv:
        gradle = str(ROOT / ("gradlew.bat" if WINDOWS else "gradlew"))
        subprocess.run([gradle, "build", "-q"], cwd=ROOT, check=True)
    prepare()
    mailpit = start_mailpit()
    # Fresh test-only passwords for each run; never printed.
    server_pw, player_pw = random_password(14), random_password(12)
    results = []
    try:
        for mode in modes:
            results += run_mode(mode, server_pw, player_pw)
    finally:
        if mailpit:
            mailpit.kill()
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    for name in failed:
        print(f"  FAILED: {name}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
