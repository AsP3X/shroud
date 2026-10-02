#!/usr/bin/env bash
# Builds the release APKs twice with one throwaway keystore under /tmp and compares them.
# The owner's release key is not used and is not created here. The throwaway password is
# never printed. v1 signing is off (app/build.gradle.kts), so a JAR signature timestamp is
# not part of the APK. Zip entry timestamps are ignored: the compare hashes each entry's
# bytes. The APK Signature Scheme v2/v3 block is not a zip entry; if every entry matches and
# the file bytes still differ, that difference is reported and is not a failure.
#
# From android/:  e2e/repro-build.sh
# Needs JDK 21, the Android SDK (NDK and CMake pinned in app/build.gradle.kts), and python3.
set -euo pipefail

ANDROID_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ANDROID_DIR"

export JAVA_HOME="${JAVA_HOME:-/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home}"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1700000000}"
export TZ=UTC

if [ ! -x "$JAVA_HOME/bin/keytool" ]; then
  echo "keytool not found under JAVA_HOME=$JAVA_HOME" >&2
  exit 1
fi

WORK=$(mktemp -d /tmp/shroud-repro.XXXXXX)
KS="$WORK/release.p12"
PASSFILE="$WORK/pass"
umask 077
PASS=$(openssl rand -hex 16)
printf '%s' "$PASS" > "$PASSFILE"
chmod 600 "$PASSFILE"

cleanup() {
  chmod u+rwx "$WORK" 2>/dev/null || true
  rm -f "$KS" "$PASSFILE"
  unset PASS SHROUD_RELEASE_STORE_PASSWORD SHROUD_RELEASE_KEY_PASSWORD || true
}
trap cleanup EXIT

"$JAVA_HOME/bin/keytool" -genkeypair \
  -keystore "$KS" -storetype PKCS12 \
  -alias shroud -keyalg RSA -keysize 2048 -validity 3650 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Shroud repro, O=Shroud, C=US" >/dev/null

export SHROUD_RELEASE_STORE="$KS"
export SHROUD_RELEASE_STORE_PASSWORD="$PASS"
export SHROUD_RELEASE_KEY_ALIAS=shroud
export SHROUD_RELEASE_KEY_PASSWORD="$PASS"
unset PASS

copy_apks() {
  dest=$1
  mkdir -p "$dest"
  rel=app/build/outputs/apk/release
  if [ -e "$rel/app-universal-release.apk" ] || [ -e "$rel/app-universal-release-unsigned.apk" ]; then
    echo "release produced a universal APK" >&2
    exit 1
  fi
  for abi in arm64-v8a x86_64; do
    apk="$rel/app-${abi}-release.apk"
    if [ ! -f "$apk" ]; then
      echo "missing signed split $apk" >&2
      find "$rel" -name '*.apk' >&2 || true
      exit 1
    fi
    cp "$apk" "$dest/app-${abi}-release.apk"
  done
}

build_once() {
  dest=$1
  # --no-build-cache so the second build compiles again instead of restoring the first.
  ./gradlew --no-daemon --no-build-cache clean :app:assembleRelease --console=plain
  copy_apks "$dest"
}

echo "repro work dir: $WORK (keystore is deleted on exit; the password is not printed)"
build_once "$WORK/first"
build_once "$WORK/second"

python3 - "$WORK/first" "$WORK/second" <<'PY'
import hashlib, sys, zipfile
from pathlib import Path

first, second = Path(sys.argv[1]), Path(sys.argv[2])

def skip(name: str) -> bool:
    # v1 JAR signatures are disabled. If a toolchain still emits them, their
    # timestamps are not app content. v2/v3 live in the signing block, not here.
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    if upper == "META-INF/MANIFEST.MF":
        return True
    return upper.endswith((".SF", ".RSA", ".DSA", ".EC"))

def payloads(path: Path):
    out = {}
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            if skip(info.filename):
                continue
            out[info.filename] = hashlib.sha256(zf.read(info.filename)).hexdigest()
    return out

failed = False
for name in ("app-arm64-v8a-release.apk", "app-x86_64-release.apk"):
    a, b = first / name, second / name
    da, db = a.read_bytes(), b.read_bytes()
    if da == db:
        print(f"{name}: byte-identical ({len(da)} bytes)")
        continue
    pa, pb = payloads(a), payloads(b)
    only_a = sorted(set(pa) - set(pb))
    only_b = sorted(set(pb) - set(pa))
    differ = sorted(n for n in set(pa) & set(pb) if pa[n] != pb[n])
    if not only_a and not only_b and not differ:
        print(f"{name}: zip entry payloads match; APK bytes differ outside entry contents (signing block or zip metadata)")
        continue
    failed = True
    print(f"{name}: DIFFER")
    for n in only_a:
        print(f"  only in first: {n}")
    for n in only_b:
        print(f"  only in second: {n}")
    for n in differ:
        print(f"  payload differs: {n}")

if failed:
    sys.exit(1)
PY

echo "repro compare: OK"
