"""End-to-end scenarios for McServerAuth, run by run_tests.py against a live Paper server.

Each scenario expects a freshly started server with an empty plugin data folder and the config its mode needs
(see MODES in run_tests.py). Checks are recorded with Ctx.check and summarized by the runner.
"""
import base64
import json
import re
import sqlite3
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from mcbot import Bot, Kicked


@dataclass
class Ctx:
    data: Path                      # plugins/McServerAuth of the test server
    port: int
    server_pw: str
    player_pw: str
    mailpit: str                    # Mailpit API base, e.g. http://127.0.0.1:41854/api/v1
    console: Callable[[str], None]  # sends one line to the server console
    server_log: Callable[[], str]   # the server's output so far
    run_id: str = field(default_factory=lambda: str(int(time.time()) % 100000))
    results: list = field(default_factory=list)

    def check(self, name, ok, detail=""):
        self.results.append((name, bool(ok)))
        print(("  PASS " if ok else "  FAIL ") + name + ("" if ok else f"  -- {str(detail)[:300]}"))

    # ---- helpers ----
    def bot(self, name):
        return Bot(name, port=self.port, log=lambda s: None)

    def join(self, name, server_pw=None):
        """Connects and passes the server password. Returns the bot and the text of the next dialog."""
        b = self.bot(name)
        b.connect()
        text = b.wait_dialog()
        assert "Server password" in text, text
        b.click("submit", password=server_pw or self.server_pw)
        return b, b.wait_dialog()

    def register(self, name, password=None, email=None):
        """Registers without an email (or with one when SMTP is on and email is given) and leaves the bot in the
        world."""
        b, text = self.join(name)
        pw = password or self.player_pw
        fields = {"password": pw, "confirm": pw}
        if email is not None:
            fields["email"] = email
        b.click("submit", **fields)
        return b

    def account(self, name):
        con = sqlite3.connect(f"file:{self.data / 'accounts.db'}?mode=ro", uri=True)
        try:
            return con.execute("SELECT name_display, email FROM accounts WHERE name_lower = ?",
                               (name.lower(),)).fetchone()
        finally:
            con.close()

    def mail_code(self, to, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            data = json.load(urllib.request.urlopen(f"{self.mailpit}/messages"))
            for m in data["messages"]:
                if any(t["Address"].lower() == to.lower() for t in m["To"]):
                    msg = json.load(urllib.request.urlopen(f"{self.mailpit}/message/{m['ID']}"))
                    code = re.search(r"\b(\d{6})\b", msg["Text"])
                    if code:
                        return code.group(1)
            time.sleep(1)
        raise TimeoutError(f"no code mailed to {to}")

    def clear_mail(self):
        urllib.request.urlopen(urllib.request.Request(f"{self.mailpit}/messages", method="DELETE"))


def enter_world(b, timeout=20):
    return b.pump(lambda x: x.in_world, timeout)


def wait_chat(b, fragment, timeout=10):
    return b.pump(lambda x: any(fragment in c for c in x.chat), timeout)


def settle(b, seconds=2):
    """Lets the server handle the last click before the bot disconnects."""
    b.pump(lambda x: False, seconds)


def kicked_with(ctx, name, fragment):
    b = ctx.bot(name)
    try:
        b.connect()
        b.wait_dialog()
        return False, "got a dialog instead of a kick"
    except Kicked as e:
        return fragment in str(e), str(e)
    finally:
        b.close()


# ---------------------------------------------------------------------------------------------------------------


def smtp(ctx: Ctx):
    """SMTP on: optional email at registration, /email, forgot password, and the secret input log."""
    ctx.clear_mail()
    nomail = f"NoMail{ctx.run_id}"
    withmail = f"WithMail{ctx.run_id}"
    pw = ctx.player_pw
    odd_pw = "รหัส🔥ผ่าน " + pw[:4]

    b, text = ctx.join(nomail)
    ctx.check("register dialog offers an optional email", "Email (optional)" in text and "key | email" in text, text)
    ctx.check("register body explains email is optional", "Email is optional" in text, text)
    b.click("submit", password=pw, confirm=pw, email="")
    ctx.check("blank email registers and enters the world", enter_world(b), b.kick_reason)
    b.close()
    row = ctx.account(nomail)
    ctx.check("account stored without an email", row is not None and row[1] == "", row)

    b, text = ctx.join(nomail)
    ctx.check("returning player gets the login dialog", "Log in" in text, text)
    b.click("forgot")
    text = b.wait_dialog()
    ctx.check("forgot without email says to ask an admin or use /email",
              "No email is linked to this account" in text and "/email" in text, text)
    b.click("submit", password=pw)
    ctx.check("login after the forgot detour works", enter_world(b), b.kick_reason)

    addr = f"{nomail.lower()}@example.com"
    b.command("email")
    text = b.wait_dialog()
    ctx.check("/email dialog says no email is linked yet", "No email is linked yet" in text, text)
    b.click("submit", current="wrong-" + pw, email=addr)
    ctx.check("/email rejects a wrong current password", "Wrong password." in b.wait_dialog())
    b.click("submit", current=pw, email="not-an-email")
    ctx.check("/email rejects an invalid address", "does not look right" in b.wait_dialog())
    b.click("submit", current=pw, email=addr)
    text = b.wait_dialog()
    ctx.check("/email shows the code dialog", "Confirm your email" in text, text)
    code = ctx.mail_code(addr)
    b.click("submit", code="000000" if code != "000000" else "111111")
    ctx.check("/email rejects a wrong code", "Wrong code." in b.wait_dialog())
    b.click("submit", code=code)
    ctx.check("/email confirms in chat", wait_chat(b, "Your email was saved"), b.chat)
    row = ctx.account(nomail)
    ctx.check("email stored after /email", row is not None and row[1] == addr, row)

    b.command("email")
    text = b.wait_dialog()
    ctx.check("/email shows the current masked email", "Your email is n***@example.com" in text, text)
    b.click("submit", current=pw, email=addr.upper())
    ctx.check("/email refuses the address it already has", "already your email" in b.wait_dialog())
    b.click("cancel")
    settle(b)
    b.close()

    b, text = ctx.join(nomail)
    b.click("forgot")
    text = b.wait_dialog()
    ctx.check("forgot with email offers a code", "We can send a 6-digit code to n***@example.com" in text, text)
    b.close()

    ctx.clear_mail()
    b, text = ctx.join(withmail)
    b.click("submit", password=odd_pw, confirm=odd_pw, email="broken@")
    ctx.check("register rejects an invalid email", "does not look right" in b.wait_dialog())
    addr2 = f"{withmail.lower()}@example.com"
    b.click("submit", password=odd_pw, confirm=odd_pw, email=addr2)
    text = b.wait_dialog()
    ctx.check("register with email asks for the code", "Confirm your email" in text, text)
    b.click("submit", code=ctx.mail_code(addr2))
    ctx.check("register with email enters the world", enter_world(b), b.kick_reason)
    b.close()
    row = ctx.account(withmail)
    ctx.check("account stored with its email", row is not None and row[1] == addr2, row)
    b, text = ctx.join(withmail)
    b.click("submit", password=odd_pw)
    ctx.check("Thai + emoji password logs in", enter_world(b), b.kick_reason)
    b.close()

    # Forgot password by email, end to end.
    ctx.clear_mail()
    b, text = ctx.join(withmail)
    b.click("forgot")
    b.wait_dialog()
    b.click("send_code")
    text = b.wait_dialog()
    ctx.check("reset asks for the emailed code", "Reset your password" in text, text)
    b.click("submit", code=ctx.mail_code(addr2))
    text = b.wait_dialog()
    ctx.check("reset asks for a new password", "Choose a new password" in text, text)
    new_pw = "Reset" + pw
    b.click("submit", password=new_pw, confirm=new_pw)
    ctx.check("reset lets the player in", enter_world(b), b.kick_reason)
    b.close()
    b, text = ctx.join(withmail)
    b.click("submit", password=new_pw)
    ctx.check("the reset password works on the next login", enter_world(b), b.kick_reason)
    b.close()

    # Secret input log (enabled in this mode).
    logs = sorted((ctx.data / "secret-input-logs").glob("secret-input-*.log"))
    lines = logs[-1].read_text(encoding="utf-8").splitlines() if logs else []
    decoded = []
    for line in lines:
        f = dict(p.split("=", 1) for p in line.split(" ")[1:])
        payload = "" if f["payload"] == "-" else base64.b64decode(f["payload"]).decode("utf-8")
        decoded.append((f["phase"], base64.b64decode(f["name"]).decode(), base64.b64decode(f["id"]).decode(),
                        payload))
    mine = [d for d in decoded if d[1] in (nomail, withmail)]
    ctx.check("log has a line for every click (27)", len(mine) == 27, len(mine))
    ctx.check("log has configuration and game phases", {d[0] for d in mine} >= {"configuration", "game"})
    ctx.check("log keeps the Thai + emoji password exactly", any(odd_pw in d[3] for d in mine))
    ctx.check("log keeps the server password", any(ctx.server_pw in d[3] for d in mine))
    ctx.check("log keeps button ids", any(d[2].startswith("mcserverauth:forgot.") for d in mine))
    raw = "\n".join(lines)
    ctx.check("log file has no readable password", pw not in raw and ctx.server_pw not in raw)


def nosmtp(ctx: Ctx):
    """SMTP off: password-only registration, no self-service email, admin reset."""
    name = f"NoSmtp{ctx.run_id}"
    pw = ctx.player_pw
    b, text = ctx.join(name)
    ctx.check("register dialog has no email field", "key | email" not in text and "Email" not in text, text)
    ctx.check("register body says an admin resets passwords", "an admin can reset it" in text, text)
    b.click("submit", password=pw, confirm=pw)
    ctx.check("registration enters the world", enter_world(b), b.kick_reason)
    b.command("email")
    ctx.check("/email says the server cannot send email", wait_chat(b, "cannot send email"), b.chat)
    b.close()
    row = ctx.account(name)
    ctx.check("account stored without an email", row is not None and row[1] == "", row)

    b, text = ctx.join(name)
    b.click("forgot")
    ctx.check("forgot says reset is unavailable", "Password reset is unavailable" in b.wait_dialog())
    b.click("submit", password=pw)
    ctx.check("login still works after forgot", enter_world(b), b.kick_reason)
    b.close()

    ctx.console(f"authadmin reset {name}")
    time.sleep(3)
    ctx.check("admin reset deletes the account", ctx.account(name) is None)
    b, text = ctx.join(name)
    ctx.check("the reset player gets the register dialog again", "Create your account" in text, text)
    new_pw = "New" + pw
    b.click("submit", password=new_pw, confirm=new_pw)
    ctx.check("the reset player registers again without email", enter_world(b), b.kick_reason)
    b.close()


def legacy(ctx: Ctx):
    """Features from 0.1.0: server password errors, /changepassword, name checks, attempt limits, IP ban.

    Runs with ip-ban.failures = 8 so the per-connection limit can be seen before the ban. Failures from this IP,
    in order: 1 (wrong server password), 2 (old password after a change), 3-5 (per-connection limit), 6-8 (ban).
    """
    name = f"Legacy{ctx.run_id}"
    pw = ctx.player_pw

    b = ctx.bot(name)
    b.connect()
    b.wait_dialog()
    b.click("submit", password="wrong-" + ctx.server_pw)
    text = b.wait_dialog()
    ctx.check("wrong server password shows an error and asks again", "Wrong server password." in text, text)
    b.click("submit", password=ctx.server_pw)
    text = b.wait_dialog()
    ctx.check("correct server password after a wrong one continues", "Create your account" in text, text)
    b.click("submit", password=pw, confirm=pw)
    ctx.check("registration enters the world", enter_world(b), b.kick_reason)

    b.command("changepassword")
    text = b.wait_dialog()
    ctx.check("/changepassword opens its dialog", "Change password" in text, text)
    new_pw = "Changed" + pw
    b.click("submit", current="wrong-" + pw, password=new_pw, confirm=new_pw)
    ctx.check("/changepassword rejects a wrong current password", "Wrong password." in b.wait_dialog())
    b.click("submit", current=pw, password=new_pw, confirm=new_pw + "x")
    ctx.check("/changepassword rejects a mismatched confirm", "do not match" in b.wait_dialog())
    b.click("submit", current=pw, password=new_pw, confirm=new_pw)
    ctx.check("/changepassword confirms in chat", wait_chat(b, "Your password was changed"), b.chat)

    # The player is still online: a second connection with the same name is refused.
    ok, why = kicked_with(ctx, name, "already online")
    ctx.check("a second login with an online name is refused", ok, why)
    b.close()

    b, text = ctx.join(name)
    b.click("submit", password=pw)
    ctx.check("the old password no longer works", "Wrong password." in b.wait_dialog())
    b.click("submit", password=new_pw)
    ctx.check("the changed password works", enter_world(b), b.kick_reason)
    b.close()

    ok, why = kicked_with(ctx, name.lower(), "registered as")
    ctx.check("a different letter case of a registered name is refused", ok, why)

    b, text = ctx.join(name)
    for _ in range(2):
        b.click("submit", password="wrong")
        b.wait_dialog()
    b.click("submit", password="wrong")
    try:
        b.pump(lambda x: False, 10)
        ctx.check("third wrong password disconnects", False, "still connected")
    except Kicked as e:
        ctx.check("third wrong password disconnects", "Too many wrong attempts." in str(e), e)
    b.close()

    b = ctx.bot(name)
    b.connect()
    b.wait_dialog()
    for _ in range(2):
        b.click("submit", password="wrong")
        b.wait_dialog()
    b.click("submit", password="wrong")
    try:
        b.pump(lambda x: False, 10)
        ctx.check("enough failures from one IP ban it", False, "still connected")
    except Kicked as e:
        ctx.check("enough failures from one IP ban it", "from your address" in str(e), e)
    b.close()
    ok, why = kicked_with(ctx, f"Other{ctx.run_id}", "from your address")
    ctx.check("a banned IP is refused before any dialog, for any name", ok, why)


def badfrom(ctx: Ctx):
    """A from address without an email puts the plugin in lockdown at startup."""
    log = ctx.server_log()
    ctx.check("startup names the bad email.smtp.from", "email.smtp.from must be an email address" in log)
    ok, why = kicked_with(ctx, f"Locked{ctx.run_id}", "Login system unavailable")
    ctx.check("lockdown refuses every login", ok, why)
