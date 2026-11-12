package ai.proxyma.core.system.legal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE DPA IS SERVED WITH ITS OWN DATE (2026-11-11 release review): it read "Last updated: 2026-10-30" and the app served it
 * as 2026-11-09, the consent version, which follows the Terms, Privacy Policy and EULA rather than the DPA.
 */
class DpaLastUpdatedTest {

    @Test
    @DisplayName("the DPA's date is the one on its own Last updated line; with none, the consent version")
    void ownDate() {
        assertThat(LegalDocumentController.lastUpdatedOf("# DPA\n\n**Last updated:** 2026-10-30\n")).isEqualTo("2026-10-30");
        assertThat(LegalDocumentController.lastUpdatedOf("no date")).isEqualTo(LegalDocuments.CURRENT_VERSION);
        assertThat(LegalDocumentController.lastUpdatedOf(null)).isEqualTo(LegalDocuments.CURRENT_VERSION);
    }

    @Test
    @DisplayName("the shipped DPA states a date the controller reads")
    void shippedDpa() throws Exception {
        String dpa = new String(getClass().getClassLoader().getResourceAsStream("legal/data-processing-agreement.md").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(LegalDocumentController.lastUpdatedOf(dpa)).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    }
}
