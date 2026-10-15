package ai.proxyma.core.chat.domain;

// A user's live response to a ChatEvent.Type.TOOL_APPROVAL_REQUEST pause in ChatService's ReAct
// loop (see ChatService.callTool). ACCEPT_ALL additionally suppresses further prompts for the
// rest of that conversation.
public enum ApprovalDecision {
    ACCEPT_ONCE,
    ACCEPT_ALL,
    DENY
}
