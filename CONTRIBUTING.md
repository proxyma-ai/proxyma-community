# Contributing to Proxyma Community

Thank you for helping. Bug reports, fixes, connectors, translations and documentation are all
welcome.

## Before you start

- **Every contribution needs the Contributor License Agreement.** Read [CLA.md](CLA.md). When you
  open your first pull request, the CLA Assistant bot asks you to agree to it in a comment; a pull
  request is not reviewed until you have. You keep the copyright in your work. The agreement lets
  the project ship it under the AGPL and under the separate license of Proxyma Enterprise, which
  is built on this code - without it, the project could not accept your change.
- For anything bigger than a small fix, open an issue first and describe what you want to change.
  It saves you work if the change does not fit.
- Report security issues privately, never in an issue: see [SECURITY.md](SECURITY.md).

## How a pull request reaches a release

This repository is published from the maintainer's private source tree, one commit per release.
An accepted pull request is applied there with your authorship kept, and reaches this repository
in the next release's commit, which credits you as a co-author. The pull request is then closed
with a link to that release rather than merged.

## Building and testing

You need JDK 21, Maven 3.9, Python 3 and Node.js 22.18 or newer (the `node --test` files are
TypeScript, run with Node's own type stripping).

```bash
mvn -pl :proxyma-desktop -am package -DskipTests -Dskip.installer=true   # the jar
bash tools/oss/verify-community.sh .                                     # every check CI runs
```

`tools/oss/verify-community.sh` runs each module's tests, builds the jar with its web interface,
runs the frontend `node --test` files and checks the generated lists and translation catalogs. A
pull request should pass it. The functional suite in `tests/` boots the jar and drives it in a
browser and needs Docker: `mvn -f tests/pom.xml verify` after building the jar.

For frontend work, `cd apps/app/frontend && npm ci && npm run dev` serves the interface against a
running jar - started as the README's "Build from source" shows; the page asks for that jar's
access code once.

## Conventions

- **ASCII only** in source, comments and documentation (translation catalogs excepted), and
  **American English** everywhere.
- **TypeScript: named exports only**, never default exports.
- **A change in behavior comes with a test** that fails without it - a JUnit test in the module,
  or a `node --test` file for frontend logic kept in a plain `.ts` module.
- **Every string a person reads goes through `t()`** and has a Vietnamese entry in
  `apps/app/frontend/src/i18n/catalogs/vi.ts`; `I18N_STRICT=1 node libs/ui-kit/i18n/check-catalogs.mjs`
  checks it. A sentence the backend sends to the page is listed by
  `node tools/i18n/server-messages.mjs` - run it after changing one.
- **Never let an absolute server path reach the client**: run tool results and assistant text
  through `SensitivePaths.scrub()`.
- **The UI kit is the single source of components and tokens.** Change `libs/ui-kit` and run
  `node libs/ui-kit/sync.mjs`; never edit the generated copies.
- **No new dependency without a reason** in the pull request. The build fails on a dependency
  whose license is not on the allow-list in `apps/app/backend/pom.xml` (and for the web interface,
  in `apps/app/frontend/vite.config.ts`).
- A new connector: start with [CONNECTORS.md](CONNECTORS.md).

## Commit messages

Imperative and specific: what changed and why, in a sentence or two. One logical change per pull
request.

## Code of Conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
