package ai.proxyma.core.telemetryclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A stable, opaque identifier for THIS INSTALLATION.
 *
 * Identifies a copy of the software, never a person. It exists so several reports from one
 * struggling installation can be recognised as one story instead of read as several unrelated
 * ones, and so a fix can be confirmed against the install that reported the problem.
 *
 * A random UUID generated once and written to the data directory - deliberately NOT derived from a
 * hardware fingerprint, a MAC address, a hostname or a username. A derived id would be stable
 * across reinstalls, which sounds useful and is exactly the property that makes it a device
 * identifier rather than an installation one. Delete the file and this installation becomes a new,
 * unlinkable one; that is the intended escape hatch, not a flaw.
 */
@Component
public class InstanceIdProvider {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdProvider.class);
    private static final String FILE_NAME = "instance-id";

    @Value("${proxyma.data.dir:${server.data.dir:data}}")
    private String dataDir;

    private volatile String cached;

    public String getInstanceId() {
        String local = cached;
        if (local != null) return local;
        synchronized (this) {
            if (cached != null) return cached;
            cached = loadOrCreate();
            return cached;
        }
    }

    private String loadOrCreate() {
        Path file = Path.of(dataDir, FILE_NAME);
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) return existing;
            }
            String minted = UUID.randomUUID().toString();
            Files.createDirectories(file.getParent());
            Files.writeString(file, minted, StandardCharsets.UTF_8);
            return minted;
        } catch (IOException e) {
            // A read-only or unwritable data directory must not stop somebody sending feedback.
            // An in-memory id is worse (it changes every restart, so reports from one install look
            // like reports from many) but it is not worth failing over.
            log.warn("[InstanceId] Could not persist an instance id in {}: {}", dataDir, e.getMessage());
            return UUID.randomUUID().toString();
        }
    }
}
