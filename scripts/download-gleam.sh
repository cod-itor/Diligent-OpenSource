#!/usr/bin/env bash
set -euo pipefail

# Download the Gleam compiler from the Termux APT repo and place it in the
# toolchain_gleam asset pack module.
#
# Modelled on download-lua.sh: the same signed index, the same digest check on
# each .deb, the same ELF gate before anything is written for the app to read.
#
# gleam is one ELF that compiles Gleam to Erlang and then runs the Erlang tools
# (erlc, erl, escript) by name, with fork and execvp from libc, which the
# terminal's exec interceptor wraps. Those tools are the Erlang pack's, which
# ToolchainRegistry names in `requires`, and the install is refused until
# Erlang/OTP is there.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_gleam/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    gleam
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Gleam Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-gleam.tsv "${REQUIRED_PACKAGES[@]}"
GLEAM_VERSION="$(termux_pkg_version gleam)"
echo "  Gleam version: $GLEAM_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Gleam toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin"

SRC="extracted/gleam/data/data/com.termux/files/usr/bin/gleam"
if [ ! -f "$SRC" ]; then
    echo "  ERROR: $SRC is not in the gleam package" >&2
    exit 1
fi
cp -L "$SRC" "$PACK_ASSETS/usr/bin/gleam"
echo "  bin/gleam"

# --- Step 5: Place the upstream notice beside what it describes ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_gleam.json ---
echo ""
echo "Writing toolchain_gleam.json..."

# gleam is a real binary, so it gets the shell function and the exec table row
# every ELF in `binaries` gets. `env` is present even when empty:
# regenerateEnvFileLocked skips a toolchain with no `env` object entirely.
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_gleam.json" << EOF
{
    "name": "gleam",
    "displayName": "Gleam",
    "version": "$GLEAM_VERSION",
    "binaries": ["usr/bin/gleam"],
    "env": {},
    "pathDirs": ["usr/bin"],
    "ownedPaths": [$OWNED_PATHS],
    "libs": []
}
EOF
echo "  toolchain_gleam.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Gleam binaries ==="
# Against the base APK's usr/lib, which a toolchain installs beside, though
# gleam needs nothing beyond the system's libc.
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
    echo "  ERROR: $verify_failures of $verify_checked Gleam binaries would fail to load on a device." >&2
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
echo "=== Gleam Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Gleam download complete ==="
