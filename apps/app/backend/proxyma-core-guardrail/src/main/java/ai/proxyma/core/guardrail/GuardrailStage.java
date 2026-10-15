package ai.proxyma.core.guardrail;

/**
 * Which side of the model a check is running on.
 *
 * <p>Not cosmetic: several rules only make sense in one direction. Prompt-injection phrasing is an
 * INPUT concern - a model that writes "ignore all previous instructions" in an answer is quoting,
 * not attacking - and an absolute server path is an OUTPUT concern, since a user typing one has
 * told us nothing we did not already let them type.
 */
public enum GuardrailStage {

    /** What the user sent, before it is persisted or reaches any provider. */
    INPUT,

    /** What the model produced, before it reaches the client or is persisted. */
    OUTPUT,

    /**
     * What a tool returned, before the agent loop hands it back to the model.
     *
     * <p>THE THIRD SIDE, and the one the other two cannot see. A search hit, a fetched page, a
     * Jira comment and an MCP tool's result all become model context without any person having
     * typed them and without the model having written them - so they pass INPUT and OUTPUT
     * untouched, in a product whose whole job is retrieval.
     *
     * <p>It is a genuinely different threat. INPUT defends the deployment against its own user;
     * this defends the user against a document. Nobody chose to send this text, which is why a
     * violation here is not the user's fault and the refusal never reaches them as one - the
     * agent is told the result was withheld and carries on.
     */
    RETRIEVAL
}
