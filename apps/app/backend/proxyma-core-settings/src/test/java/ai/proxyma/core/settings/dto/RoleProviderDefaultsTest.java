package ai.proxyma.core.settings.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model role holds a provider INSTANCE id - a uuid - and these three shipped a default that is
 * not one.
 *
 * <p>Chat, embedding and knowledge-graph extraction all defaulted their provider to the
 * pre-instance protocol string {@code "ollama"}, left behind when roles stopped naming protocols
 * and started naming instances. Nothing can resolve it, so every fresh install began life with
 * three roles pointing at a provider that cannot exist. It was visible the whole time and read as
 * cosmetic: a desktop install logged "is not a configured provider" for each role on every start,
 * and the fix applied at the time was to downgrade that line from ERROR to WARN.
 *
 * <p>What it actually cost surfaced on 2026-09-06, the first time anyone configured the knowledge
 * graph on a live install. Extraction is guarded by {@code isAvailable()}, which needs a resolvable
 * model; the stale default meant the guard failed, and the guard returns early with no log line at
 * all. The operator saw a graph switch that turned on, a sync that ran, and an empty graph.
 *
 * <p>This test exists because the defect is invisible in every direction that normally catches
 * things: it compiles, it has no null to dereference, the string is a real product name, and the
 * only symptom is a feature quietly declining to run. The assertion is deliberately about the
 * SHAPE - "not a uuid" - rather than about the literal "ollama", because the next stale default
 * will be a different word.
 */
@DisplayName("A role's default provider must not be a value that cannot resolve")
class RoleProviderDefaultsTest {

    private static boolean isUuid(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            UUID.fromString(s.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * ALL SEVEN, because the first fix caught three of them.
     *
     * <p>Chat, embedding and graph were corrected first; a fresh install then still logged the
     * stale default for query expansion and reranking, which is how the other four were found. A
     * per-class test would have passed three times and missed that - so this one enumerates every
     * role settings class that carries a provider, and a new role with the old default fails here
     * the day it is added.
     */
    @Test
    @DisplayName("no role ships a provider default that cannot resolve - all seven")
    void everyRoleDefault() {
        assertThat(new ChatSettings().getProvider()).as("chat").isEmpty();
        assertThat(new EmbeddingSettings().getProvider()).as("embedding").isEmpty();
        assertThat(new GraphSettings().getProvider()).as("graph").isEmpty();
        assertThat(new QueryExpansionSettings().getProvider()).as("query expansion").isEmpty();
        assertThat(new RerankingSettings().getProvider()).as("reranking").isEmpty();
        assertThat(new OcrSettings().getProvider()).as("ocr").isEmpty();
        assertThat(new SubAgentSettings().getProvider()).as("sub-agent").isEmpty();
    }

    @Test
    @DisplayName("a blank default is not accidentally a uuid-shaped string")
    void blankIsNotAnId() {
        assertThat(isUuid(new ChatSettings().getProvider()))
                .as("a non-blank default would have to be a resolvable instance id")
                .isFalse();
    }

    @Test
    @DisplayName("extraction stays off by default - it spends money per chunk at ingest")
    void graphIsOffByDefault() {
        assertThat(new GraphSettings().isEnabled()).isFalse();
    }

    /**
     * The bound that made one document in four produce nothing on a local reasoning model, with no
     * way to raise it. A default is fine; a hardcoded constant was not.
     */
    @Test
    @DisplayName("the extraction call timeout is configurable, and defaults to something usable")
    void graphCallTimeoutIsConfigurable() {
        GraphSettings s = new GraphSettings();
        assertThat(s.getCallTimeoutSeconds()).isEqualTo(120);

        s.setCallTimeoutSeconds(300);
        assertThat(s.getCallTimeoutSeconds()).isEqualTo(300);
    }
}
