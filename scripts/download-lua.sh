#!/usr/bin/env bash
set -euo pipefail

# Download Lua 5.4 from the Termux APT repo and place it in the toolchain_lua
# asset pack module.
#
# Modelled on download-ruby.sh and download-php.sh: the same signed index, the
# same digest check on each .deb, the same ELF gate before anything is written
# for the app to read.
#
# The smallest pack there is. Termux links the interpreter statically against
# liblua, so `lua5.4` needs nothing beyond readline, which the base runtime
# already ships, and the pack carries no library of its own. The two commands
# are placed as `lua` and `luac`, the names a user types; Termux reaches them
# through update-alternatives, which nothing runs here.
#
# os.execute() and io.popen() go through bionic's system() and popen(), which
# use /system/bin/sh, so unlike php and libperl there is no compiled-in shell to
# repoint. os.tmpname() is the one Termux path still compiled in (usr/tmp under
# Termux's prefix) and it fails here; io.tmpfile() does not use it.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_lua/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    lua54
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Lua Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-lua.tsv "${REQUIRED_PACKAGES[@]}"
LUA_VERSION="$(termux_pkg_version lua54)"
echo "  Lua version: $LUA_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Lua toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin"

LUA_USR="extracted/lua54/data/data/com.termux/files/usr"
for pair in "lua5.4:lua" "luac5.4:luac"; do
    src="$LUA_USR/bin/${pair%%:*}"
    if [ ! -f "$src" ]; then
        echo "  ERROR: $src is not in the lua54 package" >&2
        exit 1
    fi
    cp -L "$src" "$PACK_ASSETS/usr/bin/${pair##*:}"
    echo "  bin/${pair##*:} (from ${pair%%:*})"
done

# --- Step 5: Place the upstream notice beside what it describes ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_lua.json ---
echo ""
echo "Writing toolchain_lua.json..."

# Both commands are real binaries, so each gets the shell function and the exec
# table row every ELF in `binaries` gets. `env` is present and empty rather
# than absent: regenerateEnvFileLocked skips a toolchain with no `env` object
# entirely, functions and PATH included.
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_lua.json" << EOF
{
    "name": "lua",
    "displayName": "Lua 5.4",
    "version": "$LUA_VERSION",
    "binaries": ["usr/bin/lua", "usr/bin/luac"],
    "env": {},
    "pathDirs": ["usr/bin"],
    "ownedPaths": [$OWNED_PATHS],
    "libs": []
}
EOF
echo "  toolchain_lua.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Lua binaries ==="
# Against the base APK's usr/lib, which a toolchain installs beside: readline is
# the one library these need beyond the system's.
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
    echo "  ERROR: $verify_failures of $verify_checked Lua binaries would fail to load on a device." >&2
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
echo "=== Lua Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Lua download complete ==="
