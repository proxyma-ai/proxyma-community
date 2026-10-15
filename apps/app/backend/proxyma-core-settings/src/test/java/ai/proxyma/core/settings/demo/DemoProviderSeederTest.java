package ai.proxyma.core.settings.demo;

import ai.proxyma.core.settings.provider.ProviderInstance;
import ai.proxyma.core.settings.provider.ProviderInstanceRepository;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderInstanceService.ProviderInstanceInput;
import ai.proxyma.core.settings.provider.ProviderInstanceView;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.service.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sandbox's provider seeder, pinned where getting it wrong is silent.
 *
 * <p>Three of these are security assertions rather than behaviour ones. The seeded value is an API
 * key on a PUBLIC instance, so what matters is not only that it arrives but that it arrives
 * through {@code ProviderInstanceService} - the path that encrypts it - and that a restart with a
 * key missing from the environment cannot quietly blank a working one.
 */
class DemoProviderSeederTest {

    private ProviderInstanceService providers;
    private ProviderInstanceRepository repository;
    private SettingsService settings;
    private Map<String, String> env;

    private static final UUID EXISTING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CREATED_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @BeforeEach
    void setUp() {
        providers = mock(ProviderInstanceService.class);
        repository = mock(ProviderInstanceRepository.class);
        settings = mock(SettingsService.class);
        env = new HashMap<>();
        when(repository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(providers.create(any())).thenReturn(view(CREATED_ID));
    }

    private DemoProviderSeeder seeder() {
        return new DemoProviderSeeder(providers, repository, settings, env::get);
    }

    private static ProviderInstanceView view(UUID id) {
        return new ProviderInstanceView(id, DemoProviderSeeder.INSTANCE_NAME,
                ProviderProtocol.ANTHROPIC, "Anthropic", null, null, true, Map.of(), false);
    }

    @Nested
    @DisplayName("when the environment says nothing")
    class Unconfigured {

        @Test
        @DisplayName("nothing is written, and startup is not failed")
        void doesNothing() {
            // The public demo shipped for months with no provider at all. That state must stay
            // reachable: a sandbox that refuses to boot because an optional variable is unset would
            // be a worse outcome than one that renders and cannot answer.
            assertDoesNotThrow(() -> seeder().seed());
            verifyNoInteractions(providers);
            verifyNoInteractions(settings);
        }

        @Test
        @DisplayName("a blank variable counts as unset, because that is how a shell passes one")
        void blankIsAbsent() {
            env.put(DemoProviderSeeder.ENV_PROTOCOL, "   ");
            seeder().seed();
            verifyNoInteractions(providers);
            verifyNoInteractions(settings);
        }
    }

    @Nested
    @DisplayName("when a provider is configured")
    class Configured {

        @BeforeEach
        void configure() {
            env.put(DemoProviderSeeder.ENV_PROTOCOL, "ANTHROPIC");
            env.put(DemoProviderSeeder.ENV_API_KEY, "sk-demo-key");
            env.put(DemoProviderSeeder.ENV_CHAT_MODEL, "claude-haiku-4-5-20251001");
            env.put(DemoProviderSeeder.ENV_EMBEDDING_MODEL, "text-embedding-3-small");
        }

        @Test
        @DisplayName("it is created, and BOTH roles are pointed at it")
        void createsAndWiresRoles() {
            seeder().seed();

            ArgumentCaptor<ProviderInstanceInput> input =
                    ArgumentCaptor.forClass(ProviderInstanceInput.class);
            verify(providers).create(input.capture());
            assertEquals(ProviderProtocol.ANTHROPIC, input.getValue().protocol());
            assertEquals("sk-demo-key", input.getValue().apiKey());

            // A provider instance nothing points at configures nothing. This is the assertion that
            // would have caught a seeder which created a row and left the sandbox unable to answer.
            verify(settings).saveAgentProvider(CREATED_ID.toString());
            verify(settings).saveEmbeddingProvider(CREATED_ID.toString());
            verify(settings).saveAgentModel("claude-haiku-4-5-20251001");
            verify(settings).saveEmbeddingModel("text-embedding-3-small");
            // Without these the models are never rebuilt on the Server edition - see the seeder.
            verify(settings).announceRoleChanged("chat");
            verify(settings).announceRoleChanged("embedding");
        }

        @Test
        @DisplayName("THE KEY NEVER GOES NEAR THE REPOSITORY - only the encrypting service")
        void keyGoesThroughTheEncryptingPath() {
            seeder().seed();
            // ProviderInstanceService.apply() runs CredentialEncryption over the key; the
            // repository does not. Saving the entity directly would put an API key in clear text
            // in H2 on a public host, which is SEC-31 reproduced on purpose. Pinned so a later
            // "simplification" to a direct save fails here rather than in an incident.
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an existing instance is UPDATED, not duplicated")
        void updatesExisting() {
            ProviderInstance existing = new ProviderInstance();
            existing.setId(EXISTING_ID);
            existing.setName(DemoProviderSeeder.INSTANCE_NAME);
            when(repository.findByNameIgnoreCase(DemoProviderSeeder.INSTANCE_NAME))
                    .thenReturn(Optional.of(existing));

            seeder().seed();

            verify(providers).update(eq(EXISTING_ID), any());
            verify(providers, never()).create(any());
            // The roles are re-pointed on every boot, so a hand-edited settings row cannot leave
            // the sandbox pointing somewhere else than the instance this class maintains.
            verify(settings).saveAgentProvider(EXISTING_ID.toString());
        }

        @Test
        @DisplayName("restarting without the key in the environment does not wipe a working one")
        void missingKeyLeavesTheStoredOneAlone() {
            // null apiKey means "unchanged" to ProviderInstanceInput, and clearApiKey stays false.
            // The opposite behaviour would take the sandbox down on any deploy that forgot to pass
            // the secret - silently, since the provider would still exist.
            env.remove(DemoProviderSeeder.ENV_API_KEY);
            ProviderInstance existing = new ProviderInstance();
            existing.setId(EXISTING_ID);
            existing.setName(DemoProviderSeeder.INSTANCE_NAME);
            when(repository.findByNameIgnoreCase(DemoProviderSeeder.INSTANCE_NAME))
                    .thenReturn(Optional.of(existing));

            seeder().seed();

            ArgumentCaptor<ProviderInstanceInput> input =
                    ArgumentCaptor.forClass(ProviderInstanceInput.class);
            verify(providers).update(eq(EXISTING_ID), input.capture());
            assertNull(input.getValue().apiKey(), "a missing key must mean unchanged");
            assertEquals(false, input.getValue().clearApiKey(),
                    "the seeder must never clear a stored credential");
        }

        @Test
        @DisplayName("restarting without the base URL does not blank a working one")
        void missingBaseUrlLeavesTheStoredOneAlone() {
            // THE BASE URL DOES NOT GET "unchanged" FOR FREE, unlike the key above, and that
            // asymmetry is the whole reason this test exists. ProviderInstanceService.apply()
            // leaves a null key, a null header map and a null rerank flag alone, but writes a null
            // base URL straight through as null - correct for the settings UI, where emptying the
            // field is how a person clears it. The base URL secret is documented as OPTIONAL, so
            // without the seeder carrying the stored value forward, any deploy that omitted it
            // would blank the address of a working provider on the next restart and leave the
            // sandbox unable to answer - while logging success. Found in review, not in production.
            env.remove(DemoProviderSeeder.ENV_BASE_URL);
            ProviderInstance existing = new ProviderInstance();
            existing.setId(EXISTING_ID);
            existing.setName(DemoProviderSeeder.INSTANCE_NAME);
            existing.setBaseUrl("https://in-use.example.com/v1");
            when(repository.findByNameIgnoreCase(DemoProviderSeeder.INSTANCE_NAME))
                    .thenReturn(Optional.of(existing));

            seeder().seed();

            ArgumentCaptor<ProviderInstanceInput> input =
                    ArgumentCaptor.forClass(ProviderInstanceInput.class);
            verify(providers).update(eq(EXISTING_ID), input.capture());
            assertEquals("https://in-use.example.com/v1", input.getValue().baseUrl(),
                    "a missing base URL must carry the stored one forward, not null it out");
        }

        @Test
        @DisplayName("a base URL in the environment still wins over the stored one")
        void suppliedBaseUrlWins() {
            // The other half of the rule: the environment is the source of truth when it speaks.
            // Without this, "leave it alone" would quietly become "can never be changed".
            env.put(DemoProviderSeeder.ENV_BASE_URL, "https://new.example.com/v1");
            ProviderInstance existing = new ProviderInstance();
            existing.setId(EXISTING_ID);
            existing.setName(DemoProviderSeeder.INSTANCE_NAME);
            existing.setBaseUrl("https://old.example.com/v1");
            when(repository.findByNameIgnoreCase(DemoProviderSeeder.INSTANCE_NAME))
                    .thenReturn(Optional.of(existing));

            seeder().seed();

            ArgumentCaptor<ProviderInstanceInput> input =
                    ArgumentCaptor.forClass(ProviderInstanceInput.class);
            verify(providers).update(eq(EXISTING_ID), input.capture());
            assertEquals("https://new.example.com/v1", input.getValue().baseUrl());
        }

        @Test
        @DisplayName("a model left unset leaves the stored model alone")
        void modelsAreOptional() {
            env.remove(DemoProviderSeeder.ENV_CHAT_MODEL);
            env.remove(DemoProviderSeeder.ENV_EMBEDDING_MODEL);
            seeder().seed();
            verify(settings, never()).saveAgentModel(anyString());
            verify(settings, never()).saveEmbeddingModel(anyString());
            // The provider is still wired, because the roles are the point.
            verify(settings).saveAgentProvider(CREATED_ID.toString());
        }
    }

    @Nested
    @DisplayName("when the environment is wrong")
    class Misconfigured {

        @Test
        @DisplayName("an unknown protocol writes nothing and does not fail startup")
        void unknownProtocol() {
            env.put(DemoProviderSeeder.ENV_PROTOCOL, "NOT_A_PROTOCOL");
            env.put(DemoProviderSeeder.ENV_API_KEY, "sk-demo-key");

            // seed() catches so the sandbox still serves what it can - but nothing is half-written.
            assertDoesNotThrow(() -> seeder().seed());
            verifyNoInteractions(providers);
            verifyNoInteractions(settings);
        }

        @Test
        @DisplayName("a protocol is matched case-insensitively, since it comes from a shell")
        void protocolIsCaseInsensitive() {
            env.put(DemoProviderSeeder.ENV_PROTOCOL, "anthropic");
            seeder().seed();
            verify(providers).create(any());
        }

        @Test
        @DisplayName("a failure inside the provider service is logged, not thrown at startup")
        void serviceFailureIsContained() {
            env.put(DemoProviderSeeder.ENV_PROTOCOL, "ANTHROPIC");
            when(providers.create(any())).thenThrow(new IllegalStateException("boom"));
            assertDoesNotThrow(() -> seeder().seed());
        }
    }

    /**
     * Without {@code @Profile("sandbox")} this runs on every desktop and server installation and
     * repoints the customer's chat and embedding providers at an instance built from environment
     * variables they never set - overwriting a working configuration on every restart. The
     * annotation is what confines it, so the annotation is pinned.
     */
    @Test
    @DisplayName("it is sandbox-profile ONLY - without that annotation it rewrites customer settings")
    void isRestrictedToTheSandboxProfile() {
        org.springframework.context.annotation.Profile profile =
                DemoProviderSeeder.class.getAnnotation(
                        org.springframework.context.annotation.Profile.class);
        org.junit.jupiter.api.Assertions.assertNotNull(profile,
                "DemoProviderSeeder must be @Profile(\"sandbox\")");
        // The public Enterprise sandbox, and nothing a customer runs.
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[] {"sandbox"}, profile.value(),
                "the profile must be exactly sandbox");
    }

    /**
     * SPRING MUST BE ABLE TO CHOOSE A CONSTRUCTOR, and until 2026-09-09 it could not.
     *
     * <p>This class has two constructors - the public one Spring should use and the
     * package-private test seam every other test in this file calls. Neither was annotated, and
     * Spring only auto-selects a constructor when a class declares exactly ONE. With two
     * candidates and no {@code @Autowired} it selects none, falls back to the no-arg constructor
     * that does not exist, and the whole application dies at context refresh with
     * {@code BeanInstantiationException: No default constructor found}. That shipped in 0.6.0 and
     * left demo.proxyma.ai (the public demo, removed 2026-10-09) in a restart loop until somebody
     * read the journal.
     *
     * <p><b>Every other test here passed throughout</b>, because they all construct the seeder
     * directly. That is the right way to inject a fake environment and it is exactly why none of
     * them could see this: they never asked Spring to build the bean. The lesson is not "mock
     * less" - it is that constructor SELECTION is a separate contract from constructor behaviour,
     * and it needs its own assertion.
     *
     * <p>This asks the real resolver rather than restating the rule.
     * {@code determineCandidateConstructors} is the exact method
     * {@code AbstractAutowireCapableBeanFactory} consults; a null return is the fallback-to-no-arg
     * path, which is the failure. Cheap, and it needs no application context - a
     * {@code @SpringBootTest} would catch it too and would cost seconds per run to pin one
     * annotation.
     */
    @Test
    @DisplayName("Spring can select a constructor for this bean")
    void springCanInstantiateThisBean() {
        var processor = new org.springframework.beans.factory.annotation
                .AutowiredAnnotationBeanPostProcessor();

        java.lang.reflect.Constructor<?>[] candidates =
                processor.determineCandidateConstructors(DemoProviderSeeder.class, "demoProviderSeeder");

        org.junit.jupiter.api.Assertions.assertNotNull(candidates,
                "Spring found no candidate constructor, so it would fall back to a no-arg "
                        + "constructor this class does not have and the context would fail to "
                        + "refresh. Annotate the injectable constructor with @Autowired.");
        assertEquals(1, candidates.length,
                "exactly one constructor should be offered to the container");
        assertEquals(3, candidates[0].getParameterCount(),
                "the three-argument constructor is the injectable one; the four-argument "
                        + "constructor is the test seam and must not be what Spring picks");
    }
}
