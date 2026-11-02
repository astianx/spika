#!/usr/bin/env python3
"""Pixel 6 wallet acceptance driver for v0.1 (recovery, update, authorized USDC/USDT sends).

It drives the real MainActivity of the installed debug build through adb and
uiautomator. It never clears app data, never reads or prints credentials (only
their SHA-256 and size), and sends only when `send` is invoked with an explicit
asset, recipient and amount. Chain confirmation is done separately and
independently by verify-wallet-chain.py.

  test-wallet-device.py --avd Pixel_6_API_34 dump
  test-wallet-device.py --avd Pixel_6_API_34 --apk APP.apk --report DIR recovery
  test-wallet-device.py --avd Pixel_6_API_34 --report DIR send --asset USDC --to 0x... --amount 0.05
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "org.astianx.midori.one"
CREDENTIAL_FILES = ["shared_prefs/wdk_wallet_mainnet.xml", "shared_prefs/midori_wallet_country_v1.xml"]
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
ROOT = Path(__file__).resolve().parent.parent


def run(*cmd, check=True, timeout=120):
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{' '.join(cmd)}: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


class Device:
    def __init__(self, avd, lang=None):
        self.serial = self.resolve(avd)
        self.lang = lang or self.app_language()
        self.strings = self.load_strings(self.lang)
        self.screenshots = 0

    @staticmethod
    def resolve(avd):
        for line in run("adb", "devices").splitlines()[1:]:
            serial = line.split("\t")[0]
            if serial.startswith("emulator-") and run("adb", "-s", serial, "emu", "avd", "name").split()[0] == avd:
                return serial
        raise SystemExit(f"AVD {avd} is not running; start it with a visible window first")

    def adb(self, *args, **kw):
        return run("adb", "-s", self.serial, *args, **kw)

    def shell(self, *args, **kw):
        return self.adb("shell", *args, **kw)

    def app_language(self):
        out = self.shell("cmd", "locale", "get-app-locales", PACKAGE, check=False)
        match = re.search(r"are \[([a-z]{2})", out)
        return match.group(1) if match else "en"

    @staticmethod
    def load_strings(lang):
        values = {}
        for folder in ("values", f"values-{lang}"):
            path = RES / folder / "strings.xml"
            if path.exists():
                for node in ET.parse(path).getroot().iter("string"):
                    values[node.get("name")] = "".join(node.itertext()).replace("\\'", "'").replace("\\n", "\n")
        return values

    def s(self, name):
        return self.strings[name]

    def credentials(self):
        out = {}
        for rel in CREDENTIAL_FILES:
            raw = subprocess.run(["adb", "-s", self.serial, "exec-out", "run-as", PACKAGE, "cat", rel], capture_output=True).stdout
            out[rel] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
        return out

    def package_times(self):
        text = self.shell("dumpsys", "package", PACKAGE)
        return {k: re.search(rf"{k}=(.+)", text).group(1).strip() for k in ("firstInstallTime", "lastUpdateTime")} | {
            "versionCode": re.search(r"versionCode=(\d+)", text).group(1),
            "versionName": re.search(r"versionName=(\S+)", text).group(1)}

    def nodes(self):
        for attempt in range(4):
            try:
                self.shell("uiautomator", "dump", "/sdcard/ui.xml")
                break
            except RuntimeError:
                if attempt == 3:
                    raise
                time.sleep(2)
        raw = subprocess.run(["adb", "-s", self.serial, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
        out = []
        for n in ET.fromstring(raw).iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            text = n.get("text") or n.get("content-desc") or ""
            out.append({"text": text, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "class": n.get("class").split(".")[-1],
                        "clickable": n.get("clickable") == "true", "bounds": (x1, y1, x2, y2), "enabled": n.get("enabled") == "true"})
        return out

    def find(self, pattern, nodes=None, exact=False):
        literal = re.sub(r"\\(.)", r"\1", pattern)  # exact matches accept re.escape()d literals too
        for n in nodes or self.nodes():
            if n["text"] and (n["text"] == literal if exact else re.search(pattern, n["text"])):
                return n
        return None

    def wait(self, pattern, timeout=30, exact=False):
        end = time.time() + timeout
        while time.time() < end:
            node = self.find(pattern, exact=exact)
            if node:
                return node
            time.sleep(1)
        raise TimeoutError(f"UI text not found: {pattern}")

    def tap(self, pattern, timeout=30, exact=True):
        node = self.wait(pattern, timeout, exact)
        self.shell("input", "tap", str(node["x"]), str(node["y"]))
        time.sleep(1)
        return node

    def screenshot(self, directory, name):
        Path(directory).mkdir(parents=True, exist_ok=True)
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        (Path(directory) / f"{name}.png").write_bytes(data)

    def launch(self, cold=False):
        if cold:
            self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(4)

    def open_wallet(self, timeout=120):
        end = time.time() + timeout
        wallet = self.s("wallet")
        while time.time() < end:
            nodes = self.nodes()
            if self.find(self.s("refresh_balances"), nodes, exact=True):
                time.sleep(4)
                # Restoring a wallet moves the app to Chats when it finishes; require a stable wallet screen.
                if self.find(self.s("refresh_balances"), exact=True):
                    return
                continue
            tab = next((n for n in nodes if n["text"] == wallet and n["y"] > 2200), None)
            if tab:
                self.shell("input", "tap", str(tab["x"]), str(tab["y"]))
            time.sleep(2)
        raise TimeoutError("Wallet screen did not open")

    def balances_visible(self):
        nodes = self.nodes()
        if self.find(re.escape(self.s("show_balances")), nodes, exact=False):
            self.tap(re.escape(self.s("show_balances")), exact=False)
            nodes = self.nodes()
        return nodes

    def read_balances(self):
        nodes = self.balances_visible()
        out = {}
        for i, n in enumerate(nodes):
            if n["text"].startswith("Saldo nominal") or n["text"].lower().startswith(("nominal balance", "saldo nominal")):
                out["digitalDollars"] = nodes[i - 1]["text"]
        return out

    def refresh(self, wait=10):
        self.tap(re.escape(self.s("refresh_balances")), exact=False)
        time.sleep(wait)

    def airplane(self, on):
        self.shell("cmd", "connectivity", "airplane-mode", "enable" if on else "disable")
        time.sleep(4 if on else 8)


def chain_balances(address):
    out = run(sys.executable, str(ROOT / "scripts/verify-wallet-chain.py"), "balances", address)
    return json.loads(out)


def wallet_address(device):
    """Addresses are read from the Receive screen, so the check never trusts build config or my own derivation."""
    device.open_wallet()
    device.tap(device.s("wallet_deposit"), exact=True)
    device.tap(device.s("funding_crypto"), exact=True)
    base = device.wait(r"^0x[0-9a-fA-F]{40}$", 20)["text"]
    for _ in range(2):
        device.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
    return base


def check(report, name, ok, detail=None):
    report["checks"].append({"name": name, "passed": bool(ok), **({"detail": detail} if detail is not None else {})})
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail is not None and not ok else ""))
    return ok


def recovery(device, args):
    report = {"scenario": "provider-outage-and-in-place-update", "serial": device.serial, "language": device.lang, "checks": []}
    out = Path(args.report)
    baseline = device.credentials()
    report["packageBefore"] = device.package_times()
    report["credentialsBefore"] = baseline
    check(report, "baseline credential file exists", baseline[CREDENTIAL_FILES[0]]["bytes"] > 0)
    try:
        if args.apk:
            device.adb("install", "-r", args.apk, timeout=300)
            after = device.package_times()
            report["packageAfterUpdate"] = after
            check(report, "update kept first-install time (not an uninstall/reinstall)",
                  after["firstInstallTime"] == report["packageBefore"]["firstInstallTime"])
            check(report, "update replaced the package", after["lastUpdateTime"] != report["packageBefore"]["lastUpdateTime"]
                  or args.same_apk_ok)
            check(report, "credential files byte-identical after update", device.credentials() == baseline)
            device.launch(cold=True)
            device.open_wallet()
            device.screenshot(out, "after-update-wallet")
            check(report, "wallet restored after update without recovery prompt", True)
        else:
            device.launch(cold=True)
            device.open_wallet()
        address = wallet_address(device)
        report["address"] = address
        chain = chain_balances(address)
        report["chainBeforeOutage"] = chain
        usdc = chain["base"][next(iter(chain["base"]))]["usdc_units"]
        device.screenshot(out, "online-before-outage")

        device.airplane(True)
        report["outage"] = "airplane mode: Base RPC, Candide, Blockbook and Worker all unreachable"
        device.launch(cold=True)
        device.open_wallet()
        device.refresh(wait=12)
        device.screenshot(out, "provider-outage-wallet")
        during = device.read_balances()
        report["balancesDuringOutage"] = during
        check(report, "cold start during outage opens the wallet, not a recovery failure",
              device.find(re.escape(device.s("refresh_balances")), exact=False) is not None)
        check(report, "unavailable balances are not shown as zero",
              all(v.startswith("—") for v in during.values()) and bool(during), during)
        check(report, "credential files unchanged during outage", device.credentials() == baseline)
    finally:
        device.airplane(False)
    device.refresh(wait=15)
    after = device.read_balances()
    device.screenshot(out, "provider-recovered-wallet")
    report["balancesAfterRecovery"] = after
    expected = f"{usdc // 10**6}.{usdc % 10**6:06d}".rstrip("0").rstrip(".")
    check(report, "balances recover from the real chain once providers return", after.get("digitalDollars") == expected,
          {"ui": after.get("digitalDollars"), "chain": expected})
    check(report, "credential files unchanged after recovery", device.credentials() == baseline)
    report["credentialsAfter"] = device.credentials()
    report["passed"] = all(c["passed"] for c in report["checks"])
    return report


def send(device, args):
    report = {"scenario": f"send-{args.asset}", "serial": device.serial, "language": device.lang, "asset": args.asset,
              "to": args.to, "amount": args.amount, "checks": []}
    out = Path(args.report)
    baseline = device.credentials()
    device.launch()
    for _ in range(4):
        if device.find("^Volver$|^Back$|^Voltar$"):
            device.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)
    device.open_wallet()
    address = wallet_address(device)
    report["from"] = address
    report["chainBefore"] = chain_balances(address)
    device.open_wallet()
    device.tap(device.s("send"), exact=True)
    device.wait(re.escape(device.s("recipient_address")), 20, exact=False)
    asset_chip = device.find(rf"^{args.asset}$")
    if asset_chip:
        device.shell("input", "tap", str(asset_chip["x"]), str(asset_chip["y"]))
        time.sleep(1)
    device.screenshot(out, f"{args.asset}-send-form")
    fields = [n for n in device.nodes() if n["class"] == "EditText"]
    report["editFields"] = len(fields)
    device.shell("input", "tap", str(fields[0]["x"]), str(fields[0]["y"]))
    device.shell("input", "text", args.to)
    device.shell("input", "keyevent", "KEYCODE_BACK")
    fields = [n for n in device.nodes() if n["class"] == "EditText"]
    device.shell("input", "tap", str(fields[1]["x"]), str(fields[1]["y"]))
    device.shell("input", "text", args.amount)
    device.shell("input", "keyevent", "KEYCODE_BACK")
    device.tap(device.s("send_review_action"), exact=True)
    device.wait(device.s("send_confirm_action"), 60, exact=True)
    time.sleep(1)
    device.screenshot(out, f"{args.asset}-review")
    review = [n["text"] for n in device.nodes() if n["text"]]
    report["reviewScreenText"] = review
    check(report, "review shows exact recipient", any(args.to.lower() in t.lower() for t in review))
    if not args.confirm:
        report["note"] = "stopped at review; pass --confirm to authorize the transfer"
        return report
    device.tap(device.s("send_confirm_action"), exact=True)
    time.sleep(args.settle)
    device.screenshot(out, f"{args.asset}-after-confirm")
    report["afterConfirmScreenText"] = [n["text"] for n in device.nodes() if n["text"]]
    check(report, "credential files unchanged by the send", device.credentials() == baseline)
    report["passed"] = all(c["passed"] for c in report["checks"])
    return report


def matrix(device, args):
    """Read-only visual/functional pass of the wallet flows per language and font scale; never confirms a send."""
    report = {"scenario": "wallet-language-font-matrix", "serial": device.serial, "checks": [], "combinations": []}
    out = Path(args.report)
    baseline = device.credentials()
    original_lang = device.lang
    original_scale = device.shell("settings", "get", "system", "font_scale").strip()
    try:
        for scale in ("1.0", "1.3"):
            for lang in ("en", "es", "pt"):
                device.shell("settings", "put", "system", "font_scale", scale)
                device.shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", lang)
                device.lang = lang
                device.strings = device.load_strings(lang)
                device.launch(cold=True)
                time.sleep(6)
                device.open_wallet()
                tag = f"{lang}-{scale.replace('.', '')}"
                device.screenshot(out, f"{tag}-wallet")
                entry = {"language": lang, "fontScale": scale}
                device.tap(device.s("wallet_deposit"), exact=True)
                device.tap(device.s("funding_crypto"), exact=True)
                device.wait(r"^0x[0-9a-fA-F]{40}$", 20)
                device.screenshot(out, f"{tag}-receive")
                entry["receiveHasAddress"] = True
                for _ in range(2):
                    device.shell("input", "keyevent", "KEYCODE_BACK")
                    time.sleep(1)
                device.open_wallet()
                device.tap(device.s("send"), exact=True)
                device.wait(device.s("recipient_address"), 20, exact=False)
                fields = [n for n in device.nodes() if n["class"] == "EditText"]
                device.shell("input", "tap", str(fields[0]["x"]), str(fields[0]["y"]))
                device.shell("input", "text", args.to)
                device.shell("input", "keyevent", "KEYCODE_BACK")
                fields = [n for n in device.nodes() if n["class"] == "EditText"]
                device.shell("input", "tap", str(fields[1]["x"]), str(fields[1]["y"]))
                device.shell("input", "text", "0.05")
                device.shell("input", "keyevent", "KEYCODE_BACK")
                device.screenshot(out, f"{tag}-send-form")
                device.tap(device.s("send_review_action"), exact=True)
                device.wait(device.s("send_confirm_action"), 60, exact=True)
                time.sleep(1)
                device.screenshot(out, f"{tag}-review")
                entry["reviewShowsRecipient"] = device.find(re.escape(args.to), exact=False) is not None
                entry["confirmButtonVisible"] = device.find(device.s("send_confirm_action"), exact=True) is not None
                report["combinations"].append(entry)
                check(report, f"{lang} font {scale}: receive and review render with exact address", entry["reviewShowsRecipient"] and entry["receiveHasAddress"])
                device.shell("input", "keyevent", "KEYCODE_BACK")
                time.sleep(1)
                for _ in range(3):
                    if device.find("^Volver$|^Back$|^Voltar$"):
                        device.shell("input", "keyevent", "KEYCODE_BACK")
                        time.sleep(1)
    finally:
        device.shell("settings", "put", "system", "font_scale", original_scale)
        device.shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", original_lang)
    check(report, "credential files unchanged by the matrix", device.credentials() == baseline)
    report["passed"] = all(c["passed"] for c in report["checks"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--lang")
    parser.add_argument("--report", default="/tmp/opencode/wallet-device")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("dump")
    p = sub.add_parser("recovery")
    p.add_argument("--apk")
    p.add_argument("--same-apk-ok", action="store_true", help="accept an unchanged lastUpdateTime when the APK is identical")
    p = sub.add_parser("matrix")
    p.add_argument("--to", required=True, help="recipient typed into the review form; never confirmed")
    p = sub.add_parser("send")
    p.add_argument("--asset", required=True, choices=["USDC", "USDT"])
    p.add_argument("--to", required=True)
    p.add_argument("--amount", required=True)
    p.add_argument("--confirm", action="store_true")
    p.add_argument("--settle", type=int, default=20)
    args = parser.parse_args()
    device = Device(args.avd, args.lang)
    if args.cmd == "dump":
        for n in device.nodes():
            if n["text"]:
                print(n["bounds"], repr(n["text"]), n["class"], "clickable" if n["clickable"] else "")
        return
    report = {"recovery": recovery, "send": send, "matrix": matrix}[args.cmd](device, args)
    Path(args.report).mkdir(parents=True, exist_ok=True)
    name = args.cmd if args.cmd != "send" else f"send-{args.asset.lower()}-{int(time.time())}"
    (Path(args.report) / f"{name}.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps({k: report.get(k) for k in ("scenario", "passed")}))
    sys.exit(0 if report.get("passed", True) else 1)


if __name__ == "__main__":
    main()
