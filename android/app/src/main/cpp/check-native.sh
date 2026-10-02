#!/bin/sh
# Checks native libraries in one or more built APKs (W2-WHISPER, 00-plan §5.2, W4-RELEASE).
# POSIX sh, so the Ubuntu CI job can run it (the previous script was zsh).
#
# For each APK:
#   - a split named for one ABI must contain that ABI; any other APK must contain both
#   - no ABI other than arm64-v8a and x86_64
#   - libshroud_whisper.so, libggml*.so and libc++_shared.so are present; their sizes are printed
#   - every ELF LOAD segment of every .so (whisper and the rest, including WebRTC) is aligned
#     to at least 16 KB
#   - uncompressed libraries are 16 KB zip-aligned (zipalign -P 16)
#   - libshroud_whisper.so exports only its JNI entry points (plus libc++ RTTI that stays visible)
#
# Usage: app/src/main/cpp/check-native.sh <apk> [apk...]
# Needs ANDROID_HOME (default ~/Library/Android/sdk) with the pinned NDK and build-tools.
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: check-native.sh <apk> [apk...]" >&2
  exit 2
fi

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=$SDK/ndk/30.0.16248370

first_existing() {
  for f in "$@"; do
    if [ -e "$f" ]; then
      printf '%s\n' "$f"
      return 0
    fi
  done
  return 1
}

READELF=$(first_existing "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf) || {
  echo "llvm-readelf not found under $NDK" >&2
  exit 1
}
NM=$(first_existing "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm) || {
  echo "llvm-nm not found under $NDK" >&2
  exit 1
}
ZIPALIGN=
for f in "$SDK"/build-tools/*/zipalign; do
  if [ -x "$f" ]; then
    ZIPALIGN=$f
  fi
done
if [ -z "$ZIPALIGN" ]; then
  echo "zipalign not found under $SDK/build-tools" >&2
  exit 1
fi

# readelf prints alignment as 0x4000. awk must not treat the value as a filename.
hex_to_dec() {
  awk -v s="$1" 'BEGIN {
    h = tolower(s)
    if (h ~ /^0x/) {
      sub(/^0x/, "", h)
      n = 0
      for (i = 1; i <= length(h); i++) {
        c = substr(h, i, 1)
        d = index("0123456789abcdef", c)
        if (d == 0) exit 2
        n = n * 16 + (d - 1)
      }
      printf "%d\n", n
    } else {
      printf "%d\n", h + 0
    }
  }'
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fail=0

check_apk() {
  apk=$1
  name=$(basename "$apk")
  slot=$WORK/apk-$name
  rm -rf "$slot"
  mkdir -p "$slot"
  unzip -q -o "$apk" 'lib/*' -d "$slot"

  case $name in
    *arm64-v8a*) required="arm64-v8a" ;;
    *x86_64*) required="x86_64" ;;
    *) required="arm64-v8a x86_64" ;;
  esac

  if [ -d "$slot/lib" ]; then
    for dir in "$slot"/lib/*; do
      [ -d "$dir" ] || continue
      abi=$(basename "$dir")
      case $abi in
        arm64-v8a|x86_64) ;;
        *)
          echo "$name: unexpected ABI $abi"
          fail=1
          ;;
      esac
    done
  fi

  for abi in $required; do
    dir=$slot/lib/$abi
    if [ ! -d "$dir" ]; then
      echo "$name: $abi: no libraries"
      fail=1
      continue
    fi
    for req in libshroud_whisper.so libc++_shared.so; do
      if [ ! -f "$dir/$req" ]; then
        echo "$name: $abi: missing $req"
        fail=1
      fi
    done
    ggml=0
    for so in "$dir"/libggml*.so; do
      if [ -f "$so" ]; then
        ggml=1
      fi
    done
    if [ "$ggml" -eq 0 ]; then
      echo "$name: $abi: missing libggml*.so"
      fail=1
    fi

    total=0
    for so in "$dir"/*.so; do
      [ -f "$so" ] || continue
      base=$(basename "$so")
      size=$(stat -f %z "$so" 2>/dev/null || stat -c %s "$so")
      case $base in
        libshroud_whisper.so|libc++_shared.so|libggml*.so)
          total=$((total + size))
          ;;
      esac
      bad=""
      if ! "$READELF" -lW "$so" > "$slot/readelf.txt"; then
        echo "$name: $abi: readelf failed for $base"
        fail=1
        continue
      fi
      aligns=$(awk '$1 == "LOAD" { print $NF }' "$slot/readelf.txt")
      if [ -z "$aligns" ]; then
        echo "$name: $abi: $base has no LOAD segment"
        fail=1
        continue
      fi
      for align in $aligns; do
        dec=$(hex_to_dec "$align") || {
          echo "$name: $abi: $base: bad alignment '$align'"
          fail=1
          continue
        }
        if [ "$dec" -lt 16384 ]; then
          bad="$bad $align"
        fi
      done
      if [ -n "$bad" ]; then
        printf '%s: %-14s %-44s %9d bytes  LOAD align%s < 16 KB\n' "$name" "$abi" "$base" "$size" "$bad"
        fail=1
      else
        printf '%s: %-14s %-44s %9d bytes\n' "$name" "$abi" "$base" "$size"
      fi
    done
    mb=$(awk -v t="$total" 'BEGIN { printf "%.2f", t / 1048576 }')
    printf '%s: %-14s %-44s %9d bytes (%s MB)\n' "$name" "$abi" "total (Whisper + ggml + libc++_shared)" "$total" "$mb"

    whisper=$dir/libshroud_whisper.so
    if [ -f "$whisper" ]; then
      if ! "$NM" -D --defined-only "$whisper" > "$slot/nm.txt"; then
        echo "$name: $abi: nm failed for libshroud_whisper.so"
        fail=1
      else
        exports=$(awk '{ print $3 }' "$slot/nm.txt" | grep -v -E '^(Java_de_corespace_shroud_core_transcription_WhisperNative_|JNI_OnLoad$|_ZT[ISV]NSt6__ndk1)' || true)
        if [ -n "$exports" ]; then
          echo "$name: $abi: libshroud_whisper.so exports more than its JNI entry points:"
          printf '%s\n' "$exports" | head -20
          fail=1
        else
          echo "$name: $abi: libshroud_whisper.so exports only JNI entry points"
        fi
      fi
    fi
  done

  if "$ZIPALIGN" -c -P 16 -v 4 "$apk" > "$slot/zipalign.txt" 2>&1; then
    echo "$name: zipalign -P 16: OK"
  else
    echo "$name: zipalign -P 16: FAILED"
    grep -i -E 'lib/.*\.so' "$slot/zipalign.txt" | grep -v -i 'OK' | head || true
    fail=1
  fi
}

for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    echo "not a file: $apk" >&2
    fail=1
    continue
  fi
  check_apk "$apk"
done

exit "$fail"
