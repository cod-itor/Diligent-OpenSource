#!/usr/bin/env bash
set -euo pipefail

# Download the Kotlin compiler from the Termux APT repo and place it in the
# toolchain_kotlin asset pack module.
#
# Modelled on download-ruby.sh: the same signed index, the same digest check on
# each .deb, the same -L copy (neither delivery path can carry a symbolic link).
#
# The compiler is a set of JVM jars and the commands that start it are bash
# scripts, so the pack holds no ELF object at all and runs on the JDK that
# toolchain_java installs. Termux's package depends on openjdk-21 for the same
# reason; that is the pack this one names in ToolchainRegistry's `requires`, and
# the install is refused until Java 21 is there. Each script runs `java` by
# name, which in a terminal is the function toolchain-env.sh defines for Java,
# with the flags that pack's manifest carries.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_kotlin/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    kotlin
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Kotlin Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-kotlin.tsv "${REQUIRED_PACKAGES[@]}"
KOTLIN_VERSION="$(termux_pkg_version kotlin)"
echo "  Kotlin version: $KOTLIN_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
#
# The package's own tree, usr/opt/kotlin, becomes the install root. Its
# usr/bin entries are links into that tree's bin/, which the pack cannot carry;
# the script wrappers below name the files in the tree instead. The scripts find
# the jars from their own location (BASH_SOURCE), which is the tree either way.
echo ""
echo "Placing Kotlin toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/opt"

KOTLIN_SRC="extracted/kotlin/data/data/com.termux/files/usr/opt/kotlin"
if [ ! -f "$KOTLIN_SRC/bin/kotlinc" ] || [ ! -f "$KOTLIN_SRC/lib/kotlin-compiler.jar" ]; then
    echo "  ERROR: no kotlinc or kotlin-compiler.jar under $KOTLIN_SRC" >&2
    exit 1
fi
cp -RL "$KOTLIN_SRC" "$PACK_ASSETS/usr/opt/kotlin"
echo "  opt/kotlin/ ($(ls "$PACK_ASSETS/usr/opt/kotlin/lib" | wc -l | tr -d ' ') jars and files in lib/)"

# --- Step 5: Place the upstream notice beside what it describes ---
#
# The project's own LICENSE.txt and third-party notices already ship in
# opt/kotlin/license; this adds Termux's copyright file beside the others.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_kotlin.json ---
echo ""
echo "Writing toolchain_kotlin.json..."

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
for cmd in "$PACK_ASSETS/usr/opt/kotlin/bin"/*; do
    [ -f "$cmd" ] || continue
    if is_elf "$cmd"; then
        echo "  ERROR: $cmd is an ELF object; this pack has no rule for one" >&2
        exit 1
    fi
    name="$(basename "$cmd")"
    if [ "$FIRST" = true ]; then FIRST=false; else BINARIES+=','; SCRIPT_WRAPPERS+=','; fi
    BINARIES+="\"usr/opt/kotlin/bin/$name\""
    SCRIPT_WRAPPERS+="\"$name\":\"usr/opt/kotlin/bin/$name\""
done
BINARIES+=']'
SCRIPT_WRAPPERS+='}'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_kotlin.json" << EOF
{
    "name": "kotlin",
    "displayName": "Kotlin",
    "version": "$KOTLIN_VERSION",
    "binaries": $BINARIES,
    "env": {},
    "installRoot": "usr/opt/kotlin",
    "ownedPaths": [$OWNED_PATHS],
    "libs": [],
    "scriptWrappers": {
        "interpreter": "bash",
        "scripts": $SCRIPT_WRAPPERS
    }
}
EOF
echo "  toolchain_kotlin.json written"

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
echo "=== Kotlin Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Kotlin download complete ==="
