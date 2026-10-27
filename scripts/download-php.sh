#!/usr/bin/env bash
set -euo pipefail

# Download the PHP command-line interpreter and Composer from the Termux APT
# repo, and place them in the toolchain_php asset pack module.
#
# Modelled on download-ruby.sh, which is the reference for every step below:
# the same signed index, the same digest check on each .deb, the same copy
# with -L (neither delivery path can carry a symlink), and the same ELF gate
# before anything is written for the app to read.
#
# What the base runtime already ships is not downloaded again, because an asset
# both the base module and a pack carry is a conflict bundletool refuses:
# openssl, zlib, libcurl, libiconv, pcre2, readline, libsqlite, libffi, libbz2,
# liblzma, zstd, libc++, libandroid-glob, libandroid-support, libresolv-wrapper
# and ICU's uc and i18n halves. php links ICU's io library as well, which the
# base does not carry, so that one file is taken from libicu and nothing else.
#
# Only `php` itself is shipped from the package's bin directory. php-cgi and
# phpdbg are two more 24 MB builds of the same engine for a web server and a
# debugger nobody here can attach, and phpize and php-config build C extensions
# with autoconf, which this app does not have.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_php/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    php
    # A PHP archive run by php, so it is a script wrapper and not a binary.
    composer
    capstone
    libgmp
    libxml2
    libxslt
    # libxslt's own dependencies.
    libgcrypt
    libgpg-error
    oniguruma
    tidy
    libzip
    # libicuio.so.78 only; see the header.
    libicu
    # The shared licence texts; see termux_copy_notices.
    termux-licenses
)

# What termux_copy_notices is asked about: the shipped components, and not
# libicu, whose notice the base APK already carries at usr/share/doc/libicu. A
# second copy in this pack is an overlap bundletool refuses.
NOTICE_PACKAGES=(php composer capstone libgmp libxml2 libxslt libgcrypt libgpg-error oniguruma tidy libzip termux-licenses)

# Soname mapping for the shared libraries this pack adds. Each is placed under
# the name its consumer records in DT_NEEDED. check-termux-licenses.py reads this
# function too.
get_sonames() {
    case "$1" in
        capstone)      echo "libcapstone.so" ;;
        libgmp)        echo "libgmp.so" ;;
        libxml2)       echo "libxml2.so.16" ;;
        libxslt)       echo "libxslt.so libexslt.so" ;;
        libgcrypt)     echo "libgcrypt.so" ;;
        libgpg-error)  echo "libgpg-error.so" ;;
        oniguruma)     echo "libonig.so" ;;
        tidy)          echo "libtidy.so" ;;
        libzip)        echo "libzip.so" ;;
        libicu)        echo "libicuio.so.78" ;;
        *)             echo "" ;;
    esac
}

LIB_PACKAGES=(capstone libgmp libxml2 libxslt libgcrypt libgpg-error oniguruma tidy libzip libicu)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading PHP Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-php.tsv "${REQUIRED_PACKAGES[@]}"
PHP_VERSION="$(termux_pkg_version php)"
echo "  PHP version: $PHP_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing PHP toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib"

PHP_USR="extracted/php/data/data/com.termux/files/usr"
COMPOSER_USR="extracted/composer/data/data/com.termux/files/usr"

for src in "$PHP_USR/bin/php" "$COMPOSER_USR/bin/composer"; do
    if [ ! -f "$src" ]; then
        echo "  ERROR: $src is not in its package" >&2
        exit 1
    fi
    cp -L "$src" "$PACK_ASSETS/usr/bin/$(basename "$src")"
    echo "  bin/$(basename "$src")"
done

# Shared libraries, fatal on a missing one for the reason download-ruby.sh gives:
# the ELF gate below checks what is present, and this is about what is absent.
echo ""
echo "Placing shared libraries..."
for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        src=""
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            src="$pkg_lib_dir/$soname"
        else
            base_soname="$(echo "$soname" | sed 's/\.so\..*/\.so/')"
            if [ "$base_soname" != "$soname" ] && { [ -f "$pkg_lib_dir/$base_soname" ] || [ -L "$pkg_lib_dir/$base_soname" ]; }; then
                src="$pkg_lib_dir/$base_soname"
            fi
        fi
        if [ -z "$src" ]; then
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            exit 1
        fi
        # The rename trap download-termux-tools.sh closes: the unversioned link
        # a fallback finds can point at a library whose SONAME moved a major.
        actual_soname="$(read_soname "$src")"
        if [ -n "$actual_soname" ] && [ "$actual_soname" != "$soname" ]; then
            echo "  ERROR: $pkg ships $(basename "$src") with SONAME $actual_soname," >&2
            echo "         but this script expects $soname. Update get_sonames()." >&2
            exit 1
        fi
        cp -L "$src" "$PACK_ASSETS/usr/lib/$soname"
        echo "  $soname ($(du -sh "$PACK_ASSETS/usr/lib/$soname" | cut -f1))"
    done
done

# --- Step 5: Point the compiled-in default shell at one this app can run ---
#
# php carries Termux's /data/data/com.termux/files/usr/bin/sh once, as the C
# constant behind exec(), shell_exec(), system(), passthru(), popen() and
# proc_open() with a command string. That directory belongs to another
# application, so every one of those failed with ENOENT, and Composer runs git
# and its scripts through them. patch-default-shell.py keeps the ELF's length
# and fails closed if the package moves its shell. Before the ELF gate, so what
# the gate examines is what ships.
echo ""
echo "Pointing the compiled-in default shell at /system/bin/sh..."
python3 "$SCRIPT_DIR/patch-default-shell.py" "$PACK_ASSETS/usr/bin/php"

# --- Step 5b: Place the upstream notices beside what they describe ---
termux_copy_notices "$PACK_ASSETS/usr" "${NOTICE_PACKAGES[@]}"

# --- Step 6: Write toolchain_php.json ---
echo ""
echo "Writing toolchain_php.json..."

# php is the one real binary; composer is a phar with a `#!` line into Termux's
# prefix, which SELinux would refuse to execute from filesDir anyway, so it
# gets a function that hands the file to php, as Ruby's scripts are handed to
# ruby.
#
# `env` is present and empty rather than absent: regenerateEnvFileLocked skips a
# toolchain with no `env` object entirely, functions and PATH included.
LIBS='['
FIRST=true
for f in "$PACK_ASSETS/usr/lib"/*.so*; do
    [ -f "$f" ] || continue
    [ "$FIRST" = true ] && FIRST=false || LIBS+=','
    LIBS+="\"$(basename "$f")\""
done
LIBS+=']'
OWNED_PATHS=''
for doc in "$PACK_ASSETS"/usr/share/doc/*/; do
    [ -d "$doc" ] || continue
    [ -n "$OWNED_PATHS" ] && OWNED_PATHS+=','
    OWNED_PATHS+="\"usr/share/doc/$(basename "$doc")\""
done
cat > "$PACK_ASSETS/toolchain_php.json" << EOF
{
    "name": "php",
    "displayName": "PHP",
    "version": "$PHP_VERSION",
    "binaries": ["usr/bin/php", "usr/bin/composer"],
    "env": {},
    "pathDirs": ["usr/bin"],
    "ownedPaths": [$OWNED_PATHS],
    "libs": $LIBS,
    "scriptWrappers": {
        "interpreter": "php",
        "scripts": {"composer": "usr/bin/composer"}
    }
}
EOF
echo "  toolchain_php.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying PHP binaries ==="
# The pack's own usr/lib and the base APK's, which a toolchain installs beside.
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
    echo "  ERROR: $verify_failures of $verify_checked PHP binaries would fail to load on a device." >&2
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
echo "=== PHP Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total ($(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB in usr/)"
echo ""
echo "=== PHP download complete ==="
