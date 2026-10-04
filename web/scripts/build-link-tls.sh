#!/bin/sh
# Builds the link-preview TLS client (web/tls: rustls compiled to WebAssembly) into
# src/linkPreview/tls/, where the web client loads it on first use (linkPreview/tls.ts).
#
# Needs: the wasm32-unknown-unknown Rust target, clang with a WebAssembly backend (ring's C
# code), an llvm-ar (rustup's `llvm-tools` component ships one), and wasm-bindgen-cli at the
# exact version pinned in tls/Cargo.toml. The web Docker image's first stage provides all four.
set -eu
cd "$(dirname "$0")/.."

: "${CC_wasm32_unknown_unknown:=clang}"
if [ -z "${AR_wasm32_unknown_unknown:-}" ]; then
  host="$(rustc -vV | sed -n 's/^host: //p')"
  rustup_ar="$(rustc --print sysroot)/lib/rustlib/${host}/bin/llvm-ar"
  if [ -x "$rustup_ar" ]; then
    AR_wasm32_unknown_unknown="$rustup_ar"
  else
    AR_wasm32_unknown_unknown="llvm-ar"
  fi
fi
export CC_wasm32_unknown_unknown AR_wasm32_unknown_unknown

cargo build --manifest-path tls/Cargo.toml --release --target wasm32-unknown-unknown --locked
wasm-bindgen --target web --out-dir src/linkPreview/tls --out-name link_tls \
  tls/target/wasm32-unknown-unknown/release/link_tls.wasm

# The crates compiled in and their license files, from the registry the build just used, for
# Settings → About Shroud → Open-Source Licenses (vite.config.ts adds them to licenses.json).
licenses=src/linkPreview/tls/licenses
registry="${CARGO_HOME:-$HOME/.cargo}/registry/src"
rm -rf "$licenses"
mkdir -p "$licenses"
: >"$licenses/index.tsv"
cargo tree --manifest-path tls/Cargo.toml --locked --target wasm32-unknown-unknown \
  --edges normal,no-proc-macro --prefix none --no-dedupe --format '{p}|{l}' |
  grep -v ' (/' | sort -u |
  while IFS='|' read -r package license; do
    name="${package% v*}"
    version="${package##* v}"
    printf '%s\t%s\t%s\n' "$name" "$version" "$license" >>"$licenses/index.tsv"
    mkdir -p "$licenses/$name-$version"
    for file in "$registry"/*/"$name-$version"/*; do
      case "$(basename "$file")" in
        LICEN[SC]E*|[Ll]icen[sc]e*|COPYING*|NOTICE*) cp "$file" "$licenses/$name-$version/" ;;
      esac
    done
  done
echo "link TLS module written to src/linkPreview/tls/"
