#!/usr/bin/env bash
set -euo pipefail

# Download Apache Groovy from the Termux APT repo and place it in the
# toolchain_groovy asset pack module.
#
# Modelled on download-kotlin.sh, and for the same reason: Groovy is a set of
# JVM jars started by shell scripts, so the pack holds no ELF object and runs
# on the JDK that toolchain_java installs. ToolchainRegistry names that pack in
# `requires`, and the install is refused until Java 21 is there. startGroovy
# runs $JAVA_HOME/bin/java, which the Java pack's manifest sets, and finds
# GROOVY_HOME from its own location, which is the pack's tree.
#
# Left out: the grooid jars (Groovy compiled for Android's dex tool chain,
# which nothing here runs), groovyConsole (a Swing window, and the JDK here has
# no display to put it on), the Windows icon and the bash completion files.
# startGroovy stays: every command sources it.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_groovy/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    groovy
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Groovy Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-groovy.tsv "${REQUIRED_PACKAGES[@]}"
GROOVY_VERSION="$(termux_pkg_version groovy)"
echo "  Groovy version: $GROOVY_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
#
# The package's own tree, usr/opt/groovy, becomes the install root. Its usr/bin
# entries are links into that tree's bin/, which the pack cannot carry; the
# script wrappers below name the files in the tree instead.
echo ""
echo "Placing Groovy toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/opt"

GROOVY_SRC="extracted/groovy/data/data/com.termux/files/usr/opt/groovy"
if [ ! -f "$GROOVY_SRC/bin/groovy" ] || [ ! -f "$GROOVY_SRC/bin/startGroovy" ]; then
    echo "  ERROR: no groovy or startGroovy under $GROOVY_SRC" >&2
    exit 1
fi
cp -RL "$GROOVY_SRC" "$PACK_ASSETS/usr/opt/groovy"
rm -rf "$PACK_ASSETS/usr/opt/groovy/grooid"
rm -f "$PACK_ASSETS/usr/opt/groovy/bin/groovyConsole" \
      "$PACK_ASSETS/usr/opt/groovy/bin/groovy.ico" \
      "$PACK_ASSETS/usr/opt/groovy/bin/"*_completion
echo "  opt/groovy/ ($(ls "$PACK_ASSETS/usr/opt/groovy/lib" | wc -l | tr -d ' ') jars and files in lib/)"

# --- Step 5: Place the upstream notice beside what it describes ---
#
# The project's own LICENSE, NOTICE and licenses/ already ship in opt/groovy;
# this adds Termux's copyright file beside them.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_groovy.json ---
echo ""
echo "Writing toolchain_groovy.json..."

# Every command is a bash script, so each is a script wrapper with bash as its
# interpreter: SELinux refuses to execute a file under filesDir, and the
# shebang names Termux's env anyway. bash is not an ELF this manifest ships, so
# the exec table gets no rows for these and ToolchainManager says so in the
# log; a terminal reaches them through the functions. `env` is present and
# empty rather than absent: regenerateEnvFileLocked skips a toolchain with no
# `env` object entirely.
BINARIES='['
SCRIPT_WRAPPERS='{'
FIRST=true
for cmd in "$PACK_ASSETS/usr/opt/groovy/bin"/*; do
    [ -f "$cmd" ] || continue
    if is_elf "$cmd"; then
        echo "  ERROR: $cmd is an ELF object; this pack has no rule for one" >&2
        exit 1
    fi
    name="$(basename "$cmd")"
    # Sourced by every command, not one itself.
    [ "$name" = "startGroovy" ] && continue
    if [ "$FIRST" = true ]; then FIRST=false; else BINARIES+=','; SCRIPT_WRAPPERS+=','; fi
    BINARIES+="\"usr/opt/groovy/bin/$name\""
    SCRIPT_WRAPPERS+="\"$name\":\"usr/opt/groovy/bin/$name\""
done
BINARIES+=']'
SCRIPT_WRAPPERS+='}'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_groovy.json" << EOF
{
    "name": "groovy",
    "displayName": "Groovy",
    "version": "$GROOVY_VERSION",
    "binaries": $BINARIES,
    "env": {},
    "installRoot": "usr/opt/groovy",
    "ownedPaths": [$OWNED_PATHS],
    "libs": [],
    "scriptWrappers": {
        "interpreter": "bash",
        "scripts": $SCRIPT_WRAPPERS
    }
}
EOF
echo "  toolchain_groovy.json written"

# --- Step 7: Verify what the pack ships ---
echo ""
echo "=== Verifying the pack holds no native code ==="
elf_count=0
while IFS= read -r obj; do
    is_elf "$obj" && elf_count=$((elf_count + 1))
done < <(find "$PACK_ASSETS/usr" -type f)
if [ "$elf_count" -ne 0 ]; then
    echo "  ERROR: $elf_count ELF object(s) in a pack written as JVM-only; verify them before shipping" >&2
    exit 1
fi
echo "  none"

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
echo "=== Groovy Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Groovy download complete ==="
