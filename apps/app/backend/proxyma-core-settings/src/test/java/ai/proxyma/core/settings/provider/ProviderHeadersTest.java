package ai.proxyma.core.settings.provider;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * isd-02: a provider's custom headers are credentials as often as not - a relay's Authorization, a
 * gateway's tenant key - and they were stored and returned in clear beside an API key that was
 * encrypted and masked. Now they are encrypted at rest, a credential header leaves by its ends only,
 * and the mask (or a blank) sent back by an edit keeps the stored value.
 */
class ProviderHeadersTest {

    @Nested
    @DisplayName("which headers carry a credential - the page's own rule (headerValue.ts)")
    class Names {
        @Test
        @DisplayName("the usual names do, in any case")
        void credentialNames() {
            for (String name : List.of("Authorization", "authorization", "X-Api-Key", "api-key", "X-API_KEY",
                    "X-Auth-Token", "Cookie", "X-Session", "Ocp-Apim-Subscription-Key", "x-goog-api-key",
                    "X-Secret", "Proxy-Authorization")) {
                assertThat(ProviderHeaders.isSecret(name)).as(name).isTrue();
            }
        }

        @Test
        @DisplayName("attribution and routing headers do not")
        void ordinaryNames() {
            for (String name : List.of("HTTP-Referer", "X-Title", "User-Agent", "X-Region", "Accept-Language",
                    "Keyboard-Layout")) {
                assertThat(ProviderHeaders.isSecret(name)).as(name).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a credential leaves by its ends, its scheme word kept; anything else as it is")
    void masking() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-live-0123456789abcdef");
        headers.put("X-Api-Key", "short");
        headers.put("X-Title", "Proxyma");

        Map<String, String> out = ProviderHeaders.masked(headers);

        assertThat(out.get("Authorization")).isEqualTo("Bearer sk-l***def");
        assertThat(out.get("X-Api-Key")).isEqualTo("***");
        assertThat(out.get("X-Title")).isEqualTo("Proxyma");
        assertThat(out.toString()).doesNotContain("0123456789");
    }

    @Nested
    @DisplayName("an edit")
    class Merge {
        private final Map<String, String> stored = Map.of(
                "Authorization", "Bearer sk-live-0123456789abcdef", "X-Title", "Proxyma");

        @Test
        @DisplayName("sending a credential back as its mask, or blank, keeps the stored value")
        void maskEchoKeeps() {
            Map<String, String> echoed = new LinkedHashMap<>(ProviderHeaders.masked(stored));
            assertThat(ProviderHeaders.merge(stored, echoed)).isEqualTo(stored);

            Map<String, String> blank = Map.of("Authorization", "", "X-Title", "Proxyma");
            assertThat(ProviderHeaders.merge(stored, blank).get("Authorization"))
                    .isEqualTo("Bearer sk-live-0123456789abcdef");
        }

        @Test
        @DisplayName("a new value replaces it, and a header left out is removed")
        void newValueReplaces() {
            Map<String, String> out = ProviderHeaders.merge(stored, Map.of("Authorization", "Bearer other-key-value"));
            assertThat(out).containsExactly(Map.entry("Authorization", "Bearer other-key-value"));
        }

        @Test
        @DisplayName("a credential header that was only renamed keeps its value")
        void renameKeeps() {
            Map<String, String> renamed = Map.of("Proxy-Authorization", ProviderHeaders.maskValue(stored.get("Authorization")));
            assertThat(ProviderHeaders.merge(stored, renamed).get("Proxy-Authorization"))
                    .isEqualTo("Bearer sk-live-0123456789abcdef");
        }
    }

    @Nested
    @DisplayName("through the service")
    class Stored {
        private ProviderInstanceRepository repository;
        private ProviderInstanceService service;
        private ProviderInstance row;

        @BeforeEach
        void setUp() {
            repository = mock(ProviderInstanceRepository.class);
            CredentialEncryptionService encryption = mock(CredentialEncryptionService.class);
            when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
            when(encryption.decrypt(anyString())).thenAnswer(inv -> {
                String v = inv.getArgument(0);
                return v.startsWith("ENC(") ? v.substring(4, v.length() - 1) : v;
            });
            when(repository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                ProviderInstance e = inv.getArgument(0);
                if (e.getId() == null) e.setId(UUID.randomUUID());
                row = e;
                when(repository.findById(e.getId())).thenReturn(Optional.of(e));
                return e;
            });
            service = new ProviderInstanceService(repository, encryption, new ObjectMapper(),
                    mock(ApplicationEventPublisher.class), mock(ModelPricingService.class));
        }

        private ProviderInstanceService.ProviderInstanceInput input(Map<String, String> headers) {
            return new ProviderInstanceService.ProviderInstanceInput("Relay", ProviderProtocol.OPENAI,
                    "https://relay.example/v1", null, false, headers, null);
        }

        @Test
        @DisplayName("stored encrypted, returned masked, and resolved in full for the model call")
        void encryptedAtRestMaskedOnRead() {
            ProviderInstanceView view = service.create(input(Map.of("X-Api-Key", "relay-key-0123456789")));

            assertThat(row.getHeadersJson()).startsWith("ENC(");
            assertThat(view.headers().get("X-Api-Key")).isEqualTo("rela***789");
            assertThat(service.resolve(view.id()).orElseThrow().headers().get("X-Api-Key"))
                    .isEqualTo("relay-key-0123456789");
        }

        @Test
        @DisplayName("an edit that sends the masked headers back changes nothing")
        void editKeepsTheStoredCredential() {
            ProviderInstanceView view = service.create(input(Map.of("X-Api-Key", "relay-key-0123456789")));

            service.update(view.id(), input(view.headers()));

            assertThat(service.resolve(view.id()).orElseThrow().headers().get("X-Api-Key"))
                    .isEqualTo("relay-key-0123456789");
        }

        @Test
        @DisplayName("a row written before encryption still reads")
        void legacyPlainJsonReads() {
            ProviderInstance legacy = new ProviderInstance();
            legacy.setId(UUID.randomUUID());
            legacy.setName("Old");
            legacy.setProtocol(ProviderProtocol.OPENAI);
            legacy.setHeadersJson("{\"X-Title\":\"Proxyma\"}");
            when(repository.findById(legacy.getId())).thenReturn(Optional.of(legacy));

            assertThat(service.resolve(legacy.getId()).orElseThrow().headers()).containsEntry("X-Title", "Proxyma");
        }
    }
}
