package ai.proxyma.core.chat.service;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.settings.dto.SpaceFactSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.PlatformService;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

// The one astronomy fact shown under the chat hero, plus the source it is attributed to.
//
// Deployment-wide, not per-user: the fact says nothing about the reader, so one generation serves
// everybody and the whole feature costs one short LLM call per 30 minutes - and only while someone
// is actually loading the page (see SpaceFactRefreshJob, which is what calls refresh()).
//
// THE MODEL NO LONGER CHOOSES THE LINK, since 2026-09-26. It used to be asked for a fact AND its
// URL, and one installation's log showed what that costs: 59 of 80 refreshes rejected, nearly all
// the same topic (neutron stars) with a different invented science.nasa.gov path each time - so
// the fact on screen sat unchanged for days while the job worked. Now each refresh picks a TOPIC
// from TOPICS, which carries a page known to exist, and the model writes only the sentence.
// Variety is ours too: the last RECENT_TOPICS topics are skipped. The checks below still run on
// the chosen link, where they now guard against link rot rather than invention.
//
// THE SOURCE LINK WAS THE HARD PART, and it is why this class is longer than the prompt suggests.
// A model asked for a fact and a citation will produce a URL shaped exactly like a real one for a
// page that has never existed, and a hero that links confidently into a 404 is worse than a hero
// with no link at all. So a generated fact is stored only if BOTH hold:
//
//   1. its URL's host is on ALLOWED_HOSTS - an allowlist, not a blocklist, because the failure
//      being guarded against is an invented name, and no pattern distinguishes an invented host
//      from a real one;
//   2. the URL actually answers - fetched here, through the proxy-aware external client, before
//      anyone is shown it.
//
// A fact failing either check is discarded whole (not "kept without its link") and the previous
// one stays up.
//
// THERE IS NO FALLBACK FACT. Six hand-written seeds used to rotate here whenever generation was
// impossible - no chat model, no network, quota exhausted - so the hero always had a subtitle.
// They are gone deliberately: a seed is indistinguishable on screen from a generated fact, so a
// deployment with no working model showed a hero that looked alive while nothing behind it was.
// current() now returns null in exactly that case.
//
// THE PAGE HAS ONE DEFAULT, since 2026-10-15 (maintainer's request): on that null it shows a fixed
// sentence about Proxima Centauri, the star the product is named after (the app's
// spaceFactShown.ts) - one sentence that never changes, chosen over the reason the rotating seeds
// went. It lives in the page, where it is translated like every other string, not here, so this
// service still says honestly that it has generated nothing.
@Service
public class SpaceFactService {

    private static final Logger log = LoggerFactory.getLogger(SpaceFactService.class);

    // Hosts a source link may point at. Deliberately short and all long-lived institutional or
    // encyclopedic sites: the point is that the model cannot invent a plausible domain, so
    // widening this list is a real decision rather than a convenience.
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "science.nasa.gov", "www.nasa.gov", "nasa.gov", "jpl.nasa.gov",
            "www.esa.int", "esa.int", "hubblesite.org", "en.wikipedia.org");

    /** One thing to write about, and the page a reader can check the fact on. */
    record Topic(String subject, String url) {}

    // Every URL here answered 200 on 2026-09-26. Wikipedia rather than a mix of sites: its article
    // URLs are stable and predictable, and one encyclopedia covers every topic on the list.
    static final List<Topic> TOPICS = List.of(
            wiki("the Sun", "Sun"), wiki("the Moon", "Moon"), wiki("Mercury", "Mercury_(planet)"),
            wiki("Venus", "Venus"), wiki("Mars", "Mars"), wiki("Jupiter", "Jupiter"),
            wiki("Saturn", "Saturn"), wiki("Uranus", "Uranus"), wiki("Neptune", "Neptune"),
            wiki("Pluto", "Pluto"), wiki("Europa, the moon of Jupiter", "Europa_(moon)"),
            wiki("Titan, the moon of Saturn", "Titan_(moon)"), wiki("Enceladus", "Enceladus"),
            wiki("Io, the moon of Jupiter", "Io_(moon)"), wiki("Ganymede", "Ganymede_(moon)"),
            wiki("the asteroid belt", "Asteroid_belt"), wiki("the Kuiper belt", "Kuiper_belt"),
            wiki("the Oort cloud", "Oort_cloud"), wiki("comets", "Comet"), wiki("the Milky Way", "Milky_Way"),
            wiki("the Andromeda Galaxy", "Andromeda_Galaxy"), wiki("black holes", "Black_hole"),
            wiki("neutron stars", "Neutron_star"), wiki("pulsars", "Pulsar"), wiki("white dwarfs", "White_dwarf"),
            wiki("supernovae", "Supernova"), wiki("exoplanets", "Exoplanet"),
            wiki("Proxima Centauri", "Proxima_Centauri"), wiki("Betelgeuse", "Betelgeuse"),
            wiki("Sirius", "Sirius"), wiki("the cosmic microwave background", "Cosmic_microwave_background"),
            wiki("dark matter", "Dark_matter"), wiki("the Hubble Space Telescope", "Hubble_Space_Telescope"),
            wiki("the James Webb Space Telescope", "James_Webb_Space_Telescope"), wiki("Voyager 1", "Voyager_1"),
            wiki("the International Space Station", "International_Space_Station"), wiki("Apollo 11", "Apollo_11"),
            wiki("the Perseverance rover", "Perseverance_(rover)"), wiki("the rings of Saturn", "Rings_of_Saturn"),
            wiki("the Great Red Spot of Jupiter", "Great_Red_Spot"), wiki("Olympus Mons", "Olympus_Mons"),
            wiki("the Large Magellanic Cloud", "Large_Magellanic_Cloud"), wiki("the Orion Nebula", "Orion_Nebula"),
            wiki("gravitational waves", "Gravitational_wave"), wiki("the solar wind", "Solar_wind"),
            wiki("auroras", "Aurora"));

    // How many recent topics a refresh skips, so the hero does not come back to the same subject.
    static final int RECENT_TOPICS = 8;

    private static Topic wiki(String subject, String article) {
        return new Topic(subject, "https://en.wikipedia.org/wiki/" + article);
    }

    // A fact longer than this is rejected rather than truncated - it renders as one subtitle line
    // under the hero heading, and cutting a sentence in half mid-clause reads as a bug.
    private static final int MAX_FACT_CHARS = 300;
    private static final int MAX_SOURCE_TITLE_CHARS = 60;
    // Vietnamese runs longer than the English it translates; this still fits the one subtitle line.
    private static final int MAX_TRANSLATED_CHARS = 400;

    /** What the hero renders: one sentence, plus a short link title and a checked URL. */
    public record SpaceFact(String text, String sourceTitle, String sourceUrl, long generatedAt,
                            Map<String, String> translations) {
        /** The fact in each language the app speaks besides English, keyed by locale ("vi").
         *  Since 2026-10-01; empty when the model gave none, and the hero falls back to text. */
        public SpaceFact {
            translations = translations == null ? Map.of() : Map.copyOf(translations);
        }

        public SpaceFact(String text, String sourceTitle, String sourceUrl, long generatedAt) {
            this(text, sourceTitle, sourceUrl, generatedAt, Map.of());
        }
    }

    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final PlatformSpendService platformSpendService;
    private final GlobalQuotaGate globalQuotaGate;
    private final RestClient externalRestClient;

    // The last successfully generated-and-verified fact, mirroring what is in settings. Volatile
    // rather than synchronized: one writer (the scheduler's thread) and many readers (request
    // threads), and a reader seeing the previous fact for a few microseconds is not a defect.
    private volatile SpaceFact generated;

    // Set by every read of the hero fact, cleared by the refresh job. This is what makes an idle
    // deployment spend nothing: no page loads in a window means no LLM call for that window.
    private final AtomicBoolean requestedSinceRefresh = new AtomicBoolean(false);

    // URLs of the topics used most recently, newest first. Touched only by the refresh job's
    // single thread, so it needs no locking; lost on restart, which costs at most one repeat.
    private final Deque<String> recentTopics = new ArrayDeque<>();

    public SpaceFactService(LlmGateway llmGateway,
                            SettingsService settingsService,
                            PlatformSpendService platformSpendService,
                            GlobalQuotaGate globalQuotaGate,
                            @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder) {
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.platformSpendService = platformSpendService;
        this.globalQuotaGate = globalQuotaGate;
        this.externalRestClient = externalRestClientBuilder.build();
        this.generated = readStored();
    }

    /**
     * The fact to render right now, or NULL when none has ever been generated on this deployment -
     * no chat model, no network, or every attempt so far rejected for an unreachable source. The
     * page shows its Proxima Centauri default in that case; see this class's header for why there
     * is no seed to fall back to here.
     *
     * <p>Never blocks on the model: the hero must paint immediately, so a read is only ever a
     * memory access plus the flag below.
     */
    public SpaceFact current() {
        requestedSinceRefresh.set(true);
        return generated;
    }

    /** Whether a fact has ever been generated here - see {@link SpaceFactRefreshJob}, which asks
     *  so a deployment with an empty hero retries sooner than the half-hourly cadence. */
    boolean hasFact() {
        return generated != null;
    }

    /** True (and clears the flag) when someone has read the fact since the last refresh. */
    boolean consumeDemand() {
        return requestedSinceRefresh.getAndSet(false);
    }

    /**
     * Generates one fact, verifies its source link, and stores it. Every failure path leaves the
     * previous fact in place and logs at debug/warn - nothing here is worth failing a request or a
     * scheduler tick over, and an unconfigured or offline deployment hits these paths permanently
     * rather than exceptionally.
     */
    void refresh() {
        try {
            // SPACE_FACT rides the query-expansion model, and which model that resolves to is the
            // gateway's business now - this service used to make that choice here AND repeat it
            // inside recordSpend() to work out what to bill it against.
            if (!llmGateway.isAvailable(LlmRole.SPACE_FACT)) {
                log.debug("[SpaceFact] no chat model configured, keeping current fact");
                return;
            }

            SpaceFact previous = generated;
            Topic topic = pickTopic(previous);
            String system = """
                    You write one short, surprising, verifiable fact about the astronomy topic you \
                    are given, for the home screen of a work application. Plain, warm, \
                    non-technical English, no exclamation marks, no second person, no greeting, \
                    no question. Only state something the topic's Wikipedia article supports.

                    Reply with EXACTLY these two lines and nothing else - the second is the same \
                    fact in natural Vietnamese, for readers of the app in that language:

                    FACT: <one or two sentences, at most 250 characters>
                    FACT_VI: <the same fact in Vietnamese>""";

            StringBuilder user = new StringBuilder("Topic: ").append(topic.subject()).append('.');
            if (previous != null && previous.text() != null && !previous.text().isBlank()) {
                user.append("\n\nDo not repeat this one, which is currently on screen:\n")
                        .append(previous.text());
            }

            // Gated in its own right, like the memory digest and unlike query expansion or
            // reranking: those run inside a chat turn that was already checked at entry, whereas
            // this is a background call with no gated caller above it.
            globalQuotaGate.checkOrRefuse("refreshing the chat home fact");
            LlmGateway.LlmResult result = llmGateway.chat(LlmCaller.system(LlmRole.SPACE_FACT),
                    List.of(new SystemMessage(system), new UserMessage(user.toString())));
            recordSpend(result);

            SpaceFact candidate = parse(result.text(), topic);
            if (candidate == null) {
                log.warn("[SpaceFact] could not parse the model's reply, keeping current fact");
                return;
            }
            if (!hostAllowed(candidate.sourceUrl())) {
                log.warn("[SpaceFact] rejecting source outside the allowlist: {}", candidate.sourceUrl());
                return;
            }
            if (!reachable(candidate.sourceUrl())) {
                log.warn("[SpaceFact] rejecting unreachable source: {}", candidate.sourceUrl());
                return;
            }

            store(candidate);
            remember(topic);
            log.info("[SpaceFact] refreshed, source {}", candidate.sourceUrl());
        } catch (Exception e) {
            log.warn("[SpaceFact] refresh failed: {}", e.getMessage());
        }
    }

    // - generation helpers -

    // A random topic that is neither the one on screen nor one of the last RECENT_TOPICS.
    Topic pickTopic(SpaceFact previous) {
        String onScreen = previous != null ? previous.sourceUrl() : null;
        List<Topic> fresh = TOPICS.stream()
                .filter(t -> !t.url().equals(onScreen) && !recentTopics.contains(t.url()))
                .toList();
        List<Topic> pool = fresh.isEmpty() ? TOPICS : fresh;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    void remember(Topic topic) {
        recentTopics.remove(topic.url());
        recentTopics.addFirst(topic.url());
        while (recentTopics.size() > RECENT_TOPICS) recentTopics.removeLast();
    }

    // The model supplies only the FACT line; the link title and URL come from the topic, so there
    // is nothing left for it to invent. Tolerant of the usual embellishments (markdown bold on the
    // label, surrounding quotes, a stray blank line), but a reply with no fact line is discarded.
    static SpaceFact parse(String raw, Topic topic) {
        if (raw == null || raw.isBlank()) return null;
        String text = null;
        String vi = null;
        for (String line : raw.split("\\r?\\n")) {
            String cleaned = line.strip().replaceFirst("^#+\\s*", "").replace("*", "").strip();
            String upper = cleaned.toUpperCase(Locale.ROOT);
            if (upper.startsWith("FACT_VI:")) vi = unquote(cleaned.substring(8));
            else if (upper.startsWith("FACT:")) text = unquote(cleaned.substring(5));
        }
        if (text == null || text.isBlank() || text.length() > MAX_FACT_CHARS) return null;
        String sourceTitle = "Wikipedia - " + capitalize(topic.subject());
        if (sourceTitle.length() > MAX_SOURCE_TITLE_CHARS) sourceTitle = "Wikipedia";
        // A missing or overlong translation costs only the translation: the English fact stands.
        Map<String, String> translations = vi != null && !vi.isBlank() && vi.length() <= MAX_TRANSLATED_CHARS
                ? Map.of("vi", vi) : Map.of();
        return new SpaceFact(text, sourceTitle, topic.url(), System.currentTimeMillis(), translations);
    }

    private static String capitalize(String subject) {
        return subject.isEmpty() ? subject : Character.toUpperCase(subject.charAt(0)) + subject.substring(1);
    }

    private static String unquote(String value) {
        String v = value.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).strip();
        }
        return v;
    }

    static boolean hostAllowed(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    // HEAD first, GET as a second attempt: plenty of real pages answer 403 or 405 to a HEAD while
    // serving a GET perfectly, and treating those as "does not exist" would reject good sources.
    // Both go through the proxy-aware external client, so a deployment behind a corporate proxy
    // verifies through it rather than silently failing every link.
    private boolean reachable(String url) {
        return succeeds(HttpMethod.HEAD, url) || succeeds(HttpMethod.GET, url);
    }

    private boolean succeeds(HttpMethod method, String url) {
        try {
            Boolean ok = externalRestClient.method(method)
                    .uri(URI.create(url))
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.debug("[SpaceFact] {} {} failed: {}", method, url, e.getMessage());
            return false;
        }
    }

    // Bookkeeping never fails the refresh - the whole service is best-effort. Charged to the
    // PLATFORM rather than a user: nobody is waiting on this answer, and it is not about anybody.
    private void recordSpend(LlmGateway.LlmResult result) {
        try {
            TokenUsage usage = result.usage();
            if (usage == TokenUsage.EMPTY) return;
            // The binding comes back WITH the answer, so the dedicated-or-main choice is not made
            // twice. It used to be: this method re-read the query-expansion settings when the
            // dedicated model had been used and the chat settings otherwise, which is the same
            // decision the caller had already made, spelled out again a hundred lines away.
            platformSpendService.record(PlatformService.SPACE_FACT, usage, result.provider(), result.model());
        } catch (Exception e) {
            log.debug("[PlatformSpend] Could not record space-fact spend: {}", e.getMessage());
        }
    }
    // - storage -

    private void store(SpaceFact fact) {
        generated = fact;
        try {
            SpaceFactSettings s = new SpaceFactSettings();
            s.setFact(fact.text());
            s.setFactVi(fact.translations().get("vi"));
            s.setSourceTitle(fact.sourceTitle());
            s.setSourceUrl(fact.sourceUrl());
            s.setGeneratedAt(fact.generatedAt());
            settingsService.setSettings(SpaceFactSettings.PREFIX, s);
        } catch (Exception e) {
            // In memory is enough to keep serving; only the "survives a restart" half is lost.
            log.warn("[SpaceFact] could not persist the fact: {}", e.getMessage());
        }
    }

    private SpaceFact readStored() {
        try {
            SpaceFactSettings s = settingsService.getSettings(SpaceFactSettings.PREFIX, SpaceFactSettings.class);
            if (s == null || s.getFact() == null || s.getFact().isBlank()
                    || s.getSourceUrl() == null || s.getSourceUrl().isBlank()) {
                return null;
            }
            return new SpaceFact(s.getFact(), s.getSourceTitle(), s.getSourceUrl(), s.getGeneratedAt(),
                    s.getFactVi() == null || s.getFactVi().isBlank() ? Map.of() : Map.of("vi", s.getFactVi()));
        } catch (Exception e) {
            log.debug("[SpaceFact] no stored fact: {}", e.getMessage());
            return null;
        }
    }
}
