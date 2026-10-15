#!/usr/bin/env bash
# ==============================================================================
# Proxyma Home Server - installer. Run on the host, from this directory.
#
#   ./install.sh
#
# Idempotent: every step is skipped when it has already been done, so re-running
# after an upgrade or a config change is safe and is the intended way to apply
# both.
#
# It will not touch anything outside this directory, will not install Docker,
# and will not overwrite a value you have already set in .env.
#
# Non-interactive (CI, config management): set PROXYMA_ASSUME_YES=1 and make
# sure .env is complete beforehand. The script then fails rather than prompting.
# ==============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

ASSUME_YES="${PROXYMA_ASSUME_YES:-0}"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  [WARN] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m  [ERROR] %s\033[0m\n' "$*" >&2; exit 1; }

# --- 1. Prerequisites ---------------------------------------------------------
say "[1/4] Prerequisites"
command -v docker >/dev/null 2>&1 || die "docker is not installed. See INSTALL.md."
docker compose version >/dev/null 2>&1 || die "the docker compose plugin is not installed. See INSTALL.md."
if docker info >/dev/null 2>&1; then
    DOCKER="docker"
elif sudo -n docker info >/dev/null 2>&1 || sudo docker info >/dev/null 2>&1; then
    DOCKER="sudo docker"
    warn "using sudo for docker - add yourself to the docker group to avoid this"
else
    die "cannot talk to the docker daemon, with or without sudo."
fi
echo "  $($DOCKER --version)"

# --- 2. Configuration ---------------------------------------------------------
say "[2/4] Configuration"
if [ ! -f .env ]; then
    cp .env.example .env
    chmod 600 .env
    echo "  created .env from .env.example"
    if [ "$ASSUME_YES" != "1" ]; then
        echo
        echo "  Edit .env now - section 1 is the only part that needs a decision: who can"
        echo "  reach an application with no accounts, where one access code is the only sign-in."
        echo
        echo "    \$EDITOR $DIR/.env"
        exit 0
    fi
else
    echo "  .env exists - leaving your settings alone"
fi

sed -i "s|^HOST_UID=.*|HOST_UID=$(id -u)|" .env
sed -i "s|^HOST_GID=.*|HOST_GID=$(id -g)|" .env

# .env is read by docker compose itself, never sourced into this shell - a stray unquoted value
# in it would otherwise execute here.
#
# `|| true` because AN ABSENT KEY IS A NORMAL ANSWER. Without it this function is a landmine under
# `set -euo pipefail`: grep exits 1 when it matches nothing, pipefail makes the pipeline exit 1,
# and a bare `VAR="$(envget MISSING)"` then ends the script silently. The server installer was
# bitten by exactly this once a call site read a key that ships commented out.
envget() { grep -E "^$1=" .env | head -1 | cut -d= -f2- || true; }

HOSTS="$(envget PROXYMA_ALLOWED_HOSTS)"
BIND="$(envget BIND_ADDR)";  BIND="${BIND:-127.0.0.1}"
PORT="$(envget HTTP_PORT)";  PORT="${PORT:-4246}"

# Say out loud what this installation is going to be, every time, before starting it. There are no
# accounts in this edition, so these lines and the access code are the whole access-control story.
if [ -z "$HOSTS" ]; then
    echo "  reachable from: this machine only (loopback)"
    if [ "$BIND" != "127.0.0.1" ]; then
        warn "BIND_ADDR is $BIND but PROXYMA_ALLOWED_HOSTS is empty."
        echo "        The port is published on your network and the application will answer 403"
        echo "        to every request that does not arrive as localhost. That is safe, and it is"
        echo "        probably not what you wanted - name your host in PROXYMA_ALLOWED_HOSTS."
    fi
elif [ "$HOSTS" = "*" ]; then
    warn "PROXYMA_ALLOWED_HOSTS=* - ANY host name is accepted, with the access code the only sign-in."
    echo "        Anyone who can route to ${BIND}:${PORT} and has the code has every document you"
    echo "        index, every conversation, and your AI provider spend. Intended only behind your"
    echo "        own tunnel or authenticating proxy."
else
    echo "  reachable from: $HOSTS (and loopback) - with the access code, on ${BIND}:${PORT}"
fi

# --- 3. Data directory --------------------------------------------------------
# Office Files: the key the app and the sandbox container share (docker-compose.yml, the `office`
# profile). Generated once. An .env from before Office Files has no line for it, so it is appended.
if [ -z "$(envget PROXYMA_SANDBOX_TOKEN)" ]; then
    SANDBOX_TOKEN="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
    if grep -qE '^PROXYMA_SANDBOX_TOKEN=' .env; then
        sed -i "s|^PROXYMA_SANDBOX_TOKEN=.*|PROXYMA_SANDBOX_TOKEN=${SANDBOX_TOKEN}|" .env
    else
        printf '\nPROXYMA_SANDBOX_TOKEN=%s\n' "$SANDBOX_TOKEN" >> .env
    fi
    echo "  generated PROXYMA_SANDBOX_TOKEN for the Office Files sandbox"
fi

say "[3/4] Data directory"
mkdir -p data
chown -R "$(id -u):$(id -g)" data 2>/dev/null || true
echo "  ok - the database, the search index, the vector store and your conversations live here"

# --- 4. Start -----------------------------------------------------------------
# Build or pull, decided by one file rather than by a flag nobody would know to pass. A service
# with a `build:` key builds a missing image rather than fetching it, so the pull has to be
# explicit - and it names the service, out of habit learned the hard way on the server package.
say "[4/4] Starting"
IMAGE="$(envget PROXYMA_IMAGE)"; IMAGE="${IMAGE:-ghcr.io/proxyma-ai/proxyma-homeserver:latest}"
# Is that a tag that moves? :latest does; a version tag and a @sha256 digest do not, and an
# image written with no tag at all is :latest by definition.
#
# The test is applied to the part after the LAST SLASH, because that is the only place a tag
# can appear. Testing the whole reference makes a private registry with a port -
# localhost:5000/proxyma - look tagged when it is really :latest, which would pin it and never
# re-pull it. PROXYMA_IMAGE exists so a host can point at exactly such a registry.
IMAGE_REF="${IMAGE##*/}"
case "$IMAGE" in
    *@sha256:*) MOVING_TAG=no ;;
    *)
        case "$IMAGE_REF" in
            *:latest) MOVING_TAG=yes ;;
            *:*)      MOVING_TAG=no  ;;
            *)        MOVING_TAG=yes ;;
        esac
        ;;
esac
if [ -f app.jar ]; then
    echo "  app.jar is here - building the image from it"
    $DOCKER compose up -d --build
# A PINNED tag already on this host is definitively the right image. A MOVING one is not.
#
# The shortcut below exists for the no-registry route in INSTALL.md - docker load an image
# tar, then install without ever reaching a registry - and that case still works. But the
# default image is :latest, which MOVES: "already on this host" then means "some older build
# of latest is cached here", so re-running this script after a release kept the old container
# and printed success. From the local daemon a stale :latest and a current one are one name.
elif [ "$MOVING_TAG" = no ] && $DOCKER image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "  $IMAGE is pinned and already on this host - not pulling"
    $DOCKER compose up -d
# A moving tag is re-fetched every time, and falls back to the local copy ONLY when the
# registry cannot be reached - so an offline host still starts, and is told what it is running.
elif $DOCKER image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "  $IMAGE is a moving tag - checking the registry for a newer build"
    if $DOCKER compose pull proxyma; then
        echo "  image is up to date"
    else
        warn "could not reach the registry, so this is starting the copy already on this host.
         If a newer release exists, you are NOT running it."
    fi
    $DOCKER compose up -d
else
    echo "  pulling $IMAGE"
    $DOCKER compose pull proxyma \
        || die "could not pull $IMAGE.
          Check this host's network access, or set PROXYMA_IMAGE in .env to an image it can reach."
    $DOCKER compose up -d
fi

echo
$DOCKER compose ps

say "Waiting for Proxyma to answer"
# The status code, not curl's exit code. `curl -f` collapses "connection refused" and "500" into
# one failure, and those need completely different fixes.
#
# Asked as localhost deliberately: that is the one Host header this application always accepts, so
# a 403 here would mean something other than the allow-list is wrong. The readiness probe, because
# it is the one that needs no access code and answers 200 only once the application has started.
#
# First start is slow - the vector store is brought up before the web port opens, and that alone
# can take a minute.
LAST_CODE=000
for i in $(seq 1 60); do
    LAST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: localhost:${PORT}" \
                     "http://127.0.0.1:${PORT}/api/v1/health/ready" 2>/dev/null || echo 000)
    if [ "$LAST_CODE" = "200" ]; then
        echo "  healthy after $((i * 5))s"
        cat <<EOF

  Proxyma is running.

    On this machine : http://localhost:${PORT}
EOF
        [ -n "$HOSTS" ] && [ "$HOSTS" != "*" ] && echo "    From your LAN   : http://${HOSTS%%,*}:${PORT}"
        cat <<EOF

  There is no account to create. Open it and enter the access code:
EOF
        CODE_FILE="data/.local/share/proxyma/access-code"
        if [ -n "$(envget PROXYMA_ACCESS_CODE)" ]; then
            echo "    the one you set in .env (PROXYMA_ACCESS_CODE)"
        elif [ -r "$CODE_FILE" ]; then
            echo "    $(cat "$CODE_FILE")"
        else
            echo "    $DOCKER compose logs proxyma | grep -A1 'access code'"
        fi
        cat <<EOF

  Logs    : $DOCKER compose logs -f proxyma
  Stop    : $DOCKER compose down
  Upgrade : docker compose pull proxyma && ./install.sh
EOF
        exit 0
    fi
    sleep 5
done
if [ "$LAST_CODE" = "000" ]; then
    warn "Proxyma never answered on http://127.0.0.1:${PORT} - it did not bind."
    echo "  Usual causes: it crashed on startup, or BIND_ADDR/HTTP_PORT in .env disagree with"
    echo "  what docker-compose publishes."
else
    warn "Proxyma answered $LAST_CODE, expected 200."
fi
echo
$DOCKER compose logs --tail 120 proxyma
exit 1
