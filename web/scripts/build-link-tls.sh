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
echo "link TLS module written to src/linkPreview/tls/"
