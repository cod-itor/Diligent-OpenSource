#!/usr/bin/env bash
set -euo pipefail

# Download SBCL (Steel Bank Common Lisp) from the Termux APT repo and place it
# in the toolchain_sbcl asset pack module.
#
# Modelled on download-lua.sh and download-ruby.sh: the same signed index, the
# same digest check on each .deb, the same ELF gate before anything is written
# for the app to read.
#
# The runtime is one ELF, bin/sbcl, and the Lisp image it maps is
# lib/sbcl/sbcl.core, with the contrib modules (ASDF, sb-posix and the rest)
# beside it. sbcl looks for that directory in SBCL_HOME first, then beside its
# own /proc/self/exe, which is /system/bin/linker64 when a shell function
# starts it, then under Termux's prefix. So the manifest sets SBCL_HOME, and
# `require` finds the contribs from it. run-program uses fork and execvp from
# libc, which the terminal's exec interceptor wraps.
#
# Not covered: save-lisp-and-die with :executable t copies the runtime from
# sb-ext:*runtime-pathname*, the same /proc/self/exe, so it would copy the
# linker. A core saved without :executable loads with `sbcl --core`.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_sbcl/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    sbcl
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading SBCL Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-sbcl.tsv "${REQUIRED_PACKAGES[@]}"
SBCL_VERSION="$(termux_pkg_version sbcl)"
echo "  SBCL version: $SBCL_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing SBCL toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib"

SBCL_USR="extracted/sbcl/data/data/com.termux/files/usr"
if [ ! -f "$SBCL_USR/bin/sbcl" ] || [ ! -f "$SBCL_USR/lib/sbcl/sbcl.core" ]; then
    echo "  ERROR: no bin/sbcl or lib/sbcl/sbcl.core in the sbcl package" >&2
    exit 1
fi
cp -L "$SBCL_USR/bin/sbcl" "$PACK_ASSETS/usr/bin/sbcl"
cp -RL "$SBCL_USR/lib/sbcl" "$PACK_ASSETS/usr/lib/sbcl"
# A makefile fragment for building against the runtime, which nothing here does.
rm -f "$PACK_ASSETS/usr/lib/sbcl/sbcl.mk"
echo "  bin/sbcl, lib/sbcl/ ($(ls "$PACK_ASSETS/usr/lib/sbcl/contrib" | grep -c '\.fasl$') contrib modules)"

# --- Step 5: Place the upstream notice beside what it describes ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_sbcl.json ---
echo ""
echo "Writing toolchain_sbcl.json..."

# sbcl is a real binary, so it gets the shell function and the exec table row
# every ELF in `binaries` gets.
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_sbcl.json" << EOF
{
    "name": "sbcl",
    "displayName": "SBCL",
    "version": "$SBCL_VERSION",
    "binaries": ["usr/bin/sbcl"],
    "env": {
        "SBCL_HOME": "\$FILESDIR/usr/lib/sbcl"
    },
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/sbcl",
    "ownedPaths": [$OWNED_PATHS],
    "libs": []
}
EOF
echo "  toolchain_sbcl.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying SBCL binaries ==="
# Against the base APK's usr/lib, which a toolchain installs beside: zstd, for
# compressed cores, is the one library sbcl needs beyond the system's.
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
    echo "  ERROR: $verify_failures of $verify_checked SBCL binaries would fail to load on a device." >&2
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
echo "=== SBCL Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== SBCL download complete ==="
