package ai.proxyma.core.configtransfer;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Export a deployment's configuration to a file, and put one back.
 *
 * <p>MOUNTED AT ITS OWN TOP-LEVEL PATH, not under /api/v1/settings/. That prefix also hosts
 * SettingsController's generic {@code /{prefix}/{field}} mapping, which structurally overlaps any
 * two-segment path beneath it - the overlap that has already produced a 405 on skill save once, and
 * the reason chat skills live at /api/v1/chat/skills rather than under settings. Same escape here.
 *
 * <p>EVERY ROUTE IS A POST, INCLUDING EXPORT. Export takes the passphrase in a body rather than a
 * query string so it never reaches an access log, a proxy log, a browser history entry or a
 * Referer header. It is not a GET despite being a read; the secret in the request is what decides
 * the method.
 *
 * <p>ROLE. On the server, SecurityConfig gates {@code /api/v1/config-transfer/**} at SUPER_ADMIN -
 * the same place {@code /api/v1/license/**} is gated, and the same level that already guards
 * changing system configuration. Export hands over every credential the deployment holds and import
 * rewrites what every user's chat runs on, so neither belongs at ADMIN. Desktop has no filter chain
 * at all and is single-user by design, so both are simply available there, as every other setting is.
 */
@RestController
@RequestMapping("/api/v1/config-transfer")
public class ConfigTransferController {

    /**
     * The largest file an import reads. A file carries every guardrail policy document whole, as
     * base64 (PolicyLibrary.MAX_DOCUMENT_BYTES each), so it is not a small JSON body - and through
     * the shared JSON decoder it was capped at the codec's limit, which on Desktop and Home Server is
     * WebFlux's default 256 KB: one ordinary policy PDF made a file un-importable on the edition that
     * exported it (settings-core-06). Read here with a limit of its own instead.
     */
    static final int MAX_IMPORT_BYTES = 64 * 1024 * 1024;

    private final ConfigTransferService configTransferService;
    private final ObjectMapper objectMapper;

    public ConfigTransferController(ConfigTransferService configTransferService, ObjectMapper objectMapper) {
        this.configTransferService = configTransferService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param passphrase seals the secrets. Blank or absent exports WITHOUT them - every credential
     *                   becomes {@code ***omitted***} and the file is safe to share, at the cost of
     *                   the target needing its keys entered by hand.
     * @param groups     the TransferGroups to include; absent or empty for all of them.
     */
    public record ExportRequest(String passphrase, List<String> groups) {}

    /** {@code file} is the parsed export; {@code passphrase} opens its secrets block; {@code groups}
     *  limits the import to those TransferGroups, absent or empty for all of them. */
    public record ImportRequest(ConfigExport file, String passphrase, List<String> groups) {}

    /** {@code groups}: every group the FILE holds something for, whatever was chosen - the choices
     *  an import offers. {@code requiresRestart} on a preview means applying it will need one. */
    public record ImportResponse(List<ConfigImportReport.Entry> entries, List<String> groups,
            boolean requiresRestart, boolean secretsApplied, long created, long updated, long unchanged,
            long skipped) {}

    @PostMapping("/export")
    public Mono<ResponseEntity<?>> export(@RequestBody(required = false) ExportRequest request,
                                          ServerWebExchange exchange) {
        return userIdOrEmpty(exchange).flatMap(rawId -> {
            String uid = rawId.isEmpty() ? null : rawId;
            return Mono.<ResponseEntity<?>>fromCallable(() -> {
                        String passphrase = request == null ? null : request.passphrase();
                        List<String> groups = request == null ? null : request.groups();
                        ConfigExport exported = configTransferService.export(passphrase, uid, groups);
                        // Whether the file carries secrets, never the passphrase that seals them.
                        AuditEntry.of(AuditCategory.CONFIG, "config.exported")
                                .user(uid)
                                .summary("Configuration exported")
                                .detail("groups", groupList(groups))
                                .detail("secrets", passphrase != null && !passphrase.isBlank() ? "sealed" : "omitted")
                                .record();
                        return ResponseEntity.ok(exported);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(SecretEnvelope.WeakPassphraseException.class,
                            ConfigTransferController::badRequest);
        });
    }

    /**
     * What an import WOULD do. Writes nothing.
     *
     * <p>Worth its own route rather than a flag on the apply: the whole point is that the reader
     * sees which of their settings are about to be overwritten BEFORE anything is, and a preview
     * reached by passing {@code apply=false} to the same endpoint is one typo away from not being
     * a preview.
     */
    @PostMapping("/import/preview")
    public Mono<ResponseEntity<?>> preview(ServerWebExchange exchange) {
        return readImport(exchange).flatMap(request -> runImport(request, false, exchange))
                .onErrorResume(UnreadableImportException.class, ConfigTransferController::badRequest);
    }

    @PostMapping("/import")
    public Mono<ResponseEntity<?>> apply(ServerWebExchange exchange) {
        return readImport(exchange).flatMap(request -> runImport(request, true, exchange))
                .onErrorResume(UnreadableImportException.class, ConfigTransferController::badRequest);
    }

    /** The request body, read whole up to {@link #MAX_IMPORT_BYTES} and parsed. */
    Mono<ImportRequest> readImport(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_IMPORT_BYTES)
                .onErrorMap(DataBufferLimitException.class, e -> new UnreadableImportException(
                        "That file is larger than an import can read (" + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB)."))
                .publishOn(Schedulers.boundedElastic())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return objectMapper.readValue(bytes, ImportRequest.class);
                    } catch (java.io.IOException e) {
                        throw new UnreadableImportException("That file is not a Proxyma configuration export.");
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .switchIfEmpty(Mono.error(new UnreadableImportException("That file is not a Proxyma configuration export.")));
    }

    static final class UnreadableImportException extends RuntimeException {
        UnreadableImportException(String message) {
            super(message);
        }
    }

    private Mono<ResponseEntity<?>> runImport(ImportRequest request, boolean apply, ServerWebExchange exchange) {
        return userIdOrEmpty(exchange).flatMap(rawId -> {
            String uid = rawId.isEmpty() ? null : rawId;
            return Mono.<ResponseEntity<?>>fromCallable(() -> {
                        if (request == null || request.file() == null) {
                            return ResponseEntity.badRequest()
                                    .body(Map.of("error", "That file is not a Proxyma configuration export."));
                        }
                        ConfigImportReport report = configTransferService.importConfig(
                                request.file(), request.passphrase(), apply, uid, request.groups());
                        // The apply, not the preview, which writes nothing.
                        if (apply) {
                            AuditEntry.of(AuditCategory.CONFIG, "config.imported")
                                    .user(uid)
                                    .summary("Configuration imported")
                                    .detail("groups", groupList(request.groups()))
                                    .detail("created", report.count(ConfigImportReport.Outcome.CREATED))
                                    .detail("updated", report.count(ConfigImportReport.Outcome.UPDATED))
                                    .detail("unchanged", report.count(ConfigImportReport.Outcome.UNCHANGED))
                                    .detail("skipped", report.count(ConfigImportReport.Outcome.SKIPPED))
                                    .detail("secretsApplied", report.isSecretsApplied())
                                    .detail("requiresRestart", report.isRequiresRestart())
                                    .record();
                        }
                        // A preview reports the restart the apply WILL need, read off its entries;
                        // the apply reports what it actually wrote.
                        boolean restart = apply ? report.isRequiresRestart()
                                : report.getEntries().stream().anyMatch(e -> e.requiresRestart()
                                        && (e.outcome() == ConfigImportReport.Outcome.CREATED
                                        || e.outcome() == ConfigImportReport.Outcome.UPDATED));
                        return ResponseEntity.ok(new ImportResponse(
                                report.getEntries(), configTransferService.groupsIn(request.file()),
                                restart, report.isSecretsApplied(),
                                report.count(ConfigImportReport.Outcome.CREATED),
                                report.count(ConfigImportReport.Outcome.UPDATED),
                                report.count(ConfigImportReport.Outcome.UNCHANGED),
                                report.count(ConfigImportReport.Outcome.SKIPPED)));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnError(RuntimeException.class, e -> {
                        if (!apply) return;
                        AuditEntry.of(AuditCategory.CONFIG, "config.imported")
                                .user(uid)
                                .outcome(AuditEntry.FAILED)
                                .summary("Configuration import failed")
                                .detail("groups", groupList(request == null ? null : request.groups()))
                                .detail("error", e.getMessage())
                                .record();
                    })
                    // The one error worth its own message: everything else about a bad file is a
                    // malformed-JSON 400 from the deserializer, which the caller cannot act on.
                    .onErrorResume(SecretEnvelope.WrongPassphraseException.class,
                            ConfigTransferController::badRequest)
                    .onErrorResume(IllegalArgumentException.class, ConfigTransferController::badRequest);
        });
    }

    // Absent or empty means every group, as TransferGroups.selection reads it.
    private static String groupList(List<String> groups) {
        return groups == null || groups.isEmpty() ? "all" : String.join(", ", groups);
    }

    private static Mono<ResponseEntity<?>> badRequest(RuntimeException e) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
    }

    // Mirrors every other controller in this codebase: Principal-based, empty for anonymous and
    // for every desktop-mode request, converted to null at the call site. Skills and workflows are
    // created owned by whoever imported them.
    private Mono<String> userIdOrEmpty(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }
}
