package ai.proxyma.core.configtransfer;

import java.util.ArrayList;
import java.util.List;

/**
 * What an import did, or - from the preview endpoint - what it WOULD do.
 *
 * <p>One type for both, because the preview's only job is to be trustworthy: if it were assembled
 * by different code from the apply, the two would eventually disagree and the confirmation screen
 * would be describing something other than what the button does. The service runs the same walk
 * either way and only writes when told to.
 *
 * <p>Every entry says which section, which item, and what happened to it. "Skipped" is a first-class
 * outcome rather than silence - an import that quietly did four of seven things reads as success.
 */
public class ConfigImportReport {

    /** What happened, or would happen, to one item. */
    public enum Outcome {
        /** Did not exist on the target; will be created. */
        CREATED,
        /** Existed with a different value; will be overwritten. */
        UPDATED,
        /** Already identical - nothing to do. Reported so a re-import reads as a no-op rather than
         *  as having done nothing for an unexplained reason. */
        UNCHANGED,
        /** Deliberately not applied. `detail` always says why. */
        SKIPPED,
    }

    /**
     * {@code group} is the TransferGroups id the item belongs to - what the reader chose by.
     * {@code requiresRestart}: this item, once written, takes effect only after a restart - said
     * per item, and in the preview, so the reader knows before applying.
     */
    public record Entry(String section, String group, String item, Outcome outcome, String detail,
                        boolean requiresRestart) {}

    private final List<Entry> entries = new ArrayList<>();
    private boolean requiresRestart;
    private boolean secretsApplied;

    public void add(String section, String group, String item, Outcome outcome, String detail) {
        add(section, group, item, outcome, detail, false);
    }

    public void add(String section, String group, String item, Outcome outcome, String detail,
                    boolean requiresRestart) {
        entries.add(new Entry(section, group, item, outcome, detail, requiresRestart));
    }

    public void markRequiresRestart() {
        this.requiresRestart = true;
    }

    public void markSecretsApplied() {
        this.secretsApplied = true;
    }

    public List<Entry> getEntries() {
        return List.copyOf(entries);
    }

    /** True when at least one written field only takes effect after a restart - today only the
     *  "proxy" prefix, which is read once into a startup-built client (see
     *  SettingsService.saveAiProviderField). */
    public boolean isRequiresRestart() {
        return requiresRestart;
    }

    /** False when the file carried no sealed secrets - it was exported without a passphrase. A file
     *  that carries them and a missing or wrong passphrase refuse the whole import instead
     *  (SecretEnvelope.open), so this never reports a half-applied envelope. The UI says so rather
     *  than letting the reader believe a credential came across when it did not. */
    public boolean isSecretsApplied() {
        return secretsApplied;
    }

    public long count(Outcome outcome) {
        return entries.stream().filter(e -> e.outcome() == outcome).count();
    }
}
