package ai.proxyma.core.graph.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one extraction call returned, before any of it has been resolved against what is already
 * stored. Names here are still the model's own strings - they become entity ids only in
 * {@link EntityResolver}.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} throughout, and that is not laziness: the
 * model is asked for a fixed shape and will sometimes add a field anyway. Failing the parse over a
 * key nobody asked for would throw away an otherwise perfectly good extraction, and this runs once
 * per chunk across an entire corpus - it has to degrade, not refuse.
 */
public record ExtractedGraph(List<Entity> entities, List<Relation> relations) {

    public static ExtractedGraph empty() {
        return new ExtractedGraph(List.of(), List.of());
    }

    public boolean isEmpty() {
        return entities.isEmpty() && relations.isEmpty();
    }

    /**
     * Union of two passes, de-duplicated by normalised name and by triple.
     *
     * <p>Gleaning re-asks the model what it missed, and models re-state what they already said
     * roughly as often as they add to it. Without this the second pass would inflate relation
     * weights - which are supposed to count INDEPENDENT sources, so a repeat inside one chunk is
     * not corroboration, it is the same sentence read twice.
     */
    public ExtractedGraph merge(ExtractedGraph other) {
        Map<String, Entity> byName = new LinkedHashMap<>();
        for (Entity e : entities) byName.put(GraphNames.normalize(e.name()), e);
        for (Entity e : other.entities()) byName.putIfAbsent(GraphNames.normalize(e.name()), e);

        Map<String, Relation> byTriple = new LinkedHashMap<>();
        for (Relation r : relations) byTriple.put(r.key(), r);
        for (Relation r : other.relations()) byTriple.putIfAbsent(r.key(), r);

        return new ExtractedGraph(new ArrayList<>(byName.values()), new ArrayList<>(byTriple.values()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(String name, String type, String description, List<String> aliases) {

        public List<String> aliasesOrEmpty() {
            return aliases == null ? List.of() : aliases;
        }

        /**
         * The type as stored: lower case (GraphNames.type), GraphNames.UNTYPED when the passage did
         * not make it clear. Never a placeholder word - see KgEntity.type.
         */
        public String storedType() {
            return GraphNames.type(type);
        }
    }

    /**
     * One relation. The three instants are for source METADATA only (see KgRelation) - a model is
     * never asked for them and never sends them, so they are ignored in JSON both ways.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(String subject, String predicate, String object, String description,
                           @com.fasterxml.jackson.annotation.JsonIgnore java.time.Instant validFrom,
                           @com.fasterxml.jackson.annotation.JsonIgnore java.time.Instant validTo,
                           @com.fasterxml.jackson.annotation.JsonIgnore java.time.Instant occurredAt) {

        public Relation(String subject, String predicate, String object, String description) {
            this(subject, predicate, object, description, null, null, null);
        }

        String key() {
            return GraphNames.normalize(subject) + "\u0000"
                    + GraphNames.predicate(predicate) + "\u0000"
                    + GraphNames.normalize(object);
        }

        public boolean isUsable() {
            return GraphNames.isUsable(subject)
                    && GraphNames.isUsable(object)
                    && predicate != null && !predicate.isBlank()
                    // A self-edge asserts nothing and would surface in the neighbourhood of every
                    // query touching that entity.
                    && !GraphNames.normalize(subject).equals(GraphNames.normalize(object));
        }
    }
}
