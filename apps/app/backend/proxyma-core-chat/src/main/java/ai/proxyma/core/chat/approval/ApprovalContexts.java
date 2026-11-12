package ai.proxyma.core.chat.approval;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.search.domain.SearchContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Asks the {@link ApprovalContextProvider}s what a write waiting for a person is about - once per
 * request, for the approvals inbox (ApprovalInbox.park) and for the chat's own approval prompt
 * (ChatService). BOUNDED: the providers run on a worker thread of their own (BlockingWork) with the
 * conversation's user and id set there, and get {@link #TIMEOUT} between them; slow, throwing or
 * empty, the answer is "no context", and the request goes on exactly as it would have without one -
 * unless its tool requires fields ({@link #prepare}), when the request is refused rather than kept with a
 * card that lacks one.
 */
@Service
public class ApprovalContexts {

    private static final Logger log = LoggerFactory.getLogger(ApprovalContexts.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    public static final int SUBJECT_MAX = 200;

    private final ObjectProvider<ApprovalContextProvider> providers;
    private List<ApprovalContextProvider> fixed;
    private Executor executor = BlockingWork.executor();
    private Duration timeout = TIMEOUT;

    public ApprovalContexts(ObjectProvider<ApprovalContextProvider> providers) {
        this.providers = providers;
    }

    /** Tests only: these providers, and this limit. */
    public static ApprovalContexts of(List<ApprovalContextProvider> providers, Duration timeout) {
        ApprovalContexts c = new ApprovalContexts(null);
        c.fixed = List.copyOf(providers);
        c.timeout = timeout;
        return c;
    }

    /**
     * What this call is about, bounded (ApprovalContext.bounded), or empty. Never throws, and returns
     * within the limit whatever a provider does.
     */
    public Optional<ApprovalContext> resolve(String toolName, String argumentsJson, String conversationId, String userId) {
        return resolve(toolName, argumentsJson, conversationId, userId, timeout);
    }

    /**
     * The same, within {@code limit} rather than the request's own few seconds - for a lookup nobody waits
     * on (ApprovalInbox filling a context in later), which can afford a slow service.
     */
    public Optional<ApprovalContext> resolve(String toolName, String argumentsJson, String conversationId, String userId,
                                             Duration limit) {
        return lookUp(toolName, argumentsJson, conversationId, userId, limit).context();
    }

    // What one lookup came to: the context found, else why a provider could not make one (Unavailable), else
    // whether the limit ran out first.
    private record Outcome(Optional<ApprovalContext> context, ApprovalContextProvider.Unavailable unavailable,
                           boolean timedOut) {
        static final Outcome NONE = new Outcome(Optional.empty(), null, false);
    }

    private Outcome lookUp(String toolName, String argumentsJson, String conversationId, String userId, Duration limit) {
        List<ApprovalContextProvider> all;
        try {
            all = fixed != null ? fixed : providers.orderedStream().toList();
        } catch (RuntimeException e) {
            return Outcome.NONE;
        }
        if (all.isEmpty() || toolName == null) return Outcome.NONE;
        Map<String, Object> arguments = arguments(argumentsJson);
        CompletableFuture<Outcome> asked;
        try {
            asked = CompletableFuture.supplyAsync(() -> ask(all, toolName, arguments, conversationId, userId), executor);
        } catch (RuntimeException e) {
            return Outcome.NONE;
        }
        try {
            Outcome found = asked.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            return found == null ? Outcome.NONE : found;
        } catch (java.util.concurrent.TimeoutException e) {
            asked.cancel(true);
            log.info("[Approvals] no context for '{}': its lookup took longer than {}ms", toolName, limit.toMillis());
            return new Outcome(Optional.empty(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.NONE;
        } catch (Exception e) {
            return Outcome.NONE;
        }
    }

    // -- A card that must be whole (ApprovalContextProvider.requiredFields) -------------------------------

    /**
     * What making a write ready to be shown to a person came to: its {@code context} (null for a tool no provider
     * describes, or one whose card may be partial and whose lookup found nothing), or {@code refusal} - the sentence
     * the agent reads, nothing having been kept - with {@code reason}, set when the refusal is DEFINITE (what the
     * write is about does not exist or cannot be read), the short sentence a page shows on a request withdrawn for it.
     */
    public record Prepared(ApprovalContext context, String refusal, String reason) {

        public boolean refused() {
            return refusal != null;
        }

        /** Refused for a definite answer: retrying the same call will not help. */
        public boolean definite() {
            return refusal != null && reason != null;
        }
    }

    /** The reason a provider's refusal carries: the write must not be kept at all (ApprovalContextProvider.refusal). */
    public static final String NOT_KEPT = "NOT_KEPT";

    // What the agent reads for a card that could not be made whole, by why (written for the model, never shown).
    static final String TOO_SLOW = "Looking up what this write is about took too long, so a person could not be "
            + "shown it. Nothing was kept or changed; try again in a few minutes.";
    static final String NOT_FOUND = "What this write is about could not be read, so a person could not be shown it. "
            + "Nothing was kept or changed; check its arguments and try again.";

    /** The fields every card of this tool must carry (ApprovalContextProvider.requiredFields), of every provider. */
    public java.util.Set<String> requiredFields(String toolName) {
        if (toolName == null) return java.util.Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            for (ApprovalContextProvider p : all()) {
                java.util.Set<String> r = p.requiredFields(toolName);
                if (r != null) out.addAll(r);
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] could not ask which fields '{}' needs: {}", toolName, e.getMessage());
        }
        return out;
    }

    /**
     * The required fields this card lacks, in the order required: one it does not carry, and one LINK labeled only by
     * the id its own address carries - a video named "8QFVNhzw5GE" because its title could not be read (2026-11-05: the
     * Approvals page showed such cards), which names nothing a person can recognize. Empty for a whole card.
     */
    public static List<String> missing(java.util.Set<String> required, ApprovalContext context) {
        if (required == null || required.isEmpty()) return List.of();
        if (context == null || context.fields() == null) return List.copyOf(required);
        java.util.Set<String> carried = new java.util.HashSet<>();
        for (ApprovalContext.Field f : context.fields()) {
            if (f != null && f.key() != null && f.value() != null && !f.value().isBlank() && !labeledByItsId(f)) {
                carried.add(f.key());
            }
        }
        return required.stream().filter(k -> !carried.contains(k)).toList();
    }

    // A link whose words are only a value of its own address's query - "v2" on ".../watch?v=v2".
    private static boolean labeledByItsId(ApprovalContext.Field f) {
        if (!ApprovalContext.LINK.equals(f.kind()) || f.href() == null) return false;
        String value = f.value().strip();
        int q = f.href().indexOf('?');
        if (q < 0 || value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) return false;
        for (String pair : f.href().substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                if (java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8).equals(value)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Not an encoded value: not this one.
            }
        }
        return false;
    }

    /** Whether this card carries every field its tool requires (true for a tool that requires none). */
    public boolean whole(String toolName, ApprovalContext context) {
        return missing(requiredFields(toolName), context).isEmpty();
    }

    /** Whether a card of this tool must be whole before its write is kept for a person (requiredFields). */
    public boolean requiresWhole(String toolName) {
        return !requiredFields(toolName).isEmpty();
    }

    /**
     * Makes a write ready to be shown to a person, within this service's few seconds: asks whether it may be kept at
     * all ({@link #refusal}), then what it is about - and, for a tool with required fields, refuses it unless the card
     * carries every one ({@link #complete}). Never throws.
     */
    public Prepared prepare(String toolName, String argumentsJson, String conversationId, String userId) {
        Optional<String> refused = refusal(toolName, argumentsJson, conversationId, userId);
        // Definite: a provider refuses only what must never be kept (another channel's comment), so retrying the same
        // call cannot help (2026-11-11 review: it was retried every 15 minutes, a model call each time).
        if (refused.isPresent()) return new Prepared(null, refused.get(), NOT_KEPT);
        return complete(toolName, argumentsJson, conversationId, userId, timeout);
    }

    /**
     * What this call is about, within {@code limit}: for a tool with no required fields, what any lookup found (or a
     * null context); for one with some, the card only when it carries every one, else refused - with the provider's
     * sentence and, when its answer was definite, the reason (ApprovalContextProvider.Unavailable); with a sentence of
     * this service's when the lookup ran out of time, found nothing or left a field out, none of which is definite.
     * Never throws.
     */
    public Prepared complete(String toolName, String argumentsJson, String conversationId, String userId, Duration limit) {
        java.util.Set<String> required = requiredFields(toolName);
        Outcome found = lookUp(toolName, argumentsJson, conversationId, userId, limit);
        if (required.isEmpty()) return new Prepared(found.context().orElse(null), null, null);
        if (found.context().isPresent()) {
            List<String> missing = missing(required, found.context().get());
            if (missing.isEmpty()) return new Prepared(found.context().get(), null, null);
            log.info("[Approvals] the card of '{}' lacks {}: refused", toolName, missing);
            return new Prepared(null, "The details a person needs to decide this write could not all be read (missing: "
                    + String.join(", ", missing) + "). Nothing was kept or changed; try again later.", null);
        }
        if (found.unavailable() != null) {
            ApprovalContextProvider.Unavailable u = found.unavailable();
            String sentence = u.getMessage() == null || u.getMessage().isBlank() ? NOT_FOUND : u.getMessage().strip();
            return new Prepared(null, sentence, u.definite() ? u.reason().strip() : null);
        }
        return new Prepared(null, found.timedOut() ? TOO_SLOW : NOT_FOUND, null);
    }

    /**
     * Why this write must not be kept (ApprovalContextProvider.refusal) - the first provider that says - or empty.
     * BOUNDED like a context's lookup, on a worker thread with the conversation's user and id set; slow, throwing or
     * empty, the answer is "it may", and the tool checks again when it runs. Never throws.
     */
    public Optional<String> refusal(String toolName, String argumentsJson, String conversationId, String userId) {
        if (toolName == null) return Optional.empty();
        List<ApprovalContextProvider> refusers;
        try {
            refusers = all();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (refusers.isEmpty()) return Optional.empty();
        Map<String, Object> arguments = arguments(argumentsJson);
        CompletableFuture<Optional<String>> asked;
        try {
            asked = CompletableFuture.supplyAsync(() -> {
                SearchContext.setUserId(userId);
                SearchContext.setConversationId(conversationId);
                try {
                    for (ApprovalContextProvider p : refusers) {
                        try {
                            Optional<String> why = p.refusal(toolName, arguments, conversationId, userId);
                            if (why != null && why.isPresent() && !why.get().isBlank()) return Optional.of(why.get().strip());
                        } catch (RuntimeException e) {
                            log.info("[Approvals] could not ask {} whether '{}' may be kept: {}",
                                    p.getClass().getSimpleName(), toolName, e.getMessage());
                        }
                    }
                    return Optional.<String>empty();
                } finally {
                    SearchContext.clear();
                }
            }, executor);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            Optional<String> found = asked.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return found == null ? Optional.empty() : found;
        } catch (java.util.concurrent.TimeoutException e) {
            asked.cancel(true);
            log.info("[Approvals] whether '{}' may be kept took longer than {}ms: kept", toolName, timeout.toMillis());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The subject a reference names (ApprovalContextProvider.subjectNamed), bounded like subjectOf, or empty. */
    public Optional<String> subjectNamed(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        try {
            for (ApprovalContextProvider p : all()) {
                Optional<String> s = p.subjectNamed(reference.strip());
                if (s != null && s.isPresent() && !s.get().isBlank()) {
                    String key = s.get().strip();
                    return Optional.of(key.length() <= SUBJECT_MAX ? key : key.substring(0, SUBJECT_MAX));
                }
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] no subject named by a reference: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** Whether any provider describes calls of this tool (ApprovalContextProvider.describes). Never throws. */
    public boolean describes(String toolName) {
        if (toolName == null) return false;
        try {
            List<ApprovalContextProvider> all = fixed != null ? fixed : providers.orderedStream().toList();
            for (ApprovalContextProvider p : all) {
                if (p.describes(toolName)) return true;
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] could not ask who describes '{}': {}", toolName, e.getMessage());
        }
        return false;
    }

    /** Whether any provider checks waiting requests of this tool against the world (ApprovalContextProvider.settles). */
    public boolean settles(String toolName) {
        if (toolName == null) return false;
        try {
            for (ApprovalContextProvider p : all()) {
                if (p.settles(toolName)) return true;
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] could not ask who checks '{}': {}", toolName, e.getMessage());
        }
        return false;
    }

    /**
     * Of these waiting requests of one person, those done another way meanwhile (ApprovalContextProvider.settled):
     * request id to the reason. BOUNDED like a context's lookup - on a worker thread, within {@code limit} - and
     * never throws: slow or failing, the answer is "none", and every request stays as it was.
     */
    public Map<String, String> settled(String toolName, List<ApprovalContextProvider.Waiting> waiting, String userId,
                                       Duration limit) {
        if (toolName == null || waiting == null || waiting.isEmpty()) return Map.of();
        List<ApprovalContextProvider> checkers;
        try {
            checkers = all().stream().filter(p -> p.settles(toolName)).toList();
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (checkers.isEmpty()) return Map.of();
        CompletableFuture<Map<String, String>> asked;
        try {
            asked = CompletableFuture.supplyAsync(() -> {
                Map<String, String> out = new java.util.LinkedHashMap<>();
                try {
                    for (ApprovalContextProvider p : checkers) {
                        try {
                            Map<String, String> found = p.settled(toolName, waiting, userId);
                            if (found != null) found.forEach(out::putIfAbsent);
                        } catch (RuntimeException e) {
                            log.info("[Approvals] could not check the waiting '{}' with {}: {}", toolName,
                                    p.getClass().getSimpleName(), e.getMessage());
                        }
                    }
                } finally {
                    SearchContext.clear();
                }
                return out;
            }, executor);
        } catch (RuntimeException e) {
            return Map.of();
        }
        try {
            Map<String, String> found = asked.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            return found == null ? Map.of() : found;
        } catch (java.util.concurrent.TimeoutException e) {
            asked.cancel(true);
            log.info("[Approvals] the check of the waiting '{}' took longer than {}ms", toolName, limit.toMillis());
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** How long a write refused with the connectors' own limit sentence waits before it is tried again. */
    static final Duration LIMIT_WAIT = Duration.ofMinutes(30);
    /** The sentence a connector's client fails a rate-limited request with (ApiClient.failure): "X is limiting ...". */
    static final String LIMIT_SENTENCE = " is limiting how often it is asked.";

    /**
     * When a failed approved call may be made again (ApprovalContextProvider.heldUntil), else empty: the first
     * provider that answers, then, for any tool, a result whose error is the connectors' limit sentence - tried
     * again after {@link #LIMIT_WAIT}. Pure and cheap; never throws.
     */
    public Optional<java.time.Instant> heldUntil(String toolName, String result, java.time.Instant now) {
        if (toolName == null || result == null || now == null) return Optional.empty();
        try {
            for (ApprovalContextProvider p : all()) {
                Optional<java.time.Instant> at = p.heldUntil(toolName, result, now);
                if (at != null && at.isPresent()) return at;
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] could not ask whether '{}' waits for a limit: {}", toolName, e.getMessage());
        }
        return errorOf(result).filter(e -> e.contains(LIMIT_SENTENCE)).map(e -> now.plus(LIMIT_WAIT));
    }

    /** The "error" a call's JSON result states, or empty. */
    public static Optional<String> errorOf(String result) {
        if (result == null || result.isBlank()) return Optional.empty();
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(result);
            if (n == null || !n.isObject()) return Optional.empty();
            String e = n.path("error").asText("");
            return e.isBlank() ? Optional.empty() : Optional.of(e.strip());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<ApprovalContextProvider> all() {
        return fixed != null ? fixed : providers.orderedStream().toList();
    }

    /** What the call is about (ApprovalContextProvider.subjectOf), bounded to 200 characters, or empty. Never throws. */
    public Optional<String> subjectOf(String toolName, String argumentsJson) {
        if (toolName == null) return Optional.empty();
        try {
            List<ApprovalContextProvider> all = fixed != null ? fixed : providers.orderedStream().toList();
            Map<String, Object> arguments = arguments(argumentsJson);
            for (ApprovalContextProvider p : all) {
                Optional<String> s = p.subjectOf(toolName, arguments);
                if (s != null && s.isPresent() && !s.get().isBlank()) {
                    String key = s.get().strip();
                    return Optional.of(key.length() <= SUBJECT_MAX ? key : key.substring(0, SUBJECT_MAX));
                }
            }
        } catch (RuntimeException e) {
            log.info("[Approvals] no subject for '{}': {}", toolName, e.getMessage());
        }
        return Optional.empty();
    }

    // The first context a provider gives, bounded; else the first Unavailable one threw.
    private static Outcome ask(List<ApprovalContextProvider> all, String toolName, Map<String, Object> arguments,
                               String conversationId, String userId) {
        SearchContext.setUserId(userId);
        SearchContext.setConversationId(conversationId);
        ApprovalContextProvider.Unavailable unavailable = null;
        try {
            for (ApprovalContextProvider p : all) {
                try {
                    Optional<ApprovalContext> c = p.contextFor(toolName, arguments, conversationId, userId);
                    if (c != null && c.isPresent()) {
                        Optional<ApprovalContext> bounded = c.get().bounded();
                        if (bounded.isPresent()) return new Outcome(bounded, null, false);
                    }
                } catch (ApprovalContextProvider.Unavailable e) {
                    log.info("[Approvals] no whole context for '{}' from {}{}: {}", toolName, p.getClass().getSimpleName(),
                            e.definite() ? " (definite)" : "", e.getMessage());
                    if (unavailable == null) unavailable = e;
                } catch (RuntimeException e) {
                    log.info("[Approvals] no context for '{}' from {}: {}", toolName, p.getClass().getSimpleName(),
                            e.getMessage());
                }
            }
            return new Outcome(Optional.empty(), unavailable, false);
        } finally {
            SearchContext.clear();
        }
    }

    private static Map<String, Object> arguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
