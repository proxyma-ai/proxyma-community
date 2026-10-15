# Writing a connector

A connector reads one kind of source - a service's projects, channels, mailboxes or files - into
Proxyma. Every source type is a plugin on one framework; nothing else in the product switches on the
type. The GitHub connector (`apps/app/backend/proxyma-connectors/src/main/java/ai/proxyma/connectors/github/`)
is the reference: copy its shape.

## The pieces

All in `ai.proxyma.core.connector.spi` (`proxyma-core-connector`) unless named otherwise.

- **`ConnectorPlugin`** - one per source type, a Spring `@Component`. Holds the type's descriptor,
  opens a session for one source, and answers `identity(config)` (what makes two sources the same
  one), `displayName(config)`, `offered()` and `webUrl(config, serverUrl)`.
- **`ConnectorDescriptor`** - everything the rest of the product reads instead of switching on the
  type: name, noun, add-dialog title, category, mark, the service it reads through, its fields
  (`FieldSpec`), its capabilities (incremental, cursor, file-type filter, subfolders, shareable,
  edition, live read, push), how often it syncs, its manual anchor.
- **`ConnectorSession`** - one source, one pass: `verifyAccess()`, `list(ListRequest)` (`FULL`, or
  `CHANGES` since the last complete pass, with the cursor that pass returned), `fetch(item)` (the item
  as Markdown), `close()`. Build listings with `Listings` - a failure is an answer, never an exception
  or an empty iterator.
- **`ConnectionPlugin`** - one per service a connection can name (`ConnectionProtocol`). Its
  `ProtocolDescriptor` says how it signs in (token, basic, key pair, service account, OAuth2 code or
  client credentials) and its address field; `test()` is the connection dialog's Test.
- **`OAuth2Spec`** - makes "Sign in with ..." work with no other code, including what differs at the
  token endpoint (a JSON body, extra parameters, fields a session needs later).
- **`ApiClient` / `ConnectorHttp`** - the one HTTP client: pinned to the connection's origin, no
  credentials on a redirect to another host, retries 429/502/503/504 honoring `Retry-After` and the
  service's `RateLimitHint`, slows down when a success says the budget is nearly spent, refreshes
  once on a 401, and fails with a sentence a person can act on. `Paginator` covers Link headers,
  cursors, offsets and page numbers.

A source's identity is its `identity(config)` through ONE connection: the same project through a
second account is a second source. A field that forms the identity cannot be edited later.

## Adding one

1. **One enum line each:** `ConnectorType` (`NAME("wirename")`), and `ConnectionProtocol` if it reads
   through a new service.
2. **A package `ai.proxyma.connectors.<name>`** in `apps/app/backend/proxyma-connectors`. It depends on
   the SPI and nothing else; component scan finds it.
   - `<Name>Api` - the `ApiClient`: base URL, auth header, accept and version headers, `RateLimitHint`.
   - `<Name>ConnectionPlugin` - the `ProtocolDescriptor` and a `test()` that makes one cheap
     authenticated call ("Signed in as ...").
   - `<Name>ConnectorPlugin` - the `ConnectorDescriptor`. Override `identity` when the default
     (required values joined) is wrong, and `webUrl` so the source's name links to its page.
   - `<Name>Session` - stable item ids that say what the item is (`issue:12`, `file:docs/a.md`); the
     item's address in its `url` extra and its author in `ItemMetadata.AUTHOR`; a gone item is
     `ItemGoneException`; a `CHANGES` pass lists only what changed since `request.since()` (overlap it
     by a minute) and returns a cursor wherever the service can resume.
3. **Text and translation.** Descriptor text is literal English, one string per call. Run
   `node tools/i18n/connector-labels.mjs`, then `I18N_STRICT=1 node libs/ui-kit/i18n/check-catalogs.mjs`,
   and add the Vietnamese it names to `apps/app/frontend/src/i18n/catalogs/vi.ts`. A new sentence the
   connector throws also needs `node tools/i18n/server-messages.mjs`.
4. **A mark.** `mark` names a `MarkId` in `libs/ui-kit/react/BrandMark.tsx`. Add a vendor's mark only
   from a source whose terms allow naming an integration, recording the source and terms in that
   file's header; otherwise use a generic glyph. Then `node libs/ui-kit/sync.mjs`.
5. **Tests** in `proxyma-connectors/src/test`, against `LoopbackApi` (the framework's fake HTTP API),
   as `GitHubConnectorTest` does: the descriptor and its identity, paging, a `CHANGES` pass asks with
   `since`, fetch renders, a 404 is a gone item, a 401 and a 429 say what to do, the connection test
   names the account.
6. **A fixture** in `tools/testing/fake-services.mjs` under `/<service>/...` - the endpoints the session
   reads, deterministic, with a word per item a search can find. Then add the source by hand on a jar
   started as the README's "Build from source" shows.
7. **The manual.** Set `docsAnchor`; say in the pull request what the source needs from its user, and
   the maintainers write the manual section.

Nothing else needs touching: the Sources page, the source and connection dialogs, the agent's source
tools, the registry, the scheduler, the license gate and the file-type filter all read the descriptor.
