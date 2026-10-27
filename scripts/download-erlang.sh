#!/usr/bin/env bash
set -euo pipefail

# Download Erlang/OTP from the Termux APT repo and place it in the
# toolchain_erlang asset pack module.
#
# Modelled on download-ruby.sh and download-perl.sh: the same signed index, the
# same digest check on each .deb, the same ELF gate before anything is written
# for the app to read.
#
# How it starts here. `erl` is a /system/bin/sh script that finds the release
# root from its own path (ROOTDIR, so the pack's tree rather than Termux's) and
# execs erts-*/bin/erlexec, which execs beam.smp; the emulator starts
# erl_child_setup, and that starts ports with /system/bin/sh. Every one of those
# is an execv or execve through libc, which the terminal's exec interceptor
# wraps, so each runs from under filesDir. `erlc`, `escript` and the other ELF
# commands in bin/ find `erl` beside their own argv[0] the same way.
#
# Left out: each application's src/ (52 MB of .erl files the release does not
# load; include/ stays, so -include_lib works), the static erl_interface
# archives, and the man pages. Anything whose library dependencies the device
# cannot meet is listed below and removed rather than shipped broken.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_erlang/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    erlang
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Erlang Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-erlang.tsv "${REQUIRED_PACKAGES[@]}"
ERLANG_VERSION="$(termux_pkg_version erlang)"
echo "  Erlang version: $ERLANG_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Erlang toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/lib"

ERL_SRC="extracted/erlang/data/data/com.termux/files/usr/lib/erlang"
ERTS_DIR="$(cd "$ERL_SRC" && ls -d erts-* | head -1)"
if [ -z "$ERTS_DIR" ] || [ ! -f "$ERL_SRC/bin/erl" ] || [ ! -f "$ERL_SRC/$ERTS_DIR/bin/beam.smp" ]; then
    echo "  ERROR: no bin/erl or erts-*/bin/beam.smp under $ERL_SRC" >&2
    exit 1
fi
ERL="$PACK_ASSETS/usr/lib/erlang"
cp -RL "$ERL_SRC" "$ERL"
for app in "$ERL"/lib/*/; do
    rm -rf "$app/src"
done
find "$ERL" -name '*.a' -delete
rm -rf "$ERL/misc" "$ERL/Install"

# Drivers and NIFs for libraries this app does not ship: ODBC (libodbc) and wx
# (wxWidgets). Each would fail to load on the device, so the whole application
# goes; nothing else in OTP needs either.
for app in odbc wx; do
    for dir in "$ERL"/lib/"$app"-*; do
        [ -d "$dir" ] || continue
        rm -rf "$dir"
        echo "  removed lib/$(basename "$dir") (needs a library the device does not have)"
    done
done
echo "  lib/erlang/ ($ERTS_DIR, $(ls "$ERL/lib" | wc -l | tr -d ' ') applications)"

# A few tools in the applications' bin/ and priv/ start with a #! line naming
# Termux's sh or env, which this app cannot read. Android's own are the same
# programs at /system/bin, so the line is pointed there; nothing else in the
# file changes.
python3 - "$ERL" <<'SHEBANGEOF'
import os, sys
OLD = (b"#!/data/data/com.termux/files/usr/bin/sh", b"#!/data/data/com.termux/files/usr/bin/env")
NEW = (b"#!/system/bin/sh", b"#!/system/bin/env")
for dirpath, _, files in os.walk(sys.argv[1]):
    for name in files:
        path = os.path.join(dirpath, name)
        with open(path, "rb") as f:
            data = f.read()
        line, sep, rest = data.partition(b"\n")
        for old, new in zip(OLD, NEW):
            if line == old or line.startswith(old + b" "):
                with open(path, "wb") as f:
                    f.write(new + line[len(old):] + sep + rest)
                print("  #! line repointed: " + os.path.relpath(path, sys.argv[1]))
                break
SHEBANGEOF

# --- Step 5: Place the upstream notice beside what it describes ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_erlang.json ---
echo ""
echo "Writing toolchain_erlang.json..."

# binaries: every ELF, so each gets the execute bit. The commands are the ones in
# bin/; erts-*/bin holds the runtime they start (erlexec, beam.smp,
# erl_child_setup, heart, inet_gethost) and its own copies of the bin/ commands,
# which the runtime starts by that path (epmd, for a distributed node). Both
# copies are listed, so a name in both gets two identical shell functions and
# the second wins. An ELF under lib/<app>/priv is under the install root's
# lib/, which ToolchainManager does not make a command.
#
# erl is the one command that is a script. Its interpreter is bash, which is
# not an ELF this manifest ships, so it is reached through the shell function
# and through PATH, where the interceptor runs its #!/system/bin/sh line. The
# scripts are in `binaries` too, for the execute bit: a shell searching PATH
# skips a file without it, and that search is how erlc, escript and Elixir's
# scripts reach `erl`. So is every script under an application's bin/ or priv/.
is_script() {
    [ "$(dd if="$1" bs=2 count=1 2>/dev/null)" = "#!" ]
}
BINARIES='['
FIRST=true
add_binary() {
    if [ "$FIRST" = true ]; then FIRST=false; else BINARIES+=','; fi
    BINARIES+="\"${1#$PACK_ASSETS/}\""
}
for obj in "$ERL/bin"/* "$ERL/$ERTS_DIR/bin"/*; do
    case "$obj" in *.src) continue ;; esac
    { is_elf "$obj" || is_script "$obj"; } && add_binary "$obj"
done
while IFS= read -r obj; do
    case "$obj" in *.so) continue ;; esac
    { is_elf "$obj" || is_script "$obj"; } && add_binary "$obj"
done < <(find "$ERL/lib" -type f \( -path '*/priv/*' -o -path '*/bin/*' \))
BINARIES+=']'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_erlang.json" << EOF
{
    "name": "erlang",
    "displayName": "Erlang/OTP",
    "version": "$ERLANG_VERSION",
    "binaries": $BINARIES,
    "env": {},
    "pathDirs": ["usr/lib/erlang/bin"],
    "installRoot": "usr/lib/erlang",
    "ownedPaths": [$OWNED_PATHS],
    "libs": [],
    "scriptWrappers": {
        "interpreter": "bash",
        "scripts": {"erl": "usr/lib/erlang/bin/erl"}
    }
}
EOF
echo "  toolchain_erlang.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Erlang binaries ==="
# Against the base APK's usr/lib, which a toolchain installs beside: ncurses,
# zlib, libc++ and OpenSSL's libcrypto are the libraries these need beyond the
# system's, and the base ships each.
verify_failures=0
verify_checked=0
while IFS= read -r obj; do
    is_elf "$obj" || continue
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$obj" \
                   --lib-dir "$ROOT_DIR/android/app/src/main/assets/usr/lib" 2>&1); then
        echo "  FAILED  ${obj#$PACK_ASSETS/}" >&2
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
done < <(find "$PACK_ASSETS/usr" -type f)
if [ "$verify_failures" -gt 0 ]; then
    echo "  ERROR: $verify_failures of $verify_checked Erlang binaries would fail to load on a device." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"

echo ""
echo "=== Verifying no file in the pack names a shell it cannot reach ==="
python3 "$SCRIPT_DIR/patch-default-shell.py" --check "$PACK_ASSETS"

echo ""
echo "=== Verifying the pack carries no symbolic link ==="
link_count=$(find "$PACK_ASSETS" -type l | wc -l | tr -d ' ')
if [ "$link_count" -ne 0 ]; then
    echo "  ERROR: $link_count symbolic link(s) in the pack:" >&2
    find "$PACK_ASSETS" -type l | sed "s|^$PACK_ASSETS/|     |" >&2
    exit 1
fi
echo "  none"

# --- Step 8: Size summary ---
echo ""
echo "=== Erlang Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Erlang download complete ==="
