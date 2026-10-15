package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.system.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the bodies of pushed documents live: {@code {dataRoot}/ingest/{source}/{file}}, one file per
 * version of a document.
 *
 * <p><b>In the DATA root, on every edition.</b> It is the one directory every edition keeps:
 * {@code %LOCALAPPDATA%\proxyma} on Windows, the {@code /data} volume in the Home Server and
 * Enterprise Server images (HOME=/data). The upload storage root was the other candidate and is
 * wrong for this: a desktop has none, and on the server it is shaped for one owner's files under one
 * folder a source is registered at, while these belong to a source and go with it.
 *
 * <p><b>A new name for every version, never an overwrite.</b> A drain may be reading the previous
 * version while the next one arrives; replacing the file under it would hand the converter half of
 * each. The row names the current file; the old one is deleted once the row no longer names it.
 *
 * <p>Names are made here and only here (a UUID), and checked against that shape before they are
 * resolved, so a stored name can never walk out of the source's directory.
 */
@Component
public class IngestStore {

    private static final Logger log = LoggerFactory.getLogger(IngestStore.class);

    private static final Pattern STORED_NAME = Pattern.compile("[0-9a-f]{32}\\.body");

    private final Path root;

    @Autowired
    public IngestStore(AppPaths appPaths) {
        this(appPaths.dataRoot().resolve("ingest"));
    }

    /** A store under {@code root} - for tests. */
    public IngestStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Moves {@code spooled} - a file the caller wrote and owns until this returns - into the source's
     * directory under a new name, and answers with that name.
     */
    public String keep(UUID sourceId, Path spooled) {
        String name = UUID.randomUUID().toString().replace("-", "") + ".body";
        Path dir = dir(sourceId);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(name);
            try {
                Files.move(spooled, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException crossDevice) {
                // The spool is in the system's temp directory, which may be another volume than
                // the data root (a container's /tmp and its /data volume): a copy there instead.
                Files.copy(spooled, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(spooled);
            }
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the document's content: " + e.getMessage(), e);
        }
    }

    /** Writes {@code bytes} as a new version and answers with its name. */
    public String keep(UUID sourceId, byte[] bytes) {
        try {
            Path spool = Files.createTempFile("proxyma-ingest-", ".part");
            try {
                Files.write(spool, bytes);
                return keep(sourceId, spool);
            } finally {
                Files.deleteIfExists(spool);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the document's content: " + e.getMessage(), e);
        }
    }

    /** The body named {@code name}; NoSuchFileException when it is not there. The caller closes it. */
    public InputStream open(UUID sourceId, String name) throws IOException {
        Path file = resolve(sourceId, name);
        if (file == null) throw new NoSuchFileException(String.valueOf(name));
        return Files.newInputStream(file);
    }

    /** Removes one body. Best effort: a body left behind is a few bytes, a failed request is not. */
    public void delete(UUID sourceId, String name) {
        Path file = resolve(sourceId, name);
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[Ingest] Could not delete a stored body of source={}: {}", sourceId, e.getMessage());
        }
    }

    /** Removes everything a source held - when the source itself is removed. */
    public void deleteSource(UUID sourceId) {
        Path dir = dir(sourceId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[Ingest] Could not delete {} of a removed source: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[Ingest] Could not clear the directory of removed source={}: {}", sourceId, e.getMessage());
        }
    }

    private Path dir(UUID sourceId) {
        return root.resolve(sourceId.toString().replace("-", ""));
    }

    private Path resolve(UUID sourceId, String name) {
        if (sourceId == null || name == null || !STORED_NAME.matcher(name).matches()) return null;
        return dir(sourceId).resolve(name);
    }
}
