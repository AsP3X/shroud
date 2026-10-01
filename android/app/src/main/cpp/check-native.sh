#!/bin/zsh
# Checks the whisper.cpp libraries in a built APK (W2-WHISPER acceptance, 00-plan §5.2, W4-RELEASE):
#   - size of each Shroud-built .so per ABI and their total (the APK delta of on-device Whisper)
#   - 16 KB page alignment of every ELF LOAD segment (Android 15+ 16 KB devices, Play/F-Droid checks)
#   - 16 KB zip alignment of the uncompressed libraries in the APK (zipalign -P 16)
#   - libshroud_whisper.so exports only its JNI entry points
#
# Usage: app/src/main/cpp/check-native.sh app/build/outputs/apk/release/app-release.apk
# Needs ANDROID_HOME (default ~/Library/Android/sdk) with the pinned NDK and build-tools.
set -e
APK=${1:?usage: check-native.sh <apk>}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=$SDK/ndk/30.0.16248370
READELF=$(print -l $NDK/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)
NM=$(print -l $NDK/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -1)
ZIPALIGN=$(print -l $SDK/build-tools/*/zipalign | tail -1)
WORK=$(mktemp -d)
trap 'rm -rf $WORK' EXIT
unzip -q -o $APK 'lib/*' -d $WORK

fail=0
for abi in arm64-v8a x86_64; do
  dir=$WORK/lib/$abi
  [ -d $dir ] || { echo "$abi: no libraries"; fail=1; continue; }
  total=0
  for so in $dir/libshroud_whisper.so $dir/libggml*.so $dir/libc++_shared.so; do
    [ -f $so ] || { echo "$abi: missing ${so:t}"; fail=1; continue; }
    size=$(stat -f %z $so 2>/dev/null || stat -c %s $so)
    total=$((total + size))
    # Every LOAD segment must be aligned to at least 16 KB (0x4000).
    bad=""
    for align in $($READELF -lW $so | awk '$1 == "LOAD" { print $NF }'); do
      (( align < 16384 )) && bad="$bad $align"
    done
    printf '%-14s %-44s %9d bytes  %s\n' $abi ${so:t} $size "${bad:+LOAD align $bad < 16 KB}"
    [ -z "$bad" ] || fail=1
  done
  printf '%-14s %-44s %9d bytes (%.2f MB)\n' $abi "total (Whisper + ggml + libc++_shared)" $total $((total / 1048576.0))
done

# libc++'s template type info (_ZTI/_ZTS/_ZTV in std::__ndk1, from std::regex) keeps default
# visibility on purpose, so RTTI matches across libraries sharing libc++_shared.so.
exports=$($NM -D --defined-only $WORK/lib/arm64-v8a/libshroud_whisper.so | awk '{print $3}' \
  | grep -v -E '^(Java_de_corespace_shroud_core_transcription_WhisperNative_|JNI_OnLoad$|_ZT[ISV]NSt6__ndk1)' || true)
if [ -n "$exports" ]; then
  echo "libshroud_whisper.so exports more than its JNI entry points:"; echo $exports | head -20; fail=1
else
  echo "libshroud_whisper.so exports only JNI entry points"
fi

if $ZIPALIGN -c -P 16 -v 4 $APK > $WORK/zipalign.txt 2>&1; then
  echo "zipalign -P 16: OK"
else
  echo "zipalign -P 16: FAILED"; grep -i -E "lib/.*\.so" $WORK/zipalign.txt | grep -v -i "OK" | head; fail=1
fi
exit $fail
