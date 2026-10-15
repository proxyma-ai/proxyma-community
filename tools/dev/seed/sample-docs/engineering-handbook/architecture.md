# System architecture

## Two editions, one codebase

The desktop edition is single-user: an embedded database, a bundled vector store, and no
accounts. The server edition is multi-user: one shared database, sign-in, and per-user quotas.

They are the same source. What differs is decided at build time by a profile, not at runtime by a
flag, so a desktop build cannot serve a server-only page even by accident - the code for it is
not in the bundle.

## The reactor

One aggregating build file over roughly two dozen modules, packaged by feature. Building one
application brings its dependencies from source, so there is no separate step to build the shared
libraries first.

Modules talk to each other through published events and injected service interfaces. A module
reaching directly into another module's storage is the thing this structure exists to prevent.

## The request path

Controllers are reactive. The storage layer is not - it is blocking, and every call into it is
explicitly moved onto a worker thread pool. A storage call left on the event loop stalls every
concurrent request, not just its own, which is why the rule is absolute rather than a preference.
