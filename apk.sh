#!/usr/bin/env bash
# Build a Shroud Android APK you can send to a phone.
# Same shape as ./deploy.sh: the first run asks, later runs reuse the answers.
#
#   ./apk.sh                 build with the saved options
#   ./apk.sh --init          ask again, then build
#   ./apk.sh --edit          change options, do not build
#   ./apk.sh --status        show the saved options
#   ./apk.sh --clean         this build only: clean first
#   ./apk.sh --yes           accept defaults, do not prompt
#   ./apk.sh --help
#
# Options live in android/.apk.env (not committed). Edit that file, or use --edit.
# Bash 3.2 compatible (stock macOS).

set -Eeuo pipefail

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[0;31m'
  GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; CYAN=''; NC=''
fi

step() { printf '%s→ %s%s\n' "$CYAN" "$*" "$NC"; }
ok()   { printf '%s✓ %s%s\n' "$GREEN" "$*" "$NC"; }
warn() { printf '%sWARNING: %s%s\n' "$YELLOW" "$*" "$NC" >&2; }
die()  { printf '%sERROR: %s%s\n' "$RED" "$*" "$NC" >&2; exit 1; }

CONFIG_ERROR=""
fail() { CONFIG_ERROR="$*"; return 1; }

CURRENT_STAGE="startup"
on_error() {
  local rc=$? line="$1" cmd="$2" src="${3:-$0}"
  printf '\n%sFailed during: %s%s (exit %s)\n' "$RED$BOLD" "$CURRENT_STAGE" "$NC" "$rc" >&2
  printf '  Command: %s%s%s\n' "$DIM" "$cmd" "$NC" >&2
  printf '  At:      %s%s:%s%s\n' "$DIM" "${src#./}" "$line" "$NC" >&2
  printf '  Options:  %s./apk.sh --edit%s\n' "$DIM" "$NC" >&2
  printf '  Status:   %s./apk.sh --status%s\n' "$DIM" "$NC" >&2
  exit "$rc"
}

show_help() {
  cat <<EOF

  ${BOLD}Shroud — Android APK${NC}

  ${BOLD}Usage:${NC}
    ./apk.sh                 Build with the saved options (asks the first time)
    ./apk.sh --init          Ask for the options again, then build
    ./apk.sh --edit          Change options and save. Does not build
    ./apk.sh --status        Show the saved options
    ./apk.sh --clean         Clean once, then build. Does not stick
    ./apk.sh --yes           Accept the defaults and skip questions
    ./apk.sh --help          This help

  ${BOLD}What you get:${NC}
    A signed release APK for a phone, by default. It opens on the official
    server (shroud-app.com). The file is copied to the output folder.

  ${BOLD}Options:${NC}
    Saved in android/.apk.env. Change them with ./apk.sh --edit, or edit the
    file. The keystore password is in that file. It is not committed.

    VARIANT         release | debug
    ABI             arm64-v8a (phone) | x86_64 (emulator) | both
    OUTPUT_DIR      folder the finished APK is copied to
    KEYSTORE        PKCS12 file. The same file lets an update install.
                    The default is the Shroud release key, ~/.shroud/shroud-release.p12
    KEY_ALIAS       alias inside the keystore
    CLEAN           0 | 1
    VERSION_NAME    shown in Android settings. Starts with a number (1.2.0, 1.2.0-beta)
    VERSION_CODE    positive integer. Goes up by one after a good build
    JAVA_HOME       a JDK 21 installation

  ${BOLD}Install code:${NC}
    A phone release installs as 2000 + version code. An emulator release
    installs as 4000 + version code. The next file installs over the last
    one only when the code is higher and the signing key is the same.

  ${BOLD}Release key:${NC}
    Back up the keystore and its password from android/.apk.env together.
    Without them no new release installs over the last one.

  ${BOLD}Environment:${NC}
    NO_COLOR                 disable coloured output
    SHROUD_SETUP_ASSUME_YES  same as --yes

EOF
}

row() { printf '  %-16s %s\n' "$1" "$2"; }

env_quote() {
  local out
  out=$(printf '%s' "$1" | sed "s/'/'\\\\''/g")
  printf "'%s'" "$out"
}

expand_path() {
  case "$1" in
    "~") printf '%s' "$HOME" ;;
    "~/"*) printf '%s' "$HOME/${1#"~/"}" ;;
    *) printf '%s' "$1" ;;
  esac
}

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

prompt() {
  local label="$1" default="${2:-}" value=""
  if [[ -n "$default" ]]; then
    printf '  %s %s[%s]%s: ' "$label" "$DIM" "$default" "$NC" >&2
  else
    printf '  %s: ' "$label" >&2
  fi
  if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    echo "" >&2
    value=""
  else
    read -r value || true
  fi
  printf '%s' "${value:-$default}"
}

prompt_secret() {
  local label="$1" value=""
  printf '  %s: ' "$label" >&2
  if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    echo "" >&2
    die "$label is required. Run ./apk.sh without --yes so it can ask."
  fi
  read -r -s value || true
  echo >&2
  printf '%s' "$value"
}

kind_label() {
  if [[ "$VARIANT" == "debug" ]]; then
    if [[ "$ABI" == "both" ]]; then
      printf 'Debug (local server)'
    else
      printf 'Debug, %s (local server)' "$ABI"
    fi
    return
  fi
  case "$ABI" in
    arm64-v8a) printf 'Phone release (official server)' ;;
    x86_64) printf 'Emulator release (official server)' ;;
    both) printf 'Phone and emulator release' ;;
    *) printf 'Release (%s)' "$ABI" ;;
  esac
}

server_label() {
  if [[ "$VARIANT" == "release" ]]; then
    printf 'Official · shroud-app.com'
  else
    printf 'Local · http://10.0.2.2:8080/api/v1'
  fi
}

phone_code() { printf '%s' $((2000 + 10#$VERSION_CODE)); }
emu_code() { printf '%s' $((4000 + 10#$VERSION_CODE)); }

version_label() {
  if [[ "$VARIANT" == "debug" ]]; then
    printf '%s (debug install code %s)' "$VERSION_NAME" "$VERSION_CODE"
    return
  fi
  case "$ABI" in
    arm64-v8a) printf '%s (phone install code %s)' "$VERSION_NAME" "$(phone_code)" ;;
    x86_64) printf '%s (emulator install code %s)' "$VERSION_NAME" "$(emu_code)" ;;
    both) printf '%s (phone %s, emulator %s)' "$VERSION_NAME" "$(phone_code)" "$(emu_code)" ;;
  esac
}

clean_label() {
  if [[ "$CLEAN" == "1" ]]; then printf 'yes'; else printf 'no'; fi
}

apply_kind() {
  case "$1" in
    1) VARIANT="release"; ABI="arm64-v8a" ;;
    2) VARIANT="release"; ABI="x86_64" ;;
    3) VARIANT="release"; ABI="both" ;;
    4) VARIANT="debug"; ABI="both" ;;
    *) return 1 ;;
  esac
}

kind_number() {
  if [[ "$VARIANT" == "debug" ]]; then printf '4'; return; fi
  case "$ABI" in
    arm64-v8a) printf '1' ;;
    x86_64) printf '2' ;;
    both) printf '3' ;;
    *) printf '1' ;;
  esac
}

no_newline() {
  case "$2" in
    *$'\n'*) fail "$1 cannot contain a newline"; return 1 ;;
  esac
}

validate_config() {
  CONFIG_ERROR=""
  no_newline "VARIANT" "${VARIANT:-}" || return 1
  no_newline "ABI" "${ABI:-}" || return 1
  no_newline "OUTPUT_DIR" "${OUTPUT_DIR:-}" || return 1
  no_newline "KEYSTORE" "${KEYSTORE:-}" || return 1
  no_newline "KEY_ALIAS" "${KEY_ALIAS:-}" || return 1
  no_newline "VERSION_NAME" "${VERSION_NAME:-}" || return 1
  no_newline "JAVA_HOME" "${JAVA_HOME:-}" || return 1
  case "${KEYSTORE_PASSWORD:-}" in *$'\n'*) fail "KEYSTORE_PASSWORD cannot contain a newline"; return 1 ;; esac
  case "${KEY_PASSWORD:-}" in *$'\n'*) fail "KEY_PASSWORD cannot contain a newline"; return 1 ;; esac

  case "$VARIANT" in
    release|debug) ;;
    *) fail "VARIANT must be release or debug"; return 1 ;;
  esac
  case "$ABI" in
    arm64-v8a|x86_64|both) ;;
    *) fail "ABI must be arm64-v8a, x86_64, or both"; return 1 ;;
  esac
  case "$CLEAN" in
    0|1) ;;
    *) fail "CLEAN must be 0 or 1"; return 1 ;;
  esac
  case "$VERSION_CODE" in
    ''|*[!0-9]*) fail "VERSION_CODE must be a positive integer"; return 1 ;;
  esac
  if [[ $((10#$VERSION_CODE)) -lt 1 ]]; then
    fail "VERSION_CODE must be a positive integer"
    return 1
  fi
  VERSION_CODE=$((10#$VERSION_CODE))
  case "$VERSION_NAME" in
    "") fail "VERSION_NAME is empty"; return 1 ;;
    *[!0-9A-Za-z._+-]*)
      fail "VERSION_NAME may use letters, numbers, dots, dashes, underscores, and plus"
      return 1
      ;;
  esac
  # The server's update check reads the release number at the start (0.2.0-beta is fine) and
  # refuses a name without one, so the app could never be told to update.
  case "$VERSION_NAME" in
    [0-9]*) ;;
    *) fail "VERSION_NAME must start with a number, like 1.2.0 or 1.2.0-beta"; return 1 ;;
  esac
  if [[ -z "$OUTPUT_DIR" ]]; then
    fail "OUTPUT_DIR is empty"
    return 1
  fi
  OUTPUT_DIR=$(expand_path "$OUTPUT_DIR")
  if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
    fail "JAVA_HOME must be a JDK 21 home (no java at ${JAVA_HOME:-<unset>}/bin/java)"
    return 1
  fi
  local major line
  line=$("$JAVA_HOME/bin/java" -version 2>&1 || true)
  line=${line%%$'\n'*}
  major=$(printf '%s\n' "$line" | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p')
  if [[ "$major" != "21" ]]; then
    fail "JAVA_HOME is not JDK 21 ($line)"
    return 1
  fi
  if [[ -z "$KEYSTORE" || -z "$KEY_ALIAS" || -z "$KEYSTORE_PASSWORD" || -z "$KEY_PASSWORD" ]]; then
    fail "the signing key is incomplete. Run ./apk.sh --edit"
    return 1
  fi
  if [[ ! -f "$KEYSTORE" ]]; then
    fail "keystore not found: $KEYSTORE"
    return 1
  fi
  return 0
}

write_config() {
  local tmp
  mkdir -p "$(dirname "$CONFIG")"
  tmp=$(mktemp "${CONFIG}.XXXXXX")
  cat > "$tmp" <<EOF
# Options for ./apk.sh. Change them with ./apk.sh --edit, or edit this file.
# The keystore password is in here. This file is not committed.
#
# VARIANT         release | debug
# ABI             arm64-v8a | x86_64 | both
# OUTPUT_DIR      folder the finished APK is copied to
# KEYSTORE        PKCS12 file. Keep the same file so updates install. Back it up with the
#                 passwords below: without both, no release installs over the last one.
# KEY_ALIAS       alias inside the keystore
# CLEAN           0 | 1
# VERSION_NAME    shown in Android settings. Starts with a number (1.2.0, 1.2.0-beta)
# VERSION_CODE    positive integer. Goes up by one after a good build.
# JAVA_HOME       JDK 21
VARIANT=$(env_quote "$VARIANT")
ABI=$(env_quote "$ABI")
OUTPUT_DIR=$(env_quote "$OUTPUT_DIR")
KEYSTORE=$(env_quote "$KEYSTORE")
KEY_ALIAS=$(env_quote "$KEY_ALIAS")
KEYSTORE_PASSWORD=$(env_quote "$KEYSTORE_PASSWORD")
KEY_PASSWORD=$(env_quote "$KEY_PASSWORD")
CLEAN=$(env_quote "$CLEAN")
VERSION_NAME=$(env_quote "$VERSION_NAME")
VERSION_CODE=$(env_quote "$VERSION_CODE")
JAVA_HOME=$(env_quote "$JAVA_HOME")
EOF
  if [[ -n "${LAST_APK:-}" ]]; then
    printf 'LAST_APK=%s\n' "$(env_quote "$LAST_APK")" >> "$tmp"
  fi
  chmod 600 "$tmp"
  mv "$tmp" "$CONFIG"
}

load_config() {
  [[ -f "$CONFIG" ]] || return 1
  # shellcheck disable=SC1090
  source "$CONFIG"
  VARIANT="${VARIANT:-}"
  ABI="${ABI:-}"
  OUTPUT_DIR="${OUTPUT_DIR:-}"
  KEYSTORE="${KEYSTORE:-}"
  KEY_ALIAS="${KEY_ALIAS:-shroud}"
  KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
  KEY_PASSWORD="${KEY_PASSWORD:-}"
  CLEAN="${CLEAN:-0}"
  VERSION_NAME="${VERSION_NAME:-0.1.0}"
  VERSION_CODE="${VERSION_CODE:-1}"
  JAVA_HOME="${JAVA_HOME:-}"
  LAST_APK="${LAST_APK:-}"
}

detect_java() {
  local candidate="/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home"
  if [[ -x "$candidate/bin/java" ]]; then
    printf '%s' "$candidate"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    printf '%s' "$JAVA_HOME"
    return
  fi
  if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    /usr/libexec/java_home -v 21
    return
  fi
  printf ''
}

RELEASE_KEYSTORE="$HOME/.shroud/shroud-release.p12"
OLD_TEST_KEYSTORE="$HOME/.shroud/apk-test.p12"

# keytool reads the password from the environment, so it never shows in the process list.
verify_keystore() {
  SHROUD_KEYSTORE_PASS="$KEYSTORE_PASSWORD" "$JAVA_HOME/bin/keytool" -list \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -storepass:env SHROUD_KEYSTORE_PASS >/dev/null 2>&1
}

# SHA-256 of the signing certificate, lowercase hex: what apksigner prints as the
# "certificate SHA-256 digest".
cert_sha256() {
  SHROUD_KEYSTORE_PASS="$KEYSTORE_PASSWORD" "$JAVA_HOME/bin/keytool" -exportcert \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -storepass:env SHROUD_KEYSTORE_PASS 2>/dev/null |
    openssl dgst -sha256 -r | cut -d' ' -f1
}

# RSA 4096, valid for 30 years: Android never accepts a new key for an installed app, so this
# key signs every release for as long as Shroud ships.
create_release_key() {
  local pass dir
  dir=$(dirname "$KEYSTORE")
  mkdir -p "$dir"
  chmod 700 "$dir"
  command -v openssl >/dev/null 2>&1 || die "openssl is required to create the release key"
  pass=$(openssl rand -hex 32)
  KEYSTORE_PASSWORD="$pass"
  KEY_PASSWORD="$pass"
  local err
  err=$(mktemp)
  if ! SHROUD_KEYSTORE_PASS="$pass" "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10958 \
    -storepass:env SHROUD_KEYSTORE_PASS -keypass:env SHROUD_KEYSTORE_PASS \
    -dname "CN=Shroud, O=Shroud" >"$err" 2>&1; then
    cat "$err" >&2
    rm -f "$err"
    die "could not create the release key"
  fi
  rm -f "$err"
  chmod 600 "$KEYSTORE"
  ok "Release key created at $KEYSTORE"
  echo "  Certificate SHA-256: $(cert_sha256)"
  echo "  Back up this file and the password in android/.apk.env, somewhere safe and offline."
  echo "  Without them no new release installs over the last one. Phones would have to"
  echo "  uninstall Shroud, and lose its data, to take a build signed with another key."
}

choose_release_key() {
  if [[ "${KEYSTORE:-}" != "$RELEASE_KEYSTORE" ]]; then
    KEYSTORE_PASSWORD=""
    KEY_PASSWORD=""
  fi
  KEYSTORE="$RELEASE_KEYSTORE"
  KEY_ALIAS="shroud"
  reuse_or_create_release_key
}

reuse_or_create_release_key() {
  KEYSTORE="${KEYSTORE:-$RELEASE_KEYSTORE}"
  KEY_ALIAS="${KEY_ALIAS:-shroud}"
  if [[ -f "$KEYSTORE" && -n "${KEYSTORE_PASSWORD:-}" && -n "${KEY_PASSWORD:-}" ]]; then
    verify_keystore || die "could not open $KEYSTORE. Run ./apk.sh --edit and check the key."
    ok "Signing key reused ($KEYSTORE)"
    return
  fi
  if [[ -f "$KEYSTORE" ]]; then
    KEYSTORE_PASSWORD=$(prompt_secret "Keystore password")
    KEY_PASSWORD=$(prompt_secret "Key password (Enter if it is the same)")
    [[ -n "$KEY_PASSWORD" ]] || KEY_PASSWORD="$KEYSTORE_PASSWORD"
    verify_keystore || die "could not open $KEYSTORE with that password and alias $KEY_ALIAS"
    ok "Signing key reused ($KEYSTORE)"
    return
  fi
  create_release_key
}

ask_existing_keystore() {
  local path alias
  path=$(prompt "Keystore path" "${KEYSTORE:-}")
  [[ -n "$path" ]] || die "a keystore path is required"
  KEYSTORE=$(expand_path "$path")
  [[ -f "$KEYSTORE" ]] || die "keystore not found: $KEYSTORE"
  alias=$(prompt "Key alias" "${KEY_ALIAS:-shroud}")
  [[ -n "$alias" ]] || die "a key alias is required"
  KEY_ALIAS="$alias"
  KEYSTORE_PASSWORD=$(prompt_secret "Keystore password")
  KEY_PASSWORD=$(prompt_secret "Key password (Enter if it is the same)")
  [[ -n "$KEY_PASSWORD" ]] || KEY_PASSWORD="$KEYSTORE_PASSWORD"
  verify_keystore || die "could not open $KEYSTORE with that password and alias $KEY_ALIAS"
  ok "Signing key: $KEYSTORE"
}

ask_signing() {
  echo ""
  echo "${BOLD}── Signing key ──${NC}"
  echo "  The same key lets the next APK install over the one already on the phone."
  echo "  1) Shroud release key, created once and reused"
  echo "  2) A keystore I already have"
  local choice
  choice=$(prompt "Choice" "1")
  case "$choice" in
    1) choose_release_key ;;
    2) ask_existing_keystore ;;
    *) fail "pick 1 or 2"; return 1 ;;
  esac
}

ask_kind() {
  echo ""
  echo "${BOLD}── What do you want to build? ──${NC}"
  echo "  1) Phone release      official server, arm64"
  echo "  2) Emulator release   official server, x86_64"
  echo "  3) Both releases      arm64 and x86_64"
  echo "  4) Debug              local server at 10.0.2.2:8080, for an emulator here"
  local choice
  choice=$(prompt "Choice" "$(kind_number)")
  if ! apply_kind "$choice"; then
    fail "pick 1, 2, 3, or 4"
    return 1
  fi
}

ask_clean() {
  local answer
  answer=$(lower "$(prompt "Clean rebuild every time? y/N" "$(clean_label)")")
  case "$answer" in
    y|yes) CLEAN=1 ;;
    n|no) CLEAN=0 ;;
    *) fail "answer y or n"; return 1 ;;
  esac
}

print_summary() {
  echo ""
  echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"
  echo "${CYAN}${BOLD}  Shroud — Android APK${NC}"
  echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"
  echo ""
  row "Kind" "$(kind_label)"
  row "Server" "$(server_label)"
  row "Output" "$OUTPUT_DIR"
  row "Signing key" "$KEYSTORE"
  row "Version" "$(version_label)"
  row "Clean" "$(clean_label)"
  row "JDK" "$JAVA_HOME"
  if [[ -n "${LAST_APK:-}" && -f "$LAST_APK" ]]; then
    row "Previous file" "$LAST_APK"
  fi
  echo ""
  warn_old_test_key
}

warn_old_test_key() {
  [[ "$KEYSTORE" == "$OLD_TEST_KEYSTORE" ]] || return 0
  warn "this signs with the old test key, not the Shroud release key.
  Switch with ./apk.sh --edit, 3, then 1. A phone with a test-key build has to uninstall
  Shroud once before it takes a release-key build."
}

check_checkout() {
  [[ -x "$REPO_ROOT/android/gradlew" ]] ||
    die "android/gradlew is missing. Run ./apk.sh from the repository root."
  [[ -f "$REPO_ROOT/android/app/build.gradle.kts" ]] ||
    die "android/app/build.gradle.kts is missing."
}

default_output_dir() {
  if [[ -d "$HOME/Desktop" ]]; then
    printf '%s' "$HOME/Desktop/Shroud"
  else
    printf '%s' "$REPO_ROOT/android/dist"
  fi
}

run_wizard() {
  if [[ ! -t 0 && "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]]; then
    die "the setup questions need an interactive terminal.
  Run ./apk.sh --init from a terminal, or write android/.apk.env and re-run."
  fi
  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_HOME=$(detect_java)
  fi
  [[ -n "$JAVA_HOME" ]] || die "JDK 21 was not found. Install it, then run ./apk.sh --edit."
  VARIANT="${VARIANT:-release}"
  ABI="${ABI:-arm64-v8a}"
  CLEAN="${CLEAN:-0}"
  VERSION_NAME="${VERSION_NAME:-0.1.0}"
  VERSION_CODE="${VERSION_CODE:-1}"
  KEY_ALIAS="${KEY_ALIAS:-shroud}"
  KEYSTORE="${KEYSTORE:-$RELEASE_KEYSTORE}"
  KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
  KEY_PASSWORD="${KEY_PASSWORD:-}"
  LAST_APK="${LAST_APK:-}"
  OUTPUT_DIR="${OUTPUT_DIR:-$(default_output_dir)}"

  echo ""
  echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"
  echo "${CYAN}${BOLD}  Shroud — Android APK${NC}"
  echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"

  ask_kind || die "$CONFIG_ERROR"
  echo ""
  echo "${BOLD}── Where should the APK go? ──${NC}"
  OUTPUT_DIR=$(expand_path "$(prompt "Folder" "$OUTPUT_DIR")")
  ask_signing || die "$CONFIG_ERROR"
  echo ""
  echo "${BOLD}── Version ──${NC}"
  echo "  Android installs an update only when this code is higher than the one"
  echo "  already on the phone. It goes up by one after each build that works."
  VERSION_NAME=$(prompt "Version name" "$VERSION_NAME")
  VERSION_CODE=$(prompt "Version code" "$VERSION_CODE")
  ask_clean || die "$CONFIG_ERROR"
  if ! validate_config; then
    die "$CONFIG_ERROR"
  fi
  write_config
  ok "Saved options in android/.apk.env"
}

edit_menu() {
  while true; do
    echo ""
    echo "${BOLD}── Options ──${NC}"
    echo "  Change a number. Enter saves and leaves. The file is android/.apk.env."
    echo ""
    row "1  Kind" "$(kind_label)"
    row "2  Output folder" "$OUTPUT_DIR"
    row "3  Signing key" "$KEYSTORE"
    row "4  Version name" "$VERSION_NAME"
    row "5  Version code" "$VERSION_CODE"
    row "6  Clean rebuild" "$(clean_label)"
    row "7  JDK" "$JAVA_HOME"
    echo ""
    local choice value
    choice=$(prompt "Number to change" "")
    case "$choice" in
      "")
        if ! validate_config; then
          printf '%sERROR: %s%s\n' "$RED" "$CONFIG_ERROR" "$NC" >&2
          continue
        fi
        write_config
        ok "Saved options in android/.apk.env"
        return 0
        ;;
      1) ask_kind || printf '%sERROR: %s%s\n' "$RED" "$CONFIG_ERROR" "$NC" >&2 ;;
      2)
        value=$(prompt "Output folder" "$OUTPUT_DIR")
        OUTPUT_DIR=$(expand_path "$value")
        ;;
      3) ask_signing ;;
      4) VERSION_NAME=$(prompt "Version name" "$VERSION_NAME") ;;
      5) VERSION_CODE=$(prompt "Version code" "$VERSION_CODE") ;;
      6)
        if ! ask_clean; then
          printf '%sERROR: %s%s\n' "$RED" "$CONFIG_ERROR" "$NC" >&2
        fi
        ;;
      7) JAVA_HOME=$(prompt "JDK 21 home" "$JAVA_HOME") ;;
      *) echo "  Pick 1–7, or Enter." ;;
    esac
    if ! validate_config; then
      printf '%sERROR: %s%s\n' "$RED" "$CONFIG_ERROR" "$NC" >&2
      printf '  That change was not saved.\n' >&2
      load_config || true
    else
      write_config
    fi
  done
}

show_status() {
  print_summary
  echo "  Change an option:  ${BOLD}./apk.sh --edit${NC}"
  echo "  Build:             ${BOLD}./apk.sh${NC}"
  echo ""
}

confirm_build() {
  if [[ ! -t 0 || "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    return 0
  fi
  while true; do
    printf '  %s[Enter]%s build    %se%s change an option    %sq%s quit: ' \
      "$DIM" "$NC" "$DIM" "$NC" "$DIM" "$NC"
    local choice=""
    read -r choice || true
    case "$(lower "$choice")" in
      ""|y|yes) return 0 ;;
      e|edit)
        edit_menu
        print_summary
        ;;
      q|n|no)
        echo "Cancelled."
        exit 0
        ;;
      *) echo "  Enter, e, or q." ;;
    esac
  done
}

copy_one() {
  local src="$1" dest="$2"
  cp "$src" "$dest"
  if [[ -z "${COPIED:-}" ]]; then
    COPIED="$dest"
  else
    COPIED="${COPIED}"$'\n'"${dest}"
  fi
}

copy_outputs() {
  local out_dir="$OUTPUT_DIR" abi src unsigned tag dest
  mkdir -p "$out_dir"
  COPIED=""
  if [[ "$VARIANT" == "debug" ]]; then
    src="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$src" ]] || die "the debug APK was not produced ($src)"
    dest="$out_dir/shroud-debug-${VERSION_NAME}-c${VERSION_CODE}.apk"
    copy_one "$src" "$dest"
    return
  fi
  local list="$ABI"
  if [[ "$ABI" == "both" ]]; then
    list="arm64-v8a x86_64"
  fi
  for abi in $list; do
    src="$REPO_ROOT/android/app/build/outputs/apk/release/app-${abi}-release.apk"
    unsigned="$REPO_ROOT/android/app/build/outputs/apk/release/app-${abi}-release-unsigned.apk"
    if [[ ! -f "$src" && -f "$unsigned" ]]; then
      die "the $abi APK is unsigned, so a phone will not install it. Check the signing key with ./apk.sh --edit"
    fi
    if [[ ! -f "$src" ]]; then
      die "the $abi APK was not produced ($src)"
    fi
    tag="$abi"
    if [[ "$abi" == "arm64-v8a" ]]; then
      tag="arm64"
    fi
    dest="$out_dir/shroud-${VERSION_NAME}-c${VERSION_CODE}-${tag}.apk"
    copy_one "$src" "$dest"
  done
}

build_apk() {
  local do_clean="$CLEAN"
  if [[ "${ONE_SHOT_CLEAN:-0}" == "1" ]]; then
    do_clean=1
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
  if [[ "$VARIANT" == "release" ]]; then
    export SHROUD_RELEASE_STORE="$KEYSTORE"
    export SHROUD_RELEASE_STORE_PASSWORD="$KEYSTORE_PASSWORD"
    export SHROUD_RELEASE_KEY_ALIAS="$KEY_ALIAS"
    export SHROUD_RELEASE_KEY_PASSWORD="$KEY_PASSWORD"
  fi

  local gradle_args
  gradle_args=(./gradlew --console=plain)
  if [[ "$do_clean" == "1" ]]; then
    gradle_args+=(clean)
  fi
  if [[ "$VARIANT" == "release" ]]; then
    gradle_args+=(:app:assembleRelease)
  else
    gradle_args+=(:app:assembleDebug)
  fi
  gradle_args+=("-PshroudVersionCode=$VERSION_CODE" "-PshroudVersionName=$VERSION_NAME")
  if [[ "$ABI" != "both" ]]; then
    gradle_args+=("-PshroudAbi=$ABI")
  fi

  step "Building the APK. Native code can take several minutes the first time."
  (
    cd "$REPO_ROOT/android"
    "${gradle_args[@]}"
  )

  CURRENT_STAGE="copy APK"
  copy_outputs
  LAST_APK=${COPIED%%$'\n'*}
  local built_code="$VERSION_CODE"
  VERSION_CODE=$((10#$VERSION_CODE + 1))
  write_config

  echo ""
  ok "APK ready."
  local file
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    printf '  %s\n' "$file"
  done <<EOF
$COPIED
EOF
  echo ""
  if [[ "$VARIANT" == "release" ]]; then
    echo "  The app opens on the official server. Install the file from the phone's Files app."
  else
    echo "  This debug build talks to 10.0.2.2:8080, which is an emulator on this machine."
    echo "  It does not install over a release build. Uninstall that build first."
  fi
  echo "  Next build is version code ${VERSION_CODE} (this one was ${built_code}), so it installs over this one."
  echo "  Change an option: ${BOLD}./apk.sh --edit${NC}"
  echo ""
}

main() {
  trap 'on_error "$LINENO" "$BASH_COMMAND" "${BASH_SOURCE[0]}"' ERR

  cd "$(dirname "${BASH_SOURCE[0]}")" || die "cannot enter the repository directory."
  REPO_ROOT=$(pwd)
  CONFIG="$REPO_ROOT/android/.apk.env"

  local cmd="build"
  ONE_SHOT_CLEAN=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help|help) cmd="help" ;;
      --init|init) cmd="init" ;;
      --edit|edit) cmd="edit" ;;
      --status|status) cmd="status" ;;
      --clean|clean) ONE_SHOT_CLEAN=1 ;;
      --yes|-y) export SHROUD_SETUP_ASSUME_YES=1 ;;
      *) die "unknown option: $1
  Valid: --init --edit --status --clean --yes --help" ;;
    esac
    shift
  done

  if [[ "$cmd" == "help" ]]; then
    show_help
    exit 0
  fi

  CURRENT_STAGE="preflight"
  check_checkout

  case "$cmd" in
    status)
      load_config || die "no options yet. Run ./apk.sh"
      if ! validate_config; then
        die "$CONFIG_ERROR"
      fi
      show_status
      exit 0
      ;;
    edit)
      if ! load_config; then
        CURRENT_STAGE="setup"
        run_wizard
        exit 0
      fi
      if ! validate_config; then
        die "$CONFIG_ERROR"
      fi
      CURRENT_STAGE="edit options"
      edit_menu
      exit 0
      ;;
    init)
      if [[ -f "$CONFIG" && "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]]; then
        echo ""
        echo "${YELLOW}Saved options found in android/.apk.env.${NC}"
        local overwrite
        overwrite=$(lower "$(prompt "Start over? y/N" "n")")
        case "$overwrite" in
          y|yes) ;;
          *) echo "Cancelled."; exit 0 ;;
        esac
        load_config || true
      elif [[ -f "$CONFIG" ]]; then
        load_config || true
      fi
      CURRENT_STAGE="setup"
      run_wizard
      ;;
    build)
      if ! load_config; then
        CURRENT_STAGE="setup"
        run_wizard
      else
        if ! validate_config; then
          die "$CONFIG_ERROR"
        fi
        print_summary
        confirm_build
      fi
      ;;
  esac

  if ! validate_config; then
    die "$CONFIG_ERROR"
  fi
  CURRENT_STAGE="gradle build"
  local started=$SECONDS
  build_apk
  local elapsed=$((SECONDS - started))
  ok "Finished in $((elapsed / 60))m $((elapsed % 60))s."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
