package ai.proxyma.desktop.legal;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.audit.AuditEvent;
import ai.proxyma.core.settings.audit.AuditEventRepository;
import ai.proxyma.core.system.legal.LegalDocuments;
import ai.proxyma.core.system.util.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE LEGAL DOCUMENTS ARE ACCEPTED BEFORE PROXYMA IS USED, on Desktop and Home Server (2026-11-14, the maintainer:
 * "the best place to show our disclaimer is when Proxyma is started for the first time. Force the user to agree with
 * the privacy policy. If the user don't, shutdown the app, next start ask again. If the user agree, log the agreement,
 * non-deletable."). Proxyma passes data to the AI services the person connects - through their APIs, or to an AI
 * assistant app over MCP - and what those keep or train on is set in the person's account there, which the page's gate
 * says before the Terms, Privacy Policy and EULA are accepted. Enterprise Server asks each account instead
 * (LegalConsent, ReConsentModal), so this is built into the single-user editions alone, as ShutdownController is.
 *
 * <p>THE RECORD IS A FILE, {@value #FILE} in the data directory, one line per acceptance and only ever appended to:
 * the time, the version of the documents ({@link LegalDocuments#CURRENT_VERSION}) and Proxyma's version. Nothing in
 * the app deletes it - a full reset deletes the databases and named folders and leaves the data directory's other
 * files alone (PendingReset), as it does the license. The gate is open while a line names the version in force, so a
 * change of the documents asks again.
 *
 * <p>EACH ACCEPTANCE IS ALSO ON THE LOGS PAGE, a {@link AuditCategory#LEGAL} row that neither a tab's Clear nor
 * retention deletes ({@link AuditEvent#LEGAL_ACCEPTED}): written at once, not through the audit queue, which drops an
 * entry when full, and written back from the file at the next start when a full reset took the database.
 */
@Service
public class LegalAcceptances implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegalAcceptances.class);

    static final String FILE = "legal-acceptances.log";
    static final String SUMMARY = "Accepted the Terms of Service, Privacy Policy and EULA, version ";

    /** One acceptance: when, which documents, on which Proxyma. */
    public record Acceptance(Instant at, String version, String appVersion) {}

    /** What the gate needs: the version in force, whether it was accepted, and the newest acceptance if any. */
    public record Status(String version, boolean accepted, Acceptance last) {}

    /** The documents in force changed since the page asked: it must show them again. */
    public static class StaleVersionException extends RuntimeException {
        StaleVersionException(String message) {
            super(message);
        }
    }

    private final Path file;
    private final AuditEventRepository audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String appVersion;

    @Autowired
    public LegalAcceptances(AppPaths paths, AuditEventRepository audit,
                            @Qualifier("chatTransactionManager") PlatformTransactionManager manager, ObjectMapper json,
                            @Value("${spring.application.version:0.0.0}") String appVersion) {
        this(paths.dataRoot().resolve(FILE), audit, manager, json, appVersion);
    }

    LegalAcceptances(Path file, AuditEventRepository audit, PlatformTransactionManager manager, ObjectMapper json,
                     String appVersion) {
        this.file = file;
        this.audit = audit;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.appVersion = appVersion;
    }

    public Status status() {
        List<Acceptance> all = read();
        String current = LegalDocuments.CURRENT_VERSION;
        boolean accepted = all.stream().anyMatch(a -> current.equals(a.version()));
        return new Status(current, accepted, all.isEmpty() ? null : all.get(all.size() - 1));
    }

    /**
     * Records the acceptance of {@code version}, which must be the version in force. The file first: once it holds
     * the line the gate is open, whatever becomes of the Logs row.
     */
    public synchronized Status accept(String version) {
        if (version == null || !LegalDocuments.CURRENT_VERSION.equals(version.strip())) throw new StaleVersionException(
                "The documents changed while this page was open. Reload the page and accept the current version.");
        Acceptance a = new Acceptance(Instant.now(), LegalDocuments.CURRENT_VERSION, appVersion);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, a.at() + "\t" + a.version() + "\t" + a.appVersion() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new IllegalStateException("Proxyma could not record your acceptance: " + e.getMessage(), e);
        }
        log.info("[Legal] the documents of {} accepted", a.version());
        logRow(a);
        return status();
    }

    /** Every acceptance the file holds, oldest first; a line that cannot be read is passed over. */
    List<Acceptance> read() {
        if (!Files.isRegularFile(file)) return List.of();
        List<Acceptance> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.strip().split("\t");
                if (f.length < 2 || f[1].isBlank()) continue;
                try {
                    out.add(new Acceptance(Instant.parse(f[0]), f[1], f.length > 2 ? f[2] : ""));
                } catch (DateTimeParseException ignored) {
                    // Not a line this class wrote.
                }
            }
        } catch (IOException e) {
            log.warn("[Legal] {} could not be read: {}", FILE, e.getMessage());
        }
        return out;
    }

    /** At start: an acceptance the Logs page no longer shows - a full reset took the database - is written back. */
    @Override
    public void run(ApplicationArguments args) {
        restoreLogRows();
    }

    void restoreLogRows() {
        try {
            Map<String, Acceptance> firstOfEach = new LinkedHashMap<>();
            for (Acceptance a : read()) firstOfEach.putIfAbsent(a.version(), a);
            for (Acceptance a : firstOfEach.values()) {
                if (!audit.existsByActionAndTarget(AuditEvent.LEGAL_ACCEPTED, a.version())) logRow(a);
            }
        } catch (RuntimeException e) {
            log.warn("[Legal] the Logs page's acceptances could not be checked: {}", e.getMessage());
        }
    }

    private void logRow(Acceptance a) {
        try {
            AuditEvent row = new AuditEvent();
            row.setOccurredAt(a.at());
            row.setCategory(AuditCategory.LEGAL);
            row.setAction(AuditEvent.LEGAL_ACCEPTED);
            row.setOutcome(AuditEntry.OK);
            row.setActorKind(SpendSubject.LOCAL.kind().name());
            row.setActorId(SpendSubject.LOCAL.id());
            row.setTarget(a.version());
            row.setSummary(SUMMARY + a.version());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("version", a.version());
            detail.put("proxymaVersion", a.appVersion());
            detail.put("documents", List.of(LegalDocuments.TERMS_URL, LegalDocuments.PRIVACY_URL, LegalDocuments.EULA_URL));
            row.setDetail(json.writeValueAsString(detail));
            tx.executeWithoutResult(s -> audit.save(row));
        } catch (Exception e) {
            log.warn("[Legal] the acceptance of {} is recorded but not on the Logs page: {}", a.version(), e.getMessage());
        }
    }
}
