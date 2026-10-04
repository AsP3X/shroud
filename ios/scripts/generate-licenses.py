"""Generate the iOS app's open-source license list (Settings > About Shroud > Open-Source Licenses).

    python3 ios/scripts/generate-licenses.py <SourcePackages/checkouts>          # write the JSON
    python3 ios/scripts/generate-licenses.py <SourcePackages/checkouts> --check  # exit 1 if stale

<checkouts> is the `SourcePackages/checkouts` folder of a DerivedData build of ios/shroud.xcodeproj,
e.g. ~/Library/Developer/Xcode/DerivedData/shroud-<hash>/SourcePackages/checkouts (resolve packages
in Xcode or run any xcodebuild first). Writes ios/shroud/Resources/OpenSourceLicenses.json, which the
app target picks up through its file-system synchronized group.

Every pin in Package.resolved is listed with its LICENSE (and NOTICE, when the package has one),
unless EXCLUDED says it never reaches the app. A new dependency is therefore listed by default; a
license the detector can't name stops the script so someone reads it. Components that aren't Swift
packages (the vendored WebRTC binary and the libraries inside it, the BIP39 wordlist, the downloaded
Whisper model) come from NON_SPM below, with their texts in ios/scripts/licenses/. Re-run after
changing dependencies, and update NON_SPM when WebRTC.xcframework.zip is replaced.
"""
import json, pathlib, sys

IOS = pathlib.Path(__file__).resolve().parents[1]
RESOLVED = IOS / "shroud.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
OUTPUT = IOS / "shroud/Resources/OpenSourceLicenses.json"
LICENSES = IOS / "scripts/licenses"

# Pins that are resolved but never linked into the app or its extensions.
EXCLUDED = {
    "swift-argument-parser": "only WhisperKit's command-line tool (whisperkit-cli) uses it",
    "swift-asn1": "only swift-crypto's CryptoExtras uses it; the app links Crypto alone",
}

LICENSE_NAMES = ["LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING"]
NOTICE_NAMES = ["NOTICE", "NOTICE.txt", "NOTICE.md"]

BIP39_LICENSE = """MIT License

Copyright (c) Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe (the BIP-0039 authors)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

WHISPER_LICENSE = """MIT License

Copyright (c) 2022 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The Core ML conversion Shroud downloads on first use comes from Argmax
(huggingface.co/argmaxinc/whisperkit-coreml), also under the MIT License.
"""

# WebRTC.xcframework.zip (ios/Vendor/WebRTC) is stasel/WebRTC 153.0.0, Chromium M153. Besides
# WebRTC itself the binary links these third_party libraries (seen in its strings: source paths,
# "WebM Project VP8 Encoder v1.16.0"); each gets its own entry, as on Android.
WEBRTC = "WebRTC M153"

# Not Swift packages: `file` is a text under ios/scripts/licenses/, or `text` the license itself.
NON_SPM = [
    {"id": "webrtc", "name": "WebRTC", "version": "M153", "license": "BSD-3-Clause",
     "url": "https://webrtc.googlesource.com/src", "file": "webrtc.txt"},
    {"id": "abseil", "name": "Abseil", "version": WEBRTC, "license": "Apache-2.0",
     "url": "https://github.com/abseil/abseil-cpp", "file": "abseil.txt"},
    {"id": "boringssl", "name": "BoringSSL", "version": WEBRTC, "license": "Apache-2.0",
     "url": "https://boringssl.googlesource.com/boringssl", "file": "boringssl.txt"},
    {"id": "dav1d", "name": "dav1d", "version": WEBRTC, "license": "BSD-2-Clause",
     "url": "https://code.videolan.org/videolan/dav1d", "file": "dav1d.txt"},
    {"id": "libaom", "name": "libaom", "version": WEBRTC, "license": "BSD-2-Clause",
     "url": "https://aomedia.googlesource.com/aom", "file": "libaom.txt"},
    {"id": "libcxx", "name": "LLVM libc++", "version": WEBRTC, "license": "Apache-2.0 WITH LLVM-exception",
     "url": "https://libcxx.llvm.org", "file": "libcxx.txt"},
    {"id": "libsrtp", "name": "libsrtp", "version": WEBRTC, "license": "BSD-3-Clause",
     "url": "https://github.com/cisco/libsrtp", "file": "libsrtp.txt"},
    {"id": "libvpx", "name": "libvpx", "version": "1.16.0", "license": "BSD-3-Clause",
     "url": "https://chromium.googlesource.com/webm/libvpx", "file": "libvpx.txt"},
    {"id": "libyuv", "name": "libyuv", "version": WEBRTC, "license": "BSD-3-Clause",
     "url": "https://chromium.googlesource.com/libyuv/libyuv", "file": "libyuv.txt"},
    {"id": "opus", "name": "Opus", "version": WEBRTC, "license": "BSD-3-Clause",
     "url": "https://opus-codec.org", "file": "opus.txt"},
    {"id": "protobuf", "name": "Protocol Buffers (lite)", "version": WEBRTC, "license": "BSD-3-Clause",
     "url": "https://github.com/protocolbuffers/protobuf", "file": "protobuf.txt"},
    {"id": "bip39-wordlist", "name": "BIP39 English Wordlist", "version": "BIP-0039", "license": "MIT",
     "url": "https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki", "text": BIP39_LICENSE},
    # TranscriptionModelID.default; downloaded the first time a voice note is transcribed.
    {"id": "whisper-model", "name": "Whisper Speech Model", "version": "small", "license": "MIT",
     "url": "https://github.com/openai/whisper", "text": WHISPER_LICENSE},
]


def detect_license(text):
    """SPDX id for the license texts our dependencies use; None for anything else."""
    if "Apache License" in text and "Version 2.0" in text:
        if "Runtime Library Exception" in text:
            return "Apache-2.0 WITH Swift-exception"
        return "Apache-2.0"
    if "Permission is hereby granted, free of charge" in text:
        return "MIT"
    if "Neither the name of" in text and "Redistribution and use in source and binary forms" in text:
        return "BSD-3-Clause"
    return None


def read_first(folder, names):
    for name in names:
        path = folder / name
        if path.is_file():
            return path.read_text(encoding="utf-8").strip("\n")
    return None


def spm_entries(checkouts):
    pins = json.loads(RESOLVED.read_text())["pins"]
    entries = []
    for pin in pins:
        identity = pin["identity"]
        if identity in EXCLUDED:
            continue
        location = pin["location"].removesuffix(".git").rstrip("/")
        name = location.rsplit("/", 1)[-1]
        folder = checkouts / name
        if not folder.is_dir():
            folder = checkouts / identity
        if not folder.is_dir():
            sys.exit(f"{identity}: no checkout under {checkouts}; resolve packages first")
        license_text = read_first(folder, LICENSE_NAMES)
        if license_text is None:
            sys.exit(f"{identity}: no license file in {folder}")
        license_id = detect_license(license_text)
        if license_id is None:
            sys.exit(f"{identity}: unrecognised license in {folder}; add it to detect_license")
        text = license_text
        notice = read_first(folder, NOTICE_NAMES)
        if notice:
            text += "\n\n" + "-" * 72 + "\n\n" + notice
        state = pin["state"]
        entries.append({
            "id": identity,
            "name": name,
            "version": state.get("version") or state.get("revision", "")[:7],
            "license": license_id,
            "url": location,
            "text": text,
        })
    return entries


def static_entries():
    entries = []
    for item in NON_SPM:
        entry = {key: item[key] for key in ("id", "name", "version", "license", "url")}
        if "file" in item:
            entry["text"] = (LICENSES / item["file"]).read_text(encoding="utf-8").strip("\n")
        else:
            entry["text"] = item["text"].strip("\n")
        entries.append(entry)
    return entries


def main():
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    if len(args) != 1:
        sys.exit(__doc__)
    checkouts = pathlib.Path(args[0]).expanduser()
    entries = spm_entries(checkouts) + static_entries()
    entries.sort(key=lambda entry: entry["name"].casefold())
    output = json.dumps(entries, indent=2, ensure_ascii=False) + "\n"
    if "--check" in sys.argv[1:]:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.is_file() else ""
        if current != output:
            sys.exit(f"{OUTPUT.relative_to(IOS.parent)} is stale; re-run without --check")
        print(f"{OUTPUT.relative_to(IOS.parent)} is up to date ({len(entries)} components)")
        return
    OUTPUT.write_text(output, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(IOS.parent)} ({len(entries)} components)")


if __name__ == "__main__":
    main()
