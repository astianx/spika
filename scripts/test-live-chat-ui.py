#!/usr/bin/env python3
"""Opt-in acceptance against visible Pixel/MainActivity and the real local ejabberd.

Creates actual lab accounts, or only a new companion for --existing-phone-account.
Keeps account A and its messages after acceptance; B's independent JVM state is encrypted in --workspace.
Never clears app data or prints credentials.
Build/install the debug and androidTest APKs first. The emulator must already be
running with its visible window. This runner does not start a headless emulator.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import ssl
import subprocess
import sys
import time


PACKAGE = "org.astianspika.android"
PRIVATE_FILES = (
    "files/chat-live-ui.json",
    "files/chat-live-ui-phone.json",
    "files/chat-live-ui-peer-trusted",
)


def private_write(path, data):
    path = Path(path)
    temporary = path.with_name("." + path.name + "." + secrets.token_hex(5) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.fchmod(descriptor, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data if isinstance(data, bytes) else data.encode())
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def command(arguments, *, data=None, timeout=30, label="Laboratory command"):
    result = subprocess.run(arguments, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        # Commands may contain an account password; never echo argv/stdout/stderr.
        raise RuntimeError(f"{label} failed (exit {result.returncode}); no credentials were logged")
    return result.stdout


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Resolved serial of the visible Pixel 6")
    parser.add_argument("--avd", required=True, help="Expected AVD name, checked before any device action")
    parser.add_argument("--pin", required=True, help="Previously inspected public SHA-256 certificate fingerprint")
    parser.add_argument("--workspace", required=True, type=Path, help="Private directory retained for account/peer continuity")
    parser.add_argument("--container", default="ejabberd-docker")
    parser.add_argument("--existing-phone-account", help="Retain this already-signed-in lab account; provision only a new companion if workspace is new")
    parser.add_argument("--rich-chat", action="store_true", help="Also exercise real voice/video/photo capture and encrypted transfer")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.pin):
        parser.error("--pin must be the explicitly inspected 64-character SHA-256 fingerprint")
    args.pin = args.pin.lower()
    if args.existing_phone_account and not re.fullmatch(r"midori-ui-a-[a-z0-9]+@localhost", args.existing_phone_account):
        parser.error("--existing-phone-account must identify a previously authorized local UI lab account")
    repository = Path(__file__).resolve().parents[1]
    workspace = args.workspace.expanduser().resolve()
    workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
    workspace.chmod(0o700)
    adb = ["adb", "-s", args.serial]
    actual_avd = command(adb + ["emu", "avd", "name"], label="AVD identity read").decode().splitlines()[0].strip()
    if actual_avd != args.avd or "pixel" not in actual_avd.lower() or "6" not in actual_avd:
        raise RuntimeError("The selected serial does not match the explicitly selected Pixel 6 AVD")
    command(adb + ["shell", "run-as", PACKAGE, "true"], label="Debug app access check")

    # Read only the public leaf certificate and compare it to the supplied, inspected
    # fingerprint. Never learn a replacement certificate or read a server private key.
    observed = subprocess.run(["openssl", "s_client", "-connect", "127.0.0.1:5222", "-starttls", "xmpp",
                               "-xmpphost", "localhost", "-servername", "localhost", "-showcerts"],
                              input=b"", stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
    match = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", observed.stdout.decode(), re.S)
    observed_pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(match.group())).hexdigest() if match else None
    if observed_pin != args.pin:
        raise RuntimeError("The public server certificate does not match the independently inspected pin")

    credential_file = workspace / "accounts.json"
    if credential_file.exists():
        config = json.loads(credential_file.read_text())
        if config.get("pin") != args.pin:
            raise RuntimeError("Stored laboratory pin differs; refusing to replace trust")
        for key, prefix in (("first", "midori-ui-a-"), ("second", "midori-ui-b-")):
            if not re.fullmatch(prefix + r"[a-z0-9]+@localhost", config.get(key, "")):
                raise RuntimeError("Workspace does not contain this runner's lab accounts")
    else:
        suffix = secrets.token_hex(5)
        config = {"first": args.existing_phone_account or f"midori-ui-a-{suffix}@localhost", "second": f"midori-ui-b-{suffix}@localhost",
                  "password": secrets.token_urlsafe(32), "pin": args.pin,
                  "requireExistingPhoneAccount": bool(args.existing_phone_account)}
        registered = []
        try:
            for key in (("second",) if args.existing_phone_account else ("first", "second")):
                username = config[key].split("@", 1)[0]
                command(["docker", "exec", args.container, "bin/ejabberdctl", "register", username,
                         "localhost", config["password"]], label="New lab account registration")
                registered.append(username)
            private_write(credential_file, json.dumps(config))
        except BaseException:
            for username in registered:
                subprocess.run(["docker", "exec", args.container, "bin/ejabberdctl", "unregister", username, "localhost"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            raise
    if args.existing_phone_account and config["first"] != args.existing_phone_account:
        raise RuntimeError("Workspace belongs to a different phone account; refusing to replace it")
    config["richChat"] = args.rich_chat
    run_id = secrets.token_hex(3)
    config["previousMessage"] = config.get("message", "")
    config["message"] = f"Prueba real desde el Pixel 6 · {run_id}"
    config["reply"] = f"Recibido en ejabberd. La conversación funciona · {run_id}"
    private_write(credential_file, json.dumps(config))
    for name in ("peer-ready.json", "phone-ready.json", "peer-trusted", "peer-report.json", "finish"):
        (workspace / name).unlink(missing_ok=True)

    def device_write(filename, content):
        if filename not in PRIVATE_FILES:
            raise RuntimeError("Refusing to write outside the runner's explicit device file list")
        remote = f"run-as {shlex.quote(PACKAGE)} sh -c {shlex.quote('umask 077; cat > ' + filename)}"
        command(adb + ["shell", remote], data=content, label="Private test configuration transfer")

    def device_read(filename):
        result = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", filename],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15)
        return result.stdout if result.returncode == 0 else None

    def clean_device_inputs():
        command(adb + ["shell", "run-as", PACKAGE, "rm", "-f", *PRIVATE_FILES], label="Only runner input cleanup")

    init = workspace / "peer.init.gradle"
    private_write(init, """allprojects {
    tasks.withType(Test).configureEach {
        if (name == 'testDebugUnitTest') {
            systemProperty 'midori.liveUi.fixture', System.getenv('MIDORI_LIVE_UI_FIXTURE')
            // The laboratory relay listens on IPv4 loopback; TLS still verifies localhost.
            systemProperty 'java.net.preferIPv4Stack', 'true'
            outputs.upToDateWhen { false }
        }
    }
}
""")
    environment = dict(os.environ, MIDORI_LIVE_UI_FIXTURE=str(credential_file))
    peer = None
    phone = None
    account_bound = False
    clean_device_inputs()
    try:
        with (workspace / "peer.log").open("wb") as peer_log, (workspace / "device.log").open("wb") as device_log:
            peer = subprocess.Popen([str(repository / "gradlew"), ":app:testDebugUnitTest", "--offline",
                                     "--tests", "org.astianx.midori.one.chat.LiveUiPeerTest", "--init-script", str(init)],
                                    cwd=repository, env=environment, stdout=peer_log, stderr=subprocess.STDOUT)
            print("Real JVM peer starting; accounts and encrypted peer state retained privately.", flush=True)
            deadline = time.monotonic() + 300
            peer_ready = None
            while peer_ready is None:
                if peer.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Real JVM peer did not become ready; inspect the private peer.log")
                try:
                    peer_ready = json.loads((workspace / "peer-ready.json").read_text())
                except (FileNotFoundError, json.JSONDecodeError):
                    pass
                time.sleep(0.5)
            if peer_ready["account"] != config["second"]:
                raise RuntimeError("Companion account mismatch")
            device_config = dict(config, peerFingerprint=peer_ready["fingerprint"])
            device_write("files/chat-live-ui.json", json.dumps(device_config).encode())
            phone = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                                            "org.astianx.midori.one.chat.LiveChatUiDeviceTest",
                                            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"],
                                     stdout=device_log, stderr=subprocess.STDOUT)
            print("Real MainActivity is running on the visible Pixel 6; waiting for encrypted round trip.", flush=True)
            phone_published = False
            trusted = False
            deadline = time.monotonic() + (900 if args.rich_chat else 360)
            while phone.poll() is None:
                if not phone_published:
                    metadata = device_read("files/chat-live-ui-phone.json")
                    if metadata:
                        try:
                            payload = json.loads(metadata)
                        except json.JSONDecodeError:
                            time.sleep(0.1)
                            continue
                        if payload.get("account") != config["first"] or not payload.get("fingerprint"):
                            raise RuntimeError("Phone fingerprint metadata failed account binding")
                        private_write(workspace / "phone-ready.json", metadata)
                        phone_published = True
                        account_bound = True
                if not trusted and (workspace / "peer-trusted").exists():
                    device_write("files/chat-live-ui-peer-trusted", b"verified")
                    trusted = True
                if peer.poll() is not None:
                    raise RuntimeError("JVM peer stopped before UI acceptance finished; inspect private logs")
                if time.monotonic() > deadline:
                    raise RuntimeError("Live UI acceptance timed out; inspect the visible Pixel and private logs")
                time.sleep(0.5)
            private_write(workspace / "finish", "finished")
            peer.wait(timeout=60)
        device_output = (workspace / "device.log").read_text(errors="replace")
        if phone.returncode != 0 or "OK (1 test)" not in device_output or peer.returncode != 0:
            raise RuntimeError("Live UI acceptance failed; inspect private device.log / peer.log")
        # Intent launches are a separate test: ActivityScenario must first destroy its
        # owned activity, so an Android task switch cannot invalidate that lifecycle test.
        launch_output = command(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
            "org.astianx.midori.one.chat.AppLaunchDeviceTest",
            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120,
            label="App intent acceptance")
        private_write(workspace / "launch.log", launch_output)
        if b"OK (1 test)" not in launch_output:
            raise RuntimeError("App intent acceptance failed; inspect private launch.log")
        evidence = workspace / "evidence"
        evidence.mkdir(exist_ok=True, mode=0o700)
        for filename in ("conversation.png", "chats.png", "settings.png", "contact-link-review.png", "notification-conversation.png", "own-contact-qr.png", "device-report.json", "launch-report.json"):
            content = device_read("cache/live-ui/" + filename)
            if not content:
                raise RuntimeError("A required real UI evidence artifact is missing")
            private_write(evidence / filename, content)
        if args.rich_chat:
            for filename in ("emoji-picker", "voice-recording", "voice-paused", "voice-sent", "new-chat-contacts",
                             "video-note-camera", "video-note-paused", "video-note-review", "video-note-delivered",
                             "video-note-playing", "video-note-playback-paused",
                             "photo-hd-review", "photo-hd-delivered", "contact-profile-real", "contact-local-details",
                             "own-contact-qr", "own-profile-publication-control", "contact-link-review"):
                content = device_read("cache/live-ui/" + filename + ".png")
                if not content:
                    raise RuntimeError("A required rich-chat evidence artifact is missing: " + filename)
                private_write(evidence / (filename + ".png"), content)
        private_write(evidence / "peer-report.json", (workspace / "peer-report.json").read_bytes())
        print(json.dumps({"status": "passed", "serial": args.serial, "avd": actual_avd,
                          "realEjabberd": True, "realMainActivity": True, "accountRetained": config["first"],
                          "peerRetained": config["second"], "evidence": str(evidence)}, indent=2), flush=True)
    finally:
        private_write(workspace / "finish", "finished")
        if phone is not None and phone.poll() is None:
            # Cancelling the adb client does not cancel instrumentation. Stop the tested
            # process on timeout, preserving all app data, then reopen it below.
            phone.terminate()
            command(adb + ["shell", "am", "force-stop", PACKAGE], label="Cancel only the timed-out instrumentation process")
        if peer is not None and peer.poll() is None:
            peer.terminate()
        clean_device_inputs()
        launch = adb + ["shell", "am", "start", "-n", f"{PACKAGE}/org.astianx.midori.one.MainActivity"]
        if account_bound:
            launch += ["--es", "chat_peer", config["second"]]
        command(launch,
                label="Reopen the normal app with its retained real conversation")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired, OSError, ValueError) as error:
        # TimeoutExpired.__str__ includes the command, potentially including a password.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(f"Live UI acceptance stopped: {message}", file=sys.stderr)
        sys.exit(1)
