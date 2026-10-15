package ai.proxyma.core.settings.demo;

import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.provider.ProviderInstance;
import ai.proxyma.core.settings.provider.ProviderInstanceRepository;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderInstanceService.ProviderInstanceInput;
import ai.proxyma.core.settings.provider.ProviderInstanceView;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.service.SettingsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Configures the public Enterprise sandbox's AI provider at startup, from the environment.
 *
 * <p><b>WHY THIS EXISTS AT ALL.</b> Provider settings are database-backed rather than Spring
 * properties, and a visitor cannot configure the provider: {@code SandboxGuardFilter} refuses
 * {@code /api/v1/providers} even to the sandbox's Admin. So without this a fresh sandbox could
 * render, and could not answer. Written for the public demo (the Home Server edition under the
 * {@code demo} profile, removed 2026-10-09), which refused every HTTP write and so had the same
 * problem.
 *
 * <p><b>THE ALTERNATIVE WAS LETTING VISITORS CONFIGURE IT, and that is not a smaller risk.</b>
 * The sandbox's accounts are shared, so a visitor who could edit the provider could repoint the
 * seeded instance's URL and send our API key to a server of their choosing, or paste a key of their
 * own and fund every visitor after them. Configuring it ourselves is the smaller exposure, and this
 * is the only server-side moment where that can happen.
 *
 * <p><b>IT GOES THROUGH {@link ProviderInstanceService}, NOT THE REPOSITORY.</b> That path
 * validates, encrypts the key with {@code CredentialEncryption}, and publishes the change event
 * that rebuilds every model role - so the seeded key is stored exactly like one typed into the UI.
 * Writing the entity directly would store a credential in clear text, which is SEC-31 reproduced
 * deliberately, and would leave the roles pointing at a provider nothing had rebuilt.
 *
 * <p><b>IDEMPOTENT, AND THE ENVIRONMENT WINS.</b> It updates on every boot rather than only
 * creating when absent, so the deployed configuration is the source of truth and the instance
 * cannot drift from it. That also means a lost data directory self-heals on the next restart
 * instead of silently reverting the sandbox to "no provider configured".
 *
 * <p><b>ABSENT CONFIGURATION IS NOT AN ERROR.</b> With no protocol and no key in the environment
 * this logs and returns, leaving the instance exactly as it is today. A sandbox that fails to start
 * because nobody set an optional variable would be a worse outcome than one that cannot answer,
 * and this class must never be the reason the site is down.
 *
 * <p>Nothing here makes the key readable: {@code DemoSecretMasker} already takes credentials out of
 * every settings response, and {@link ProviderInstanceView} only ever exposes a masked form.
 */
@Component
// The public Enterprise sandbox only. There the environment is the fallback since 2026-10-09 - the
// golden copy carries a provider the operator configured on the page, and
// tools/ci/sandbox-golden.sh refuses PROXYMA_DEMO_PROVIDER_PROTOCOL beside one, because this would
// replace it. Nowhere else - see the test pinning exactly this profile.
@Profile("sandbox")
public class DemoProviderSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoProviderSeeder.class);

    /**
     * The name the seeded instance carries.
     *
     * <p>Stable, because {@code ProviderInstanceService.update} keys per-model rates on the
     * provider NAME - renaming would orphan them - and because this is what the lookup below uses
     * to decide between create and update. It is also what a visitor reading the Providers screen
     * sees, so it says what it is.
     */
    public static final String INSTANCE_NAME = "Demo";

    /** Where {@link #seed} runs among the ApplicationReadyEvent listeners: before any default-ordered one. */
    public static final int ORDER = org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100;

    static final String ENV_PROTOCOL = "PROXYMA_DEMO_PROVIDER_PROTOCOL";
    static final String ENV_API_KEY = "PROXYMA_DEMO_PROVIDER_API_KEY";
    static final String ENV_BASE_URL = "PROXYMA_DEMO_PROVIDER_BASE_URL";
    static final String ENV_CHAT_MODEL = "PROXYMA_DEMO_CHAT_MODEL";
    static final String ENV_EMBEDDING_MODEL = "PROXYMA_DEMO_EMBEDDING_MODEL";

    private final ProviderInstanceService providers;
    private final ProviderInstanceRepository repository;
    private final SettingsService settings;
    private final UnaryOperator<String> env;

    /**
     * THE {@code @Autowired} IS LOAD-BEARING AND ITS ABSENCE TOOK THE DEMO DOWN FOR A DAY.
     *
     * <p>Spring uses a lone constructor implicitly, so most classes here need no annotation. This
     * class has TWO - this one and the package-private test seam below - and with more than one
     * candidate and none annotated, Spring stops guessing and falls back to the NO-ARG
     * constructor, which does not exist. The result is not a wiring warning but a hard
     * {@code BeanInstantiationException: No default constructor found} at context refresh, so the
     * whole application fails to start.
     *
     * <p>It shipped in 0.6.0 and put demo.proxyma.ai (the public demo, removed 2026-10-09) into a
     * restart loop: up, five seconds of
     * context refresh, exit 1, eighteen seconds later again. Nothing caught it, and the reason is
     * worth more than the fix. The two tests beside this class both construct it by calling the
     * four-argument constructor directly, which is exactly what a test wanting a fake environment
     * should do - and it means neither of them ever asked Spring to build the bean. A green suite
     * and an application that cannot start are entirely compatible when the thing that is broken is
     * the wiring rather than the logic. {@code springCanInstantiateThisBean} exists for that.
     */
    @Autowired
    public DemoProviderSeeder(ProviderInstanceService providers,
                              ProviderInstanceRepository repository,
                              SettingsService settings) {
        this(providers, repository, settings, System::getenv);
    }

    /** The environment is injected so a test can drive this without setting real variables. */
    DemoProviderSeeder(ProviderInstanceService providers,
                       ProviderInstanceRepository repository,
                       SettingsService settings,
                       UnaryOperator<String> env) {
        this.providers = providers;
        this.repository = repository;
        this.settings = settings;
        this.env = env;
    }

    /**
     * On ApplicationReadyEvent rather than a constructor or {@code @PostConstruct}: this writes
     * through a transactional service and publishes an event that rebuilds model roles, so it must
     * not run while the context is still coming up. {@code @Transactional} would not apply to
     * {@code @PostConstruct} anyway - that annotation comes from a proxy and the callback runs on
     * the raw target.
     *
     * <p><b>THE BLOCKING JPA CALLS BELOW ARE CORRECT HERE, and must not be wrapped.</b> The
     * standing rule is that every call into the persistence layer goes through
     * {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}, because a repository call
     * inside a reactive chain runs on Netty's event loop and stalls every concurrent request. This
     * is not in a reactive chain: an {@code ApplicationReadyEvent} listener runs synchronously on
     * the startup thread, before this instance serves anything. Blocking it is the intent -
     * the provider must be configured before the first visitor arrives - and making it reactive
     * would hand startup back early and lose exactly that ordering.
     *
     * <p><b>ORDERED FIRST</b> among the ready listeners, since 2026-10-06. The seeder that registers
     * sources (SandboxScenarioSeeder) starts syncs, and a sync embeds - with the provider this
     * configures. Unordered, Spring runs same-event listeners in no promised order; the public
     * demo's content seeder (removed 2026-10-09) said in its javadoc that it ran after this one,
     * and nothing made it so.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(ORDER)
    public void seed() {
        try {
            seedOrExplain();
        } catch (RuntimeException e) {
            // NEVER FATAL. A misconfigured variable must not stop the sandbox from serving the
            // pages it can serve. The message names the class so the log says whose problem it is.
            log.error("[DemoProviderSeeder] could not configure the demo provider: {}",
                    e.getMessage());
        }
    }

    private void seedOrExplain() {
        String protocolRaw = value(ENV_PROTOCOL);
        String apiKey = value(ENV_API_KEY);

        if (protocolRaw == null) {
            log.info("[DemoProviderSeeder] {} is not set - leaving the provider configuration "
                    + "alone. The sandbox will render but cannot answer.", ENV_PROTOCOL);
            return;
        }

        ProviderProtocol protocol = parseProtocol(protocolRaw);
        String baseUrl = value(ENV_BASE_URL);
        String chatModel = value(ENV_CHAT_MODEL);
        String embeddingModel = value(ENV_EMBEDDING_MODEL);

        // A key is optional because OLLAMA needs none. Every other protocol does, and saying so
        // here is better than a provider that exists and fails on the first question.
        //
        // THE MESSAGE NAMES BOTH OUTCOMES, because this runs before upsert() has decided which
        // one applies and they are opposites. It used to say only "created without a key and
        // calls to it will fail", which is right on a first run and WRONG on every restart after
        // one: a null key means "leave it alone" on the update path - deliberately, so a deploy
        // that forgets the environment variable cannot blank a working provider, which is the
        // behaviour one of this class's tests exists to pin. Someone reading that line after a
        // restart would have gone looking for a broken provider that was working.
        if (apiKey == null && protocol != ProviderProtocol.OLLAMA) {
            log.warn("[DemoProviderSeeder] {} is set to {} but {} is not. If this is the first run "
                            + "the provider is created without a key and calls to it will fail; if "
                            + "it already exists, its stored key is left untouched.",
                    ENV_PROTOCOL, protocol, ENV_API_KEY);
        }

        UUID id = upsert(protocol, baseUrl, apiKey);

        // The ROLES are what actually make the sandbox answer. A provider instance nothing points
        // at configures nothing, which is the shape of failure this whole class exists to avoid, so
        // these are not optional extras.
        settings.saveAgentProvider(id.toString());
        settings.saveEmbeddingProvider(id.toString());
        if (chatModel != null) settings.saveAgentModel(chatModel);
        if (embeddingModel != null) settings.saveEmbeddingModel(embeddingModel);
        // AND REBUILD THEM. The typed savers above publish no change event, so the models are built
        // from these settings only if LlmProviderFactory's own startup build happens to run after
        // this listener. On the Home Server demo (removed 2026-10-09) it did; on the Server edition
        // (the Enterprise sandbox) it ran first, built nothing, and every chat failed with "No model
        // is configured for role CHAT" while the settings read back perfectly correct.
        settings.announceRoleChanged(ChatSettings.PREFIX);
        settings.announceRoleChanged(EmbeddingSettings.PREFIX);

        // The key is never logged, not even masked: a masked key still confirms its length and its
        // first characters to anyone who can read the journal.
        log.info("[DemoProviderSeeder] provider '{}' ({}) configured for chat and embedding; "
                        + "chat model {}, embedding model {}",
                INSTANCE_NAME, protocol,
                chatModel == null ? "unchanged" : chatModel,
                embeddingModel == null ? "unchanged" : embeddingModel);
    }

    /**
     * Creates the instance, or updates the one already carrying {@link #INSTANCE_NAME}.
     *
     * <p>A null key on update means "unchanged" to {@code ProviderInstanceInput}, which is the
     * behaviour wanted: restarting without the key in the environment must not silently wipe a
     * working provider, and {@code clearApiKey} is therefore never set from here.
     *
     * <p><b>THE BASE URL DOES NOT SHARE THAT BEHAVIOUR, and this method is what compensates.</b>
     * {@code ProviderInstanceService.apply} leaves a null key, a null header map and a null
     * rerank flag alone, but a null base URL it writes straight through as null - correct for the
     * settings UI, where emptying the field is how a person clears it, and wrong here. The base
     * URL secret is documented as OPTIONAL, so a deploy that omits it would have blanked the
     * address of a working provider on the next restart and left the sandbox unable to answer, with
     * a log line reporting success. Carrying the stored value forward makes the whole seeder mean
     * one thing: absent in the environment is "leave it as it is", for every field.
     *
     * <p>Found in review rather than in production. A test pins it, beside the one that pins the
     * same guarantee for the key.
     */
    private UUID upsert(ProviderProtocol protocol, String baseUrl, String apiKey) {
        Optional<ProviderInstance> found = repository.findByNameIgnoreCase(INSTANCE_NAME);
        String effectiveBaseUrl = baseUrl != null
                ? baseUrl
                : found.map(ProviderInstance::getBaseUrl).orElse(null);
        ProviderInstanceInput input = new ProviderInstanceInput(
                INSTANCE_NAME, protocol, effectiveBaseUrl, apiKey, false, null, null);
        Optional<UUID> existing = found.map(instance -> instance.getId());
        if (existing.isPresent()) {
            providers.update(existing.get(), input);
            return existing.get();
        }
        ProviderInstanceView created = providers.create(input);
        return created.id();
    }

    /**
     * A protocol name, matched case-insensitively.
     *
     * <p>Throws rather than defaulting. A typo silently falling back to some other protocol would
     * produce a sandbox that is configured, looks configured, and answers nothing - and the log
     * line above would claim success. Naming the valid values in the message is what makes the fix one
     * reading rather than a search.
     */
    private static ProviderProtocol parseProtocol(String raw) {
        try {
            return ProviderProtocol.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    ENV_PROTOCOL + "=" + raw + " is not a protocol. One of: "
                            + java.util.Arrays.toString(ProviderProtocol.values()));
        }
    }

    /** Trimmed, with blank treated as absent - an empty variable is how a shell passes "unset". */
    private String value(String name) {
        String raw = env.apply(name);
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
