package ai.proxyma.core.settings.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resolved provider carries its credential DECRYPTED, and a record prints every component - so
 * one log line or assertion message formatting it wrote the key, and every custom header's value,
 * to a log that travels with feedback attachments.
 */
class ResolvedProviderTest {

    @Test
    @DisplayName("toString names the instance and masks the key; header values never appear")
    void toStringCarriesNoCredential() {
        ResolvedProvider p = new ResolvedProvider(UUID.randomUUID(), "Relay", ProviderProtocol.ANTHROPIC,
                "https://relay.corp.example", "sk-ant-secret-value-xyz",
                Map.of("Authorization", "Bearer header-secret"), false);

        String text = p.toString();
        assertThat(text).contains("Relay", "ANTHROPIC", "https://relay.corp.example", "Authorization", "s***z");
        assertThat(text).doesNotContain("sk-ant-secret-value-xyz", "header-secret");
    }
}
