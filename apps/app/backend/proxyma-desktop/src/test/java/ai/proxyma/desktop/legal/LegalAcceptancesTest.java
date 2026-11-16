package ai.proxyma.desktop.legal;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.settings.audit.AuditEvent;
import ai.proxyma.core.settings.audit.AuditEventRepository;
import ai.proxyma.core.system.legal.LegalDocuments;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE LEGAL DOCUMENTS ARE ACCEPTED AT THE START, AND THE ACCEPTANCE STAYS (2026-11-14, the maintainer: "Force the user
 * to agree ... If the user don't, shutdown the app, next start ask again. If the user agree, log the agreement,
 * non-deletable"): the gate is closed until the version in force is accepted, an acceptance is a line appended to a
 * file of the data directory and a LEGAL row on the Logs page, a page holding an older version is refused, and a row a
 * full reset took is written back from the file. Declining is the quit, which records nothing.
 */
class LegalAcceptancesTest {

    @TempDir
    Path dir;

    private final List<AuditEvent> rows = new ArrayList<>();
    private final Set<String> logged = new HashSet<>();
    private AuditEventRepository audit;
    private PlatformTransactionManager tm;

    @BeforeEach
    void setUp() {
        audit = mock(AuditEventRepository.class);
        when(audit.save(any(AuditEvent.class))).thenAnswer(i -> {
            AuditEvent e = i.getArgument(0);
            rows.add(e);
            logged.add(e.getTarget());
            return e;
        });
        when(audit.existsByActionAndTarget(eq(AuditEvent.LEGAL_ACCEPTED), anyString()))
                .thenAnswer(i -> logged.contains(i.<String>getArgument(1)));
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private LegalAcceptances acceptances() {
        return new LegalAcceptances(dir.resolve(LegalAcceptances.FILE), audit, tm, new ObjectMapper(), "0.42.0");
    }

    @Test
    @DisplayName("a first start is not accepted, and has no acceptance to show")
    void firstStartAsks() {
        LegalAcceptances.Status s = acceptances().status();
        assertThat(s.accepted()).isFalse();
        assertThat(s.version()).isEqualTo(LegalDocuments.CURRENT_VERSION);
        assertThat(s.last()).isNull();
    }

    @Test
    @DisplayName("an acceptance opens the gate, is a line of the file and a LEGAL row on the Logs page")
    void acceptedAndLogged() throws Exception {
        LegalAcceptances.Status s = acceptances().accept(LegalDocuments.CURRENT_VERSION);

        assertThat(s.accepted()).isTrue();
        assertThat(s.last().appVersion()).isEqualTo("0.42.0");
        assertThat(Files.readString(dir.resolve(LegalAcceptances.FILE), StandardCharsets.UTF_8))
                .contains("\t" + LegalDocuments.CURRENT_VERSION + "\t0.42.0");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getCategory()).isEqualTo(AuditCategory.LEGAL);
            assertThat(r.getAction()).isEqualTo(AuditEvent.LEGAL_ACCEPTED);
            assertThat(r.getTarget()).isEqualTo(LegalDocuments.CURRENT_VERSION);
            assertThat(r.getActorId()).isEqualTo("_local");
            assertThat(r.getDetail()).contains(LegalDocuments.PRIVACY_URL).contains("0.42.0");
        });
        assertThat(acceptances().status().accepted()).as("the next start reads the file").isTrue();
    }

    @Test
    @DisplayName("a page holding another version is refused, and nothing is recorded")
    void staleVersionRefused() {
        assertThatThrownBy(() -> acceptances().accept("2020-01-01")).isInstanceOf(LegalAcceptances.StaleVersionException.class);
        assertThatThrownBy(() -> acceptances().accept(null)).isInstanceOf(LegalAcceptances.StaleVersionException.class);
        assertThat(dir.resolve(LegalAcceptances.FILE)).doesNotExist();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("changed documents ask again: an acceptance of an older version does not open the gate")
    void changedDocumentsAskAgain() throws Exception {
        Files.writeString(dir.resolve(LegalAcceptances.FILE), "2026-09-14T08:00:00Z\t2026-09-14\t0.20.0\n");
        LegalAcceptances.Status s = acceptances().status();
        assertThat(s.accepted()).isFalse();
        assertThat(s.last().version()).isEqualTo("2026-09-14");
    }

    @Test
    @DisplayName("an acceptance a full reset took off the Logs page is written back at the next start, once")
    void writtenBackAfterAReset() throws Exception {
        Files.writeString(dir.resolve(LegalAcceptances.FILE),
                "2026-09-14T08:00:00Z\t2026-09-14\t0.20.0\nnot a line\n2026-11-14T08:00:00Z\t2026-11-14\t0.42.0\n");
        LegalAcceptances a = acceptances();
        a.restoreLogRows();
        a.restoreLogRows();
        assertThat(rows).extracting(AuditEvent::getTarget).containsExactly("2026-09-14", "2026-11-14");
        assertThat(rows.get(0).getOccurredAt()).hasToString("2026-09-14T08:00:00Z");
    }

    @Test
    @DisplayName("the Logs row failing to write still leaves the acceptance recorded")
    void fileIsTheRecord() {
        when(audit.save(any(AuditEvent.class))).thenThrow(new IllegalStateException("database closed"));
        assertThat(acceptances().accept(LegalDocuments.CURRENT_VERSION).accepted()).isTrue();
    }
}
