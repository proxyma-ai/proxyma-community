-- Pseudo data for the DESKTOP edition's `local` datasource (local_folders.mv.db).
--
-- That datasource is the @Primary one and owns the registry: connectors and the documents
-- tracked under them, for EVERY connector type, not just the file-backed ones.
--
-- @@SAMPLE_DOCS@@ is substituted by run.ps1 with the directory it copied seed/sample-docs into,
-- so the folder connectors below point at files that ACTUALLY EXIST. That is not decoration -
-- see the note on the orchestrator at the bottom of this file.
--
-- Applied by run.bat -Seed, after one throwaway boot has let ddl-auto create the schema. Safe to
-- re-run: every row is deleted by its fixed id first.
--
-- Two things are deliberately NOT seeded, in this file or its sibling:
--
--   * No provider instance and no API key. A fake provider produces an app that looks configured
--     and fails on the first message, which is worse than an empty Settings page. Configure a
--     real provider in Settings once the app is up.
--   * No embeddings. These documents are rows in the registry, not vectors in ChromaDB - they
--     show up in the Sources UI and its counters, and they are NOT searchable. Seeding the
--     registry to claim otherwise would make search look broken.
--
-- LOCAL_FOLDER is desktop-only (server mode uses UPLOADED_FILES, whose bytes it owns), so this
-- file and server.sql cannot share a connector set.

-- Documents first: they reference the connectors by id.
DELETE FROM tracked_documents WHERE connector_id IN (
    CAST('de100001-0000-4000-8000-000000000001' AS UUID),
    CAST('de100002-0000-4000-8000-000000000002' AS UUID),
    CAST('de100003-0000-4000-8000-000000000003' AS UUID));

DELETE FROM registered_connectors WHERE id IN (
    CAST('de100001-0000-4000-8000-000000000001' AS UUID),
    CAST('de100002-0000-4000-8000-000000000002' AS UUID),
    CAST('de100003-0000-4000-8000-000000000003' AS UUID));

-- `config` is not JSON despite what it holds elsewhere in the tree: for a file-backed connector
-- it is the folder path verbatim, for JIRA/CONFLUENCE the base URL, and only WEB_PAGE uses the
-- newline-separated key=value form below (ConnectorService.parseKeyValueConfig).
--
-- last_synced_at is CURRENT_TIMESTAMP, and the counters satisfy
-- item_count = processed_count + failed_count, for the reasons at the bottom of this file.
INSERT INTO registered_connectors
    (id, connector_type, name, user_id, config, status, last_sync_error,
     item_count, processed_count, failed_count, shared, all_items_fetched, deleting, priority,
     recursive, file_types, graph_enabled, indexed_with_embedding_model,
     indexed_with_embedding_provider, reindex_in_progress, reindex_shadow_collection_id,
     last_synced_at, last_full_sync_at, created_at)
VALUES
    -- A healthy folder, fully synced. The ordinary case, and four real files on disk.
    (CAST('de100001-0000-4000-8000-000000000001' AS UUID), 'LOCAL_FOLDER', 'engineering-handbook',
     NULL, '@@SAMPLE_DOCS@@\engineering-handbook', 'ACTIVE', NULL,
     4, 4, 0, FALSE, TRUE, FALSE, TRUE,
     TRUE, 'pdf,docx,md,txt', FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, DATEADD('DAY', -2, CURRENT_TIMESTAMP),
     DATEADD('DAY', -21, CURRENT_TIMESTAMP)),

    -- A connector in ERROR with a message on it and one permanently failed document, so the
    -- failure states in the Sources UI have something to render. An empty error list is the one
    -- case that never gets looked at.
    (CAST('de100002-0000-4000-8000-000000000002' AS UUID), 'LOCAL_FOLDER', 'customer-contracts',
     NULL, '@@SAMPLE_DOCS@@\customer-contracts', 'ERROR',
     'Conversion failed for 1 of 2 documents - see the document''s own error',
     2, 1, 1, FALSE, TRUE, FALSE, FALSE,
     TRUE, 'pdf,docx,md,txt', FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, DATEADD('DAY', -9, CURRENT_TIMESTAMP),
     DATEADD('DAY', -14, CURRENT_TIMESTAMP)),

    -- A crawled website. Not file-backed, so it exercises a different half of the connector UI,
    -- and its config is the key=value form. Nothing on disk corresponds to it, which is fine -
    -- a web connector is only re-walked over the network, and not for another crawl interval.
    (CAST('de100003-0000-4000-8000-000000000003' AS UUID), 'WEB_PAGE', 'Proxyma Documentation',
     NULL,
     'seedUrl=https://proxyma.ai/docs/' || CHAR(10) || 'maxDepth=2' || CHAR(10) ||
     'maxPages=50' || CHAR(10) || 'sameDomainOnly=true' || CHAR(10) || 'renderJs=false',
     'ACTIVE', NULL,
     3, 3, 0, FALSE, TRUE, FALSE, FALSE,
     FALSE, NULL, FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, DATEADD('DAY', -4, CURRENT_TIMESTAMP),
     DATEADD('DAY', -7, CURRENT_TIMESTAMP));

-- source_id for a file-backed connector is the absolute path, which is what a re-walk matches
-- against - so these have to be the real ones.
INSERT INTO tracked_documents
    (id, connector_id, source_id, title, connector_type, status, source_last_modified,
     last_indexed_at, content_hash, total_chunks, current_chunks, content_size,
     source_size_bytes, content_type, source_url, failure_message, reindex_replace,
     content_purged, retry_count, next_retry_at, created_at)
VALUES
    (CAST('de200001-0000-4000-8000-000000000001' AS UUID),
     CAST('de100001-0000-4000-8000-000000000001' AS UUID),
     '@@SAMPLE_DOCS@@\engineering-handbook\onboarding.md', 'Engineering onboarding',
     'LOCAL_FOLDER', 'COMPLETED', DATEADD('DAY', -30, CURRENT_TIMESTAMP),
     DATEADD('HOUR', -3, CURRENT_TIMESTAMP), 'a1b2c3d4e5f60718', 4, 4, 1284,
     1284, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -21, CURRENT_TIMESTAMP)),

    (CAST('de200002-0000-4000-8000-000000000002' AS UUID),
     CAST('de100001-0000-4000-8000-000000000001' AS UUID),
     '@@SAMPLE_DOCS@@\engineering-handbook\release-process.md', 'Release process',
     'LOCAL_FOLDER', 'COMPLETED', DATEADD('DAY', -12, CURRENT_TIMESTAMP),
     DATEADD('HOUR', -3, CURRENT_TIMESTAMP), 'b2c3d4e5f6071829', 3, 3, 1102,
     1102, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -21, CURRENT_TIMESTAMP)),

    (CAST('de200003-0000-4000-8000-000000000003' AS UUID),
     CAST('de100001-0000-4000-8000-000000000001' AS UUID),
     '@@SAMPLE_DOCS@@\engineering-handbook\architecture.md', 'System architecture',
     'LOCAL_FOLDER', 'COMPLETED', DATEADD('DAY', -45, CURRENT_TIMESTAMP),
     DATEADD('HOUR', -3, CURRENT_TIMESTAMP), 'c3d4e5f607182930', 3, 3, 1215,
     1215, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -21, CURRENT_TIMESTAMP)),

    (CAST('de200004-0000-4000-8000-000000000004' AS UUID),
     CAST('de100001-0000-4000-8000-000000000001' AS UUID),
     '@@SAMPLE_DOCS@@\engineering-handbook\incident-log.md', 'Incident log 2026',
     'LOCAL_FOLDER', 'COMPLETED', DATEADD('DAY', -2, CURRENT_TIMESTAMP),
     DATEADD('HOUR', -3, CURRENT_TIMESTAMP), 'd4e5f60718293041', 4, 4, 1330,
     1330, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -21, CURRENT_TIMESTAMP)),

    (CAST('de200005-0000-4000-8000-000000000005' AS UUID),
     CAST('de100002-0000-4000-8000-000000000002' AS UUID),
     '@@SAMPLE_DOCS@@\customer-contracts\acme-msa.md', 'ACME master services agreement',
     'LOCAL_FOLDER', 'COMPLETED', DATEADD('DAY', -60, CURRENT_TIMESTAMP),
     DATEADD('HOUR', -5, CURRENT_TIMESTAMP), 'e5f6071829304152', 3, 3, 976,
     976, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -14, CURRENT_TIMESTAMP)),

    -- Terminal FAILED with its retry budget spent (next_retry_at NULL), not a transient failure
    -- with a retry pending: a document still holding a retry is picked back up by the drain.
    --
    -- total_chunks, current_chunks and content_size are 0 rather than NULL. They map to PRIMITIVE
    -- fields on TrackedDocument (docs/architecture.html#data-model spells them `int` and `long`, against
    -- source_size_bytes' boxed `Long` two lines down, and that distinction is load-bearing). A
    -- NULL there inserts happily and then throws on the first READ -
    -- "Can not set long field ai.proxyma.core.registry.model.TrackedDocument.contentSize to null
    -- value" - so the seed looks fine and the documents list returns 500.
    (CAST('de200006-0000-4000-8000-000000000006' AS UUID),
     CAST('de100002-0000-4000-8000-000000000002' AS UUID),
     '@@SAMPLE_DOCS@@\customer-contracts\globex-sow-7.md', 'Globex statement of work 7',
     'LOCAL_FOLDER', 'FAILED', DATEADD('DAY', -3, CURRENT_TIMESTAMP),
     NULL, NULL, 0, 0, 0, 941, 'text/markdown', NULL,
     'Conversion failed: the document is a scanned image and OCR is turned off in Settings',
     FALSE, FALSE, 3, NULL, DATEADD('DAY', -14, CURRENT_TIMESTAMP)),

    (CAST('de200007-0000-4000-8000-000000000007' AS UUID),
     CAST('de100003-0000-4000-8000-000000000003' AS UUID),
     'https://proxyma.ai/docs/getting-started/', 'Getting started', 'WEB_PAGE', 'COMPLETED',
     DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('HOUR', -6, CURRENT_TIMESTAMP),
     'f607182930415263', 5, 5, 14002, 14002, 'text/html',
     'https://proxyma.ai/docs/getting-started/', NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -7, CURRENT_TIMESTAMP)),

    (CAST('de200008-0000-4000-8000-000000000008' AS UUID),
     CAST('de100003-0000-4000-8000-000000000003' AS UUID),
     'https://proxyma.ai/docs/home-server/', 'Home server', 'WEB_PAGE', 'COMPLETED',
     DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('HOUR', -6, CURRENT_TIMESTAMP),
     '0718293041526374', 9, 9, 31775, 31775, 'text/html',
     'https://proxyma.ai/docs/home-server/', NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -7, CURRENT_TIMESTAMP)),

    (CAST('de200009-0000-4000-8000-000000000009' AS UUID),
     CAST('de100003-0000-4000-8000-000000000003' AS UUID),
     'https://proxyma.ai/docs/connectors/', 'Connectors', 'WEB_PAGE', 'COMPLETED',
     DATEADD('DAY', -5, CURRENT_TIMESTAMP), DATEADD('HOUR', -6, CURRENT_TIMESTAMP),
     '1829304152637485', 7, 7, 22910, 22910, 'text/html',
     'https://proxyma.ai/docs/connectors/', NULL, FALSE, FALSE, 0, NULL,
     DATEADD('DAY', -7, CURRENT_TIMESTAMP));

-- --------------------------------------------------------------------------------------------
-- Why this fixture is shaped the way it is, and why an earlier version of it kept dissolving.
--
-- SyncOrchestratorService is running the whole time. It re-walks every due connector on a 60s
-- tick and drains per-connector backlogs on a 2s one, so a seeded row is not a static row: it is
-- an input to a live pipeline that will happily correct it. The first version of this file
-- pointed at folders that did not exist and seeded documents mid-flight, and within seconds of
-- the app starting the orchestrator had re-walked the folders, rewritten the counters, and
-- marked the in-flight documents FAILED with "File does not exist" - which reads as a bug in the
-- seed script and is the product working exactly as designed.
--
-- Three properties keep this fixture stable, and all three are load-bearing:
--
--   1. The folders and files are REAL. run.ps1 copies seed/sample-docs into place first, so a
--      re-walk agrees with the fixture instead of contradicting it.
--   2. last_synced_at is NOW, so isDueForSync() says no. A file connector's interval is hourly,
--      which is longer than any session spent looking at the UI.
--   3. item_count = processed_count + failed_count on every connector, and no document is in a
--      non-terminal state (DETECTED/QUEUED/DOWNLOADING/CONVERTING/EMBEDDING) or holding a retry.
--      That is precisely the "no backlog" condition the 2s drain checks before doing anything.
--
-- After the sync interval elapses the orchestrator WILL re-walk these folders, and with no
-- embedding provider configured the documents will fail on embedding. That is honest behavior
-- rather than a defect in the fixture: the alternative is a seed that lies about what the
-- product does. Re-run with -Seed to put it back.
-- --------------------------------------------------------------------------------------------
