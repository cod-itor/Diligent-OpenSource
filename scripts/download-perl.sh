#!/usr/bin/env bash
set -euo pipefail

# Download Perl 5 from the Termux APT repo and place it in the toolchain_perl
# asset pack module.
#
# Modelled on download-ruby.sh, the closest of the existing packs: one real
# interpreter, its shared runtime, a library tree it finds through an
# environment variable, and a set of commands that are scripts in that
# interpreter's language. The same signed index, digest check, -L copy and ELF
# gate apply.
#
# The CORE headers under lib/perl5/<version>/<arch>/CORE stay, as Ruby's
# headers do, so a CPAN module with an XS part can be built once the C/C++
# toolchain is installed: Config.pm names Termux's aarch64-linux-android-clang
# as `cc`, which is the name that pack ships. usr/include/perl is a second copy
# of the same headers and is not taken.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_perl/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

# libandroid-utimes is perl's one declared dependency and is not taken. It
# supplies futimes, futimesat and lutimes for Android releases older than API
# 26; bionic has carried all three since, and this app's minSdk is 30. Termux's
# build of it has 4 KB LOAD alignment, which Android 16 refuses to load, so
# shipping it would put a library the ELF gate rejects into the pack for
# symbols libc already answers. Step 5a takes the DT_NEEDED entry out instead.
REQUIRED_PACKAGES=(
    perl
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

# Soname mapping for shared libraries. check-termux-licenses.py reads it too.
get_sonames() {
    case "$1" in
        perl)               echo "libperl.so" ;;
        *)                  echo "" ;;
    esac
}

LIB_PACKAGES=(perl)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Perl Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-perl.tsv "${REQUIRED_PACKAGES[@]}"
PERL_VERSION="$(termux_pkg_version perl)"
# The library directory is named by the version without a Debian revision:
# 5.44.0-1 installs into lib/perl5/5.44.0.
PERL_LIBVER="$(echo "$PERL_VERSION" | sed 's/^[0-9]*://; s/-.*$//')"
echo "  Perl version: $PERL_VERSION (lib/perl5/$PERL_LIBVER)"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Perl toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib"

PERL_USR="extracted/perl/data/data/com.termux/files/usr"

# Every command the package ships, read from the package rather than from a
# list kept here, for the reason download-ruby.sh gives.
if [ ! -d "$PERL_USR/bin" ]; then
    echo "  ERROR: no bin directory in the perl package at $PERL_USR/bin" >&2
    exit 1
fi
for src in "$PERL_USR/bin"/*; do
    [ -f "$src" ] || [ -L "$src" ] || continue
    cp -L "$src" "$PACK_ASSETS/usr/bin/$(basename "$src")"
done
echo "  bin/ ($(ls "$PACK_ASSETS/usr/bin" | wc -l | tr -d ' ') commands)"

PERL_LIB_SRC="$PERL_USR/lib/perl5/$PERL_LIBVER"
PERL_ARCH="aarch64-android"
if [ ! -f "$PERL_LIB_SRC/$PERL_ARCH/Config.pm" ]; then
    echo "  ERROR: no Config.pm at $PERL_LIB_SRC/$PERL_ARCH" >&2
    exit 1
fi
cp -RL "$PERL_USR/lib/perl5" "$PACK_ASSETS/usr/lib/perl5"
echo "  lib/perl5/"

echo ""
echo "Placing shared libraries..."
for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        if [ ! -f "$pkg_lib_dir/$soname" ] && [ ! -L "$pkg_lib_dir/$soname" ]; then
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            exit 1
        fi
        cp -L "$pkg_lib_dir/$soname" "$PACK_ASSETS/usr/lib/$soname"
        echo "  $soname ($(du -sh "$PACK_ASSETS/usr/lib/$soname" | cut -f1))"
    done
done

# --- Step 5: Strip what nothing on a device reads ---
#
# .packlist files are the package manager's record of which files it installed,
# under Termux's prefix; nothing here installs from them, and one of them is the
# only file left naming /data/data/com.termux/files/usr/bin/shasum, which the
# shell sweep below reads as a shell path.
echo ""
echo "Stripping unnecessary files..."
find "$PACK_ASSETS/usr/lib/perl5" -name ".packlist" -delete
find "$PACK_ASSETS/usr" -type d -name "man" -exec rm -rf {} + 2>/dev/null || true

# --- Step 5a: Drop the dependency on libandroid-utimes ---
#
# Removed from the dynamic section of every ELF object in the pack (perl,
# libperl.so and the 48 XS extensions all name it) the way
# `patchelf --remove-needed` does it: the entries after it move up one slot and
# the table ends one entry sooner. No string is edited, so nothing sharing a
# string-table tail can change, and the file keeps its length. Refused if a
# symbol version names the library, since a version requirement on a library
# that is never loaded fails at load time. Every symbol it supplied is then
# found in libc, which both objects already name in DT_NEEDED.
echo ""
echo "Dropping libandroid-utimes from DT_NEEDED..."
ELF_OBJECTS=()
while IFS= read -r obj; do
    is_elf "$obj" && ELF_OBJECTS+=("$obj")
done < <(find "$PACK_ASSETS/usr" -type f)
python3 -I - "${ELF_OBJECTS[@]}" <<'DROPEOF'
import struct, sys

DROP = b"libandroid-utimes.so"
DT_NULL, DT_NEEDED = 0, 1
SHT_DYNAMIC, SHT_GNU_VERNEED = 6, 0x6FFFFFFE

dropped = 0
for path in sys.argv[1:]:
    data = bytearray(open(path, "rb").read())
    if data[:4] != b"\x7fELF" or data[4] != 2:
        sys.exit(f"  ERROR: {path} is not a 64-bit ELF")
    shoff, = struct.unpack_from("<Q", data, 40)
    shentsize, shnum = struct.unpack_from("<HH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize)
                for i in range(shnum)]
    dyn = [s for s in sections if s[1] == SHT_DYNAMIC]
    if len(dyn) != 1:
        sys.exit(f"  ERROR: {path} has {len(dyn)} dynamic sections")
    off, size, link, ent = dyn[0][4], dyn[0][5], dyn[0][6], dyn[0][9]
    strtab = sections[link][4]

    def name_at(base, index):
        return bytes(data[base + index:data.index(0, base + index)])

    for s in sections:
        if s[1] != SHT_GNU_VERNEED:
            continue
        vstr, pos = sections[s[6]][4], s[4]
        while True:
            _, _, file_off, _, nxt = struct.unpack_from("<HHIII", data, pos)
            if name_at(vstr, file_off) == DROP:
                sys.exit(f"  ERROR: {path} carries a symbol version on {DROP.decode()}")
            if nxt == 0:
                break
            pos += nxt

    entries = [struct.unpack_from("<qQ", data, off + i * ent) for i in range(size // ent)]
    live = []
    for tag, val in entries:
        live.append((tag, val))
        if tag == DT_NULL:
            break
    kept = [(t, v) for t, v in live if not (t == DT_NEEDED and name_at(strtab, v) == DROP)]
    if len(kept) == len(live):
        continue
    kept += [(DT_NULL, 0)] * (len(entries) - len(kept))
    for i, (t, v) in enumerate(kept):
        struct.pack_into("<qQ", data, off + i * ent, t, v)
    open(path, "wb").write(data)
    dropped += 1
print(f"  dropped from {dropped} of {len(sys.argv) - 1} ELF objects")
DROPEOF

# --- Step 5b: Point the compiled-in default shells at one this app can run ---
#
# libperl.so carries Termux's /data/data/com.termux/files/usr/bin/sh once, as
# the C constant behind system(), exec(), backticks and a piped open() with a
# command string, all of which failed with ENOENT. Config_heavy.pl records the
# same path as `sh` (ExtUtils::MakeMaker writes it into every Makefile it
# generates, where make cannot be overridden from the environment), and
# CORE/config.h as SH_PATH for an XS module compiled against it.
#
# The two text files are rewritten here rather than by patch-default-shell.py:
# Config_heavy.pl spells the path three times in shapes that script does not
# know and should not learn to accept by widening its match. The rewrite takes
# the path only where it ends, so a longer name that starts with it is left as
# it is. The sweep below then holds the whole pack to the invariant.
echo ""
echo "Pointing compiled-in default shells at /system/bin/sh..."
# CORE/libperl.so is a second copy of the library, a link in the package that
# the -L copy above dereferenced: it is what an XS module links against with
# -lperl, so it stays, and carries the same constant.
python3 "$SCRIPT_DIR/patch-default-shell.py" "$PACK_ASSETS/usr/lib/libperl.so" \
    "$PACK_ASSETS/usr/lib/perl5/$PERL_LIBVER/$PERL_ARCH/CORE/libperl.so"
python3 -I - "$PACK_ASSETS/usr/lib/perl5/$PERL_LIBVER/$PERL_ARCH" <<'PYEOF'
import pathlib, re, sys
arch = pathlib.Path(sys.argv[1])
old = re.compile(rb"/data/data/com\.termux/files/usr/bin/sh(?![A-Za-z0-9_.+-])")
for rel in ("Config_heavy.pl", "CORE/config.h"):
    path = arch / rel
    data = path.read_bytes()
    count = len(old.findall(data))
    if count == 0:
        sys.exit(f"  ERROR: {path} no longer names Termux's shell; read what it uses now")
    path.write_bytes(old.sub(b"/system/bin/sh", data))
    print(f"  {rel}: {count} -> /system/bin/sh")
PYEOF

# --- Step 5c: Place the upstream notices beside what they describe ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_perl.json ---
echo ""
echo "Writing toolchain_perl.json..."

# Split by reading each file, as download-ruby.sh does: `perl` is the one ELF,
# every other command is Perl source whose shebang points into Termux's prefix,
# and each gets a function that hands it to `perl`.
BINARIES='['
SCRIPT_WRAPPERS='{'
FIRST_BIN=true
FIRST_SW=true
for cmd in "$PACK_ASSETS/usr/bin"/*; do
    [ -f "$cmd" ] || continue
    name="$(basename "$cmd")"
    [ "$FIRST_BIN" = true ] && FIRST_BIN=false || BINARIES+=','
    BINARIES+="\"usr/bin/$name\""
    if ! is_elf "$cmd"; then
        [ "$FIRST_SW" = true ] && FIRST_SW=false || SCRIPT_WRAPPERS+=','
        SCRIPT_WRAPPERS+="\"$name\":\"usr/bin/$name\""
    fi
done
BINARIES+=']'
SCRIPT_WRAPPERS+='}'

# PERL5LIB: the interpreter's compiled-in @INC is Termux's prefix, which this
# app cannot read, so the pack's own library tree is named here, behind a
# per-user tree in ~/perl5. PERL_MM_OPT and PERL_MB_OPT send `cpan` installs
# into that same tree, local::lib's layout: Config.pm's install directories are
# Termux's too, and an install aimed there fails on the first copy.
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
PL="\$FILESDIR/usr/lib/perl5"
cat > "$PACK_ASSETS/toolchain_perl.json" << EOF
{
    "name": "perl",
    "displayName": "Perl",
    "version": "$PERL_VERSION",
    "binaries": $BINARIES,
    "env": {
        "PERL5LIB": "\$HOME/perl5/lib/perl5:$PL/site_perl/$PERL_LIBVER/$PERL_ARCH:$PL/site_perl/$PERL_LIBVER:$PL/$PERL_LIBVER/$PERL_ARCH:$PL/$PERL_LIBVER",
        "PERL_MM_OPT": "INSTALL_BASE=\$HOME/perl5",
        "PERL_MB_OPT": "--install_base \$HOME/perl5"
    },
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/perl5",
    "ownedPaths": [$OWNED_PATHS],
    "libs": ["libperl.so"],
    "scriptWrappers": {
        "interpreter": "perl",
        "scripts": $SCRIPT_WRAPPERS
    }
}
EOF
echo "  toolchain_perl.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Perl binaries ==="
# The whole pack, extensions included: every ELF object in it is something
# perl will load.
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
    echo "  ERROR: $verify_failures of $verify_checked Perl binaries would fail to load on a device." >&2
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
echo "=== Perl Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Perl download complete ==="
