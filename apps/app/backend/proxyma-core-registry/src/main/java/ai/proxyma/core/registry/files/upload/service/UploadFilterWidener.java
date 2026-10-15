package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.service.ConnectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A FILE ADDED TO A SOURCE IS INDEXED, whatever the source's file-type filter said before it arrived
 * (the maintainer's request, 2026-10-10). A source made from a folder of markdown is filtered to
 * "md"; a CSV uploaded into it later was stored and then skipped by every sync, with nothing saying
 * why. Uploading a file of a type the filter leaves out now adds that type to the filter of the
 * source reading the folder - through ConnectorService.updateConnectorConfig, the same path the
 * source's settings dialog takes, which also syncs the source so the file is indexed at once.
 *
 * <p>Only a type Proxyma knows (FileTypeSupport's groups): a stray extension is not switched on by
 * being uploaded. A source with no filter already reads every supported type and is left alone.
 * Serialized, so two uploads of two new types at once cannot each write the filter without the
 * other's addition.
 */
@Component
public class UploadFilterWidener {

    private static final Logger log = LoggerFactory.getLogger(UploadFilterWidener.class);

    private static final Set<String> KNOWN = FileTypeSupport.extensionGroups().values().stream()
            .flatMap(List::stream).collect(Collectors.toUnmodifiableSet());

    private final RegisteredConnectorRepository connectors;
    private final ConnectorService connectorService;
    private final StorageService storage;

    public UploadFilterWidener(RegisteredConnectorRepository connectors, ConnectorService connectorService,
                               StorageService storage) {
        this.connectors = connectors;
        this.connectorService = connectorService;
        this.storage = storage;
    }

    /**
     * Widens the filter of every upload source reading {@code folder} (the caller's own, or the
     * single local user's when {@code userId} is null) so a file named {@code fileName} is indexed.
     *
     * @return the extension added, or empty when nothing had to change
     */
    public synchronized Optional<String> widenFor(String userId, String folder, String fileName) {
        if (folder == null || folder.isBlank()) return Optional.empty();
        String ext = extensionOf(fileName);
        if (ext == null || !KNOWN.contains(ext)) return Optional.empty();
        Path dir = absolute(storage.ensureFolder(userId, folder.trim()));
        if (dir == null) return Optional.empty();
        List<RegisteredConnector> candidates = userId == null
                ? connectors.findByConnectorType(ConnectorType.UPLOADED_FILES).stream()
                        .filter(c -> c.getUserId() == null).toList()
                : connectors.findByUserIdAndConnectorType(userId, ConnectorType.UPLOADED_FILES);
        String added = null;
        for (RegisteredConnector source : candidates) {
            if (!dir.equals(absolute(source.getConfig()))) continue;
            Set<String> types = FileTypeSupport.parseFileTypes(source.getFileTypes());
            if (types == null || types.contains(ext)) continue;
            Set<String> widened = new LinkedHashSet<>(types);
            widened.add(ext);
            connectorService.updateConnectorConfig(source.getId(), Map.of("fileTypes", String.join(",", widened)), userId);
            log.info("[Files] '{}' added to the file types of source {} for an uploaded {}", ext, source.getId(), fileName);
            added = ext;
        }
        return Optional.ofNullable(added);
    }

    static String extensionOf(String fileName) {
        if (fileName == null) return null;
        String name = fileName.strip();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Path absolute(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
