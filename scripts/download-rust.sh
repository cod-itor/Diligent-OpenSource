#!/usr/bin/env bash
set -euo pipefail

# Download Rust (rustc, cargo, rustdoc, rustfmt) from the Termux APT repo and
# place it in the toolchain_rust asset pack module.
#
# Modelled on download-ruby.sh: the same signed index, the same digest check on
# each .deb, the same -L copy and ELF gate.
#
# Why Rust can work here when Go could not (see the Go note in
# ToolchainRegistry). Android refuses execve() of a file under the app's data
# directory, and the only way round it is the terminal's preloaded exec
# interceptor, libtermux-exec.so, which interposes the libc exec family and
# starts such a file through /system/bin/linker64. Go makes the fork and exec
# system calls itself, so the interceptor never sees them. Rust's standard
# library spawns through libc instead: read from the binaries, cargo and
# librustc_driver import fork and execvp from libc and no posix_spawn, and
# execvp is one of the eight entry points the interceptor exports. So cargo
# starting rustc, rustc starting the linker, and cargo running a build script or
# `cargo run` running the program it built all pass through it, the same way a
# shell starting a compiled C program does in the C/C++ pack.
#
# Two consequences, both stated on the toolchain's card:
#
#   * it needs the C/C++ pack. rustc links by running `cc`, which that pack
#     provides, and librustc_driver links that pack's libLLVM.so (Termux builds
#     rust against the same libllvm; the version check below holds them to it).
#     ToolchainRegistry names it in `requires`.
#   * like the C/C++ pack, it does not work where the interceptor is not
#     preloaded (LD_PRELOAD set to null, or a process an extension starts
#     without a terminal): the first program it would start is refused.
#
# rustc finds its sysroot from the path of librustc_driver, through dladdr, not
# from /proc/self/exe, so being started through linker64 does not move it. That
# is not true of cargo-clippy, which finds clippy-driver beside its own
# executable and would look in /system/bin, so clippy is not shipped.
#
# Only the aarch64-linux-android target is kept. The package also carries the
# compiler's metadata for armv7, i686 and x86_64 Android, 305 MB this device
# cannot use without their standard libraries, which are separate packages.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_rust/src/main/assets"
CPP_LIB="$ROOT_DIR/android/toolchain_cpp/src/main/assets/usr/lib"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

TARGET=aarch64-linux-android

REQUIRED_PACKAGES=(
    rust
    rust-std-aarch64-linux-android
    libandroid-execinfo
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

# Soname mapping for the shared libraries this pack places in usr/lib.
# librustc_driver is not one of them: it stays in the target's lib directory,
# where rustc expects its sysroot, and reaches usr/lib as an install-time link.
# check-termux-licenses.py reads this function too.
get_sonames() {
    case "$1" in
        libandroid-execinfo)  echo "libandroid-execinfo.so" ;;
        *)                    echo "" ;;
    esac
}

LIB_PACKAGES=(libandroid-execinfo)

# The commands shipped. clippy is left out (see the header); rust-gdb,
# rust-gdbgui and rust-lldb wrap debuggers this app does not have.
COMMANDS=(cargo rustc rustdoc rustfmt cargo-fmt)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Rust Toolchain ==="
echo ""

if [ ! -f "$CPP_LIB/libLLVM.so" ]; then
    echo "  ERROR: the C/C++ pack is not built at $CPP_LIB." >&2
    echo "         Rust links its libLLVM.so; run scripts/download-cpp.sh first." >&2
    exit 1
fi

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-rust.tsv "${REQUIRED_PACKAGES[@]}"
RUST_VERSION="$(termux_pkg_version rust)"
echo "  Rust version: $RUST_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Rust toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib/rustlib"

RUST_USR="extracted/rust/data/data/com.termux/files/usr"
STD_USR="extracted/rust-std-aarch64-linux-android/data/data/com.termux/files/usr"

for cmd in "${COMMANDS[@]}"; do
    if [ ! -f "$RUST_USR/bin/$cmd" ]; then
        echo "  ERROR: $cmd is not in the rust package" >&2
        exit 1
    fi
    cp -L "$RUST_USR/bin/$cmd" "$PACK_ASSETS/usr/bin/$cmd"
    echo "  bin/$cmd"
done

# The compiler's own target directory, then the standard library's, into the
# same place: the two packages split lib/rustlib/<target>/lib between them.
for src in "$RUST_USR/lib/rustlib/$TARGET" "$STD_USR/lib/rustlib/$TARGET"; do
    if [ ! -d "$src/lib" ]; then
        echo "  ERROR: no $src/lib" >&2
        exit 1
    fi
    mkdir -p "$PACK_ASSETS/usr/lib/rustlib/$TARGET"
    cp -RL "$src/." "$PACK_ASSETS/usr/lib/rustlib/$TARGET/"
done
echo "  lib/rustlib/$TARGET/ ($(du -sh "$PACK_ASSETS/usr/lib/rustlib/$TARGET" | cut -f1))"

DRIVER="$(cd "$PACK_ASSETS/usr/lib/rustlib/$TARGET/lib" && ls librustc_driver-*.so 2>/dev/null || true)"
if [ "$(echo "$DRIVER" | grep -c .)" -ne 1 ]; then
    echo "  ERROR: expected one librustc_driver-*.so, found: ${DRIVER:-none}" >&2
    exit 1
fi
if ! ls "$PACK_ASSETS/usr/lib/rustlib/$TARGET/lib"/libstd-*.rlib > /dev/null 2>&1; then
    echo "  ERROR: no libstd rlib; rust-std-$TARGET did not land beside the compiler" >&2
    exit 1
fi

echo ""
echo "Placing shared libraries..."
for pkg in "${LIB_PACKAGES[@]}"; do
    for soname in $(get_sonames "$pkg"); do
        pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
        if [ ! -f "$pkg_lib_dir/$soname" ] && [ ! -L "$pkg_lib_dir/$soname" ]; then
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            exit 1
        fi
        cp -L "$pkg_lib_dir/$soname" "$PACK_ASSETS/usr/lib/$soname"
        echo "  $soname ($(du -sh "$PACK_ASSETS/usr/lib/$soname" | cut -f1))"
    done
done

# --- Step 5: Place the upstream notices beside what they describe ---
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_rust.json ---
echo ""
echo "Writing toolchain_rust.json..."

# librustc_driver is reached as usr/lib/<its name>, which is what rustc,
# rustdoc and rustfmt name in DT_NEEDED, through a link the install makes:
# neither delivery path can carry one, and a second 78 MB copy is not worth it.
#
# Every command is an ELF and starts the way Ruby's interpreter does, through a
# shell function and an exec-table row. That start makes linker64 the
# process's /proc/self/exe, which clang cannot work with (hence the C/C++
# pack's preloadOnly) and rustc can, since it reads its sysroot from dladdr.
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
BINARIES=''
for cmd in "${COMMANDS[@]}"; do
    [ -n "$BINARIES" ] && BINARIES+=','
    BINARIES+="\"usr/bin/$cmd\""
done
cat > "$PACK_ASSETS/toolchain_rust.json" << EOF
{
    "name": "rust",
    "displayName": "Rust",
    "version": "$RUST_VERSION",
    "binaries": [$BINARIES],
    "env": {},
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/rustlib",
    "ownedPaths": [$OWNED_PATHS],
    "libs": ["libandroid-execinfo.so"],
    "libSymlinks": {
        "$DRIVER": "rustlib/$TARGET/lib/$DRIVER"
    }
}
EOF
echo "  toolchain_rust.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Rust binaries ==="
# The pack's own usr/lib, the install-time link to librustc_driver (made here
# the way download-ruby.sh makes libruby's, and only once its target is known
# to exist), the C/C++ pack's usr/lib for libLLVM.so, and the base APK's.
SONAME_LINKS="$WORK_DIR/rust-soname-links"
rm -rf "$SONAME_LINKS"
mkdir -p "$SONAME_LINKS"
ln -s "$PACK_ASSETS/usr/lib/rustlib/$TARGET/lib/$DRIVER" "$SONAME_LINKS/$DRIVER"

verify_failures=0
verify_checked=0
while IFS= read -r obj; do
    is_elf "$obj" || continue
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$obj" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
                   --lib-dir "$SONAME_LINKS" \
                   --lib-dir "$CPP_LIB" \
                   --lib-dir "$ROOT_DIR/android/app/src/main/assets/usr/lib" 2>&1); then
        echo "  FAILED  ${obj#$PACK_ASSETS/}" >&2
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
done < <(find "$PACK_ASSETS/usr" -type f)
rm -rf "$SONAME_LINKS"
if [ "$verify_failures" -gt 0 ]; then
    echo "  ERROR: $verify_failures of $verify_checked Rust binaries would fail to load on a device." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"

# The library is found by name, so the gate above cannot tell an LLVM 21
# libLLVM.so from an LLVM 22 one. The symbol version librustc_driver asks of it
# can, and a mismatch is a dlopen failure on every rustc start.
echo ""
echo "=== Verifying librustc_driver and the C/C++ pack's libLLVM agree ==="
python3 -I - "$PACK_ASSETS/usr/lib/rustlib/$TARGET/lib/$DRIVER" "$CPP_LIB/libLLVM.so" <<'VEREOF'
import struct, sys

def versions(path):
    data = open(path, "rb").read()
    shoff, = struct.unpack_from("<Q", data, 40)
    shentsize, shnum = struct.unpack_from("<HH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    needs, defines = {}, set()
    for s in sections:
        if s[1] not in (0x6FFFFFFE, 0x6FFFFFFD):
            continue
        strtab, pos = sections[s[6]][4], s[4]
        name = lambda o: data[strtab + o:data.index(0, strtab + o)].decode()
        while True:
            if s[1] == 0x6FFFFFFE:
                _, cnt, file_off, aux, nxt = struct.unpack_from("<HHIII", data, pos)
                a = pos + aux
                for _ in range(cnt):
                    _, _, _, name_off, anext = struct.unpack_from("<IHHII", data, a)
                    needs.setdefault(name(file_off), set()).add(name(name_off))
                    a += anext
            else:
                _, _, _, cnt, _, aux, nxt = struct.unpack_from("<HHHHIII", data, pos)
                if cnt:
                    defines.add(name(struct.unpack_from("<I", data, pos + aux)[0]))
            if nxt == 0:
                break
            pos += nxt
    return needs, defines

wanted = versions(sys.argv[1])[0].get("libLLVM.so", set())
have = versions(sys.argv[2])[1]
if not wanted:
    sys.exit("  ERROR: librustc_driver names no libLLVM.so symbol version; read what it links now")
missing = wanted - have
if missing:
    sys.exit(f"  ERROR: librustc_driver needs {sorted(missing)} from libLLVM.so and the "
             f"C/C++ pack's defines {sorted(have)}")
print(f"  libLLVM.so provides {', '.join(sorted(wanted))}")
VEREOF

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
echo "=== Rust Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== Rust download complete ==="
