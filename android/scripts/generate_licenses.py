#!/usr/bin/env python3
"""Generates app/src/main/assets/licenses/third_party.json, the list Settings > About Shroud >
Open-Source Licenses shows.

Every module in the release runtime classpath (`./gradlew :app:dependencies --configuration
releaseRuntimeClasspath`) is listed, with the license its POM declares (parents followed), except
BOMs, which ship no code. AndroidX and Kotlin modules are grouped by license; everything else is
one entry per library. The components Gradle does not see (native code, fonts, icons, the BIP-39
word list, model weights, data files inside other libraries) are listed by hand in EXTRA below.

Each entry names a license-text file in assets/licenses/; those files are committed and edited by
hand, and the script fails when one is missing or a POM declares a license it cannot map.

Run from android/ after a dependency change:

    JAVA_HOME=... python3 scripts/generate_licenses.py

Needs the Gradle cache filled by a build (it reads the POMs from there); no network, no plugin.
"""

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
ASSETS = ANDROID / "app/src/main/assets/licenses"
OUTPUT = ASSETS / "third_party.json"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

# POM license names and URLs -> SPDX ids.
LICENSE_IDS = [
    (r"apache.*2\.0|apache-2\.0|licenses/LICENSE-2\.0", "Apache-2.0"),
    (r"3-clause bsd|bsd-3-clause|bsd 3-clause|new bsd|bsd license 3|opensource\.org/licenses/BSD-3-Clause", "BSD-3-Clause"),
    (r"^bsd$|bsd license|bsd-style", "BSD-3-Clause"),
    (r"\bmit\b|bouncy castle licen[cs]e", "MIT"),
    (r"eclipse public license.*2\.0|epl-2\.0", "EPL-2.0"),
]

# The text shown for a license id, unless the library has its own file (MIT and BSD texts carry a
# copyright line, so those always need one: an unmapped MIT/BSD library fails the run).
LICENSE_TEXTS = {
    "Apache-2.0": "Apache-2.0.txt",
}

# Libraries ("group:name", or a whole group) whose POM says nothing usable, that ship more than one
# license, or whose license text carries its own copyright.
OVERRIDES = {
    # Shaded protobuf-javalite inside DataStore: the jar's LICENSE.txt is protobuf's BSD license.
    "androidx.datastore:datastore-preferences-external-protobuf": {
        "name": "Protocol Buffers (lite, in AndroidX DataStore)", "license": "BSD-3-Clause", "text": "protobuf-LICENSE.txt",
    },
    # Apache-2.0 for the library; its libimage_processing_util_jni.so includes libyuv (BSD-3-Clause).
    "androidx.camera:camera-core": {"name": "CameraX core", "license": "Apache-2.0 AND BSD-3-Clause", "text": "CameraX-core-LICENSE.txt"},
    "org.bouncycastle:bcprov-jdk18on": {"license": "MIT", "text": "BouncyCastle-LICENSE.txt"},
    "org.checkerframework:checker-qual": {"license": "MIT", "text": "checker-qual-LICENSE.txt"},
    "dev.chrisbanes.haze": {"text": "Haze-LICENSE.txt"},
    "io.github.webrtc-sdk:android": {"license": "BSD-3-Clause", "text": "WebRTC-LICENSE.txt"},
}

# Display names for libraries listed on their own (group or "group:name" -> name).
NAMES = {
    "com.squareup.okhttp3": "OkHttp",
    "com.squareup.okio": "Okio",
    "org.bouncycastle": "Bouncy Castle",
    "dev.chrisbanes.haze": "Haze",
    "com.google.zxing": "ZXing",
    "io.github.webrtc-sdk": "WebRTC",
    "org.unifiedpush.android": "UnifiedPush",
    "com.google.guava": "Guava",
    "org.jspecify": "JSpecify",
    "org.checkerframework": "Checker Framework qualifiers",
    "com.google.errorprone": "Error Prone annotations",
    "com.google.j2objc": "J2ObjC annotations",
    "jakarta.inject": "Jakarta Dependency Injection",
    "javax.inject": "javax.inject",
    "com.google.code.findbugs": "JSR-305 annotations",
    "org.jetbrains:annotations": "JetBrains Java annotations",
}

# Group families: modules of these groups are pooled into one entry per license.
FAMILIES = [
    ("androidx.", "AndroidX libraries"),
    ("org.jetbrains.kotlinx", "Kotlin libraries"),
    ("org.jetbrains.kotlin", "Kotlin libraries"),
    ("org.jetbrains.androidx.", "Kotlin libraries"),
    ("org.jetbrains.compose.", "Kotlin libraries"),
]

WEBRTC = "WebRTC {io.github.webrtc-sdk:android}"
WEBRTC_SO = "libjingle_peerconnection_so.so (io.github.webrtc-sdk:android)"

# Components Gradle does not list. "artifacts" says where each ships in the APK. "{group:name}" in a
# version is replaced with that module's resolved version.
EXTRA = [
    {
        "id": "boringssl",
        "name": "BoringSSL",
        "version": WEBRTC,
        "license": "Apache-2.0",
        "text": "BoringSSL-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "libvpx",
        "name": "libvpx",
        "version": "1.16.0",
        "license": "BSD-3-Clause",
        "text": "libvpx-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "libaom",
        "name": "libaom",
        "version": WEBRTC,
        "license": "BSD-2-Clause",
        "text": "libaom-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "dav1d",
        "name": "dav1d",
        "version": WEBRTC,
        "license": "BSD-2-Clause",
        "text": "dav1d-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "opus",
        "name": "Opus",
        "version": WEBRTC,
        "license": "BSD-3-Clause",
        "text": "Opus-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "libsrtp",
        "name": "libsrtp",
        "version": WEBRTC,
        "license": "BSD-3-Clause",
        "text": "libsrtp-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "libyuv",
        "name": "libyuv",
        "version": WEBRTC,
        "license": "BSD-3-Clause",
        "text": "libyuv-LICENSE.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "abseil",
        "name": "Abseil",
        "version": WEBRTC,
        "license": "Apache-2.0",
        "text": "Apache-2.0.txt",
        "kind": "Native code",
        "artifacts": [WEBRTC_SO],
    },
    {
        "id": "whisper.cpp",
        "name": "whisper.cpp",
        "version": "1.9.4",
        "license": "MIT",
        "text": "whisper.cpp-LICENSE.txt",
        "kind": "Native code",
        "artifacts": ["libshroud_whisper.so (app/src/main/cpp/whisper.cpp)"],
    },
    {
        "id": "ggml",
        "name": "ggml",
        "version": "whisper.cpp 1.9.4",
        "license": "MIT",
        "text": "whisper.cpp-LICENSE.txt",
        "kind": "Native code",
        "artifacts": ["libggml.so, libggml-base.so, libggml-cpu-*.so (app/src/main/cpp/whisper.cpp/ggml)"],
    },
    {
        "id": "libc++",
        "name": "LLVM libc++",
        "version": "NDK 30.0.16248370",
        "license": "Apache-2.0 WITH LLVM-exception",
        "text": "LLVM-LICENSE.txt",
        "kind": "Native code",
        "artifacts": ["libc++_shared.so (Android NDK)"],
    },
    {
        "id": "inter",
        "name": "Inter",
        "version": "4.1",
        "license": "OFL-1.1",
        "text": "Inter-OFL.txt",
        "kind": "Font",
        "artifacts": ["res/font/inter_*.ttf"],
    },
    {
        "id": "jetbrains-mono",
        "name": "JetBrains Mono",
        "version": "2.304",
        "license": "OFL-1.1",
        "text": "JetBrainsMono-OFL.txt",
        "kind": "Font",
        "artifacts": ["res/font/jetbrains_mono_*.ttf"],
    },
    {
        "id": "noto-color-emoji",
        "name": "Noto Color Emoji",
        "version": "emoji2-bundled {androidx.emoji2:emoji2-bundled}",
        "license": "OFL-1.1",
        "text": "NotoColorEmoji-LICENSE.txt",
        "kind": "Font",
        "artifacts": ["assets/NotoColorEmojiCompat.ttf (androidx.emoji2:emoji2-bundled)"],
    },
    {
        "id": "lucide",
        "name": "Lucide icons",
        "version": "1.49.0",
        "license": "ISC",
        "text": "Lucide-LICENSE.txt",
        "kind": "Icons",
        "artifacts": ["ui/theme/ShroudIcons.kt (vector paths; list in icons.txt)"],
    },
    {
        "id": "phosphor",
        "name": "Phosphor Icons",
        "version": "2.1.1",
        "license": "MIT",
        "text": "Phosphor-LICENSE.txt",
        "kind": "Icons",
        "artifacts": ["ui/theme/ShroudIcons.kt (vector paths; list in icons.txt)"],
    },
    {
        "id": "bip39",
        "name": "BIP-39 English word list",
        "version": "bitcoin/bips",
        "license": "MIT",
        "text": "BIP39-LICENSE.txt",
        "kind": "Data",
        "artifacts": ["assets/bip39-english.txt"],
    },
    {
        "id": "public-suffix-list",
        "name": "Public Suffix List",
        "version": "OkHttp {com.squareup.okhttp3:okhttp}",
        "license": "MPL-2.0",
        "text": "MPL-2.0.txt",
        "kind": "Data",
        "artifacts": ["assets/PublicSuffixDatabase.list (com.squareup.okhttp3:okhttp)"],
    },
    {
        "id": "unicode-data",
        "name": "Unicode emoji data",
        "version": "emoji2-bundled {androidx.emoji2:emoji2-bundled}",
        "license": "Unicode-DFS-2016",
        "text": "Unicode-LICENSE.txt",
        "kind": "Data",
        "artifacts": ["assets/NotoColorEmojiCompat.ttf (androidx.emoji2:emoji2-bundled)"],
    },
    {
        "id": "whisper-models",
        "name": "Whisper models",
        "version": "ggml base / small, q5_1",
        "license": "MIT",
        "text": "Whisper-models-LICENSE.txt",
        "kind": "Model",
        "artifacts": ["Downloaded on first use from huggingface.co/ggerganov/whisper.cpp; not in the APK"],
    },
]

LINE = re.compile(r"^[| ]*[+\\]--- (?P<coord>[^ ]+)(?: -> (?P<resolved>[^ ]+))?(?P<rest>.*)$")


def gradle_modules() -> dict[str, str]:
    """Runs `:app:dependencies` and returns {"group:name": resolved version}."""
    output = subprocess.run(
        ["./gradlew", "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
        cwd=ANDROID, check=True, capture_output=True, text=True,
    ).stdout
    modules: dict[str, str] = {}
    for raw in output.splitlines():
        match = LINE.match(raw)
        if not match:
            continue
        rest = match["rest"]
        if "(c)" in rest or "(n)" in rest:  # constraints and unresolved entries are not modules
            continue
        parts = match["coord"].split(":")
        if len(parts) < 2:
            continue
        key = f"{parts[0]}:{parts[1]}"
        version = match["resolved"] or (parts[2] if len(parts) > 2 else None)
        if not version:
            raise SystemExit(f"no version for {raw.strip()}")
        modules[key] = version
    if not modules:
        raise SystemExit("`:app:dependencies` listed no modules")
    return modules


def pom_path(group: str, name: str, version: str) -> Path | None:
    base = CACHE / group / name / version
    for pom in sorted(base.glob(f"*/{name}-{version}.pom")):
        return pom
    return None


def read_pom(group: str, name: str, version: str) -> ET.Element:
    path = pom_path(group, name, version)
    if path is None:
        raise SystemExit(f"no POM for {group}:{name}:{version} in {CACHE}; build the release once first")
    root = ET.parse(path).getroot()
    for element in root.iter():
        element.tag = element.tag.split("}")[-1]
    return root


def is_bom(root: ET.Element) -> bool:
    """A BOM: a `pom` that only manages versions. Kotlin Multiplatform roots are `pom`s too, but
    they depend on their platform module, so they are kept."""
    packaging = (root.findtext("packaging") or "jar").strip()
    return packaging == "pom" and root.find("dependencyManagement") is not None and not root.findall("dependencies/dependency")


def pom_licenses(group: str, name: str, version: str, depth: int = 0) -> list[str]:
    root = read_pom(group, name, version)
    names = [
        " ".join(filter(None, [(lic.findtext("name") or "").strip(), (lic.findtext("url") or "").strip()]))
        for lic in root.findall("licenses/license")
    ]
    if names or depth > 5:
        return names
    parent = root.find("parent")
    if parent is None:
        return []
    return pom_licenses(parent.findtext("groupId"), parent.findtext("artifactId"), parent.findtext("version"), depth + 1)


def spdx(declared: str) -> str | None:
    lowered = declared.lower()
    for pattern, identifier in LICENSE_IDS:
        if re.search(pattern, lowered):
            return identifier
    return None


def family(group: str) -> str | None:
    for prefix, label in FAMILIES:
        if group == prefix.rstrip(".") or group.startswith(prefix):
            return label
    return None


def display_name(group: str, name: str, root: ET.Element) -> str:
    return NAMES.get(f"{group}:{name}") or NAMES.get(group) or (root.findtext("name") or name).strip()


def main() -> None:
    modules = gradle_modules()
    grouped: dict[tuple[str, str, str], list[str]] = {}
    singles: dict[tuple[str, str, str], dict] = {}
    problems: list[str] = []
    for key, version in sorted(modules.items()):
        group, name = key.split(":")
        root = read_pom(group, name, version)
        if is_bom(root):
            continue
        override = OVERRIDES.get(key) or OVERRIDES.get(group, {})
        license_id = override.get("license")
        if license_id is None:
            declared = pom_licenses(group, name, version)
            ids = sorted({i for i in (spdx(d) for d in declared) if i})
            if len(ids) != 1:
                problems.append(f"{key}:{version}: cannot map {declared!r}")
                continue
            license_id = ids[0]
        text = override.get("text") or LICENSE_TEXTS.get(license_id)
        coordinate = f"{key}:{version}"
        label = family(group)
        if label is not None and not override:
            grouped.setdefault((label, license_id, text), []).append(coordinate)
        else:
            entry_name = override.get("name") or display_name(group, name, root)
            slot = singles.setdefault((entry_name, license_id, text), {"versions": set(), "artifacts": []})
            slot["versions"].add(version)
            slot["artifacts"].append(coordinate)

    entries = []
    for (label, license_id, text), artifacts in grouped.items():
        entries.append({
            "id": f"{label.split()[0].lower()}-{license_id.lower()}",
            "name": label,
            "version": None,
            "license": license_id,
            "text": text,
            "kind": "Library",
            "artifacts": artifacts,
        })
    for (entry_name, license_id, text), slot in singles.items():
        versions = sorted(slot["versions"])
        entries.append({
            "id": re.sub(r"[^a-z0-9]+", "-", entry_name.lower()).strip("-"),
            "name": entry_name,
            "version": versions[0] if len(versions) == 1 else None,
            "license": license_id,
            "text": text,
            "kind": "Library",
            "artifacts": slot["artifacts"],
        })
    placeholder = re.compile(r"\{([^{}:]+:[^{}]+)\}")

    def resolve(text: str) -> str:
        def version_of(match: re.Match) -> str:
            if match[1] not in modules:
                problems.append(f"{text}: {match[1]} is not in the release classpath")
                return match[0]
            return modules[match[1]]
        return placeholder.sub(version_of, text)

    entries += [{**extra, "version": resolve(extra["version"])} for extra in EXTRA]
    entries.sort(key=lambda e: e["name"].lower())

    for entry in entries:
        if not (ASSETS / entry["text"]).is_file():
            problems.append(f"{entry['name']}: missing license text assets/licenses/{entry['text']}")
    ids = [e["id"] for e in entries]
    duplicates = {i for i in ids if ids.count(i) > 1}
    if duplicates:
        problems.append(f"duplicate ids: {sorted(duplicates)}")
    if problems:
        raise SystemExit("\n".join(problems))

    artifacts = sum(len(e["artifacts"]) for e in entries if e["kind"] == "Library")
    OUTPUT.write_text(json.dumps({"components": entries}, indent=2, ensure_ascii=False) + "\n")
    print(f"{len(entries)} entries, {artifacts} Maven modules -> {OUTPUT.relative_to(ANDROID)}")


if __name__ == "__main__":
    sys.exit(main())
