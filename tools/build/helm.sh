#!/usr/bin/env bash
# ==================================================================================================
#  helm.sh - helm 3 from PATH, or the pinned helm image under Docker when there is none.
#
#      tools/build/helm.sh <helm arguments>         helm, run from the repository root
#      tools/build/helm.sh --bash <script> [args]   a script that calls helm, the same way
#
#  Paths are relative to the repository root, which is the working directory either way (mounted at
#  /w in the container), so one command line works on a machine with helm and on one with only
#  Docker - the maintainer's PC has no helm. HELM names a binary to use instead of PATH's;
#  HELM_IMAGE overrides the image, which is the one the pipeline's chart:lint pins
#  (.gitlab/ci/charts.yml) - change both together.
# ==================================================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="${HELM_IMAGE:-alpine/helm:3.16.4}"

BASH_MODE=0
if [ "${1:-}" = "--bash" ]; then BASH_MODE=1; shift; fi
[ $# -gt 0 ] || { sed -n '4,6p' "${BASH_SOURCE[0]}" >&2; exit 2; }

if [ -n "${HELM:-}" ] || command -v helm >/dev/null 2>&1; then
    cd "$ROOT"
    if [ "$BASH_MODE" = 1 ]; then exec bash "$@"; fi
    exec "${HELM:-helm}" "$@"
fi

command -v docker >/dev/null 2>&1 && docker version >/dev/null 2>&1 \
    || { echo "helm.sh: no helm on PATH and no running Docker to run $IMAGE in" >&2; exit 1; }
# Git Bash rewrites anything that looks like a path, a volume spec included; the Windows form of the
# root and MSYS_NO_PATHCONV keep the mount and the arguments as written.
HOST_ROOT="$ROOT"
command -v cygpath >/dev/null 2>&1 && HOST_ROOT="$(cygpath -w "$ROOT")"
# The runner's own uid, so what helm writes (charts/*/charts/, a package) is not left owned by root.
DOCKER=(docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$HOST_ROOT:/w" -w /w)
if [ "$BASH_MODE" = 1 ]; then
    MSYS_NO_PATHCONV=1 exec "${DOCKER[@]}" --entrypoint bash "$IMAGE" "$@"
fi
MSYS_NO_PATHCONV=1 exec "${DOCKER[@]}" "$IMAGE" "$@"
