# Security policy

## Reporting a vulnerability

Please report security issues **privately, by e-mail to contact@proxyma.ai** - not in a public
issue, a pull request or a discussion. Include:

- the version (Settings > About) and the edition (Desktop, Home Server),
- what an attacker can do, and the steps or a proof of concept that shows it,
- any suggested fix.

You will get an answer within five working days. Once the issue is confirmed, we agree on a
disclosure date with you, fix it in a release, and credit you in the release notes unless you
would rather not be named.

## Supported versions

Proxyma is released as 0.x versions. Security fixes go into the **latest release only**; there are
no backports. Desktop installations update themselves, and the Home Server image is updated with
`docker compose pull`.

| Version | Supported |
|---|---|
| The latest release | Yes |
| Anything older | No - update first |

## Scope

This repository is Proxyma Community. Vulnerabilities in Proxyma Enterprise can be reported to the
same address.

There is no bug bounty program. We are grateful for every report all the same.
