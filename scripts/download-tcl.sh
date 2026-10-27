#!/usr/bin/env bash
set -euo pipefail

# Download Tcl 8.6 from the Termux APT repo and place it in the toolchain_tcl
# asset pack module.
#
# Modelled on download-ruby.sh and download-lua.sh: the same signed index, the
# same digest check on each .deb, the same ELF gate before anything is written
# for the app to read.
#
# Tcl finds its own script library (init.tcl and the rest of lib/tcl8.6) from a
# directory compiled in under Termux's prefix, which this app cannot read, so
# the manifest sets TCL_LIBRARY to where the pack puts it. The bundled packages
# (Itcl, Thread, TDBC) are found through auto_path, whose compiled-in entries
# are Termux's too, so TCLLIBPATH adds the pack's usr/lib to it. `exec` and
# `open |cmd` start programs with vfork and execvp from libc, which the
# terminal's exec interceptor wraps.
#
# The headers, the stub library and the *Config.sh files are left out: they
# serve building a C extension against this Tcl, which is not what the pack is
# for.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_tcl/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    tcl
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Tcl Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-tcl.tsv "${REQUIRED_PACKAGES[@]}"
TCL_VERSION="$(termux_pkg_version tcl)"
echo "  Tcl version: $TCL_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Tcl toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib"

TCL_USR="extracted/tcl/data/data/com.termux/files/usr"
if [ ! -f "$TCL_USR/bin/tclsh8.6" ] || [ ! -f "$TCL_USR/lib/tcl8.6/init.tcl" ]; then
    echo "  ERROR: no tclsh8.6 or lib/tcl8.6/init.tcl in the tcl package" >&2
    exit 1
fi
# Termux's tclsh is a link to tclsh8.6, and the pack cannot carry a link.
cp -L "$TCL_USR/bin/tclsh8.6" "$PACK_ASSETS/usr/bin/tclsh"
echo "  bin/tclsh (from tclsh8.6)"
cp -L "$TCL_USR/lib/libtcl8.6.so" "$PACK_ASSETS/usr/lib/"
echo "  lib/libtcl8.6.so"

# Every directory under lib/ is Tcl's script library or a bundled package.
TCL_DIRS=()
for dir in "$TCL_USR"/lib/*/; do
    name="$(basename "$dir")"
    [ "$name" = "pkgconfig" ] && continue
    cp -RL "$dir" "$PACK_ASSETS/usr/lib/$name"
    find "$PACK_ASSETS/usr/lib/$name" -name '*Config.sh' -delete
    TCL_DIRS+=("$name")
    echo "  lib/$name/"
done

# --- Step 5: Place the upstream notice beside what it describes ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_tcl.json ---
echo ""
echo "Writing toolchain_tcl.json..."

# tclsh is the one command and a real binary, so it gets the shell function and
# the exec table row every ELF in `binaries` gets. The install root is the
# script library; the bundled packages sit beside it in the shared usr/lib and
# are owned paths, so removing Tcl takes them and nothing else.
OWNED_PATHS=''
for name in "${TCL_DIRS[@]}"; do
    [ "$name" = "tcl8.6" ] && continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/lib/$name\""
done
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    OWNED_PATHS+=",\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_tcl.json" << EOF
{
    "name": "tcl",
    "displayName": "Tcl",
    "version": "$TCL_VERSION",
    "binaries": ["usr/bin/tclsh"],
    "env": {
        "TCL_LIBRARY": "\$FILESDIR/usr/lib/tcl8.6",
        "TCLLIBPATH": "\$FILESDIR/usr/lib"
    },
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/tcl8.6",
    "ownedPaths": [$OWNED_PATHS],
    "libs": ["libtcl8.6.so"]
}
EOF
echo "  toolchain_tcl.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Tcl binaries ==="
# Against the pack's own usr/lib and the base APK's, which a toolchain installs
# beside: zlib is the one library these need beyond the system's and libtcl.
verify_failures=0
verify_checked=0
while IFS= read -r obj; do
    is_elf "$obj" || continue
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$obj" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
                   --lib-dir "$ROOT_DIR/android/app/src/main/assets/usr/lib" 2>&1); then
        echo "  FAILED  ${obj#$PACK_ASSETS/}" >&2
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
done < <(find "$PACK_ASSETS/usr" -type f)
if [ "$verify_failures" -gt 0 ]; then
    echo "  ERROR: $verify_failures of $verify_checked Tcl binaries would fail to load on a device." >&2
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
echo "=== Tcl Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Tcl download complete ==="
