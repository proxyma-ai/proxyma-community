package ai.proxyma.core.settings.event;

/**
 * Published when "Send diagnostics to Proxyma" ({@code privacy.data-collection-allowed}) is saved.
 *
 * <p>Exists so turning the switch OFF can delete what was collected but not yet sent at the moment
 * it happens, rather than at the next scheduled flush - withdrawing consent should not leave ten
 * minutes in which the data it covered is still sitting on disk waiting to go.
 */
public record DataCollectionChangedEvent(boolean allowed) {}
