package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.AgentProfile;

/**
 * The provider and model one turn runs on, and why. The chat role's pair, unless a workflow step
 * names its own (WorkflowService's {@code provider:}/{@code model:} frontmatter) or a saved agent
 * does (AgentProfile) - a workflow first, since a step picks its model for its own work.
 *
 * <p>A model without a provider means "the chat role's provider, this model", for both - see
 * WorkflowService.normalizeModelChoice. {@code own} is true whenever the pair is not the chat
 * role's, and such a pair is REFUSED when its provider is no longer configured, never quietly run
 * on the chat model: the override is usually chosen to spend less, and falling back would bill
 * its author for the opposite of what was set. {@code agent} says the agent's settings chose it,
 * so the refusal can say where to fix it.
 */
record TurnModel(String provider, String model, boolean own, boolean agent) {

    static TurnModel of(String chatProvider, String chatModel, String workflowProvider, String workflowModel,
                        AgentProfile profile) {
        if (workflowModel != null) {
            return new TurnModel(workflowProvider != null ? workflowProvider : chatProvider, workflowModel, true, false);
        }
        if (profile != null && profile.ownModel()) {
            return new TurnModel(profile.providerId() != null ? profile.providerId() : chatProvider,
                    profile.model(), true, true);
        }
        return new TurnModel(chatProvider, chatModel, false, false);
    }
}
