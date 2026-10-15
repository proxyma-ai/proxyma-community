-- Pseudo data for the DESKTOP edition's `chat` datasource (chat_history.mv.db).
--
-- Conversations and their messages. Desktop is single-user with no auth, so user_id is NULL on
-- every row here - that is what the edition actually writes, and a populated user_id would send
-- the history list looking for an owner that does not exist.
--
-- Applied by run.bat -Seed. Safe to re-run.
--
-- Settings are NOT seeded. GLOBAL_SETTINGS is a key/value table whose values are serialized
-- settings objects, so a hand-written row here is a guess at a shape the app owns, and a wrong
-- guess surfaces as an unreadable Settings page rather than as a failed INSERT. Configure a
-- provider through the UI once the app is up; it takes a minute and it is the path a user takes.

DELETE FROM chat_messages WHERE conversation_id IN
    ('dev-conv-0001', 'dev-conv-0002', 'dev-conv-0003', 'dev-conv-0004');
DELETE FROM conversations WHERE id IN
    ('dev-conv-0001', 'dev-conv-0002', 'dev-conv-0003', 'dev-conv-0004');

INSERT INTO conversations
    (id, user_id, guest_session_id, anonymous_eval_id, title, created_at, updated_at,
     message_count, pinned, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
     context_tokens, estimated_cost_usd, enabled_skill_ids, unlocked_skill_names)
VALUES
    -- Pinned, so the history list has something at the top of it.
    ('dev-conv-0001', NULL, NULL, NULL, 'How does the release process work?',
     DATEADD('DAY', -6, CURRENT_TIMESTAMP), DATEADD('DAY', -6, CURRENT_TIMESTAMP),
     4, TRUE, 8420, 1310, 6100, 2200, 9730, 0.0412, NULL, NULL),

    -- Carries a tool call and its response, which is the only way the tool-call rows in the
    -- thread get exercised without a configured provider.
    ('dev-conv-0002', NULL, NULL, NULL, 'Which documents mention the incident log?',
     DATEADD('DAY', -3, CURRENT_TIMESTAMP), DATEADD('DAY', -3, CURRENT_TIMESTAMP),
     4, FALSE, 12905, 2044, 9800, 3100, 14949, 0.0871, NULL, NULL),

    ('dev-conv-0003', NULL, NULL, NULL, 'Summarize the engineering onboarding guide',
     DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('HOUR', -20, CURRENT_TIMESTAMP),
     2, FALSE, 5210, 890, 0, 0, 6100, 0.0198, NULL, NULL),

    -- A single unanswered message: the shape a conversation has between the user pressing send
    -- and the first token arriving, and the one the history list renders least often.
    ('dev-conv-0004', NULL, NULL, NULL, 'Draft a changelog entry',
     DATEADD('HOUR', -2, CURRENT_TIMESTAMP), DATEADD('HOUR', -2, CURRENT_TIMESTAMP),
     1, FALSE, 0, 0, 0, 0, 0, 0.0, NULL, NULL);

-- id is an identity column; letting the database assign it keeps this script re-runnable without
-- fighting the sequence.
INSERT INTO chat_messages
    (conversation_id, role, content, tool_calls_json, tool_responses_json, metadata_json, created_at)
VALUES
    ('dev-conv-0001', 'USER',
     'How does the release process work here?', NULL, NULL, NULL,
     DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
    ('dev-conv-0001', 'ASSISTANT',
     'Releases are cut from a `release/` branch taken off `dev`, then promoted to `main` by pull request. Merging that promotion PR is what deploys - nothing deploys from `dev`, which exists only to batch work.',
     NULL, NULL, NULL, DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
    ('dev-conv-0001', 'USER',
     'So what actually triggers the build?', NULL, NULL, NULL,
     DATEADD('DAY', -6, CURRENT_TIMESTAMP)),
    ('dev-conv-0001', 'ASSISTANT',
     'The git tag. Pushing `v0.2.6` starts the three release workflows; the finalize job is what flips the GitHub release out of draft, so a release that stays draft means finalize did not run.',
     NULL, NULL, NULL, DATEADD('DAY', -6, CURRENT_TIMESTAMP)),

    ('dev-conv-0002', 'USER',
     'Which documents mention the incident log?', NULL, NULL, NULL,
     DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
    -- Spring AI's AssistantMessage.ToolCall is (id, type, name, arguments), with `arguments`
    -- itself a JSON string rather than an object. ChatService parses this leniently and falls
    -- back to an empty list on any failure, so a near-miss here degrades rather than breaks.
    ('dev-conv-0002', 'ASSISTANT', NULL,
     '[{"id":"call_dev_0001","type":"function","name":"search","arguments":"{\"query\":\"incident log\"}"}]',
     NULL, NULL, DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
    ('dev-conv-0002', 'TOOL', NULL, NULL,
     '[{"id":"call_dev_0001","name":"search","responseData":"1. Incident log 2026 (engineering-handbook/incident-log.md) - the log itself, 4 chunks indexed\n2. Release process (engineering-handbook/release-process.md) - references the incident log from its postmortem section"}]',
     NULL, DATEADD('DAY', -3, CURRENT_TIMESTAMP)),
    ('dev-conv-0002', 'ASSISTANT',
     'Two documents mention it. `incident-log.md` is the log itself - three incidents this year, newest first. `release-process.md` references it from its postmortem section, where every incident is required to get an entry.',
     NULL, NULL, NULL, DATEADD('DAY', -3, CURRENT_TIMESTAMP)),

    ('dev-conv-0003', 'USER',
     'Summarize the engineering onboarding guide.', NULL, NULL, NULL,
     DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
    ('dev-conv-0003', 'ASSISTANT',
     'It covers three things: getting the toolchain onto a new machine (JDK 21, a Maven unzip, and the Node the frontend build provisions itself), the branching model, and who to ask about what. The toolchain section is the one that goes stale fastest - it is worth checking the paths before following it.',
     NULL, NULL, NULL, DATEADD('HOUR', -20, CURRENT_TIMESTAMP)),

    ('dev-conv-0004', 'USER',
     'Draft a changelog entry for the guardrail work.', NULL, NULL, NULL,
     DATEADD('HOUR', -2, CURRENT_TIMESTAMP));
