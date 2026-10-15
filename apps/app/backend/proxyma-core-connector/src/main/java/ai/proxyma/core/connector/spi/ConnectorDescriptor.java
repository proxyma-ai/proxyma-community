package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;

import java.time.Duration;
import java.util.List;

/**
 * Everything the rest of the application needs to know about one source type, in one place: the
 * Sources page's tabs, groups and marks, the add-source dialog, the registration checks, the
 * scheduler's cadence, the license gate, the agent's tools and the @-mention style are all read
 * from here (through {@link ConnectorCatalog}) rather than from a switch per caller.
 *
 * @param protocol            the service its sources read through - null for a type that needs no
 *                            connection (a folder, an upload, a website)
 * @param displayName         English, passed through t() on the page
 * @param description         one sentence for the add-source dialog and the agent's listSourceTypes
 * @param noun                one source, as the Connect menu offers it ("GitHub repository")
 * @param nounPlural          its sources, as the Sources page heads their group ("GitHub repositories")
 * @param addTitle            the add-source dialog's title, as a sentence ("Add a GitHub repository")
 * @param order               where the type sits in the Connect menu and the list - lower first
 * @param mark                the kit BrandMark id (or a lucide icon name) drawn for the type
 * @param fields              what is typed to add a source, in order - the source's configuration
 * @param defaultSyncInterval how often a pass runs, unless the setting
 *                            {@link #syncIntervalSetting()} overrides it
 * @param docsAnchor          the customer documentation section that explains the type
 * @param listed              false for a type kept only so old rows read (the legacy FILES)
 * @param syncSetting         the {@code prefix.field} setting that overrides the cadence, where it
 *                            is not {@code <wireName>.sync-interval-seconds}; null for that default
 * @param targetField         the field the agent's status listing names as what the source reads (a
 *                            website's start address, a share's path), null for nothing to say -
 *                            then the server it reads from is what is named
 *
 * <p>A source's config column is these fields as JSON for every type, but the file-backed ones -
 * see {@link StoredConfig}, its one reader.
 */
public record ConnectorDescriptor(ConnectorType type, ConnectionProtocol protocol, String displayName,
                                  String description, String noun, String nounPlural, String addTitle, int order,
                                  Category category, String mark, List<FieldSpec> fields,
                                  Capabilities capabilities, Duration defaultSyncInterval, String docsAnchor,
                                  boolean listed, String syncSetting, String targetField) {

    public ConnectorDescriptor {
        if (type == null) throw new IllegalArgumentException("A descriptor needs a type");
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (capabilities == null) capabilities = Capabilities.builder().build();
        if (category == null) category = Category.OTHER;
        if (defaultSyncInterval == null) defaultSyncInterval = Duration.ofHours(1);
        if (displayName == null) displayName = type.name();
        if (noun == null) noun = displayName;
        if (nounPlural == null) nounPlural = noun;
        if (addTitle == null) addTitle = "Add " + noun;
    }

    /**
     * What the source reads, from its stored config, as the agent's status listing names it - null
     * when there is nothing to say or the server is what to say (asked of the connection instead).
     */
    public String target(String storedConfig) {
        return targetField == null ? null : StoredConfig.fields(type, storedConfig).get(targetField);
    }

    public static Builder builder(ConnectorType type) {
        return new Builder(type);
    }

    /** The {@code prefix.field} of the setting that overrides {@link #defaultSyncInterval()}. */
    public String syncIntervalSetting() {
        if (syncSetting != null) return syncSetting;
        return type.wireName() + ".sync-interval-seconds";
    }

    /** The field keyed {@code key}, or null. */
    public FieldSpec field(String key) {
        for (FieldSpec f : fields) if (f.key().equals(key)) return f;
        return null;
    }

    public static final class Builder {
        private final ConnectorType type;
        private ConnectionProtocol protocol;
        private String displayName;
        private String description;
        private String noun;
        private String nounPlural;
        private String addTitle;
        private int order = 1000;
        private Category category = Category.OTHER;
        private String mark;
        private List<FieldSpec> fields = List.of();
        private Capabilities capabilities = Capabilities.builder().build();
        private Duration interval = Duration.ofHours(1);
        private String docsAnchor;
        private boolean listed = true;
        private String syncSetting;
        private String targetField;

        private Builder(ConnectorType type) { this.type = type; }

        public Builder protocol(ConnectionProtocol value) { this.protocol = value; return this; }
        public Builder displayName(String value) { this.displayName = value; return this; }
        public Builder description(String value) { this.description = value; return this; }
        public Builder noun(String singular, String plural) { this.noun = singular; this.nounPlural = plural; return this; }
        public Builder addTitle(String value) { this.addTitle = value; return this; }
        public Builder order(int value) { this.order = value; return this; }
        public Builder category(Category value) { this.category = value; return this; }
        public Builder mark(String value) { this.mark = value; return this; }
        public Builder fields(FieldSpec... value) { this.fields = List.of(value); return this; }
        public Builder fields(List<FieldSpec> value) { this.fields = value; return this; }
        public Builder capabilities(Capabilities value) { this.capabilities = value; return this; }
        public Builder syncEvery(Duration value) { this.interval = value; return this; }
        public Builder docsAnchor(String value) { this.docsAnchor = value; return this; }
        public Builder unlisted() { this.listed = false; return this; }
        /** The setting that overrides the cadence, where it is not {@code <wireName>.sync-interval-seconds}. */
        public Builder syncSetting(String value) { this.syncSetting = value; return this; }
        /** The field the agent's status listing names as what the source reads. */
        public Builder targetField(String value) { this.targetField = value; return this; }

        public ConnectorDescriptor build() {
            return new ConnectorDescriptor(type, protocol, displayName == null ? type.name() : displayName,
                    description, noun, nounPlural, addTitle, order, category, mark == null ? type.wireName() : mark, fields, capabilities, interval,
                    docsAnchor, listed, syncSetting, targetField);
        }
    }
}
