# Proxyma Community

Semantic search and an AI agent over your own documents. Proxyma indexes the folders, wikis,
tickets, repositories and mailboxes you point it at into a hybrid keyword and vector index, and
answers questions about them with citations - in its own window, in a browser, over a REST API and
as an MCP server for other AI tools. It runs on your machine; nothing is uploaded unless you
configure a hosted AI provider.

Proxyma Community is free software under the GNU Affero General Public License v3
(`AGPL-3.0-only`, see [LICENSE](LICENSE) and [NOTICE](NOTICE)).

## Community and Enterprise

| | Community (this repository) | Enterprise |
|---|---|---|
| Who it is for | One person | A team or an organization |
| Editions | Desktop (Windows, Linux) and Home Server (Docker, Kubernetes) | Enterprise Server (Docker, Kubernetes) |
| Connectors | All but Microsoft 365 | All |
| Agent, MCP, guardrails, knowledge graph | Yes | Yes |
| Accounts, roles, SSO, per-user quotas, administration | - | Yes |
| Microsoft 365 sources, key accounts, Constellations (teams of saved agents) | - | Yes |
| License | AGPL-3.0-only, free | Commercial, with a 30-day trial |

Enterprise is not open source, and its own modules - accounts and sign-in, administration, key
accounts, Constellations, Microsoft 365, the license and trial client, the multi-user server - are not in this
repository. See
[proxyma.ai](https://proxyma.ai).

## Quick start

- **Desktop** - download the installer (Windows) or the `.deb` (Ubuntu, Debian) from
  [proxyma.ai](https://proxyma.ai) and start Proxyma from the menu.
- **Home Server** - one user, reached from a browser, behind an access code:

  ```bash
  curl -LO https://raw.githubusercontent.com/proxyma-ai/proxyma-community/main/apps/app/deploy-homeserver/compose.community.yaml
  docker compose -f compose.community.yaml up -d
  docker compose -f compose.community.yaml logs proxyma | grep -A1 -i "access code"
  ```

  Then open <http://127.0.0.1:4246> and enter the access code.
- **Kubernetes** - the `proxyma-community` Helm chart, attached to every release as
  `proxyma-community-<version>.tgz` and kept here in
  [apps/app/deploy-kubernetes](apps/app/deploy-kubernetes/charts/proxyma-community).

The manuals are at [proxyma.ai/docs](https://proxyma.ai/docs).

## Build from source

Needs JDK 21 and Maven 3.9; the build fetches its own Node.js for the web interface. Running the
frontend tests or `npm run dev` yourself needs Node.js 22.18 or newer.

```bash
mvn -pl :proxyma-desktop -am package -DskipTests -Dskip.installer=true
```

Then start the jar with an access code of your own (16 characters or more) - every API call needs
it - on a port and with folders of its own, so it cannot meet an installed Desktop (which uses
port 4246, `%LOCALAPPDATA%\proxyma` or `~/.local/share/proxyma`, and `~/.proxyma`):

```bash
DEV="$PWD/.proxyma-dev"
export PROXYMA_ACCESS_CODE=choose-a-long-access-code
export XDG_DATA_HOME="$DEV"                      # on Windows (Git Bash): export LOCALAPPDATA="$DEV"
java -Dserver.port=4299 -Dproxyma.skills-dir="$DEV/skills" -Dproxyma.workflows-dir="$DEV/workflows" \
     -Dproxyma.conversations-dir="$DEV/conversations" \
     -jar apps/app/backend/proxyma-desktop/target/proxyma-desktop-*-exec.jar
```

It is ready after about a minute, most of it spent waiting for a vector store it cannot start.
Open <http://127.0.0.1:4299/?access=choose-a-long-access-code>.

A jar started this way has no Python runtime, and so no vector store: the interface, settings,
providers and chat work, but sources cannot be indexed or searched and PDF and Office files are not
converted. That runtime comes with the parts an installed Desktop downloads on its first start
(copy the `runtime` folder from its data folder into `.proxyma-dev/proxyma/`) and with the Home
Server image.

To run every check this repository's CI runs - each module's tests, the jar with its web interface,
the frontend tests and the translation catalogs:

```bash
bash tools/oss/verify-community.sh .
```

## Repository layout

| Path | What it is |
|---|---|
| `apps/app/backend` | The application: Maven modules, Spring Boot WebFlux |
| `apps/app/frontend` | The web interface: React, Vite |
| `apps/app/desktop-shell` | The desktop window (Electron) |
| `apps/app/deploy-homeserver`, `apps/app/deploy-kubernetes` | Docker Compose and Helm |
| `libs/` | The UI kit and the wire DTOs |
| `tests/` | The functional suite: boots the jar and drives it over HTTP and in Chromium |
| `docs/ui-guideline.html` | The UI design guideline: tokens, type, motion and every kit component |
| `CONNECTORS.md` | How to write a connector |

## Sponsor

Proxyma Community is free and built by one developer. If it saves you time, you can sponsor it on
[GitHub Sponsors](https://github.com/sponsors/lesyhau) or buy me a coffee on [Ko-fi](https://ko-fi.com/lesyhau).

## Contributing

Contributions are welcome under the [Contributor License Agreement](CLA.md) - see
[CONTRIBUTING.md](CONTRIBUTING.md). Security issues: [SECURITY.md](SECURITY.md). Everyone taking
part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

"Proxyma" and the Proxyma logo are trademarks; see [TRADEMARKS.md](TRADEMARKS.md).
