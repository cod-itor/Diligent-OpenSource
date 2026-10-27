#!/usr/bin/env bash
set -euo pipefail

# Download Scala 3 from the Termux APT repo and place it in the toolchain_scala
# asset pack module.
#
# Modelled on download-scala.sh and download-kotlin.sh: Scala is JVM jars
# started by bash scripts, so the pack holds no ELF object and runs on the JDK
# that toolchain_java installs, which ToolchainRegistry names in `requires`.
# The scripts find java through $JAVA_HOME and their own tree from $0.
#
# scalac and scaladoc start the compiler from lib/, whose jars name the rest of
# the compiler in the bundled maven2/ repository. `scala` is scala-cli, a
# second launcher in libexec/ that resolves the compiler from that same
# repository. Termux's bin/ holds a link to scala-cli.jar beside the scripts;
# copied with -L it would be a second 126 MB copy, so it is removed, along with
# the Windows .bat files.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_scala/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    scala
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Scala Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-scala.tsv "${REQUIRED_PACKAGES[@]}"
SCALA_VERSION="$(termux_pkg_version scala)"
echo "  Scala version: $SCALA_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
#
# The package's own tree, usr/opt/scala, becomes the install root. Its usr/bin
# entries are links into that tree's bin/, which the pack cannot carry; the
# script wrappers below name the files in the tree instead.
echo ""
echo "Placing Scala toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/opt"

SCALA_SRC="extracted/scala/data/data/com.termux/files/usr/opt/scala"
if [ ! -f "$SCALA_SRC/bin/scalac" ] || [ ! -f "$SCALA_SRC/lib/with_compiler.jar" ]; then
    echo "  ERROR: no scalac or lib/with_compiler.jar under $SCALA_SRC" >&2
    exit 1
fi
cp -R "$SCALA_SRC" "$PACK_ASSETS/usr/opt/scala"
rm -f "$PACK_ASSETS/usr/opt/scala/bin/scala-cli.jar"
find "$PACK_ASSETS/usr/opt/scala" -name '*.bat' -delete
# Anything still a link points inside the tree; replace it with its target.
find "$PACK_ASSETS/usr/opt/scala" -type l | while IFS= read -r link; do
    target="$(cd "$(dirname "$link")" && cd "$(dirname "$(readlink "$link")")" && pwd)/$(basename "$(readlink "$link")")"
    rm "$link"
    cp "$target" "$link"
done
echo "  opt/scala/ ($(du -sk "$PACK_ASSETS/usr/opt/scala/maven2" | cut -f1) KiB in maven2/)"

# --- Step 5: Place the upstream notice beside what it describes ---
#
# Termux's copyright file is the notice; the project's own LICENSE and NOTICE
# are inside each jar's META-INF.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_scala.json ---
echo ""
echo "Writing toolchain_scala.json..."

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
for cmd in "$PACK_ASSETS/usr/opt/scala/bin"/*; do
    [ -f "$cmd" ] || continue
    if is_elf "$cmd"; then
        echo "  ERROR: $cmd is an ELF object; this pack has no rule for one" >&2
        exit 1
    fi
    name="$(basename "$cmd")"
    if [ "$FIRST" = true ]; then FIRST=false; else BINARIES+=','; SCRIPT_WRAPPERS+=','; fi
    BINARIES+="\"usr/opt/scala/bin/$name\""
    SCRIPT_WRAPPERS+="\"$name\":\"usr/opt/scala/bin/$name\""
done
BINARIES+=']'
SCRIPT_WRAPPERS+='}'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_scala.json" << EOF
{
    "name": "scala",
    "displayName": "Scala",
    "version": "$SCALA_VERSION",
    "binaries": $BINARIES,
    "env": {},
    "installRoot": "usr/opt/scala",
    "ownedPaths": [$OWNED_PATHS],
    "libs": [],
    "scriptWrappers": {
        "interpreter": "bash",
        "scripts": $SCRIPT_WRAPPERS
    }
}
EOF
echo "  toolchain_scala.json written"

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
echo "=== Scala Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Scala download complete ==="
