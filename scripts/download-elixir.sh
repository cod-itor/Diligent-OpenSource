#!/usr/bin/env bash
set -euo pipefail

# Download Elixir from the Termux APT repo and place it in the toolchain_elixir
# asset pack module.
#
# Modelled on download-kotlin.sh: Elixir is BEAM bytecode started by shell
# scripts, so the pack holds no ELF object and runs on the runtime that
# toolchain_erlang installs. ToolchainRegistry names that pack in `requires`,
# and the install is refused until Erlang/OTP is there.
#
# How it starts here. `elixir` is a sh script that runs `erl` by name, which a
# PATH search finds in the Erlang pack's bin/; `iex` and `elixirc` run the
# `elixir` beside them, and `mix` is an Elixir script whose #! line runs
# `env elixir`. Each of those is an exec of a file under filesDir, which only
# the terminal's exec interceptor makes possible, so these commands get no
# shell functions: a function would only replace the first exec of several.
# The #! lines name Termux's sh and env, which this app cannot read, and are
# pointed at /system/bin's.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_elixir/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    elixir
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Elixir Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-elixir.tsv "${REQUIRED_PACKAGES[@]}"
ELIXIR_VERSION="$(termux_pkg_version elixir)"
echo "  Elixir version: $ELIXIR_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
#
# The package's own tree, usr/opt/elixir, becomes the install root. Its usr/bin
# entries are links into that tree's bin/, which the pack cannot carry; PATH
# names the tree's bin/ instead.
echo ""
echo "Placing Elixir toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/opt"

ELIXIR_SRC="extracted/elixir/data/data/com.termux/files/usr/opt/elixir"
if [ ! -f "$ELIXIR_SRC/bin/elixir" ] || [ ! -d "$ELIXIR_SRC/lib/elixir/ebin" ]; then
    echo "  ERROR: no bin/elixir or lib/elixir/ebin under $ELIXIR_SRC" >&2
    exit 1
fi
cp -RL "$ELIXIR_SRC" "$PACK_ASSETS/usr/opt/elixir"
rm -f "$PACK_ASSETS/usr/opt/elixir/bin/"*.bat "$PACK_ASSETS/usr/opt/elixir/bin/"*.ps1
rm -f "$PACK_ASSETS/usr/opt/elixir/Makefile"

python3 - "$PACK_ASSETS/usr/opt/elixir/bin" <<'SHEBANGEOF'
import os, sys
OLD = (b"#!/data/data/com.termux/files/usr/bin/sh", b"#!/data/data/com.termux/files/usr/bin/env")
NEW = (b"#!/system/bin/sh", b"#!/system/bin/env")
for name in sorted(os.listdir(sys.argv[1])):
    path = os.path.join(sys.argv[1], name)
    with open(path, "rb") as f:
        data = f.read()
    line, sep, rest = data.partition(b"\n")
    for old, new in zip(OLD, NEW):
        if line == old or line.startswith(old + b" "):
            with open(path, "wb") as f:
                f.write(new + line[len(old):] + sep + rest)
            print("  #! line repointed: bin/" + name)
            break
    else:
        print("  ERROR: bin/" + name + " starts with " + line[:60].decode("latin1"), file=sys.stderr)
        sys.exit(1)
SHEBANGEOF
echo "  opt/elixir/ ($(ls "$PACK_ASSETS/usr/opt/elixir/lib" | tr '\n' ' '))"

# --- Step 5: Place the upstream notice beside what it describes ---
#
# The project's own LICENSE already ships in opt/elixir; this adds Termux's
# copyright file beside the others.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_elixir.json ---
echo ""
echo "Writing toolchain_elixir.json..."

# The four commands are scripts and are in `binaries` for the execute bit a
# PATH search needs; there is no ELF, so no shell function or exec table row is
# written for any of them. `env` is present and empty rather than absent:
# regenerateEnvFileLocked skips a toolchain with no `env` object entirely,
# PATH included.
BINARIES='['
FIRST=true
for cmd in "$PACK_ASSETS/usr/opt/elixir/bin"/*; do
    [ -f "$cmd" ] || continue
    if is_elf "$cmd"; then
        echo "  ERROR: $cmd is an ELF object; this pack has no rule for one" >&2
        exit 1
    fi
    if [ "$FIRST" = true ]; then FIRST=false; else BINARIES+=','; fi
    BINARIES+="\"usr/opt/elixir/bin/$(basename "$cmd")\""
done
BINARIES+=']'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_elixir.json" << EOF
{
    "name": "elixir",
    "displayName": "Elixir",
    "version": "$ELIXIR_VERSION",
    "binaries": $BINARIES,
    "env": {},
    "pathDirs": ["usr/opt/elixir/bin"],
    "installRoot": "usr/opt/elixir",
    "ownedPaths": [$OWNED_PATHS],
    "libs": []
}
EOF
echo "  toolchain_elixir.json written"

# --- Step 7: Verify what the pack ships ---
echo ""
echo "=== Verifying the pack holds no native code ==="
elf_count=0
while IFS= read -r obj; do
    is_elf "$obj" && elf_count=$((elf_count + 1))
done < <(find "$PACK_ASSETS/usr" -type f)
if [ "$elf_count" -ne 0 ]; then
    echo "  ERROR: $elf_count ELF object(s) in a pack written as bytecode-only; verify them before shipping" >&2
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
echo "=== Elixir Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Elixir download complete ==="
