"""Minimal headless Minecraft 26.3 (protocol 777) client for driving McServerAuth dialogs in tests.

Only what the tests need: offline login, the configuration phase, dialogs, keep-alives, commands and system chat.
It never moves or renders the world. Packet ids come from the server's own data generator:

    java -DbundlerMainClass=net.minecraft.data.Main -jar paper.jar --reports --output <dir>

then <dir>/reports/packets.json. Update PROTOCOL and the tables below when the Minecraft version changes.
"""
import io
import re
import socket
import struct
import time
import uuid
import zlib

PROTOCOL = 777

# Packet ids from the server's generated reports/packets.json.
LOGIN_C = {"disconnect": 0, "hello": 1, "finished": 2, "compression": 3, "cookie_request": 5}
CONFIG_C = {"cookie_request": 0, "disconnect": 2, "finish": 3, "keep_alive": 4, "ping": 5,
            "select_known_packs": 15, "clear_dialog": 18, "show_dialog": 19, "code_of_conduct": 20}
CONFIG_S = {"client_information": 0, "cookie_response": 1, "finish": 3, "keep_alive": 4, "pong": 5,
            "select_known_packs": 7, "custom_click": 8, "accept_code_of_conduct": 9}
PLAY_C = {"cookie_request": 21, "disconnect": 32, "keep_alive": 45, "login": 50, "ping": 62,
          "system_chat": 124, "clear_dialog": 142, "show_dialog": 143}
PLAY_S = {"chat_command": 7, "cookie_response": 21, "custom_click": 68,
          "keep_alive": 28, "player_loaded": 44, "pong": 45}


def varint(n):
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varint(buf):
    shift = result = 0
    while True:
        b = buf.read(1)
        if not b:
            raise EOFError
        b = b[0]
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    if result & 0x80000000:
        result -= 1 << 32
    return result


def string(s):
    data = s.encode("utf-8")
    return varint(len(data)) + data


def read_string(buf):
    return buf.read(read_varint(buf)).decode("utf-8")


def mutf8(s):
    """Java modified UTF-8: UTF-16 code units, NUL as two bytes, surrogates encoded one by one."""
    out = bytearray()
    raw = s.encode("utf-16-be", "surrogatepass")
    units = struct.unpack(">%dH" % (len(raw) // 2), raw)
    for c in units:
        if 0 < c < 0x80:
            out.append(c)
        elif c < 0x800:
            out += bytes([0xC0 | (c >> 6), 0x80 | (c & 0x3F)])
        else:
            out += bytes([0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)])
    return struct.pack(">H", len(out)) + bytes(out)


def nbt_string_compound(fields):
    """Nameless network NBT compound of string tags."""
    out = bytearray([10])
    for k, v in fields.items():
        out += bytes([8]) + mutf8(k) + mutf8(v)
    out.append(0)
    return bytes(out)


STRING_RE = re.compile(rb"[\x20-\x7e]{3,}")


def texts(raw):
    """Printable runs in a packet; good enough to find NBT strings such as click ids and dialog text."""
    return [m.decode("ascii") for m in STRING_RE.findall(raw)]


class Kicked(Exception):
    pass


class Bot:
    def __init__(self, name, host="127.0.0.1", port=34127, log=print):
        self.name = name
        self.host, self.port = host, port
        self.log = log
        self.sock = None
        self.threshold = -1
        self.phase = "login"
        self.chat = []          # system chat lines seen in play
        self.dialog = None      # raw bytes of the last dialog shown
        self.in_world = False
        self.kick_reason = None

    # ---- framing ----
    def send(self, pid, payload=b""):
        data = varint(pid) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                comp = zlib.compress(data)
                body = varint(len(data)) + comp
            else:
                body = varint(0) + data
        else:
            body = data
        self.sock.sendall(varint(len(body)) + body)

    def _recv_exact(self, n):
        out = bytearray()
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise EOFError("connection closed")
            out += chunk
        return bytes(out)

    def recv(self):
        length = 0
        shift = 0
        while True:
            b = self._recv_exact(1)[0]
            length |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        body = io.BytesIO(self._recv_exact(length))
        if self.threshold >= 0:
            data_len = read_varint(body)
            rest = body.read()
            data = zlib.decompress(rest) if data_len else rest
            body = io.BytesIO(data)
        pid = read_varint(body)
        return pid, body.read()

    # ---- connection ----
    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=30)
        self.send(0, varint(PROTOCOL) + string(self.host) + struct.pack(">H", self.port) + varint(2))
        # An offline-mode server derives the player's UUID from the name and ignores this one.
        self.send(0, string(self.name) + uuid.uuid4().bytes)
        while True:
            pid, data = self.recv()
            buf = io.BytesIO(data)
            if pid == LOGIN_C["compression"]:
                self.threshold = read_varint(buf)
            elif pid == LOGIN_C["finished"]:
                self.send(3)  # login_acknowledged
                self.phase = "configuration"
                return
            elif pid == LOGIN_C["disconnect"]:
                self.kick_reason = read_string(buf)  # JSON text component
                raise Kicked(self.kick_reason)
            elif pid == LOGIN_C["cookie_request"]:
                key = read_string(buf)
                self.send(4, string(key) + b"\x00")
            elif pid == LOGIN_C["hello"]:
                raise RuntimeError("server asked for encryption; is it in online mode?")

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass
        time.sleep(2)  # let the server finish the quit before the same name reconnects

    def _ids(self):
        return CONFIG_C if self.phase == "configuration" else PLAY_C

    def pump(self, until, timeout=20):
        """Reads packets until until(self) is true. Handles keep-alives and records dialogs/chat."""
        deadline = time.time() + timeout
        self.sock.settimeout(1)
        while time.time() < deadline:
            if until(self):
                return True
            try:
                pid, data = self.recv()
            except socket.timeout:
                continue
            except EOFError:
                raise Kicked(self.kick_reason or "connection closed")
            self._handle(pid, data)
        return until(self)

    def _handle(self, pid, data):
        buf = io.BytesIO(data)
        if self.phase == "configuration":
            if pid == CONFIG_C["keep_alive"]:
                self.send(CONFIG_S["keep_alive"], data[:8])
            elif pid == CONFIG_C["ping"]:
                self.send(CONFIG_S["pong"], data[:4])
            elif pid == CONFIG_C["select_known_packs"]:
                self.send(CONFIG_S["select_known_packs"], data)
            elif pid == CONFIG_C["cookie_request"]:
                self.send(CONFIG_S["cookie_response"], string(read_string(buf)) + b"\x00")
            elif pid == CONFIG_C["code_of_conduct"]:
                self.send(CONFIG_S["accept_code_of_conduct"])
            elif pid == CONFIG_C["show_dialog"]:
                self.dialog = data
            elif pid == CONFIG_C["clear_dialog"]:
                self.dialog = None
            elif pid == CONFIG_C["finish"]:
                self.send(CONFIG_S["finish"])
                self.phase = "play"
                self.dialog = None
            elif pid == CONFIG_C["disconnect"]:
                self.kick_reason = " ".join(texts(data))
                raise Kicked(self.kick_reason)
        else:
            if pid == PLAY_C["keep_alive"]:
                self.send(PLAY_S["keep_alive"], data[:8])
            elif pid == PLAY_C["ping"]:
                self.send(PLAY_S["pong"], data[:4])
            elif pid == PLAY_C["login"]:
                self.in_world = True
                self.send(PLAY_S["player_loaded"])
            elif pid == PLAY_C["cookie_request"]:
                self.send(PLAY_S["cookie_response"], string(read_string(buf)) + b"\x00")
            elif pid == PLAY_C["show_dialog"]:
                self.dialog = data
            elif pid == PLAY_C["clear_dialog"]:
                self.dialog = None
            elif pid == PLAY_C["system_chat"]:
                line = " ".join(texts(data))
                self.chat.append(line)
                self.log(f"  [{self.name} chat] {line}")
            elif pid == PLAY_C["disconnect"]:
                self.kick_reason = " ".join(texts(data))
                raise Kicked(self.kick_reason)

    # ---- dialogs ----
    def wait_dialog(self, timeout=20):
        self.dialog = None
        if not self.pump(lambda b: b.dialog is not None, timeout):
            raise TimeoutError(f"{self.name}: no dialog")
        return self.dialog_text()

    def dialog_text(self):
        return " | ".join(texts(self.dialog)) if self.dialog else ""

    def click_ids(self):
        return re.findall(rb"mcserverauth:([a-z_]+)\.(\d+)", self.dialog or b"")

    def click(self, action, **fields):
        ids = {a.decode(): n.decode() for a, n in self.click_ids()}
        if action not in ids:
            raise AssertionError(f"{self.name}: no '{action}' button in dialog; buttons={sorted(ids)}")
        ident = f"mcserverauth:{action}.{ids[action]}"
        tag = nbt_string_compound(fields)
        payload = string(ident) + varint(len(tag)) + tag
        pid = CONFIG_S["custom_click"] if self.phase == "configuration" else PLAY_S["custom_click"]
        self.dialog = None
        self.send(pid, payload)

    def command(self, cmd):
        self.send(PLAY_S["chat_command"], string(cmd))
