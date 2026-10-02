#!/usr/bin/env python3
"""Tiny adb UI driver for e2e runs (00-plan §6.3), on uiautomator dumps.

  ui.py dump                 visible nodes with text or content description
  ui.py tap <text> [n]       taps the n-th node whose text or description equals <text> (waits WAIT s, default 10)
  ui.py wait <text> [secs]   waits until a node's text or description contains <text> (default 15 s)
  ui.py type <text>          types text in short chunks into the focused field, reading each chunk back
                             (TYPE_CHUNK, default 6): the software-rendered API 30 emulator drops
                             characters from long `input text` runs, so a short chunk that did not
                             land whole is deleted and typed again (spaces and ! survive)
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


def raw_nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    out = []
    for match in re.finditer(r"<node [^>]*>", xml):
        node = match.group(0)

        def attr(name, node=node):
            found = re.search(" " + name + r'="([^"]*)"', node)
            return found.group(1) if found else ""

        out.append(attr)
    return out


def nodes():
    out = []
    for attr in raw_nodes():
        bounds = [int(x) for x in re.findall(r"\d+", attr("bounds"))]
        out.append((attr("text"), attr("content-desc"), attr("clickable"), attr("enabled"), attr("focused"), bounds))
    return out


def unescape(text):
    return (text.replace("&quot;", '"').replace("&apos;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&"))


def focused_field():
    """The focused text field's (text, password) — the hint reads as empty — or None."""
    for attr in raw_nodes():
        if attr("focused") != "true":
            continue
        if "EditText" not in attr("class") and attr("password") != "true":
            continue
        text = unescape(attr("text"))
        if text and text == unescape(attr("hint")):
            text = ""
        return text, attr("password") == "true"
    return None


def send_text(chunk):
    # `input text` drops `!` and what follows unless the shell sees it quoted; %s is a space.
    adb("shell", "input text " + shlex.quote(chunk.replace(" ", "%s")))


def delete_chars(count):
    if count > 0:
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * count))


def type_text(text):
    """Types [text] at the end of the focused field in chunks, each read back and retyped if it did not land whole."""
    size = max(1, int(os.environ.get("TYPE_CHUNK", "6")))
    chunks = [text[i:i + size] for i in range(0, len(text), size)]
    field = focused_field()
    if field is None:
        # Nothing to read back (no focused field in the dump): short chunks are still more reliable.
        for chunk in chunks:
            send_text(chunk)
            time.sleep(0.2)
        return
    base, _ = field
    typed = ""
    for chunk in chunks:
        for attempt in range(5):
            send_text(chunk)
            want = typed + chunk
            got = None
            landed = False
            for _ in range(6):
                time.sleep(0.15)
                current = focused_field()
                if current is None:
                    continue
                got, masked = current
                # Older dumps carry no `hint`: a placeholder ("Search…") read as the start text is gone
                # once anything was typed.
                if not typed and base and not masked and not got.startswith(base):
                    base = ""
                landed = len(got) == len(base) + len(want) if masked else got == base + want
                if landed:
                    break
            if landed:
                break
            # Delete what this chunk left (whole or in part) and type it again.
            delete_chars(len(got or "") - len(base) - len(typed))
        else:
            sys.exit(f"typing failed: the field kept dropping characters of chunk {chunk!r}")
        typed = want


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
        type_text(argv[2])
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
