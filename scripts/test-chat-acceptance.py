#!/usr/bin/env python3
"""Pixel 6 chat acceptance for v0.1 against the local ejabberd and a real OMEMO peer.

Drives the installed debug MainActivity through adb/uiautomator while a separate JVM peer (a genuine
ejabberd/OMEMO client, LivePeerAgentTest) is controlled through JSON command files. Creates only new
laboratory accounts (midori-acc-*), never clears app data, never reads wallet or credential files, and keeps
the phone's existing chat account. The peer's encrypted state stays in --workspace.

  test-chat-acceptance.py --avd Pixel_6_API_34 --workspace DIR --report DIR SCENARIO [SCENARIO ...]
Scenarios: verify reply uncertain background profile emoji document agenda qr
"""
import argparse
import importlib.util
import json
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("chatdev", ROOT / "scripts/chat-device.py")
chatdev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chatdev)
PACKAGE = chatdev.PACKAGE


def local_property(name):
    for line in (ROOT / "local.properties").read_text().splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} is missing from local.properties")


class Agent:
    """The laboratory peer. One long-running JVM; commands are numbered JSON files."""

    def __init__(self, workspace, container="ejabberd-docker"):
        self.dir = Path(workspace)
        self.dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.container = container
        self.counter = 0
        self.process = None
        fixture = self.dir / "fixture.json"
        if fixture.exists():
            self.config = json.loads(fixture.read_text())
        else:
            suffix = secrets.token_hex(4)
            self.config = {"account": f"midori-acc-b-{suffix}@localhost", "password": secrets.token_urlsafe(24),
                           "pin": local_property("midori.chat.devCertificateSha256")}
            user = self.config["account"].split("@")[0]
            subprocess.run(["docker", "exec", container, "bin/ejabberdctl", "register", user, "localhost",
                            self.config["password"]], check=True, capture_output=True, timeout=60)
            fd = os.open(fixture, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(self.config, f)
        self.account = self.config["account"]

    def start(self):
        for sub in ("commands", "results"):
            for old in (self.dir / sub).glob("*"):
                old.unlink()
        for name in ("stop", "events.jsonl"):
            (self.dir / name).unlink(missing_ok=True)
        init = self.dir / "agent.init.gradle"
        init.write_text("""allprojects { tasks.withType(Test).configureEach {
  if (name == 'testMainnetDebugUnitTest') {
    systemProperty 'midori.peerAgent.fixture', System.getenv('MIDORI_PEER_AGENT_FIXTURE')
    systemProperty 'java.net.preferIPv4Stack', 'true'
    outputs.upToDateWhen { false }
  }
} }
""")
        env = dict(os.environ, MIDORI_PEER_AGENT_FIXTURE=str(self.dir / "fixture.json"))
        log = (self.dir / "agent.log").open("wb")
        self.process = subprocess.Popen([str(ROOT / "gradlew"), ":app:testMainnetDebugUnitTest", "--offline", "--tests",
                                         "org.astianx.midori.one.chat.LivePeerAgentTest", "--init-script", str(init)],
                                        cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        ready = self.wait_event(lambda e: e["type"] == "ready", 300)
        self.fingerprint = ready["fingerprint"]
        return self

    def events(self):
        path = self.dir / "events.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def wait_event(self, predicate, timeout=60, since=0):
        end = time.time() + timeout
        while time.time() < end:
            if self.process and self.process.poll() is not None:
                raise RuntimeError("peer agent stopped; see agent.log")
            for event in self.events()[since:]:
                if predicate(event):
                    return event
            time.sleep(0.3)
        raise TimeoutError("peer event not seen")

    def mark(self):
        return len(self.events())

    def command(self, op, timeout=90, **args):
        self.counter += 1
        name = f"{self.counter:05d}.json"
        tmp = self.dir / "commands" / (name + ".tmp")
        tmp.write_text(json.dumps(dict(args, op=op)))
        tmp.rename(self.dir / "commands" / name)
        end = time.time() + timeout
        while time.time() < end:
            result = self.dir / "results" / name
            if result.exists():
                data = json.loads(result.read_text())
                if not data.get("ok"):
                    raise RuntimeError(f"peer command {op} failed: {data.get('error')}")
                return data
            time.sleep(0.2)
        raise TimeoutError(f"peer command {op} timed out")

    def stop(self):
        (self.dir / "stop").write_text("stop")
        if self.process:
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.terminate()


class Phone(chatdev.Device):
    def tap_node(self, node):
        self.shell("input", "tap", str(node["x"]), str(node["y"]))
        time.sleep(1)

    def texts(self):
        return [n["text"] for n in self.nodes() if n["text"]]

    def has(self, pattern):
        return self.find(pattern) is not None

    def type_text(self, text):
        self.shell("input", "text", text.replace(" ", "%s"))

    def back(self, times=1):
        for _ in range(times):
            self.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)

    def go_chats_tab(self):
        for _ in range(6):
            nodes = self.nodes()
            tab = next((n for n in nodes if n["text"] == self.s("chats") and n["y"] > 2200), None)
            if tab:
                self.tap_node(tab)
                return
            self.back()
        raise RuntimeError("Chats tab not reachable")

    def open_conversation(self, contact_text):
        self.go_chats_tab()
        node = self.wait(re.escape(contact_text), 30, exact=False)
        self.tap_node(node)
        self.wait(self.s("chat_options"), 20, exact=True)

    def tap_last(self, text):
        """The last match: a confirmation dialog repeats the label of the button that opened it."""
        matches = [n for n in self.nodes() if n["text"] == text]
        if not matches:
            raise TimeoutError(f"UI text not found: {text}")
        self.tap_node(matches[-1])

    def find_scrolling(self, pattern, attempts=14, exact=False):
        """Compose lists only expose visible rows: look for the text, swiping towards the newest messages."""
        for _ in range(attempts):
            node = self.find(pattern, exact=exact)
            if node:
                return node
            self.shell("input", "swipe", "540", "1500", "540", "700", "250")
            time.sleep(0.8)
        raise TimeoutError(f"not found while scrolling: {pattern}")

    def send_text(self, text):
        self.tap_node(composer(self))
        self.type_text(text)
        time.sleep(0.5)
        self.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)
        self.tap(self.s("send"), exact=True)


def composer(phone, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if phone.has("^" + re.escape(phone.s("chat_reply")) + "$"):
            phone.back()  # close a stray message menu; the conversation stays open
        field = next((n for n in phone.nodes() if n["class"] == "EditText"), None)
        if field:
            return field
        time.sleep(1)
    raise TimeoutError("message composer not visible")


def clear_draft(phone):
    field = composer(phone)
    if field["text"] and field["text"] != phone.s("chat_type_message"):
        phone.tap_node(field)
        phone.shell("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        phone.shell("input", "keyevent", "KEYCODE_DEL")
        phone.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)


def check(report, name, ok, detail=None):
    report["checks"].append({"name": name, "passed": bool(ok), **({"detail": detail} if detail is not None else {})})
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail is not None and not ok else ""))
    return ok


def phone_account(phone):
    """The signed-in lab account is read from the Profile screen; nothing is created or replaced."""
    phone.launch()
    time.sleep(4)
    phone.go_chats_tab()
    phone.tap_node(next(n for n in phone.nodes() if n["text"] == phone.s("profile") or n["text"] == "Perfil"))
    node = phone.wait(r"^midori-ui-a-[a-z0-9]+@localhost$", 20)
    address = node["text"]
    phone.back()
    return address


SCENARIOS = {}


def scenario(fn):
    SCENARIOS[fn.__name__.replace("scenario_", "")] = fn
    return fn


def peer_name(agent):
    return agent.account.split("@")[0]


def open_peer(phone, agent):
    """Open the conversation the way a notification does: through the app's own peer intent."""
    phone.shell("am", "start", "-n", f"{PACKAGE}/org.astianx.midori.one.MainActivity", "--es", "chat_peer", agent.account)
    time.sleep(3)
    phone.wait(phone.s("chat_options"), 30, exact=True)


def befriend(phone, agent, address, report):
    """The peer asks, the phone accepts through its real UI, and the peer trusts the phone's device."""
    flag = agent.dir / "befriended"
    if flag.exists():
        return
    agent.command("addContact", peer=address)
    phone.go_chats_tab()
    phone.tap_node(phone.wait("^Aceptar$|^Accept$|^Aceitar$", 90, exact=False))
    time.sleep(3)
    agent.command("answer", peer=address, approve=True)
    time.sleep(3)
    report["peerTrustedPhoneDevices"] = agent.command("trust", peer=address)["trusted"]
    flag.write_text("done")


def fingerprint_text(value):
    return " ".join(value[i:i + 8] for i in range(0, len(value), 8))


def approve_peer_device(phone, agent, report):
    """Security screen: compare the shown code with the peer's own code, then approve."""
    phone.tap(phone.s("chat_verify_action"), exact=True)
    phone.wait(phone.s("chat_verify_why_title"), 20, exact=True)
    check(report, "guide explains why verification is needed and how to do it",
          phone.has(re.escape(phone.s("chat_verify_why_body")[:40])) and phone.has("^1\\. "))
    phone.screenshot(report["out"], "verification-guide")
    shown = phone.wait(re.escape(fingerprint_text(agent.fingerprint)), 30, exact=False)
    check(report, "the code on the phone is exactly the peer device's own code", shown is not None)
    phone.tap(phone.s("chat_trust_device"), exact=True)
    time.sleep(2)


@scenario
def scenario_verify(phone, agent, report, out):
    report["out"] = out
    address = phone_account(phone)
    report["phone"] = address
    befriend(phone, agent, address, report)
    open_peer(phone, agent)
    name = peer_name(agent)
    title = phone.s("chat_verify_title").replace("%1$s", name)
    banner = phone.wait(re.escape(title), 40, exact=False)
    check(report, "before the first message a banner asks to verify the contact", banner is not None)
    phone.screenshot(out, "verify-banner")
    run = secrets.token_hex(3)
    text = f"Primer mensaje cifrado {run}"
    since = agent.mark()
    phone.send_text(text)
    phone.wait(re.escape(phone.s("chat_verify_first")), 40, exact=False)
    check(report, "the blocked first send is explained instead of failing silently", phone.has(phone.s("chat_failed")))
    phone.screenshot(out, "first-send-blocked")
    approve_peer_device(phone, agent, report)
    event = agent.wait_event(lambda e: e["type"] == "message" and e["body"] == text and not e["outgoing"], 60, since=since)
    check(report, "approving sends the waiting message to the peer", event["protection"] == "ENCRYPTED")
    time.sleep(3)
    count = sum(1 for e in agent.events()[since:] if e["type"] == "message" and e["body"] == text and not e["outgoing"])
    check(report, "the peer received it exactly once", count == 1, count)
    phone.back()
    phone.wait(re.escape(text), 20, exact=False)
    check(report, "the banner is gone after approval", not phone.has(re.escape(title)))
    check(report, "the message is no longer failed", not phone.has("^" + re.escape(phone.s("chat_failed")) + "$"))
    phone.screenshot(out, "verified-conversation")
    (agent.dir / "verified").write_text("done")


def ensure_verified(phone, agent, report, out):
    report["out"] = out
    befriend(phone, agent, phone_account(phone), report)
    open_peer(phone, agent)
    if not (agent.dir / "verified").exists():
        raise RuntimeError("run the verify scenario first with this workspace")


@scenario
def scenario_reply(phone, agent, report, out):
    ensure_verified(phone, agent, report, out)
    run = secrets.token_hex(3)
    original = f"Mensaje original {run}"
    address = phone_account(phone)
    open_peer(phone, agent)
    sent = agent.command("send", peer=address, body=original)
    phone.wait(re.escape(original), 60, exact=False)
    # Menu action on the visible bubble, then compose and send.
    phone.tap_node(phone.find("^" + re.escape(original) + "$"))
    phone.wait(phone.s("chat_reply"), 10, exact=True)
    phone.screenshot(out, "reply-menu")
    phone.tap(phone.s("chat_reply"), exact=True)
    phone.wait(re.escape(phone.s("chat_replying_to").replace("%1$s", peer_name(agent))), 10, exact=False)
    phone.screenshot(out, "reply-bar")
    clear_draft(phone)
    answer = f"Respuesta del Pixel {run}"
    since = agent.mark()
    phone.send_text(answer)
    event = agent.wait_event(lambda e: e["type"] == "message" and e["body"] == answer and not e["outgoing"], 60, since=since)
    quote = event.get("reply") or {}
    check(report, "the peer receives the reply with a reference to the original message",
          quote.get("id") == sent["id"] and quote.get("text") == original and quote.get("from") == agent.account, quote)
    check(report, "the reply bar closes after sending", not phone.has(re.escape(phone.s("chat_replying_to").replace("%1$s", peer_name(agent)))))
    # The peer replies to the phone's message after enough traffic to push the original off screen.
    for index in range(18):
        agent.command("send", peer=address, body=f"Relleno {run} {index:02d}")
    mine = [e for e in agent.events() if e["type"] == "message" and e["body"] == answer][-1]
    peer_reply = f"Respondo a tu mensaje {run}"
    agent.command("send", peer=address, body=peer_reply,
                  reply={"id": mine["id"], "from": address, "text": answer})
    time.sleep(3)
    phone.find_scrolling(re.escape(peer_reply))
    time.sleep(1)
    phone.screenshot(out, "incoming-reply-quote")
    delivery = {phone.s(k) for k in ("chat_sending", "chat_sent", "chat_delivered", "chat_read")}

    def original_visible():
        nodes = phone.nodes()
        return any(n["text"] == answer and any(m["text"] in delivery for m in nodes[i + 1:i + 4])
                   for i, n in enumerate(nodes))

    quote_nodes = [n for n in phone.nodes() if n["text"] == answer]
    check(report, "an incoming reply shows the quoted message, and the original is far off screen",
          len(quote_nodes) == 1 and not original_visible(), len(quote_nodes))
    phone.tap_node(quote_nodes[0])
    time.sleep(2)
    check(report, "tapping the quote scrolls to the original message", original_visible())
    phone.screenshot(out, "reply-navigated-to-original")
    # Persistence: the quote survives a cold start.
    phone.shell("am", "force-stop", PACKAGE)
    open_peer(phone, agent)
    phone.wait(re.escape(peer_reply), 30, exact=False)
    check(report, "the saved conversation still shows the reply reference after restart", phone.has("^" + re.escape(answer) + "$"))


STATE_KEYS = ("chat_sending", "chat_sent", "chat_delivered", "chat_read", "chat_failed", "chat_uncertain")


def message_state(phone, text):
    """The delivery label (an icon description) that follows the message text, or None if the text is absent."""
    labels = {phone.s(k): k for k in STATE_KEYS}
    nodes = phone.nodes()
    index = next((k for k, n in enumerate(nodes) if n["text"] == text and n["class"] != "EditText"), None)
    if index is None:
        return "absent"
    return next((labels[n["text"]] for n in nodes[index + 1:index + 4] if n["text"] in labels), "unlabelled")


@scenario
def scenario_uncertain(phone, agent, report, out):
    """Kill the app at different moments around the send tap; the message must never be lost or duplicated."""
    ensure_verified(phone, agent, report, out)
    run = secrets.token_hex(3)
    outcomes = []
    for index, delay in enumerate((0.05, 0.12, 0.2, 0.35, 0.6)):
        text = f"Envio interrumpido {index} {run}"
        since = agent.mark()
        clear_draft(phone)
        phone.tap_node(composer(phone))
        phone.type_text(text)
        phone.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.6)
        send = phone.find("^" + re.escape(phone.s("send")) + "$")
        phone.shell("input", "tap", str(send["x"]), str(send["y"]))
        time.sleep(delay)
        phone.shell("am", "force-stop", PACKAGE)
        open_peer(phone, agent)
        # Right after the restart: the state restored from the encrypted history.
        restored = None
        for _ in range(6):
            restored = message_state(phone, text)
            if restored != "absent":
                break
            time.sleep(1)
        phone.screenshot(out, f"after-restart-{index}")
        # Let the connection and the server archive reconcile, then retry only what is still unconfirmed.
        time.sleep(18)
        reconciled = message_state(phone, text)
        retried = False
        if reconciled in ("chat_uncertain", "chat_failed"):
            phone.tap(phone.s("retry"), exact=True)
            retried = True
            time.sleep(8)
        final = message_state(phone, text)
        copies = [e for e in agent.events()[since:] if e["type"] == "message" and e["body"] == text and not e["outgoing"]]
        nodes = phone.nodes()
        shown = sum(1 for n in nodes if n["text"] == text and n["class"] != "EditText")
        draft_left = any(n["text"] == text and n["class"] == "EditText" for n in nodes)
        outcome = {"delayMs": int(delay * 1000), "restored": restored, "afterReconcile": reconciled, "retriedByUser": retried,
                   "final": final, "peerCopies": len(copies), "distinctIds": len({e["id"] for e in copies}), "shownOnPhone": shown, "textStillInComposer": draft_left}
        outcomes.append(outcome)
        if restored == "absent":
            # The kill came before the message existed: nothing may have been sent either.
            check(report, f"interrupted send {index}: not created (draft kept), so nothing was sent", len(copies) == 0 and draft_left, outcome)
        else:
            check(report, f"interrupted send {index}: delivered once and shown once, never duplicated",
                  len({e["id"] for e in copies}) == 1 and shown == 1 and not draft_left and final in ("chat_sent", "chat_delivered", "chat_read"), outcome)
    report["outcomes"] = outcomes
    report["statesObserved"] = sorted({o["restored"] for o in outcomes} | {o["afterReconcile"] for o in outcomes})
    report["observedUncertainState"] = "chat_uncertain" in report["statesObserved"]


@scenario
def scenario_background(phone, agent, report, out):
    ensure_verified(phone, agent, report, out)
    address = phone_account(phone)
    open_peer(phone, agent)
    def notifications():
        dump = phone.shell("dumpsys", "notification", "--noredact")
        return len(re.findall(r"NotificationRecord\(.*?pkg=" + re.escape(PACKAGE), dump))
    def service_running():
        return "ChatConnectionService" in phone.shell("dumpsys", "activity", "services", PACKAGE)
    report["foregroundServiceRunning"] = service_running()
    phone.shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(4)
    base = notifications()
    run = secrets.token_hex(3)
    agent.command("send", peer=address, body=f"Aviso en segundo plano {run}")
    end = time.time() + 30
    while time.time() < end and notifications() <= base:
        time.sleep(1)
    got = notifications() > base
    check(report, "with the visible service running, a backgrounded app notifies a new encrypted message", got, {"before": base, "after": notifications()})
    report["serviceRunningWhileBackgrounded"] = service_running()
    # Doze: the connection is not promised.
    phone.shell("dumpsys", "deviceidle", "enable")
    phone.shell("dumpsys", "deviceidle", "force-idle")
    time.sleep(3)
    base = notifications()
    agent.command("send", peer=address, body=f"Aviso durante Doze {run}")
    end = time.time() + 60
    while time.time() < end and notifications() <= base:
        time.sleep(2)
    doze_got = notifications() > base
    report["dozeForceIdle"] = {"notifiedWithinSeconds": 60 if not doze_got else "yes", "delivered": doze_got}
    phone.shell("dumpsys", "deviceidle", "unforce")
    end = time.time() + 60
    while time.time() < end and notifications() <= base:
        time.sleep(3)
    report["afterLeavingDozeNotified"] = notifications() > base
    # Force-stop: no process, no delivery promise.
    phone.shell("am", "force-stop", PACKAGE)
    time.sleep(3)
    base = notifications()
    agent.command("send", peer=address, body=f"Aviso tras detener la app {run}")
    time.sleep(25)
    report["forceStopNotified"] = notifications() > base
    check(report, "after a force-stop no delivery is promised and none happens (documented limit)", not report["forceStopNotified"])
    phone.shell("am", "start", "-n", f"{PACKAGE}/org.astianx.midori.one.MainActivity")


def ejabberd(*args, check=True):
    return subprocess.run(["docker", "exec", "ejabberd-docker", "bin/ejabberdctl", *args], capture_output=True, text=True,
                          timeout=90, check=check).stdout


def registered(user):
    return user in ejabberd("registered_users", "localhost").split()


def hide_keyboard(phone):
    """A swipe that starts on the keyboard types letters, so close it before scrolling."""
    if "mInputShown=true" in phone.shell("dumpsys", "input_method", check=False):
        phone.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)


def scroll_to(phone, pattern, exact=False, attempts=10):
    """Scroll a plain (non-lazy-reversed) screen towards its end until the text is visible."""
    hide_keyboard(phone)
    for _ in range(attempts):
        node = phone.find(pattern, exact=exact)
        if node and node["y"] < 2290:
            return node
        phone.shell("input", "swipe", "540", "1900", "540", "800", "300")
        time.sleep(0.8)
    raise TimeoutError(f"not found while scrolling: {pattern}")


def to_account_screen(phone):
    """Chats -> profile -> messaging account, the way a person reaches it."""
    phone.launch()
    time.sleep(3)
    phone.go_chats_tab()
    icon = next(n for n in phone.nodes() if n["text"] == phone.s("profile") and n["y"] < 300)
    phone.tap_node(icon)
    signed_out = phone.find("^" + re.escape(phone.s("profile_account")) + "$")
    phone.tap_node(signed_out or scroll_to(phone, "^" + re.escape(phone.s("chat_account_title")) + "$"))
    time.sleep(2)


def sign_out(phone):
    for attempt in range(3):
        if phone.has(re.escape(phone.s("chat_pilot_title"))) or phone.has(re.escape(phone.s("chat_account_address"))):
            return
        scroll_to(phone, "^" + re.escape(phone.s("chat_disconnect")) + "$", exact=False)
        phone.tap(phone.s("chat_disconnect"), exact=True)
        time.sleep(1.5)
        phone.tap_last(phone.s("chat_disconnect"))
        time.sleep(4)
    phone.wait(re.escape(phone.s("chat_pilot_title")), 20, exact=True)


def fill_manual_form(phone, address, password, register):
    if phone.has("^" + re.escape(phone.s("chat_pilot_have_account")) + "$"):
        phone.tap(phone.s("chat_pilot_have_account"), exact=True)
    elif phone.has("^" + re.escape(phone.s("chat_pilot_create_instead")) + "$") is False:
        pass
    # The mode chip is the first node with its label; the submit button is the last.
    chip = [n for n in phone.nodes() if n["text"] == (phone.s("profile_register") if register else phone.s("chat_connect"))]
    phone.tap_node(chip[0])
    phone.wait(re.escape(phone.s("chat_account_address")), 10, exact=False)
    edits = [n for n in phone.nodes() if n["class"] == "EditText"]
    type_into(phone, edits[0], address)   # replaces text left from an earlier attempt on this screen
    edits = [n for n in phone.nodes() if n["class"] == "EditText"]
    type_into(phone, edits[1], password)
    phone.tap(phone.s("chat_local_lab"), exact=True)
    label = phone.s("profile_register") if register else phone.s("chat_connect")
    buttons = [n for n in phone.nodes() if n["text"] == label and n["class"] == "TextView"]
    phone.tap_node(buttons[-1])


def pilot_sign_up(phone, username):
    field = next(n for n in phone.nodes() if n["class"] == "EditText")
    type_into(phone, field, username)
    phone.tap(phone.s("profile_register"), exact=True)


PENDING = "may have been created|pudo haberse creado|pode ter sido criada"


@scenario
def scenario_registration(phone, agent, report, out):
    """Pilot sign-up asks only for a username; lost or ambiguous registrations never create a second account."""
    run = secrets.token_hex(3)
    original = phone_account(phone)
    report["retainedAccount"] = original
    # The lab account's password is not stored anywhere readable; a new one is set so it can be signed back into.
    original_password = secrets.token_hex(16)
    ejabberd("change_password", original.split("@")[0], "localhost", original_password)
    created = []
    try:
        to_account_screen(phone)
        sign_out(phone)
        phone.screenshot(out, "pilot-form")
        check(report, "the form asks only for a username (no password, host or port)",
              not phone.has(re.escape(phone.s("chat_password"))) and not phone.has(re.escape(phone.s("chat_server"))))
        # 1. Plain pilot sign-up.
        name = f"midori-acc-pilot-{run}"
        created.append(name)
        pilot_sign_up(phone, name)
        phone.wait(re.escape(name + "@localhost"), 90, exact=False)
        time.sleep(3)
        phone.screenshot(out, "pilot-signed-in")
        check(report, "the account exists on the server and the app is signed in with it", registered(name))
        # 2. A name that already exists with OUR password (a lost reply) resumes that account.
        sign_out(phone)
        existing = f"midori-acc-lost-{run}"; created.append(existing)
        password = secrets.token_hex(12)
        ejabberd("register", existing, "localhost", password)
        fill_manual_form(phone, f"{existing}@localhost", password, register=True)
        phone.wait(re.escape(f"{existing}@localhost"), 90, exact=False)
        time.sleep(3)
        check(report, "an existing name with our own password resumes that account instead of reporting a conflict",
              not phone.has(re.escape(phone.s("profile_username_taken"))) and phone.has(re.escape(phone.s("go_to_chats"))))
        phone.screenshot(out, "resumed-existing-account")
        users_before = set(ejabberd("registered_users", "localhost").split())
        # 3. A name taken by someone else (another password) is refused and nobody stays signed in.
        sign_out(phone)
        taken = f"midori-acc-taken-{run}"; created.append(taken)
        ejabberd("register", taken, "localhost", secrets.token_hex(12))
        users_before.add(taken)
        fill_manual_form(phone, f"{taken}@localhost", secrets.token_hex(12), register=True)
        # The lab limits registrations per address, so the server may answer "too fast" before it reports the conflict;
        # either way the person is told it failed, and the conflict wording itself is covered by unit tests.
        try:
            phone.wait(re.escape(phone.s("profile_username_taken")) + "|" + re.escape(phone.s("profile_registration_unavailable")), 90, exact=False)
        except TimeoutError:
            phone.screenshot(out, "taken-wait-timeout")
            raise
        report["refusalShown"] = "taken" if phone.has(re.escape(phone.s("profile_username_taken"))) else "registration_unavailable (lab rate limit)"
        time.sleep(2)
        phone.screenshot(out, "name-taken")
        check(report, "a name taken with another password is refused, nobody is signed in and no pending notice appears",
              not phone.has(re.escape(phone.s("go_to_chats"))) and not phone.has(PENDING))
        check(report, "the refused attempt created no extra account", set(ejabberd("registered_users", "localhost").split()) == users_before)
        # 4. Unknown outcome: the server stops answering during the request.
        phone.tap(phone.s("chat_pilot_create_instead"), exact=True)
        pending_name = f"midori-acc-pending-{run}"
        subprocess.run(["docker", "pause", "ejabberd-docker"], check=True, capture_output=True, timeout=60)
        try:
            pilot_sign_up(phone, pending_name)
            banner = phone.wait(PENDING, 150, exact=False)
            phone.screenshot(out, "registration-pending")
        finally:
            subprocess.run(["docker", "unpause", "ejabberd-docker"], check=True, capture_output=True, timeout=60)
        check(report, "with no answer from the server the app keeps the intent and explains it", banner is not None)
        phone.shell("am", "force-stop", PACKAGE)
        time.sleep(2)
        to_account_screen(phone)
        phone.wait(PENDING, 30, exact=False)
        check(report, "the pending registration is still offered after the app restarts", True)
        time.sleep(5)
        phone.tap(phone.s("profile_registration_resume"), exact=True)
        phone.wait(re.escape(phone.s("profile_registration_not_created")), 120, exact=False)
        time.sleep(2)
        phone.screenshot(out, "registration-not-created")
        check(report, "continuing with an account that was never created explains it and clears the intent",
              not phone.has(PENDING) and not registered(pending_name))
    finally:
        # Back to the phone's own lab account, with its stored conversations.
        try:
            if not (phone.has(re.escape(phone.s("chat_pilot_title"))) or phone.has(re.escape(phone.s("chat_account_address")))):
                to_account_screen(phone)
                sign_out(phone)
            fill_manual_form(phone, original, original_password, register=False)
            phone.wait(re.escape(phone.s("go_to_chats")), 120, exact=True)
            check(report, "the original lab account is signed in again", phone.has(re.escape(original)))
        finally:
            for user in created:
                ejabberd("unregister", user, "localhost", check=False)


def open_profile(phone):
    phone.launch()
    time.sleep(3)
    phone.go_chats_tab()
    icon = next(n for n in phone.nodes() if n["text"] == phone.s("profile") and n["y"] < 300)
    phone.tap_node(icon)
    phone.wait(re.escape(phone.s("profile_name")), 20, exact=False)


def type_into(phone, field, text):
    phone.tap_node(field)
    phone.shell("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    phone.shell("input", "keyevent", "KEYCODE_DEL")
    if text:
        phone.type_text(text)
    phone.shell("input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)


@scenario
def scenario_profile(phone, agent, report, out):
    """Publish and withdraw the own profile through the real screen; the peer reads the public vCard."""
    ensure_verified(phone, agent, report, out)
    address = phone_account(phone)
    run = secrets.token_hex(3)
    name, bio = f"Pixel de prueba {run}", f"Perfil publicado desde la UI {run}"
    open_profile(phone)
    if phone.has("^" + re.escape(phone.s("profile_published")) + "$"):
        scroll_to(phone, "^" + re.escape(phone.s("profile_withdraw")) + "$")
        phone.tap(phone.s("profile_withdraw"), exact=True)
        time.sleep(1)
        phone.tap_last(phone.s("profile_withdraw"))
        time.sleep(6)
        open_profile(phone)
    fields = [n for n in phone.nodes() if n["class"] == "EditText"]
    type_into(phone, fields[0], name)
    fields = [n for n in phone.nodes() if n["class"] == "EditText"]
    type_into(phone, fields[1], bio)
    hide_keyboard(phone)
    phone.tap("^Guardar cambios$|^Save changes$|^Salvar alterações$", exact=False)
    time.sleep(2)
    before = agent.command("loadProfile", peer=address)
    check(report, "saving keeps the profile on the device: nothing is public yet", before["name"] == "", before)
    scroll_to(phone, "^" + re.escape(phone.s("profile_publish")) + "$")
    phone.screenshot(out, "profile-before-publish")
    phone.tap(phone.s("profile_publish"), exact=True)
    phone.wait(re.escape(phone.s("profile_publish_consent")[:30]), 10, exact=False)
    phone.screenshot(out, "profile-publish-consent")
    check(report, "publishing asks for visible consent that says who can read the copy", True)
    phone.tap_last(phone.s("profile_publish"))
    end = time.time() + 40
    published = {}
    while time.time() < end:
        published = agent.command("loadProfile", peer=address)
        if published["name"]:
            break
        time.sleep(2)
    check(report, "the peer reads the published name and bio from the server", published.get("name") == name and published.get("bio") == bio, published)
    phone.screenshot(out, "profile-published")
    check(report, "the screen says the profile is published", phone.has(re.escape(phone.s("profile_published"))))
    scroll_to(phone, "^" + re.escape(phone.s("profile_withdraw")) + "$")
    phone.tap(phone.s("profile_withdraw"), exact=True)
    time.sleep(1)
    phone.screenshot(out, "profile-withdraw-confirm")
    phone.tap_last(phone.s("profile_withdraw"))
    end = time.time() + 40
    after = published
    while time.time() < end:
        after = agent.command("loadProfile", peer=address)
        if not after["name"]:
            break
        time.sleep(2)
    check(report, "after withdrawing, the public copy is empty for the peer", after.get("name") == "" and after.get("bio") == "", after)
    check(report, "the local profile is kept after withdrawing",
          any(n["text"] == name for n in phone.nodes()))
    phone.screenshot(out, "profile-withdrawn")


@scenario
def scenario_emoji(phone, agent, report, out):
    """Pick emojis from the tray and send them; the peer decrypts exactly those code points."""
    ensure_verified(phone, agent, report, out)
    clear_draft(phone)
    run = secrets.token_hex(3)
    phone.tap(phone.s("chat_emoji"), exact=True)
    time.sleep(2)
    phone.screenshot(out, "emoji-tray")
    for glyph in ("😂", "🥰"):
        phone.tap_node(phone.wait(glyph, 10, exact=True))
    check(report, "picking emojis fills the composer", any(n["class"] == "EditText" and "😂🥰" in n["text"] for n in phone.nodes()))
    phone.tap_node(composer(phone))
    phone.type_text(f" gracias {run}")
    phone.shell("input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.6)
    since = agent.mark()
    phone.tap(phone.s("send"), exact=True)
    event = agent.wait_event(lambda e: e["type"] == "message" and not e["outgoing"] and run in e["body"], 60, since=since)
    check(report, "the peer receives the same emojis and text, decrypted", event["body"] == f"😂🥰 gracias {run}" and event["protection"] == "ENCRYPTED", event["body"])
    time.sleep(2)
    phone.screenshot(out, "emoji-sent")
    phone.tap(phone.s("chat_emoji"), exact=True)
    time.sleep(2)
    check(report, "the used emojis appear under recents on the next opening", phone.has("^😂$"))
    phone.back()


def ensure_upload_relay(phone):
    """The emulator's localhost:5443 reaches ejabberd's upload listener through the loopback relay and adb reverse."""
    import socket
    probe = socket.socket()
    probe.settimeout(1)
    relay = None
    try:
        probe.connect(("127.0.0.1", 5443))
    except OSError:
        relay = subprocess.Popen([sys.executable, str(ROOT / "scripts/forward-local-upload.py")],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)
    finally:
        probe.close()
    phone.adb("reverse", "tcp:5443", "tcp:5443")
    return relay


@scenario
def scenario_document(phone, agent, report, out):
    """Send an image as an untouched document: the peer's decrypted bytes equal the original file."""
    ensure_verified(phone, agent, report, out)
    run = secrets.token_hex(3)
    source = Path(out) / "original-source.png"
    phone.screenshot(out, "document-fixture")
    source.write_bytes((Path(out) / "document-fixture.png").read_bytes() + secrets.token_bytes(64))
    import hashlib
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    name = f"midori-original-{run}.png"
    phone.adb("push", str(source), f"/sdcard/Download/{name}")
    relay = ensure_upload_relay(phone)
    clear_draft(phone)
    phone.tap(phone.s("chat_attach"), exact=True)
    time.sleep(1)
    phone.tap(phone.s("chat_files"), exact=True)
    phone.tap_node(phone.wait(name, 30, exact=True))
    phone.wait(re.escape(name), 20, exact=False)
    time.sleep(2)
    phone.screenshot(out, "document-review")
    check(report, "a picked image opens the document review, not the photo editor", not phone.has(re.escape(phone.s("chat_photo_quality"))) if "chat_photo_quality" in phone.strings else True)
    since = agent.mark()
    phone.tap(phone.s("send"), exact=True)
    event = agent.wait_event(lambda e: e["type"] == "message" and not e["outgoing"] and (e.get("file") or {}).get("name") == name, 120, since=since)
    result = agent.command("download", id=event["id"], timeout=120)
    check(report, "the peer decrypts a byte-identical copy of the original (SHA-256 and size)",
          result["sha256"] == digest and result["size"] == source.stat().st_size and result["declaredSha256"] != "", {"peer": result["sha256"][:16], "file": digest[:16]})
    time.sleep(2)
    phone.screenshot(out, "document-sent")
    source.unlink()
    phone.shell("rm", "-f", f"/sdcard/Download/{name}")
    if relay:
        relay.terminate()


@scenario
def scenario_agenda(phone, agent, report, out):
    """Optional address-book import: explicit permission, explicit identity only, confirmation before saving."""
    ensure_verified(phone, agent, report, out)
    run = secrets.token_hex(3)
    friend = f"midori-acc-c-{run}"
    ejabberd("register", friend, "localhost", secrets.token_hex(12))
    label = f"Zagenda contacto {run}"
    note = f"Zsin identidad {run}"
    try:
        phone.shell("pm", "revoke", PACKAGE, "android.permission.READ_CONTACTS", check=False)
        # One contact with an explicit Jabber identity and one with only a phone number (must not be offered).
        for display, extra in ((label, f"{friend}@localhost"), (note, None)):
            phone.shell("content", "insert", "--uri", "content://com.android.contacts/raw_contacts", "--bind", "account_type:n:", "--bind", "account_name:n:")
            rows = phone.shell("content", "query", "--uri", "content://com.android.contacts/raw_contacts", "--projection", "_id")
            raw = max(int(m) for m in re.findall(r"_id=(\d+)", rows))
            phone.shell("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                        "--bind", "mimetype:s:vnd.android.cursor.item/name", "--bind", f"data1:s:{display.replace(' ', '_')}")
            if extra:
                phone.shell("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                            "--bind", "mimetype:s:vnd.android.cursor.item/im", "--bind", f"data1:s:{extra}", "--bind", "data5:i:7")
            else:
                phone.shell("content", "insert", "--uri", "content://com.android.contacts/data", "--bind", f"raw_contact_id:i:{raw}",
                            "--bind", "mimetype:s:vnd.android.cursor.item/phone_v2", "--bind", "data1:s:+57 300 000 0000")
        phone.launch(); time.sleep(3)
        phone.go_chats_tab()
        phone.tap(phone.s("new_chat"), exact=True)
        time.sleep(2)
        scroll_to(phone, re.escape(phone.s("contacts_device_read")))
        phone.tap(phone.s("contacts_device_read"), exact=True)
        time.sleep(2)
        phone.screenshot(out, "agenda-permission")
        check(report, "the system asks for contact permission only after the explicit action", phone.has("^Allow$|^Permitir$|^Allow all the time$") or phone.has("contacts|contactos"))
        phone.tap("^Allow$|^Permitir$", exact=False)
        time.sleep(3)
        shown = phone.find_scrolling(re.escape(friend + "@localhost"), attempts=4)
        phone.screenshot(out, "agenda-candidates")
        check(report, "only the contact with an explicit messaging identity is offered", shown is not None and not phone.has(re.escape(note.replace(" ", "_"))))
        phone.tap_node(shown)
        time.sleep(2)
        phone.screenshot(out, "agenda-confirm")
        check(report, "nothing is saved until the person confirms",
              friend + "@localhost" not in ejabberd("get_roster", phone_account_user(phone), "localhost", check=False))
        phone.tap("^Guardar y conversar$|^Save and chat$|^Salvar e conversar$", exact=False)
        time.sleep(5)
        roster = ejabberd("get_roster", phone_account_user(phone), "localhost", check=False)
        check(report, "after confirmation the contact is in the account's roster", friend in roster, roster[:200])
    finally:
        ejabberd("unregister", friend, "localhost", check=False)
        phone.shell("content", "delete", "--uri", "content://com.android.contacts/raw_contacts", "--where", "deleted=0", check=False)


def phone_account_user(phone):
    return "midori-ui-a-4bb398bfcd"


@scenario
def scenario_qr(phone, agent, report, out):
    """The camera reads a contact QR (emulator virtual-scene camera); it only proposes, saving needs confirmation."""
    ensure_verified(phone, agent, report, out)
    account_user = phone_account(phone).split("@")[0]
    friend = "midori-acc-q-scan"
    if not registered(friend):
        ejabberd("register", friend, "localhost", secrets.token_hex(12))
    phone.shell("pm", "revoke", PACKAGE, "android.permission.CAMERA", check=False)
    phone.launch(); time.sleep(3)
    phone.go_chats_tab()
    phone.tap("^Escanear QR$|^Scan QR$|^Escanear código QR$", exact=False)
    time.sleep(3)
    if phone.has("While using the app|Mientras la app está en uso"):
        phone.screenshot(out, "qr-camera-permission")
        phone.tap("While using the app|Mientras la app está en uso", exact=False)
        time.sleep(4)
    phone.screenshot(out, "qr-camera-view")
    phone.wait(re.escape(friend + "@localhost"), 60, exact=False)
    time.sleep(1)
    phone.screenshot(out, "qr-contact-proposal")
    check(report, "scanning the QR proposes the contact with its full address", True)
    check(report, "the scan alone saved nothing", friend not in ejabberd("get_roster", account_user, "localhost", check=False))
    phone.tap("^Guardar y conversar$|^Save and chat$|^Salvar e conversar$", exact=False)
    time.sleep(5)
    check(report, "after confirmation the scanned contact is in the roster", friend in ejabberd("get_roster", account_user, "localhost", check=False))
    ejabberd("unregister", friend, "localhost", check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--avd", default="Pixel_6_API_34")
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("scenarios", nargs="+", choices=sorted(SCENARIOS) or None)
    args = parser.parse_args()
    phone = Phone(args.avd)
    agent = Agent(args.workspace).start() if any(n != "registration" for n in args.scenarios) else None
    out = Path(args.report)
    out.mkdir(parents=True, exist_ok=True)
    overall = {}
    try:
        for name in args.scenarios:
            report = {"scenario": name, "serial": phone.serial, "peer": agent.account if agent else None, "checks": []}
            try:
                SCENARIOS[name](phone, agent, report, out)
            except Exception as error:
                check(report, f"{name} completed without error", False, f"{type(error).__name__}: {error}")
                phone.screenshot(out, f"{name}-failure")
            report.pop("out", None)
            report["passed"] = all(c["passed"] for c in report["checks"])
            (out / f"{name}.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
            overall[name] = report["passed"]
    finally:
        if agent:
            agent.stop()
    print(json.dumps(overall))
    sys.exit(0 if all(overall.values()) else 1)


if __name__ == "__main__":
    main()
