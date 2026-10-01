#!/usr/bin/env python3
"""Tiny adb UI driver for e2e runs (00-plan §6.3), on uiautomator dumps.

  ui.py dump                 visible nodes with text or content description
  ui.py tap <text> [n]       taps the n-th node whose text or description equals <text> (waits WAIT s, default 10)
  ui.py wait <text> [secs]   waits until a node's text or description contains <text> (default 15 s)
  ui.py type <text>          types text (spaces and shell characters such as ! survive)
  ui.py key <KEYCODE …>      sends key events
  ui.py del <n>              moves to the end of the field and deletes n characters
  ui.py shot <file.png>      screenshot (0 bytes while a FLAG_SECURE screen is showing)

adb: $ADB, else $ANDROID_HOME / $ANDROID_SDK_ROOT / the default SDK folder, else PATH.
Device: $SERIAL when several are attached.
"""
import os
import re
import shlex
import shutil
import subprocess
import sys
import time


def adb_path():
    candidates = [os.environ.get("ADB")]
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk"), os.path.expanduser("~/Android/Sdk")):
        if root:
            candidates.append(os.path.join(root, "platform-tools", "adb"))
    for candidate in candidates:
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found: set ADB or ANDROID_HOME")
    return found


ADB = adb_path()
SERIAL = os.environ.get("SERIAL")


def adb_args(*args):
    return [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)


def adb(*args):
    return subprocess.run(adb_args(*args), capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    out = []
    for match in re.finditer(r"<node [^>]*>", xml):
        node = match.group(0)

        def attr(name):
            found = re.search(name + r'="([^"]*)"', node)
            return found.group(1) if found else ""

        bounds = [int(x) for x in re.findall(r"\d+", attr("bounds"))]
        out.append((attr("text"), attr("content-desc"), attr("clickable"), attr("enabled"), attr("focused"), bounds))
    return out


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    cmd = argv[1]
    if cmd == "dump":
        for text, desc, clickable, enabled, focused, bounds in nodes():
            if text or desc:
                print(f"{text!r:45} desc={desc!r:40} click={clickable} en={enabled} foc={focused} {bounds}")
    elif cmd == "tap":
        target = argv[2]
        index = int(argv[3]) if len(argv) > 3 else 0
        deadline = time.time() + float(os.environ.get("WAIT", "10"))
        while True:
            hits = [b for t, d, _, _, _, b in nodes() if t == target or d == target]
            if len(hits) > index or time.time() > deadline:
                break
            time.sleep(0.5)
        if len(hits) <= index:
            sys.exit(f"not found: {target}")
        b = hits[index]
        x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        adb("shell", "input", "tap", str(x), str(y))
        print("tapped", target, x, y)
    elif cmd == "wait":
        deadline = time.time() + float(argv[3] if len(argv) > 3 else 15)
        while time.time() < deadline:
            if any(argv[2] in (t + "|" + d) for t, d, _, _, _, _ in nodes()):
                print("found", argv[2])
                return
            time.sleep(0.5)
        sys.exit(f"timeout waiting for {argv[2]}")
    elif cmd == "type":
        # `input text` drops `!` and what follows unless the shell sees it quoted; %s is a space.
        adb("shell", "input text " + shlex.quote(argv[2].replace(" ", "%s")))
    elif cmd == "key":
        adb("shell", "input", "keyevent", *argv[2:])
    elif cmd == "del":
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * int(argv[2])))
    elif cmd == "shot":
        with open(argv[2], "wb") as fh:
            fh.write(subprocess.run(adb_args("exec-out", "screencap", "-p"), capture_output=True).stdout)
        print(argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
