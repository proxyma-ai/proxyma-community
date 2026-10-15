#!/usr/bin/env bash
# ==================================================================================================
#  verify-community.sh - build and test Proxyma Community in a tree that has NO ee/.
#
#      tools/oss/verify-community.sh <tree>
#
#  Two callers, one question - does the Community product stand on its own:
#
#    community:verify (.gitlab/ci/oss.yml)   <tree> is this repository's commit with ee/ deleted.
#                                            Proves nothing outside ee/ needs it to build or pass.
#    tools/oss/export.mjs --verify           <tree> is the public export itself, so what is pushed
#                                            to GitHub is what was built here.
#
#  What it runs, in that tree and with that tree's own scripts:
#    1. every Maven module's tests (the whole reactor - without ee/ it IS the Community reactor)
#    2. the desktop jar - the Desktop app and the Home Server image's payload - with its web interface
#    3. every `node --test` file in the tree
#    4. the generated lists and the catalogs (server messages, connector labels, strict i18n, the kit
#       copies, the Python notices list)
#  It does NOT build the Home Server Docker image, which wraps the jar from step 2 and needs Docker.
#
#  COMMUNITY_MVN_ARGS is appended to the jar build: `-Dskip.installer=true` on the PC, where the
#  Windows installer profile would otherwise run (CI is Linux and builds the jar as images.sh does).
# ==================================================================================================
set -euo pipefail

TREE="$(cd "${1:?usage: verify-community.sh <tree>}" && pwd)"
cd "$TREE"

if [ -e ee ]; then
    echo "verify-community: $TREE has an ee/ directory - this checks a tree WITHOUT Proxyma Enterprise" >&2
    exit 1
fi

step() { printf '\n== %s\n' "$*"; }

# A Python that is real. On Windows `python3` is the Store's decoy and exits 9009; on the CI host
# only python3 exists. The desktop build's Playwright trim shells out to bare `python`, so that name
# must resolve too - a shim on PATH for this script alone, as build.yml does.
PY=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys' >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "verify-community: no working python3 or python on PATH" >&2; exit 1; }
SHIM="$(mktemp -d)"
trap 'rm -rf "$SHIM"' EXIT
if ! { command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; }; then
    ln -s "$(command -v "$PY")" "$SHIM/python"
    export PATH="$SHIM:$PATH"
fi

step "1/4  Java: every module's tests"
# -Dskip.frontend.build: the test phase needs no SPA (as test:backend). Step 2 builds it for real.
mvn -B -ntp test -Dskip.frontend.build=true

step "2/4  The desktop jar, with its web interface"
# shellcheck disable=SC2086
mvn -B -ntp package -pl :proxyma-desktop -am -DskipTests ${COMMUNITY_MVN_ARGS:-}
JAR="$(ls apps/app/backend/proxyma-desktop/target/proxyma-desktop-*-exec.jar 2>/dev/null | head -n1 || true)"
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "verify-community: no proxyma-desktop-*-exec.jar was built" >&2; exit 1; }
# Entry count, not size: a jar with no SPA is the same ~340 MB (CLAUDE.md, "Build commands").
STATIC="$(jar tf "$JAR" | grep -c '^BOOT-INF/classes/static/' || true)"
jar tf "$JAR" | grep -qx 'BOOT-INF/classes/static/index.html' \
    || { echo "verify-community: $JAR has no web interface (static/index.html)" >&2; exit 1; }
[ "$STATIC" -ge 20 ] || { echo "verify-community: $JAR carries only $STATIC static entries" >&2; exit 1; }
echo "  $(basename "$JAR"): $STATIC static entries"

step "3/4  node --test"
mapfile -t TESTS < <(find apps libs tools -name node_modules -prune -o -type f \( -name '*.test.ts' -o -name '*.test.mjs' \) -print | sort)
echo "  ${#TESTS[@]} test files"
node --test "${TESTS[@]}"

step "4/4  Generated lists and catalogs"
node tools/i18n/server-messages.mjs --check
node tools/i18n/connector-labels.mjs --check
I18N_STRICT=1 node libs/ui-kit/i18n/check-catalogs.mjs
node libs/ui-kit/sync.mjs --check
(cd apps/app/frontend && node src/styles/proxyma/verify-kit.mjs)
"$PY" tools/build/python-notices.py check

printf '\nverify-community: Proxyma Community builds and passes on its own in %s\n' "$TREE"
