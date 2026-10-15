-- Pseudo data for the SERVER edition (PostgreSQL - one database for everything).
--
-- The server edition is multi-user, so this file seeds what the desktop one cannot: accounts,
-- and connectors and conversations that belong to them. Everything else is deliberately the
-- same set of facts as the desktop seed, so a bug that only shows up in one edition shows up as
-- a difference rather than as two unrelated data sets.
--
-- Applied by run.bat server -Seed, after one throwaway boot has let ddl-auto create the schema.
-- Safe to re-run: every row is deleted by its fixed id first.
--
-- SIGN-IN: all three accounts share the password  proxyma-dev
-- The hash below is a real BCrypt digest of it at the default strength, which is what
-- UserService and AdminUserController both use (a plain BCryptPasswordEncoder, so no {bcrypt}
-- prefix). Development only - the point of a shared known password is that nobody has to be
-- told one, and that is exactly why nothing like it belongs on a real deployment.
--
-- Not seeded, for the same reasons as the desktop files: no provider instance or API key (a fake
-- one produces an app that looks configured and fails on the first message), no embeddings
-- (these documents are registry rows, not vectors - they are listed, not searchable), and no
-- settings rows (GLOBAL_SETTINGS holds serialized settings objects and a hand-written guess at
-- that shape breaks the Settings page rather than failing the INSERT).

DELETE FROM chat_messages WHERE conversation_id IN
    ('dev-conv-0001', 'dev-conv-0002', 'dev-conv-0003', 'dev-conv-0004');
DELETE FROM conversations WHERE id IN
    ('dev-conv-0001', 'dev-conv-0002', 'dev-conv-0003', 'dev-conv-0004');

DELETE FROM tracked_documents WHERE connector_id IN (
    CAST('de100001-0000-4000-8000-000000000001' AS uuid),
    CAST('de100002-0000-4000-8000-000000000002' AS uuid),
    CAST('de100003-0000-4000-8000-000000000003' AS uuid));
DELETE FROM registered_connectors WHERE id IN (
    CAST('de100001-0000-4000-8000-000000000001' AS uuid),
    CAST('de100002-0000-4000-8000-000000000002' AS uuid),
    CAST('de100003-0000-4000-8000-000000000003' AS uuid));

-- What a sign-in leaves behind, first: these four point at users, so re-seeding a database somebody
-- had signed in to failed on the DELETE below (refresh_tokens' foreign key, 2026-10-06).
DELETE FROM refresh_tokens WHERE user_id IN (
    CAST('de900001-0000-4000-8000-000000000001' AS uuid),
    CAST('de900002-0000-4000-8000-000000000002' AS uuid),
    CAST('de900003-0000-4000-8000-000000000003' AS uuid));
DELETE FROM user_identities WHERE user_id IN (
    CAST('de900001-0000-4000-8000-000000000001' AS uuid),
    CAST('de900002-0000-4000-8000-000000000002' AS uuid),
    CAST('de900003-0000-4000-8000-000000000003' AS uuid));
DELETE FROM mcp_access_tokens WHERE user_id IN (
    CAST('de900001-0000-4000-8000-000000000001' AS uuid),
    CAST('de900002-0000-4000-8000-000000000002' AS uuid),
    CAST('de900003-0000-4000-8000-000000000003' AS uuid));
DELETE FROM password_reset_tokens WHERE user_id IN (
    CAST('de900001-0000-4000-8000-000000000001' AS uuid),
    CAST('de900002-0000-4000-8000-000000000002' AS uuid),
    CAST('de900003-0000-4000-8000-000000000003' AS uuid));

DELETE FROM users WHERE id IN (
    CAST('de900001-0000-4000-8000-000000000001' AS uuid),
    CAST('de900002-0000-4000-8000-000000000002' AS uuid),
    CAST('de900003-0000-4000-8000-000000000003' AS uuid));

-- One of each role. AdminUserController.forbiddenIfCannotAct is strict - only a super admin acts
-- on an admin - so an admin acting on another admin is refused, and two ADMIN rows are what make
-- that testable at all. The VIEWER is here because per-user quotas and the read-only paths are the parts of the
-- server edition that never get exercised from a single privileged account.
INSERT INTO users
    (id, email, display_name, password_hash, role, last_login_at, last_seen_at, created_at,
     status, deletion_requested_at, storage_quota_bytes, used_storage_bytes,
     monthly_token_quota_usd, current_cycle_spend_usd, current_cycle_started_at,
     five_hour_quota_usd, weekly_quota_usd, legal_version_accepted, legal_accepted_at,
     product_improvement_opt_in)
VALUES
    (CAST('de900001-0000-4000-8000-000000000001' AS uuid), 'owner@proxyma.test', 'Dev Owner',
     '$2a$10$LYzLrtW5BUa5CJm0ubt1XuYzEcnOJntJYURA3xCJW14iFgUCVRu46', 'SUPER_ADMIN',
     CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '10 minutes',
     CURRENT_TIMESTAMP - INTERVAL '90 days', 'ACTIVE', NULL,
     NULL, 812934112, NULL, 4.18, CURRENT_TIMESTAMP - INTERVAL '12 days',
     NULL, NULL, '2026-01-01', CURRENT_TIMESTAMP - INTERVAL '90 days', TRUE),

    (CAST('de900002-0000-4000-8000-000000000002' AS uuid), 'admin@proxyma.test', 'Dev Admin',
     '$2a$10$LYzLrtW5BUa5CJm0ubt1XuYzEcnOJntJYURA3xCJW14iFgUCVRu46', 'ADMIN',
     CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day',
     CURRENT_TIMESTAMP - INTERVAL '45 days', 'ACTIVE', NULL,
     5368709120, 142880231, 25.0, 6.72, CURRENT_TIMESTAMP - INTERVAL '12 days',
     2.0, 10.0, '2026-01-01', CURRENT_TIMESTAMP - INTERVAL '45 days', FALSE),

    -- Quota deliberately almost spent, so the quota banner and the refusal path have a state to
    -- render without anyone having to burn through a real budget first.
    (CAST('de900003-0000-4000-8000-000000000003' AS uuid), 'viewer@proxyma.test', 'Dev Viewer',
     '$2a$10$LYzLrtW5BUa5CJm0ubt1XuYzEcnOJntJYURA3xCJW14iFgUCVRu46', 'VIEWER',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '5 days',
     CURRENT_TIMESTAMP - INTERVAL '20 days', 'ACTIVE', NULL,
     1073741824, 47110009, 5.0, 4.86, CURRENT_TIMESTAMP - INTERVAL '12 days',
     1.0, 3.0, NULL, NULL, FALSE);

-- UPLOADED_FILES, not LOCAL_FOLDER: the server edition owns the bytes a user uploaded, under
-- {storageRoot}/users/{userId}/uploads/, and they count against that user's storage quota.
-- LOCAL_FOLDER is the desktop's by-reference type and has no meaning here.
INSERT INTO registered_connectors
    (id, connector_type, name, user_id, config, status, last_sync_error,
     item_count, processed_count, failed_count, shared, all_items_fetched, deleting, priority,
     recursive, file_types, graph_enabled, indexed_with_embedding_model,
     indexed_with_embedding_provider, reindex_in_progress, reindex_shadow_collection_id,
     last_synced_at, last_full_sync_at, created_at)
VALUES
    (CAST('de100001-0000-4000-8000-000000000001' AS uuid), 'UPLOADED_FILES',
     'engineering-handbook', 'de900001-0000-4000-8000-000000000001',
     'users/de900001-0000-4000-8000-000000000001/uploads/engineering-handbook',
     'ACTIVE', NULL,
     4, 4, 0, TRUE, TRUE, FALSE, TRUE,
     TRUE, 'pdf,docx,md,txt', FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '2 days',
     CURRENT_TIMESTAMP - INTERVAL '21 days'),

    -- Owned by the admin and NOT shared, so the per-user scoping of the Sources list is visible:
    -- signed in as the viewer, this one must not appear.
    (CAST('de100002-0000-4000-8000-000000000002' AS uuid), 'UPLOADED_FILES',
     'customer-contracts', 'de900002-0000-4000-8000-000000000002',
     'users/de900002-0000-4000-8000-000000000002/uploads/customer-contracts',
     'ERROR',
     'Conversion failed for 1 of 2 documents - see the document''s own error',
     2, 1, 1, FALSE, TRUE, FALSE, FALSE,
     TRUE, 'pdf,docx,md,txt', FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '9 days',
     CURRENT_TIMESTAMP - INTERVAL '14 days'),

    (CAST('de100003-0000-4000-8000-000000000003' AS uuid), 'WEB_PAGE',
     'Proxyma Documentation', 'de900001-0000-4000-8000-000000000001',
     'seedUrl=https://proxyma.ai/docs/' || chr(10) || 'maxDepth=2' || chr(10) ||
     'maxPages=50' || chr(10) || 'sameDomainOnly=true' || chr(10) || 'renderJs=false',
     'ACTIVE', NULL,
     3, 3, 0, TRUE, TRUE, FALSE, FALSE,
     FALSE, NULL, FALSE, 'text-embedding-3-small', 'openai', FALSE, NULL,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '4 days',
     CURRENT_TIMESTAMP - INTERVAL '7 days');

INSERT INTO tracked_documents
    (id, connector_id, source_id, title, connector_type, status, source_last_modified,
     last_indexed_at, content_hash, total_chunks, current_chunks, content_size,
     source_size_bytes, content_type, source_url, failure_message, reindex_replace,
     content_purged, retry_count, next_retry_at, created_at)
VALUES
    (CAST('de200001-0000-4000-8000-000000000001' AS uuid),
     CAST('de100001-0000-4000-8000-000000000001' AS uuid),
     'engineering-handbook/onboarding.md', 'Engineering onboarding',
     'UPLOADED_FILES', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '30 days',
     CURRENT_TIMESTAMP - INTERVAL '3 hours', 'a1b2c3d4e5f60718', 4, 4, 1284,
     1284, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '21 days'),

    (CAST('de200002-0000-4000-8000-000000000002' AS uuid),
     CAST('de100001-0000-4000-8000-000000000001' AS uuid),
     'engineering-handbook/release-process.md', 'Release process',
     'UPLOADED_FILES', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '12 days',
     CURRENT_TIMESTAMP - INTERVAL '3 hours', 'b2c3d4e5f6071829', 3, 3, 1102,
     1102, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '21 days'),

    (CAST('de200003-0000-4000-8000-000000000003' AS uuid),
     CAST('de100001-0000-4000-8000-000000000001' AS uuid),
     'engineering-handbook/architecture.md', 'System architecture',
     'UPLOADED_FILES', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '45 days',
     CURRENT_TIMESTAMP - INTERVAL '3 hours', 'c3d4e5f607182930', 3, 3, 1215,
     1215, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '21 days'),

    (CAST('de200004-0000-4000-8000-000000000004' AS uuid),
     CAST('de100001-0000-4000-8000-000000000001' AS uuid),
     'engineering-handbook/incident-log.md', 'Incident log 2026',
     'UPLOADED_FILES', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '2 days',
     CURRENT_TIMESTAMP - INTERVAL '3 hours', 'd4e5f60718293041', 4, 4, 1330,
     1330, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '2 days'),

    (CAST('de200005-0000-4000-8000-000000000005' AS uuid),
     CAST('de100002-0000-4000-8000-000000000002' AS uuid),
     'customer-contracts/acme-msa.md', 'ACME master services agreement',
     'UPLOADED_FILES', 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '60 days',
     CURRENT_TIMESTAMP - INTERVAL '5 hours', 'e5f6071829304152', 3, 3, 976,
     976, 'text/markdown', NULL, NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '14 days'),

    -- Terminal FAILED with its retry budget spent (next_retry_at NULL), not a transient failure
    -- with a retry pending: a document still holding a retry is picked back up by the drain.
    --
    -- total_chunks, current_chunks and content_size are 0 rather than NULL. They map to PRIMITIVE
    -- fields on TrackedDocument (docs/architecture.html#data-model spells them `int` and `long`, against
    -- source_size_bytes' boxed `Long` two lines down, and that distinction is load-bearing). A
    -- NULL there inserts happily and then throws on the first READ -
    -- "Can not set long field ai.proxyma.core.registry.model.TrackedDocument.contentSize to null
    -- value" - so the seed looks fine and the documents list returns 500.
    (CAST('de200006-0000-4000-8000-000000000006' AS uuid),
     CAST('de100002-0000-4000-8000-000000000002' AS uuid),
     'customer-contracts/globex-sow-7.md', 'Globex statement of work 7',
     'UPLOADED_FILES', 'FAILED', CURRENT_TIMESTAMP - INTERVAL '3 days',
     NULL, NULL, 0, 0, 0, 941, 'text/markdown', NULL,
     'Conversion failed: the document is a scanned image and OCR is turned off in Settings',
     FALSE, FALSE, 3, NULL, CURRENT_TIMESTAMP - INTERVAL '3 days'),

    (CAST('de200007-0000-4000-8000-000000000007' AS uuid),
     CAST('de100003-0000-4000-8000-000000000003' AS uuid),
     'https://proxyma.ai/docs/getting-started/', 'Getting started', 'WEB_PAGE', 'COMPLETED',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '6 hours',
     'd4e5f60718293041', 5, 5, 14002, 14002, 'text/html',
     'https://proxyma.ai/docs/getting-started/', NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '7 days'),

    (CAST('de200008-0000-4000-8000-000000000008' AS uuid),
     CAST('de100003-0000-4000-8000-000000000003' AS uuid),
     'https://proxyma.ai/docs/home-server/', 'Home server', 'WEB_PAGE', 'COMPLETED',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '6 hours',
     'e5f6071829304152', 9, 9, 31775, 31775, 'text/html',
     'https://proxyma.ai/docs/home-server/', NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '7 days'),

    (CAST('de200009-0000-4000-8000-000000000009' AS uuid),
     CAST('de100003-0000-4000-8000-000000000003' AS uuid),
     'https://proxyma.ai/docs/connectors/', 'Connectors', 'WEB_PAGE', 'COMPLETED',
     CURRENT_TIMESTAMP - INTERVAL '5 days', CURRENT_TIMESTAMP - INTERVAL '6 hours',
     'f607182930415263', 7, 7, 22910, 22910, 'text/html',
     'https://proxyma.ai/docs/connectors/', NULL, FALSE, FALSE, 0, NULL,
     CURRENT_TIMESTAMP - INTERVAL '7 days');

-- --------------------------------------------------------------------------------------------
-- The same three properties that keep the desktop fixture stable apply here, for the same
-- reason - SyncOrchestratorService is shared by both editions and is running the whole time:
--
--   1. run.ps1 copies seed/sample-docs into {storageRoot}/users/{ownerId}/uploads/ before this
--      script runs, so a re-walk agrees with these rows instead of contradicting them.
--   2. last_synced_at is NOW on every connector, so isDueForSync() says no.
--   3. item_count = processed_count + failed_count, and no document is in a non-terminal state
--      or holding a retry - the "no backlog" condition the 2s drain checks.
--
-- UNVERIFIED, unlike the desktop fixture: there is no Docker and no local PostgreSQL on the
-- maintainer's machine, so this file has been reviewed against the entity definitions and
-- never executed. The column list, the types and the enum spellings come from
-- docs/architecture.html#data-model; what has NOT been checked against a running server is the exact source_id
-- convention an UPLOADED_FILES connector writes. If the first -Seed on a real database produces
-- documents the app then re-detects as new, that is the thing to look at first.
-- --------------------------------------------------------------------------------------------

-- Split across two accounts. In multi-user mode a conversation belongs to its owner and nobody
-- else can see it, so history scoping is only testable when more than one account has any.
INSERT INTO conversations
    (id, user_id, guest_session_id, anonymous_eval_id, title, created_at, updated_at,
     message_count, pinned, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
     context_tokens, estimated_cost_usd, enabled_skill_ids, unlocked_skill_names)
VALUES
    ('dev-conv-0001', 'de900001-0000-4000-8000-000000000001', NULL, NULL,
     'How does the release process work?',
     CURRENT_TIMESTAMP - INTERVAL '6 days', CURRENT_TIMESTAMP - INTERVAL '6 days',
     4, TRUE, 8420, 1310, 6100, 2200, 9730, 0.0412, NULL, NULL),

    ('dev-conv-0002', 'de900001-0000-4000-8000-000000000001', NULL, NULL,
     'Which documents mention the incident log?',
     CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '3 days',
     4, FALSE, 12905, 2044, 9800, 3100, 14949, 0.0871, NULL, NULL),

    ('dev-conv-0003', 'de900002-0000-4000-8000-000000000002', NULL, NULL,
     'Summarize the engineering onboarding guide',
     CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '20 hours',
     2, FALSE, 5210, 890, 0, 0, 6100, 0.0198, NULL, NULL),

    ('dev-conv-0004', 'de900003-0000-4000-8000-000000000003', NULL, NULL,
     'Draft a changelog entry',
     CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '2 hours',
     1, FALSE, 0, 0, 0, 0, 0, 0.0, NULL, NULL);

INSERT INTO chat_messages
    (conversation_id, role, content, tool_calls_json, tool_responses_json, metadata_json, created_at)
VALUES
    ('dev-conv-0001', 'USER',
     'How does the release process work here?', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '6 days'),
    ('dev-conv-0001', 'ASSISTANT',
     'Releases are cut from a `release/` branch taken off `dev`, then promoted to `main` by pull request. Merging that promotion PR is what deploys - nothing deploys from `dev`, which exists only to batch work.',
     NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '6 days'),
    ('dev-conv-0001', 'USER',
     'So what actually triggers the build?', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '6 days'),
    ('dev-conv-0001', 'ASSISTANT',
     'The git tag. Pushing `v0.2.6` starts the three release workflows; the finalize job is what flips the GitHub release out of draft, so a release that stays draft means finalize did not run.',
     NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '6 days'),

    ('dev-conv-0002', 'USER',
     'Which documents mention the incident log?', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('dev-conv-0002', 'ASSISTANT', NULL,
     '[{"id":"call_dev_0001","type":"function","name":"search","arguments":"{\"query\":\"incident log\"}"}]',
     NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('dev-conv-0002', 'TOOL', NULL, NULL,
     '[{"id":"call_dev_0001","name":"search","responseData":"1. Incident log 2026 (engineering-handbook/incident-log.md) - the log itself, 4 chunks indexed\n2. Release process (engineering-handbook/release-process.md) - references the incident log from its postmortem section"}]',
     NULL, CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('dev-conv-0002', 'ASSISTANT',
     'Two documents mention it. `incident-log.md` is the log itself - three incidents this year, newest first. `release-process.md` references it from its postmortem section, where every incident is required to get an entry.',
     NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '3 days'),

    ('dev-conv-0003', 'USER',
     'Summarize the engineering onboarding guide.', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('dev-conv-0003', 'ASSISTANT',
     'It covers three things: getting the toolchain onto a new machine (JDK 21, a Maven unzip, and the Node the frontend build provisions itself), the branching model, and who to ask about what. The toolchain section is the one that goes stale fastest - it is worth checking the paths before following it.',
     NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '20 hours'),

    ('dev-conv-0004', 'USER',
     'Draft a changelog entry for the guardrail work.', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '2 hours');
