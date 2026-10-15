package ai.proxyma.core.chat.domain;

// A user's live response to a ChatEvent.Type.TOOL_ENABLE_REQUEST pause in ChatService's ReAct
// loop (see ChatService.awaitEnableDecision) - fired when a write-capable tool call is blocked
// solely by the user's own missing personal consent (McpGuardService.isBlockedOnlyByUserConsent),
// as opposed to an org-wide/org-mode block, which never pauses and is never self-fixable here.
public enum EnableDecision {
    ENABLED,
    KEEP_DISABLED
}
