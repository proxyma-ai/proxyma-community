package ai.proxyma.core.chat.agents;

/** Something was left in a conversation's inbox (AgentInbox): its agent should look, now or at its next step. */
public record AgentInboxPostedEvent(String conversationId) {}
