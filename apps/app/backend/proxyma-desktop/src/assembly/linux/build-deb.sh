#!/usr/bin/env bash
#
# Builds the Linux desktop .deb. Invoked by the Maven `linux-desktop` profile at the verify phase,
# immediately AFTER stage-linux.sh.
#
# THE .DEB IS THE ONLY LINUX PACKAGE, since 2026-09-26 (maintainer's decision). It is what an
# Ubuntu or Debian user reaches for: it lands in the menu, `apt` knows about it, removing it is
# `apt remove proxyma` - and, unlike the AppImage that shipped beside it until then, it can install
# Chromium's setuid sandbox helper, so Proxyma's application window keeps its sandbox.
#
# stage-linux.sh assembles the tree this packages - the JRE, the launcher jar and the window. The
# ORDER is therefore load-bearing: this script fails loudly rather than silently producing a hollow
# package if the staged tree is not there.
#
# THE PACKAGE IS SMALL, since 2026-09-26. The application, the Python runtime and the headless
# browser are parts the launcher downloads on the first start into the user's data directory, and
# updates without the system installer - so the package changes only when its Java runtime or the
# window does, and an update never asks for a password.
#
# THE INSTALL ROOT IS ROOT-OWNED, and that is fine because nothing writable lives in it.
#
# Usage: build-deb.sh <version> <target-dir> <assembly-dir> <releases-dir>
set -euo pipefail

VERSION="${1:?version required}"
TARGET_DIR="${2:?target dir required}"
ASSEMBLY_DIR="${3:?assembly dir required}"
RELEASES_DIR="${4:?releases dir required}"

APPDIR="$TARGET_DIR/linux-stage"
STAGE="$TARGET_DIR/deb"

# The Debian file naming convention, which is NOT the convention the other artifacts in this release
# follow (proxyma-x64-v0.4.0.exe). That inconsistency is deliberate: dpkg, apt and every tool
# that reads a package filename expect <package>_<version>_<arch>.deb, and a file named to match its
# siblings would look wrong to every Debian user and to some tooling. The name is a contract with
# two other places - requiredAssets() in apps/landing/scripts/resolve-latest-version.mjs and the
# ASSETS list in tools/release/publish.sh - and changing it means changing all three.
DEB_NAME="proxyma_${VERSION}_amd64.deb"
OUT="$RELEASES_DIR/$DEB_NAME"

if [ ! -d "$APPDIR" ]; then
    echo "ERROR: $APPDIR does not exist." >&2
    echo "       build-deb.sh packages the tree stage-linux.sh stages, so it has to run after" >&2
    echo "       it. If the staging step was skipped, this one cannot run either." >&2
    exit 1
fi

# Guard against packaging a tree that exists but is hollow. An empty jre/ or a missing launcher jar
# produces a .deb that installs cleanly and cannot start, which is the failure a user reports as
# "it does nothing" three weeks later.
for required in "$APPDIR/jre/bin/java" "$APPDIR/proxyma-launcher.jar" \
                "$APPDIR/window/proxyma-window" "$APPDIR/window/chrome-sandbox" \
                "$APPDIR/THIRD-PARTY-NOTICES.txt"; do
    if [ ! -f "$required" ]; then
        echo "ERROR: expected '$required' in the staged tree and it is not there." >&2
        exit 1
    fi
done

echo "==> Staging $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE/DEBIAN" \
         "$STAGE/opt/proxyma" \
         "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" \
         "$STAGE/usr/share/icons/hicolor/256x256/apps" \
         "$STAGE/usr/share/icons/hicolor/512x512/apps"

# THIRD-PARTY-NOTICES.txt goes in /opt/proxyma, the installation folder the EULA names.
cp -a "$APPDIR/jre" "$APPDIR/window" "$APPDIR/proxyma-launcher.jar" "$APPDIR/THIRD-PARTY-NOTICES.txt" \
      "$STAGE/opt/proxyma/"

# The program's own license and copyright notice (Proxyma Community is AGPL-3.0-only): beside the
# notices in /opt/proxyma, and as the package's copyright file where Debian looks for one.
REPO_ROOT="$(cd "$ASSEMBLY_DIR/../../../../../.." && pwd)"   # src/assembly, six levels below the root (as stage-linux.sh)
for f in LICENSE NOTICE; do
    [ -f "$REPO_ROOT/$f" ] || { echo "ERROR: $REPO_ROOT/$f is missing - the package must carry its license." >&2; exit 1; }
done
cp "$REPO_ROOT/LICENSE" "$STAGE/opt/proxyma/LICENSE.txt"
cp "$REPO_ROOT/NOTICE" "$STAGE/opt/proxyma/NOTICE.txt"
mkdir -p "$STAGE/usr/share/doc/proxyma"
cat "$REPO_ROOT/NOTICE" > "$STAGE/usr/share/doc/proxyma/copyright"
printf '\nThe full license text is in /opt/proxyma/LICENSE.txt.\n' >> "$STAGE/usr/share/doc/proxyma/copyright"

cp "$ASSEMBLY_DIR/linux/proxyma-deb-launcher" "$STAGE/usr/bin/proxyma"
chmod 755 "$STAGE/usr/bin/proxyma"

cp "$ASSEMBLY_DIR/linux/proxyma.desktop" "$STAGE/usr/share/applications/proxyma.desktop"
# Each icon in the directory of its own size, as the icon theme spec reads them: the 512 master
# went into 256x256 until 2026-10-14, which desktops scale or skip. tools/design/rasterize-logo.py
# writes both.
cp "$ASSEMBLY_DIR/linux/proxyma-256.png" \
   "$STAGE/usr/share/icons/hicolor/256x256/apps/proxyma.png"
cp "$ASSEMBLY_DIR/linux/proxyma.png" \
   "$STAGE/usr/share/icons/hicolor/512x512/apps/proxyma.png"

# dpkg refuses a package whose files are group- or world-writable, and cp -a from a build tree can
# carry whatever umask the runner had. Directories 755, files 644, then the two things that have to
# be executable are set back explicitly.
find "$STAGE/opt" "$STAGE/usr" -type d -exec chmod 755 {} +
find "$STAGE/opt" "$STAGE/usr" -type f -exec chmod 644 {} +
chmod 755 "$STAGE/usr/bin/proxyma"
# The JRE's own binaries and .so loaders, and anything else that arrived executable, would otherwise
# have just been flattened to 644 - which installs a package whose `java` cannot be run.
find "$STAGE/opt/proxyma/jre/bin" -type f -exec chmod 755 {} +
find "$STAGE/opt/proxyma/jre/lib" -name 'jspawnhelper' -exec chmod 755 {} + 2>/dev/null || true
# The application window's executables. chrome-sandbox is SETUID ROOT (4755) - Chromium's sandbox
# helper, which Electron refuses to start without unless the sandbox is switched off, and which is
# the reason Linux is .deb-only: dpkg --root-owner-group below makes it root-owned, as it must be.
chmod 755 "$STAGE/opt/proxyma/window/proxyma-window" "$STAGE/opt/proxyma/window/chrome_crashpad_handler"
chmod 4755 "$STAGE/opt/proxyma/window/chrome-sandbox"

INSTALLED_KB="$(du -sk "$STAGE/opt" "$STAGE/usr" | awk '{total += $1} END {print total}')"

echo "==> Writing DEBIAN/control"
# Depends is deliberately minimal: the JRE is bundled and CPython is a downloaded part, so there is
# no system Java or Python to depend on, and libc6 is the only thing the shipped binaries need to be
# guaranteed.
#
# There is deliberately NO Recommends list for the headless browser's shared libraries (libnss3,
# libatk, libgbm and the rest). They are genuinely needed for JavaScript rendering - but their
# package names differ across the distributions that consume .deb files, and at least one was
# renamed under Ubuntu 24.04's t64 transition. A Recommends line naming a package that no longer
# exists produces an apt warning on every install for a feature most users never touch, and I have
# no Linux machine on which to check the names for each target. The requirement is stated in the
# description instead, where being slightly vague is honest rather than broken.
#
# Tesseract IS recommended, and the difference is the names: tesseract-ocr and its language packs
# have kept theirs across Debian and Ubuntu for years (the t64 rename touched libtesseract5, which
# apt pulls in by itself). apt installs Recommends by default, which makes the offline OCR engine
# work out of the box - the bundled pytesseract is only a driver for this program.
# THE PACKAGE VERSION IS NOT QUITE THE PRODUCT VERSION FOR A DEV BUILD. A dev build is
# <next>-dev<N> (tools/release/dev-version.sh), and dpkg reads everything after the last hyphen as a
# Debian REVISION - so 0.12.1-dev57 would sort AFTER 0.12.1, and installing the release over the
# dev build would be refused as a downgrade. A tilde sorts
# before everything, including the end of the string, so 0.12.1~dev57 is below 0.12.1 as intended.
# The FILE keeps the hyphen: its name is matched by suffix and must equal the release asset's.
DEB_VERSION="$(printf '%s' "$VERSION" | sed 's/-dev/~dev/')"
cat > "$STAGE/DEBIAN/control" <<EOF
Package: proxyma
Version: $DEB_VERSION
Section: utils
Priority: optional
Architecture: amd64
Maintainer: Le Sy Hau <contact@proxyma.ai>
Installed-Size: $INSTALLED_KB
Depends: libc6
Recommends: tesseract-ocr, tesseract-ocr-eng, tesseract-ocr-vie
Homepage: https://proxyma.ai
Description: Semantic search and AI chat over your own documents
 Proxyma indexes the documents, wikis and repositories you point it at and
 lets you ask questions about them in plain language. It runs on your own
 machine; documents are not uploaded anywhere unless you configure a hosted
 AI provider.
 .
 The Java runtime is bundled. The first start downloads the rest of
 Proxyma into your home directory, and later updates download only what
 changed. Rendering JavaScript-heavy web pages additionally needs the
 shared libraries a headless Chromium requires; every other feature works
 without them.
EOF

echo "==> Building the package"
mkdir -p "$RELEASES_DIR"
rm -f "$OUT"
# --root-owner-group, so the package installs as root:root regardless of who built it. Without it
# dpkg-deb records the BUILDER's uid and gid, and lintian and some apt configurations reject that -
# a CI runner's uid 1001 has no meaning on the user's machine.
#
# THE FLAG IS --root-owner-group. This said --root-owned-files, which is not a dpkg-deb option and
# does not exist; the v0.4.0 release failed on it with "unknown option", after a four-minute build.
# It was written from memory and there was no Linux machine here to run it on, which is exactly the
# category of mistake that only running the thing catches. (dpkg 1.19.0 and later; ubuntu-latest
# carries 1.22.)
dpkg-deb --root-owner-group --build "$STAGE" "$OUT"

# Prove what was actually produced rather than trusting a zero exit. A .deb whose control file
# parses but whose payload is empty is a real outcome of a mis-staged tree.
echo ""
dpkg-deb --info "$OUT" | sed 's/^/    /'
FILE_COUNT="$(dpkg-deb --contents "$OUT" | wc -l)"
echo "    entries: $FILE_COUNT"
if [ "$FILE_COUNT" -lt 50 ]; then
    echo "ERROR: only $FILE_COUNT entries in the package - the JRE alone is far more than that." >&2
    echo "       Something staged empty. Refusing to publish a package that cannot run." >&2
    exit 1
fi

echo ""
echo "Package: $OUT"
