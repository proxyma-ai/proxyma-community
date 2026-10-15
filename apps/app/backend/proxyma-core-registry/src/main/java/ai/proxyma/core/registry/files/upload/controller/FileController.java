package ai.proxyma.core.registry.files.upload.controller;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.files.upload.dto.FileListResponse;
import ai.proxyma.core.registry.files.upload.dto.FileUploadResponse;
import ai.proxyma.core.registry.files.upload.model.FileMetadata;
import ai.proxyma.core.registry.files.upload.service.FileService;
import ai.proxyma.core.registry.quota.UserQuotaPort;
import ai.proxyma.core.system.activity.RequestUserIdAttribute;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Autowired;
import ai.proxyma.core.connector.files.FileSourceMode;
import org.springframework.context.annotation.Conditional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Present wherever this deployment takes uploads, and absent where a file source is a folder the
// user points at - see FileSourceMode, which is the single place that decides, and which both file
// connectors are gated on too.
//
// IT USED TO BE GATED ON THE "desktop" PROFILE, and the reasoning was sound for as long as the
// assumption under it held: uploading was a server-mode concept, desktop had no auth filter, so
// resolveUserId() threw and every endpoint answered 500 where 404 was the honest answer. The home
// server broke the assumption rather than the reasoning - it RUNS the desktop profile, having no
// screen rather than no user, and uploading is the only way to get a document into it.
//
// The safety the profile gave is kept: multi-user forces uploads on, so no unset property can take
// these endpoints away from a server deployment. See FileSourceMode's own note.
@RestController
@RequestMapping("/api/v1/files")
@Conditional(FileSourceMode.UploadsEnabled.class)
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    // The largest file an upload may be. Checked as the bytes arrive (UploadSpool) - nothing in
    // the framework bounds a FilePart's content, so without it an authenticated caller could
    // stream a body of any size onto the disk.
    // Public: the ingestion API takes a file up to the same size (IngestService).
    public static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024;

    private final FileService fileService;
    private final ConnectorService connectorService;
    private final UserQuotaPort quotaPort;
    private final FileSourceMode fileSourceMode;

    public FileController(FileService fileService, ConnectorService connectorService,
                          @Autowired(required = false) UserQuotaPort quotaPort,
                          FileSourceMode fileSourceMode) {
        this.fileService = fileService;
        this.connectorService = connectorService;
        this.quotaPort = quotaPort;
        this.fileSourceMode = fileSourceMode;
    }

    // Self-service usage snapshot for the current user (e.g. FileUploadModal's pre-upload check) -
    // null quotaPort (desktop mode, no auth/quota concept) reports unlimited headroom.
    @GetMapping("/quota")
    public Mono<ResponseEntity<Map<String, Object>>> quota(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            if (quotaPort == null) {
                return ResponseEntity.ok(Map.of("usedBytes", (Object) 0L, "quotaBytes", (Object) Long.MAX_VALUE));
            }
            String userId = resolveUserId(exchange);
            UserQuotaPort.QuotaUsage usage = quotaPort.getUsage(userId);
            return ResponseEntity.ok(Map.of(
                    "usedBytes", (Object) usage.usedBytes(),
                    "quotaBytes", (Object) usage.quotaBytes()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(consumes = "multipart/form-data")
    public Mono<ResponseEntity<FileUploadResponse>> upload(
            @RequestPart("file") FilePart filePart,
            @RequestPart(value = "folder", required = false) String folder,
            @RequestPart(value = "relativePath", required = false) String relativePath,
            ServerWebExchange exchange) {
        // TO A FILE AS IT ARRIVES, never into memory - see UploadSpool. The temporary file is this
        // method's: storage copies from it to where the upload lives, and it is removed whatever
        // happens, a refused or abandoned upload included.
        return Mono.fromCallable(() -> java.nio.file.Files.createTempFile("proxyma-upload-", ".part"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(spool -> UploadSpool.toFile(filePart.content(), spool, MAX_FILE_SIZE_BYTES)
                        // Storage and the database both block, and the last buffer arrives on
                        // whatever thread was reading the request.
                        .publishOn(Schedulers.boundedElastic())
                        .map(size -> {
                            String userId = resolveUserId(exchange);
                            String name   = filePart.filename();
                            String mime   = filePart.headers().getContentType() != null
                                    ? filePart.headers().getContentType().toString() : "application/octet-stream";
                            try (java.io.InputStream in = java.nio.file.Files.newInputStream(spool)) {
                                FileMetadata meta = fileService.upload(name, mime, size, in, userId, folder, relativePath);
                                // One request is one file - the dialog sends a folder file by file.
                                AuditEntry.of(AuditCategory.SOURCES, "file.uploaded")
                                        .user(userId)
                                        .target(meta.getFolder())
                                        .summary("File uploaded to " + (meta.getFolder() == null ? "uploads" : meta.getFolder()))
                                        .detail("file", meta.getOriginalName())
                                        .detail("bytes", meta.getSizeBytes())
                                        .record();
                                return ResponseEntity.ok(toDto(meta));
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        })
                        .doFinally(signal -> {
                            try {
                                java.nio.file.Files.deleteIfExists(spool);
                            } catch (java.io.IOException e) {
                                log.warn("[Files] could not remove an upload's temporary file: {}", e.getMessage());
                            }
                        }));
    }

    @GetMapping
    public Mono<ResponseEntity<FileListResponse>> list(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String userId = resolveUserId(exchange);
            List<FileUploadResponse> files = fileService.listByUser(userId)
                    .stream().map(this::toDto).collect(Collectors.toList());
            return ResponseEntity.ok(new FileListResponse(files));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/folders")
    public Mono<ResponseEntity<List<String>>> listFolders(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String userId = resolveUserId(exchange);
            return ResponseEntity.ok(fileService.listFolders(userId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/folders/ensure")
    public Mono<ResponseEntity<Map<String, Object>>> ensureFolder(
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {
        return Mono.<ResponseEntity<Map<String, Object>>>fromCallable(() -> {
            String userId = resolveUserId(exchange);
            String folder = body.getOrDefault("folder", "").trim();
            if (folder.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", (Object) "Folder name is required"));
            }
            boolean existed = fileService.listFolders(userId).contains(folder);
            fileService.ensureFolder(userId, folder);
            return ResponseEntity.ok(Map.of(
                    "folder", (Object) folder,
                    "existed", (Object) existed
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/folders/register")
    public Mono<ResponseEntity<Map<String, Object>>> registerFolder(
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {
        return Mono.<ResponseEntity<Map<String, Object>>>fromCallable(() -> {
            String userId = resolveUserId(exchange);
            String folder = body.getOrDefault("folder", "").trim();
            if (folder.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", (Object) "Folder name is required"));
            }
            String physicalPath = fileService.ensureFolder(userId, folder);
            UUID connectorId = connectorService.registerFiles(folder, physicalPath, userId);
            log.info("[Files] Registered folder '{}' as connector={}", folder, connectorId);
            return ResponseEntity.ok(Map.of(
                    "id", (Object) connectorId.toString(),
                    "folder", (Object) folder
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }


    @PostMapping("/check-duplicates")
    public Mono<ResponseEntity<List<String>>> checkDuplicates(
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String userId = resolveUserId(exchange);
            String folder = body.getOrDefault("folder", "").toString().trim();
            @SuppressWarnings("unchecked")
            List<String> relativePaths = (List<String>) body.getOrDefault("relativePaths", List.of());
            List<String> duplicates = fileService.findDuplicates(folder, relativePaths, userId)
                    .stream().map(m -> {
                        String rel = m.getRelativePath();
                        return (rel != null && !rel.isBlank()) ? rel + "/" + m.getOriginalName() : m.getOriginalName();
                    }).toList();
            return ResponseEntity.ok(duplicates);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Pre-upload cross-user/cross-connector duplicate check - candidates are hashed CLIENT-SIDE
    // (Web Crypto SHA-256) before any file bytes are sent, so this only ever receives a hash +
    // size per file, never the content itself. See FileService.findSharedDuplicates for the
    // privacy-preserving match scope (shared connectors, or the requester's own).
    private static final int MAX_DUPLICATE_CANDIDATES = 1000;

    @PostMapping("/check-shared-duplicates")
    public Mono<ResponseEntity<List<Map<String, Object>>>> checkSharedDuplicates(
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String userId = resolveUserId(exchange);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawCandidates =
                    (List<Map<String, Object>>) body.getOrDefault("candidates", List.of());
            // Bounded, and a candidate without a numeric size is skipped rather than a 500: each
            // one is a query, and the list comes from the client.
            List<FileService.DuplicateCandidate> candidates = rawCandidates.stream()
                    .limit(MAX_DUPLICATE_CANDIDATES)
                    .filter(c -> c != null && c.get("sizeBytes") instanceof Number)
                    .map(c -> new FileService.DuplicateCandidate(
                            String.valueOf(c.get("relativePath")),
                            ((Number) c.get("sizeBytes")).longValue(),
                            String.valueOf(c.get("sha256"))))
                    .toList();
            List<Map<String, Object>> result = fileService.findSharedDuplicates(candidates, userId).stream()
                    .map(m -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                        row.put("relativePath", m.relativePath());
                        row.put("matchedConnectorName", m.matchedConnectorName());
                        row.put("ownedByRequester", m.ownedByRequester());
                        return row;
                    })
                    .toList();
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{fileId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID fileId, ServerWebExchange exchange) {
        return Mono.<ResponseEntity<Void>>fromCallable(() -> {
            String userId = resolveUserId(exchange);
            FileMetadata meta = fileService.findById(fileId).orElse(null);
            if (meta == null) return ResponseEntity.status(404).<Void>build();
            // Objects.equals, not userId.equals: the single local user IS null, and calling equals
            // on them would throw a 500 out of an ownership check on the one deployment where the
            // check can never fail. Still a real comparison - a null caller matches only a null
            // uploader, which on a deployment with accounts cannot be reached at all because
            // resolveUserId() refuses a null there.
            if (!java.util.Objects.equals(userId, meta.getUploadedByUserId()))
                return ResponseEntity.status(403).<Void>build();
            fileService.delete(fileId);
            AuditEntry.of(AuditCategory.SOURCES, "file.removed")
                    .user(userId)
                    .target(meta.getFolder())
                    .summary("File removed from " + (meta.getFolder() == null ? "uploads" : meta.getFolder()))
                    .detail("file", meta.getOriginalName())
                    .record();
            return ResponseEntity.ok().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Must match ai.proxyma.core.auth.JwtAuthFilter.USER_CLAIMS_KEY exactly - inlined rather
    // than importing JwtAuthFilter itself so this module has no compile-time dependency on
    // proxyma-core-auth, only a wire-level contract (the request attribute key JwtAuthFilter
    // populates on every authenticated request).
    private static final String USER_CLAIMS_KEY = "PROXYMA_USER_CLAIMS";

    /**
     * Who is uploading: the account on the request, or null for the single local user.
     *
     * <p><b>NULL IS AN ANSWER HERE, NOT A FAILURE - but only where there are no accounts.</b> The
     * home server has exactly one user and no sign-in, and represents that user as a null owner
     * everywhere else in this codebase; demanding a JWT there would refuse every upload on a
     * deployment working exactly as designed.
     *
     * <p>Where there ARE accounts the throw stays, and it has to: a null owner in server mode would
     * not be "the local user", it would be an unauthenticated caller landing in a sandbox shared
     * with nobody's files and counted against nobody's quota. {@code uploadsAreAttributed()} is
     * exactly that question, and it is derived from {@code multi-user} rather than from a property
     * of its own, so no configuration mistake can turn this check off.
     */
    private String resolveUserId(ServerWebExchange exchange) {
        JWTClaimsSet claims = (JWTClaimsSet) exchange.getAttributes().get(USER_CLAIMS_KEY);
        if (claims != null) return claims.getSubject();
        // An API (MCP) token authenticates without claims: the auth filter names its account in this
        // attribute instead, only after the token, its account and its role have been checked. Read
        // only the claims, every upload with a token was refused - as a 503, from the throw below.
        String tokenUser = exchange.getAttribute(RequestUserIdAttribute.KEY);
        if (tokenUser != null && !tokenUser.isBlank()) return tokenUser;
        if (fileSourceMode.uploadsAreAttributed()) {
            // A 401, not the IllegalStateException this was: the global handler answers that with
            // 503, which tells a client the server is down rather than that it must sign in.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return null;
    }

    private FileUploadResponse toDto(FileMetadata meta) {
        return new FileUploadResponse(
                meta.getId().toString(),
                meta.getOriginalName(),
                meta.getMimeType(),
                meta.getSizeBytes(),
                meta.getUploadedAt());
    }
}
