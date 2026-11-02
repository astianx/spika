#!/usr/bin/env python3
"""Temporarily let the local ejabberd lab accept in-band registration (XEP-0077) from the emulator.

The emulator reaches the lab through Docker's published port, so ejabberd sees the Docker bridge address instead
of loopback and mod_register's `ip_access: trusted_network` refuses the app ("Access denied by service policy").
`enable` saves the original ejabberd.yml next to it and sets mod_register's `ip_access: all`; `revert` restores that
exact file and reloads. Lab only: never use `all` on a server reachable from the internet.

  configure-chat-lab-registration.py enable [--container ejabberd-docker]
  configure-chat-lab-registration.py revert [--container ejabberd-docker]
"""
import argparse
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

CONF = "/opt/ejabberd/conf/ejabberd.yml"
BACKUP = "/opt/ejabberd/conf/ejabberd.yml.before-registration-lab"


def docker(container, *args, check=True):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check, timeout=90)


def exec_(container, *args):
    return docker(container, "exec", container, *args).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["enable", "revert"])
    parser.add_argument("--container", default="ejabberd-docker")
    args = parser.parse_args()
    c = args.container
    if args.action == "revert":
        exec_(c, "cp", BACKUP, CONF)
        exec_(c, "bin/ejabberdctl", "reload_config")
        exec_(c, "bin/ejabberdctl", "restart_module", "localhost", "mod_register")
        print("Original ejabberd.yml restored and reloaded.")
        return
    original = exec_(c, "cat", CONF)
    if "ip_access: all" in original:
        print("Already enabled.")
        return
    if "mod_register:" not in original or "    ip_access: trusted_network" not in original:
        sys.exit("Unexpected mod_register configuration; refusing to edit.")
    exec_(c, "cp", CONF, BACKUP)
    edited = original.replace("    ip_access: trusted_network", "    ip_access: all", 1)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "ejabberd.yml"
        path.write_text(edited)
        docker(c, "cp", str(path), f"{c}:{CONF}")
    exec_(c, "bin/ejabberdctl", "reload_config")
    exec_(c, "bin/ejabberdctl", "restart_module", "localhost", "mod_register")
    print("In-band registration enabled; original saved as", BACKUP, "sha256", hashlib.sha256(original.encode()).hexdigest()[:16])


if __name__ == "__main__":
    main()
