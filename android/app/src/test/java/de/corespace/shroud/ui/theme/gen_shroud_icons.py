#!/usr/bin/env python3
"""Regenerates `ui/theme/ShroudIcons.kt` and `assets/licenses/icons.txt` from the icon sources.

The design (`design/Android-App.pen`) draws Lucide (lucide-static 1.49.0, ISC; 24-unit viewport,
2-unit round strokes) and Phosphor (@phosphor-icons/core 2.1.1, MIT; 256-unit fills) glyphs. Each
path here is the SVG's own, so the glyphs match the frames. Lucide's `circle`, `rect`, `line`,
`polyline`, `polygon` and `ellipse` elements become equivalent path data (same geometry).

Usage (from `android/`):

    python3 app/src/test/java/de/corespace/shroud/ui/theme/gen_shroud_icons.py            # write
    python3 app/src/test/java/de/corespace/shroud/ui/theme/gen_shroud_icons.py --check    # compare

The SVGs are read from the npm CDN (jsDelivr) at the pinned versions, or from `--source DIR`
holding `lucide/<name>.svg` and `phosphor/<weight>/<file>.svg` (an unpacked lucide-static /
@phosphor-icons/core). Adding an icon: append it to ICONS below and run the script; later waves add
their icons the same way (00-plan §1.2: `ui/theme/**` is owned by the wave's INT package).

Naming: PascalCase of the design name (`arrow-bend-up-left-fill` → `ArrowBendUpLeftFill`). Where both
libraries have the same name, Lucide keeps the plain name and the Phosphor regular weight gets a
`Regular` suffix (`Globe` = Lucide, `GlobeRegular` = Phosphor).
"""

import argparse
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

LUCIDE_VERSION = "1.49.0"
PHOSPHOR_VERSION = "2.1.1"
LUCIDE_URL = "https://cdn.jsdelivr.net/npm/lucide-static@{v}/icons/{name}.svg"
PHOSPHOR_URL = "https://cdn.jsdelivr.net/npm/@phosphor-icons/core@{v}/assets/{weight}/{file}.svg"

# (library, design name, Kotlin name override). Phosphor names carry their weight suffix
# (`-fill`, `-bold`); no suffix = the regular weight.
LUCIDE = [
    "arrow-down-to-line", "arrow-right", "arrow-up", "arrow-up-to-line", "at-sign", "audio-lines",
    "bell", "camera", "camera-off", "check", "check-check", "chevron-down", "chevron-left",
    "chevron-right", "chevron-up", "chevrons-up-down", "circle", "circle-check", "clipboard",
    "copy", "download", "ellipsis", "eye", "eye-off", "flip-horizontal-2", "folder", "forward",
    "globe", "hand", "hash", "image", "images", "info", "key-round", "link", "list",
    "loader-circle", "lock", "lock-keyhole", "lock-open", "maximize-2", "mic", "mic-off",
    "minimize-2", "monitor-smartphone", "pencil", "phone", "phone-off", "pin", "play", "plus",
    "qr-code", "refresh-cw", "reply", "rotate-ccw", "rotate-ccw-square", "scan", "scan-face",
    "scan-text", "screen-share", "search", "share-2", "shield", "shield-alert", "shield-check",
    "shield-half", "smartphone", "smile", "square-pen", "trash-2", "triangle-alert", "user-plus",
    "video", "video-off", "volume-2", "wifi-off", "x",
]
PHOSPHOR = [
    # fill
    "arrow-bend-up-left-fill", "bell-fill", "bell-ringing-fill", "bell-slash-fill",
    "bookmark-simple-fill", "camera-fill", "camera-rotate-fill", "chats-circle-fill",
    "chats-teardrop-fill", "check-circle-fill", "circle-half-fill",
    "device-mobile-fill", "device-tablet-fill", "file-fill", "folder-simple-fill",
    "gear-six-fill", "gift-fill", "hard-drive-fill", "hard-drives-fill", "heart-fill",
    "image-fill", "info-fill", "key-fill", "lock-fill", "lock-simple-fill",
    "lock-simple-open-fill", "map-pin-fill", "microphone-fill", "microphone-slash-fill",
    "moon-fill", "music-note-fill", "palette-fill", "paper-plane-tilt-fill", "pause-fill",
    "phone-disconnect-fill", "phone-fill", "play-fill", "seal-check-fill", "shield-check-fill",
    "shield-warning-fill", "smiley-fill", "speaker-high-fill", "speaker-simple-high-fill",
    "speaker-simple-none-fill", "speaker-slash-fill", "stop-fill", "sun-fill", "trash-fill",
    "user-circle-fill", "user-fill", "users-fill", "video-camera-fill",
    "video-camera-slash-fill", "warning-circle-fill", "warning-fill", "x-circle-fill",
    # bold
    "arrow-clockwise-bold", "arrow-down-bold", "arrow-down-left-bold", "arrow-right-bold",
    "arrow-up-bold", "arrow-up-right-bold", "caret-down-bold", "caret-left-bold",
    "caret-right-bold", "caret-up-bold", "cell-signal-slash-bold", "check-bold",
    "dots-three-bold", "export-bold", "identification-card-bold", "magnifying-glass-bold",
    "plus-bold", "x-bold",
    # regular
    "arrow-bend-up-left", "arrow-bend-up-right", "arrow-u-up-left", "bell", "bell-slash",
    "caret-left", "caret-right", "caret-up-down", "chat-circle-dots", "check", "circle", "copy",
    "crop", "download-simple", "fingerprint", "globe", "image", "list-checks", "magic-wand",
    "magnifying-glass", "pen-nib", "pencil-circle", "qr-code", "sliders-horizontal", "smiley",
    "text-aa", "text-t", "textbox", "trash", "users", "waveform", "wifi-slash",
]

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.abspath(os.path.join(HERE, "../../../../../../../../.."))
KOTLIN_OUT = os.path.join(ANDROID, "app/src/main/java/de/corespace/shroud/ui/theme/ShroudIcons.kt")
LICENSE_OUT = os.path.join(ANDROID, "app/src/main/assets/licenses/icons.txt")


def pascal(name):
    return "".join(part[:1].upper() + part[1:] for part in name.split("-"))


def phosphor_weight(name):
    if name.endswith("-fill"):
        return "fill"
    if name.endswith("-bold"):
        return "bold"
    return "regular"


def kotlin_names():
    lucide = {n: pascal(n) for n in LUCIDE}
    taken = set(lucide.values())
    phosphor = {}
    for n in PHOSPHOR:
        k = pascal(n)
        if k in taken:
            assert phosphor_weight(n) == "regular", n
            k += "Regular"
        assert k not in taken, k
        taken.add(k)
        phosphor[n] = k
    return lucide, phosphor


def read(source, url, local):
    if source:
        with open(os.path.join(source, local), encoding="utf-8") as f:
            return f.read()
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.read().decode("utf-8")
    except OSError as error:
        raise SystemExit(f"{url}: {error}")


def num(value):
    return float(value)


def element_paths(svg_text):
    """Path data of every drawing element, in document order."""
    root = ET.fromstring(re.sub(r"<!--.*?-->", "", svg_text, flags=re.S))
    paths = []
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        a = el.attrib
        if tag == "path":
            paths.append(a["d"])
        elif tag == "circle":
            cx, cy, r = num(a["cx"]), num(a["cy"]), num(a["r"])
            paths.append(f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 -{2 * r},0")
        elif tag == "ellipse":
            cx, cy, rx, ry = num(a["cx"]), num(a["cy"]), num(a["rx"]), num(a["ry"])
            paths.append(f"M{cx - rx},{cy}a{rx},{ry} 0 1,0 {2 * rx},0a{rx},{ry} 0 1,0 -{2 * rx},0")
        elif tag == "rect":
            x, y = num(a.get("x", "0")), num(a.get("y", "0"))
            w, h = num(a["width"]), num(a["height"])
            rx = num(a.get("rx", a.get("ry", "0")))
            if rx > 0:
                paths.append(
                    f"M{x + rx},{y}h{w - 2 * rx}a{rx},{rx} 0 0 1 {rx},{rx}v{h - 2 * rx}"
                    f"a{rx},{rx} 0 0 1 -{rx},{rx}h-{w - 2 * rx}a{rx},{rx} 0 0 1 -{rx},-{rx}"
                    f"v-{h - 2 * rx}a{rx},{rx} 0 0 1 {rx},-{rx}z"
                )
            else:
                paths.append(f"M{x},{y}h{w}v{h}h-{w}z")
        elif tag == "line":
            paths.append(f"M{num(a['x1'])},{num(a['y1'])}L{num(a['x2'])},{num(a['y2'])}")
        elif tag in ("polyline", "polygon"):
            pts = [float(v) for v in re.split(r"[\s,]+", a["points"].strip())]
            pairs = [f"{pts[i]},{pts[i + 1]}" for i in range(0, len(pts), 2)]
            paths.append("M" + "L".join(pairs) + ("z" if tag == "polygon" else ""))
        elif tag in ("svg", "g", "title", "defs"):
            continue
        else:
            raise ValueError(f"unsupported SVG element <{tag}>")
    if not paths:
        raise ValueError("no drawing elements")
    return paths


def kotlin_property(kotlin_name, doc, viewport, stroked, paths):
    body = ",\n".join(f'        "{p}"' for p in paths)
    return (
        f"    /** {doc} */\n"
        f"    val {kotlin_name}: ImageVector by lazy {{\n"
        f'        icon("{kotlin_name}", {viewport}f, {"true" if stroked else "false"},\n'
        f"{body})\n"
        f"    }}\n"
    )


HEADER = '''package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// GENERATED by app/src/test/java/de/corespace/shroud/ui/theme/gen_shroud_icons.py — do not edit by
// hand; add icons to the script's list and run it (00-plan §1.7.12, W1-UI-THEME).

/**
 * The icons `design/Android-App.pen` uses, as the design names them: Lucide (24-unit, 2-unit round
 * stroke; ISC) and Phosphor (256-unit fills; MIT), the union the area specs ask for (00-plan
 * §1.7.12: shell-chats §15.2, conversation-thread §23.4, conversation-compose-media §2.5, calls
 * §13.3, contacts §7.2, settings-lock §2/§11, notifications, design-inventory §2). Generated from
 * lucide-static {lucide} and @phosphor-icons/core {phosphor}: each path is the SVG's own, so the
 * glyphs match the frames. iOS draws SF Symbols instead; the design chose these equivalents.
 *
 * Naming: PascalCase of the design name. Where both libraries have the same name, Lucide keeps the
 * plain name and the Phosphor regular weight takes a `Regular` suffix ([Globe] is Lucide,
 * [GlobeRegular] is Phosphor). Every icon is listed in `assets/licenses/icons.txt`.
 *
 * Draw them with [de.corespace.shroud.ui.components.ShroudIcon], which tints them. Each vector is
 * built on first use.
 */
object ShroudIcons {{
'''

FOOTER = '''}

private fun icon(name: String, viewport: Float, stroked: Boolean, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        for (data in paths) {
            if (stroked) {
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
            }
        }
    }.build()
'''


def license_text(lucide_names, phosphor_names):
    lines = [
        f"Lucide icons (lucide-static {LUCIDE_VERSION}): ISC License, Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022.",
        "",
        f"Phosphor Icons (@phosphor-icons/core {PHOSPHOR_VERSION}): MIT License, Copyright (c) 2023 Phosphor Icons.",
        "",
        "Icons bundled as vector paths (ui/theme/ShroudIcons.kt):",
        "",
        f"Lucide {LUCIDE_VERSION}: " + ", ".join(sorted(lucide_names)),
        "",
        f"Phosphor {PHOSPHOR_VERSION}: " + ", ".join(sorted(phosphor_names)),
        "",
    ]
    return "\n".join(lines)


def generate(source):
    lucide_names, phosphor_names = kotlin_names()
    props = []
    for name in sorted(LUCIDE, key=lambda n: lucide_names[n]):
        svg = read(source, LUCIDE_URL.format(v=LUCIDE_VERSION, name=name), f"lucide/{name}.svg")
        props.append(kotlin_property(lucide_names[name], f"Lucide `{name}`.", 24, True, element_paths(svg)))
    for name in sorted(PHOSPHOR, key=lambda n: phosphor_names[n]):
        weight = phosphor_weight(name)
        svg = read(source, PHOSPHOR_URL.format(v=PHOSPHOR_VERSION, weight=weight, file=name), f"phosphor/{weight}/{name}.svg")
        props.append(kotlin_property(phosphor_names[name], f"Phosphor `{name}`.", 256, False, element_paths(svg)))
    kotlin = HEADER.format(lucide=LUCIDE_VERSION, phosphor=PHOSPHOR_VERSION) + "\n".join(props) + FOOTER
    return kotlin, license_text(LUCIDE, PHOSPHOR)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if the committed files differ")
    parser.add_argument("--source", help="directory with lucide/ and phosphor/<weight>/ SVGs")
    args = parser.parse_args()
    kotlin, licenses = generate(args.source)
    outputs = [(KOTLIN_OUT, kotlin), (LICENSE_OUT, licenses)]
    if args.check:
        stale = [path for path, text in outputs if open(path, encoding="utf-8").read() != text]
        if stale:
            print("out of date: " + ", ".join(stale))
            sys.exit(1)
        print("up to date")
        return
    for path, text in outputs:
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote " + path)


if __name__ == "__main__":
    main()
