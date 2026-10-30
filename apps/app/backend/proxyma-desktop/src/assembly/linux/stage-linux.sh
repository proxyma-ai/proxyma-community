#!/usr/bin/env bash
#
# Stages the Linux desktop build, in two halves, for the Maven `linux-desktop` profile at the verify
# phase, before build-deb.sh:
#
#   <target-dir>/linux-stage   what the .deb installs into /opt/proxyma: a JRE, the launcher jar and
#                              the application window
#   <target-dir>/parts-stage   the PARTS the launcher downloads on the first start - the app, the
#                              launcher again, the Python runtime with its wheels, and the headless
#                              browser - for tools/release/parts.py to pack and publish
#
# THE .DEB IS SMALL, since 2026-09-26. Everything that used to make it 800 MB is a part now, in the
# user's data directory, updated part by part without the system installer. The window is the one
# large thing left in it, for the reason given where it is staged below.
#
# WHY THE BIG BINARIES ARE DOWNLOADED RATHER THAN COMMITTED. The Windows build keeps its JRE and
# Python archives in git under src/assembly/setup/. Doing the same here would add roughly 75 MB of
# binaries to the repository permanently, for artifacts that are byte-identical to a published,
# checksummed upstream release. They are fetched into a cache directory instead, which CI restores
# between runs, so a warm build downloads nothing.
#
# Usage: stage-linux.sh <version> <target-dir> <assembly-dir> <releases-dir>
set -euo pipefail

VERSION="${1:?version required}"
TARGET_DIR="${2:?target dir required}"
ASSEMBLY_DIR="${3:?assembly dir required}"
RELEASES_DIR="${4:?releases dir required}"

# Pinned deliberately. The bundled CPython MINOR must match the Python that downloads the wheels
# below, or pip resolves wheels for the wrong ABI and every one of them is rejected at install time
# on the user's machine - after they have already downloaded a 300 MB file.
JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/OpenJDK21U-jre_x64_linux_hotspot_21.0.10_7.tar.gz"
JRE_TGZ="OpenJDK21U-jre_x64_linux_hotspot_21.0.10_7.tar.gz"
CPYTHON_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20250115/cpython-3.13.1+20250115-x86_64-unknown-linux-gnu-install_only.tar.gz"
CPYTHON_TGZ="cpython-3.13.1-x86_64-linux-gnu.tar.gz"

CACHE_DIR="${PROXYMA_BUILD_CACHE:-$HOME/.proxyma-build-cache}"
APPDIR="$TARGET_DIR/linux-stage"

mkdir -p "$CACHE_DIR" "$RELEASES_DIR"

# Downloads to a temporary name and renames on success, so an interrupted download can never leave a
# truncated file in the cache that every later build then happily reuses.
fetch() {
    local url="$1" dest="$2"
    if [ -f "$dest" ]; then
        echo "  cached: $(basename "$dest")"
        return
    fi
    echo "  downloading: $(basename "$dest")"
    curl -fsSL --retry 3 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
}

echo "==> Fetching build dependencies into $CACHE_DIR"
fetch "$JRE_URL"          "$CACHE_DIR/$JRE_TGZ"
fetch "$CPYTHON_URL"      "$CACHE_DIR/$CPYTHON_TGZ"

PARTS="$TARGET_DIR/parts-stage"
echo "==> Staging $APPDIR and $PARTS"
rm -rf "$APPDIR" "$PARTS"
mkdir -p "$APPDIR/jre" "$PARTS/launcher" "$PARTS/app" "$PARTS/python/wheels"

# The launcher, twice: in the .deb as what /usr/bin/proxyma starts, and as the launcher part, which
# Boot prefers once a newer release is installed.
cp "$TARGET_DIR/proxyma-desktop-$VERSION-launcher.jar" "$APPDIR/proxyma-launcher.jar"
cp "$TARGET_DIR/proxyma-desktop-$VERSION-launcher.jar" "$PARTS/launcher/proxyma-launcher.jar"
# APP_JAR: the application jar to carry (the pom's proxyma.app.jar), this module's own by default.
cp "${APP_JAR:-$TARGET_DIR/proxyma-desktop-$VERSION-exec.jar}" "$PARTS/app/proxyma-desktop.jar"

# --strip-components=1 drops the "jdk-21.0.10+7-jre/" wrapper directory, so the JRE lands as
# jre/bin/java - the path the .deb's launcher script expects.
tar -xzf "$CACHE_DIR/$JRE_TGZ" -C "$APPDIR/jre" --strip-components=1

# The CPython archive goes into the python part WHOLE, not extracted. The launcher extracts it into
# the user's data directory, the only place pip can then install into it.
cp "$CACHE_DIR/$CPYTHON_TGZ" "$PARTS/python/"
cp "$ASSEMBLY_DIR/setup/requirements.txt" "$PARTS/python/"

echo "==> Downloading offline wheels"
# Mirrors installer/download-wheels.bat, which does exactly this for Windows. The launcher installs
# from this directory with --no-index --find-links, so nothing is resolved on the user's machine.
PY="${PYTHON:-python3}"
PY_MINOR="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
if [ "$PY_MINOR" != "3.13" ]; then
    echo "ERROR: wheels must be downloaded with Python 3.13 to match the bundled CPython," >&2
    echo "       but '$PY' is $PY_MINOR. pip would resolve wheels for the wrong ABI and every" >&2
    echo "       one of them would be rejected on the user's machine." >&2
    exit 1
fi
# ONE resolution for the whole list, from the same requirements.txt the Windows build reads.
"$PY" -m pip download -r "$ASSEMBLY_DIR/setup/requirements.txt" --dest "$PARTS/python/wheels"

# The manifest ProxymaLauncher.verifyWheelChecksums checks before running pip, in the same
# "<sha256>  <filename>" format the Windows script writes.
( cd "$PARTS/python/wheels" && sha256sum ./*.whl | sed 's#\./##' > SHA256SUMS.txt )
echo "  $(find "$PARTS/python/wheels" -name '*.whl' | wc -l) wheel(s)"

# What those wheels are, read from the wheels: the python part's own notices, as the Windows build
# writes them. INSIDE wheels/, because parts.py names the python part by everything else. Fails if
# a package is missing from apps/app/third-party/python-packages.txt, the list the jar carries.
"$PY" "$ASSEMBLY_DIR/../../../../../../tools/build/python-notices.py" wheels \
    "$PARTS/python/wheels" "$PARTS/python/wheels/THIRD-PARTY-NOTICES-python.txt"

# THIRD-PARTY-NOTICES.txt, which the Maven build joined beside the jar. The .deb installs it in
# /opt/proxyma, where the EULA says it is.
if [ ! -f "$TARGET_DIR/THIRD-PARTY-NOTICES.txt" ]; then
    echo "ERROR: $TARGET_DIR/THIRD-PARTY-NOTICES.txt is missing - the build's third-party-notices step did not run." >&2
    exit 1
fi
cp "$TARGET_DIR/THIRD-PARTY-NOTICES.txt" "$APPDIR/THIRD-PARTY-NOTICES.txt"

echo "==> Bundling the headless browser"
# Playwright's OWN CLI does the download, deliberately. The browser revision is pinned per
# Playwright version (1.58.0 wants chromium 1208), and on x64 Linux the headless shell comes from
# the chrome-for-testing CDN rather than Playwright's own builds path - reimplementing that URL
# logic in bash means it breaks silently on the next Playwright bump. Asking the CLI means the
# revision and the on-disk layout are correct by construction.
#
# PLAYWRIGHT_BROWSERS_PATH puts it in our staging directory rather than the build machine's shared
# ~/.cache/ms-playwright, so the archive holds what we intend and a warm CI cache cannot leak an
# unrelated browser into a release.
#
# The part is packed as tar.gz by tools/release/parts.py, which keeps the executable bits the
# shell binary and its helpers need.
BROWSER_STAGE="$TARGET_DIR/browser-stage"
rm -rf "$BROWSER_STAGE"
mkdir -p "$BROWSER_STAGE"

# The classpath comes from Maven, in the PW_CLASSPATH environment variable - it is not discovered by globbing
# ~/.m2. Guessing the repository location is wrong on any build that relocates it, and a glob that
# finds nothing is indistinguishable from a repository that is simply somewhere else.
if [ -z "${PW_CLASSPATH:-}" ]; then
    echo "ERROR: PW_CLASSPATH is not set. It is passed by the Maven linux-desktop profile." >&2
    exit 1
fi

PLAYWRIGHT_BROWSERS_PATH="$BROWSER_STAGE" "$APPDIR/jre/bin/java" -cp "$PW_CLASSPATH" com.microsoft.playwright.CLI install chromium-headless-shell

# Fail loudly rather than publishing an empty part. A missing browser is not something anyone
# notices until a user reports that JS pages render blank.
if [ -z "$(ls -A "$BROWSER_STAGE" 2>/dev/null)" ]; then
    echo "ERROR: the browser staging directory is empty after 'playwright install'." >&2
    exit 1
fi
mkdir -p "$PARTS/browser"
cp -a "$BROWSER_STAGE/." "$PARTS/browser/"
echo "  browser: $(du -sh "$PARTS/browser" | cut -f1)"

echo "==> Staging Proxyma's application window"
# The same Electron shell Windows downloads as a part (apps/app/desktop-shell) - but on Linux it
# stays IN THE .DEB: build-deb.sh makes chrome-sandbox setuid root, which keeps Chromium's sandbox
# on, and only a package installed by root can do that. A part in the user's data directory could
# not.
node "$ASSEMBLY_DIR/../../../../../../tools/build/stage-desktop-shell.mjs" "$APPDIR/window"
if [ ! -x "$APPDIR/window/proxyma-window" ]; then
    echo "ERROR: the window was not staged at $APPDIR/window/proxyma-window" >&2
    exit 1
fi

echo ""
echo "Staged: $APPDIR (the .deb) and $PARTS (the parts)"
