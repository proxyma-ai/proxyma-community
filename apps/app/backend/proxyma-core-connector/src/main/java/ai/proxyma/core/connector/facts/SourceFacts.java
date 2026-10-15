package ai.proxyma.core.connector.facts;

import java.util.List;

/**
 * What a source's own metadata says about one item - who it is assigned to, what it is a child of,
 * which issue it links - as named things and the links between them. Exact, and free: read from
 * the header every connector renders at the top of an item (SourceFactsReader), never from a model.
 *
 * @param things every thing named, the item itself first
 * @param links  subject, predicate, object - each end one of {@code things} by name
 */
public record SourceFacts(List<Thing> things, List<Link> links) {

    public static SourceFacts empty() {
        return new SourceFacts(List.of(), List.of());
    }

    public boolean isEmpty() {
        return things.isEmpty();
    }

    /** One named thing: a person, an issue, a page, a component, a version... */
    public record Thing(String name, String type, String description) {}

    /**
     * One link, stated by the source's metadata, with WHEN where the metadata says: a state holds
     * from {@code validFrom} until {@code validTo} (null: still), an event happened at
     * {@code occurredAt}.
     */
    public record Link(String subject, String predicate, String object, java.time.Instant validFrom,
                       java.time.Instant validTo, java.time.Instant occurredAt) {

        public Link(String subject, String predicate, String object) {
            this(subject, predicate, object, null, null, null);
        }
    }
}
