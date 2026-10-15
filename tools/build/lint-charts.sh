#!/usr/bin/env bash
# ==================================================================================================
#  lint-charts.sh - the static checks on the Helm charts: helm lint, every values file rendered,
#  and the generated manifests compared with a fresh render.
#
#      tools/build/lint-charts.sh [render-dir]
#
#  helm lint --strict runs for each chart with its defaults and with every ci/*-values.yaml; each of
#  those is also rendered with `helm template` into render-dir (default: a temporary directory), one
#  file per values set, so a schema validator can read them afterwards - the pipeline's chart:lint
#  runs kubeconform over that directory. Last, render-manifests.sh --check.
#
#  First of all, each chart's `version` and `appVersion` must be the pom's version: a release ships
#  the chart as proxyma-<edition>-<version>.tgz and installs the image tagged appVersion, so a chart
#  left behind at the previous release pulls the previous image (the version bump writes both).
#
#  A chart that is not in the tree is skipped and said so: the public Community repository carries
#  proxyma-community and not proxyma-enterprise.
#
#  Needs helm 3 on PATH (or HELM naming it), and no cluster.
# ==================================================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHARTS="$ROOT/apps/app/deploy-kubernetes/charts"
HELM="${HELM:-helm}"
RENDER="${1:-$(mktemp -d)}"
mkdir -p "$RENDER"

command -v "$HELM" >/dev/null 2>&1 || { echo "[ERROR] helm not found - put it on PATH or set HELM" >&2; exit 1; }

# The pom version, read the way .gitlab/ci/oss.yml reads it - no Maven in the helm image.
POM_VERSION="$(sed -n '/<artifactId>proxyma-parent<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p}' "$ROOT/apps/app/backend/pom.xml")"
[ -n "$POM_VERSION" ] || { echo "[ERROR] could not read the version from apps/app/backend/pom.xml" >&2; exit 1; }

for chart in proxyma-enterprise proxyma-community; do
    dir="$CHARTS/$chart"
    if [ ! -f "$dir/Chart.yaml" ]; then
        echo "== $chart is not in this tree - skipped"
        continue
    fi
    for field in version appVersion; do
        got="$(awk -v f="$field" 'index($0, f ":") == 1 { v = $2; gsub(/"/, "", v); print v }' "$dir/Chart.yaml")"
        if [ "$got" != "$POM_VERSION" ]; then
            echo "[ERROR] $chart/Chart.yaml $field is '$got', the pom is $POM_VERSION - a chart is versioned with the release" >&2
            exit 1
        fi
    done
    "$HELM" dependency build "$dir" >/dev/null
    echo "== $chart (defaults)"
    "$HELM" lint --strict "$dir"
    "$HELM" template px "$dir" --namespace proxyma | tr -d '\r' > "$RENDER/$chart-defaults.yaml"
    for values in "$dir"/ci/*-values.yaml; do
        name="$(basename "$values" .yaml)"
        echo "== $chart ($name)"
        "$HELM" lint --strict "$dir" -f "$values"
        "$HELM" template px "$dir" --namespace proxyma -f "$values" | tr -d '\r' > "$RENDER/$chart-$name.yaml"
    done
done
echo "  rendered into $RENDER"

HELM="$HELM" "$ROOT/tools/build/render-manifests.sh" --check
