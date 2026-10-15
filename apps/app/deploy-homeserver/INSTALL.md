# Proxyma Home Server

The single-user edition of Proxyma for a machine with no screen, reachable from other machines. It
is the same application as the Windows installer and the Linux .deb: one user and no accounts. An
access code is the way in.

- If Proxyma will only be used on the machine it runs on, install the desktop edition instead.
- For accounts, roles or single sign-on, use Proxyma Enterprise Server:
  <https://proxyma.ai/docs/manual/#install-enterprise>.

---

## 1. Requirements

| Component | Requirement |
|---|---|
| Docker Engine | 24 or newer, with the `docker compose` plugin |
| Memory | 4 GB for the container |
| Disk | 20 GB plus whatever your indexed documents need |
| CPU | 2 cores minimum |

Everything else is inside the image. All data is stored in `./data` beside the compose file.

---

## 2. Install

1. Download and unpack the compose bundle:

   ```bash
   curl -LO https://github.com/proxyma-ai/proxyma-releases/releases/download/v<version>/proxyma-homeserver-<version>-compose.tar.gz
   tar -xzf proxyma-homeserver-<version>-compose.tar.gz
   cd proxyma-homeserver-<version>
   ```

2. Run the installer. The first run creates `.env` and stops.

   ```bash
   ./install.sh
   ```

3. Edit section 1 of `.env` (see section 3 below).
4. Run `./install.sh` again. Re-running it applies any later configuration change.
5. Open Proxyma in a browser and enter the access code `install.sh` prints. There is no account to
   create.

### Without the installer: compose.community.yaml

The bundle also carries `compose.community.yaml`, which needs no script. It keeps its data in a
named Docker volume instead of `./data`, and the port on `127.0.0.1`.

```bash
docker compose -f compose.community.yaml up -d
docker compose -f compose.community.yaml logs proxyma | grep -A1 -i "access code"
```

The `.env` settings in section 3 apply to it unchanged. It is a separate compose project from the
`install.sh` stack, with its own volume: pick one.

### On Kubernetes

Use the Helm chart `proxyma-community`, or the plain manifests rendered from it, rather than this
bundle. Both are assets of the same release as this bundle: `proxyma-community-<version>.tgz`
and `proxyma-community-<version>.yaml`, listed in its `SHA256SUMS.txt`. `helm install` takes the
file or its download address; the manual's "On Kubernetes" section
(https://proxyma.ai/docs/manual/#install-kubernetes) covers installing and values.

---

## 3. Who can reach it

**The access code is the only sign-in.** Anyone who can reach the port and has the code has every
indexed document, every conversation and your AI provider spend. The container makes a code on its
first start and prints it in its log at every start:

```bash
docker compose logs proxyma | grep -A1 "access code"
```

A browser asks for it once per session. To choose your own, set `PROXYMA_ACCESS_CODE` in `.env` (at
least 16 characters) and run `./install.sh`.

Two settings control who can reach the port at all, and both must agree:

- **`BIND_ADDR`** publishes the port: `127.0.0.1` (the default) on this machine only, `0.0.0.0` on
  your network.
- **`PROXYMA_ALLOWED_HOSTS`** lists the host names the application answers to. Empty (the default)
  means loopback only; any other host gets a 403.

```dotenv
PROXYMA_ALLOWED_HOSTS=homeserver.lan        # one name
PROXYMA_ALLOWED_HOSTS=homeserver.lan,192.168.1.50
PROXYMA_ALLOWED_HOSTS=*                     # anything, including from the internet
```

`localhost` and `127.0.0.1` always work. The list is not a firewall; it protects against DNS
rebinding. `*` disables that protection, so use it only behind a tunnel or authenticating proxy.
`install.sh` prints the resulting access on every run.

### Reaching it from outside your home

1. **A tunnel (preferred)** - Tailscale, WireGuard, or `ssh -L 4246:localhost:4246`. Proxyma stays
   loopback-only and `.env` does not change.
2. **A proxy on the public internet** - set `PROXYMA_ALLOWED_HOSTS` to your domain, and make the
   proxy require its own login as well. The access code alone is not an account system.

---

## 4. Settings that stay off when exposed

Both are unrestricted on loopback and off by default once the instance is reachable from elsewhere.
Neither is needed to use Proxyma from a browser.

| Setting | What it opens |
|---|---|
| `PROXYMA_EXPOSE_LOCAL_FILE_API` | Browsing any directory the container can read. Not used by this edition, which indexes uploaded documents. |
| `PROXYMA_EXPOSE_MCP` | The MCP endpoint: an AI client holding the instance's token (Settings > AI Assistants) gets the agent's tools, including those that write to Jira, Confluence and Bitbucket with your stored credentials. |

Enable either only on a network you fully control.

---

## 5. Adding documents

1. Open Proxyma and go to **Sources**.
2. Choose **Upload Files** and drag in files or a folder.

Uploaded files are stored in `./data/uploads` and indexed from there.

**Deleting a source keeps its uploaded files.** It leaves the index; the files stay in `./data/uploads` until you delete them.

---

## 6. Day-to-day

```bash
docker compose logs -f proxyma   # logs
docker compose ps                # status
docker compose down              # stop
./install.sh                     # apply a config change
```

### Backups

`./data` holds the whole installation: database, search index, vector store, conversations and
settings.

1. `docker compose down`
2. Copy `./data`.
3. `./install.sh` (or `docker compose up -d`).

### Upgrading

```bash
docker compose pull proxyma
./install.sh
```

To pin a version, set `PROXYMA_IMAGE=ghcr.io/proxyma-ai/proxyma-homeserver:<version>` in `.env`.

### Checking the image is ours

Every release image is signed. With [cosign](https://docs.sigstore.dev/cosign/system_config/installation/) installed:

```bash
cosign verify --key https://proxyma.ai/keys/cosign-release.pub ghcr.io/proxyma-ai/proxyma-homeserver:<version>
```

---

## 7. Configuration reference

| Variable | Meaning |
|---|---|
| `PROXYMA_ACCESS_CODE` | The access code; empty keeps the generated one (section 3). |
| `PROXYMA_ALLOWED_HOSTS` | Host names the application answers to; empty is loopback only (section 3). |
| `BIND_ADDR` | Interface the port is published on (default `127.0.0.1`). |
| `HTTP_PORT` | Host port (default `4246`). |
| `PROXYMA_EXPOSE_LOCAL_FILE_API` | Directory browsing when exposed; default `false` (section 4). |
| `PROXYMA_EXPOSE_MCP` | MCP endpoint when exposed; default `false` (section 4). |
| `HOST_UID`, `HOST_GID` | Owner of `./data`; written by `install.sh`. |
| `PROXYMA_IMAGE` | Image to run; unset tracks `latest`. |
| `PROXYMA_TELEMETRY_SERVER_URL` | Telemetry and feedback endpoint; empty disables it. |
| `PROXYMA_CREDENTIAL_KEY` | The key stored credentials are encrypted with, as base64 of 32 bytes (`openssl rand -base64 32`). Empty: made on the first start and kept with the data. Never change it once set; a different key already stored stops the server from starting. |

The outbound URL carries neither your documents nor your credentials. It carries conversation
content in two cases only: feedback somebody submits with the conversation attached, and an answer
rating, which sends the rated answer and its question anonymously when "Send diagnostics to
Proxyma" is on in Settings -> About -> Diagnostics. Set `PROXYMA_ASSUME_YES=1`
in the environment to run `install.sh` without prompts; `.env` must then be complete.

---

## 8. Troubleshooting

**Every request returns 403.** The host you typed is not in `PROXYMA_ALLOWED_HOSTS`. `homeserver`,
`homeserver.lan` and the IP address are different names.

**It answers locally but not from other machines.** `BIND_ADDR` is `127.0.0.1` (the default), or
the host firewall blocks the port. `docker compose ps` shows what is published.

**The page asks for an access code.** Expected. `install.sh` prints it, and so does
`docker compose logs proxyma | grep -A1 "access code"`.

**Search returns nothing and the log says `ChromaDB not reachable`.** The vector store failed to
start. Run `docker compose logs proxyma | grep -i chroma` and check `./data/.local/share/proxyma/logs/chromadb.log`.

**First start takes over a minute.** Expected: the vector store starts before the web port opens.
