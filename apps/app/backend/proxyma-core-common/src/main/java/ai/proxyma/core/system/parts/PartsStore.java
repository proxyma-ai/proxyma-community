package ai.proxyma.core.system.parts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The installed parts of a desktop install, in {@code <data>/parts/}.
 *
 * <pre>
 * parts/
 *   active.txt        the manifest the launcher starts from
 *   previous.txt      the one before it, kept so a release that cannot start rolls back
 *   staged.txt        downloaded by the updater, becomes active at the next restart
 *   rejected.txt      the version a rollback left, which the updater does not offer again
 *   app/&lt;id&gt;/       one directory per installed part, named by its content id
 *   .download/        partial downloads, resumed where they stopped
 * </pre>
 *
 * <p>IN THE DATA DIRECTORY, NOT THE INSTALL ROOT, for the reason the Python runtime is: the .deb's
 * /opt/proxyma is root-owned, and the updater has to write here while the application runs.
 *
 * <p>A PART DIRECTORY EXISTS ONLY ONCE IT IS COMPLETE. It is extracted beside itself under
 * {@code <id>.tmp} and renamed into place, so an interrupted install leaves a .tmp that the next
 * attempt deletes, never a half-populated directory that reads as installed.
 *
 * <p>Switching releases is writing {@code active.txt}, one small file replaced atomically, and
 * nothing else moves - which is what makes a rollback a rename rather than a reinstall.
 */
public final class PartsStore {

    private static final Logger log = LoggerFactory.getLogger(PartsStore.class);

    private static final String ACTIVE = "active.txt";
    private static final String PREVIOUS = "previous.txt";
    private static final String STAGED = "staged.txt";
    private static final String REJECTED = "rejected.txt";
    static final String DOWNLOADS = ".download";

    private final Path root;

    public PartsStore(Path root) {
        this.root = root;
    }

    public Path root() { return root; }

    public Path dir(PartsManifest.Part part) {
        return root.resolve(part.name()).resolve(part.id());
    }

    Path downloads() { return root.resolve(DOWNLOADS); }

    public boolean installed(PartsManifest.Part part) {
        return Files.isDirectory(dir(part));
    }

    /** The parts of {@code manifest} that are not installed yet. */
    public List<PartsManifest.Part> missing(PartsManifest manifest) {
        return manifest.parts().stream().filter(p -> !installed(p)).toList();
    }

    public Optional<PartsManifest> active()   { return read(ACTIVE); }
    public Optional<PartsManifest> previous() { return read(PREVIOUS); }
    public Optional<PartsManifest> staged()   { return read(STAGED); }

    /** Makes {@code manifest} the one the launcher starts, keeping the current one as the rollback. */
    public void activate(PartsManifest manifest) throws IOException {
        Optional<PartsManifest> current = active();
        if (current.isPresent() && !current.get().equals(manifest)) write(PREVIOUS, current.get());
        write(ACTIVE, manifest);
    }

    /** Records a fully downloaded release for the launcher to switch to at the next restart. */
    public void stage(PartsManifest manifest) throws IOException {
        List<PartsManifest.Part> missing = missing(manifest);
        if (!missing.isEmpty()) {
            throw new IOException("cannot stage " + manifest.version() + ": " + missing.size() + " part(s) not installed");
        }
        write(STAGED, manifest);
    }

    /** The version that last failed to start and was rolled back from, or empty. */
    public Optional<String> rejectedVersion() {
        try {
            Path file = root.resolve(REJECTED);
            if (!Files.isRegularFile(file)) return Optional.empty();
            String v = Files.readString(file, StandardCharsets.UTF_8).trim();
            return v.isEmpty() ? Optional.empty() : Optional.of(v);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void clearStaged() throws IOException {
        Files.deleteIfExists(root.resolve(STAGED));
    }

    /**
     * Switches to the staged release, if there is one, every part of it is really there, and it is
     * NEWER than the release active now. Going back is {@link #rollback}'s, and nothing else's: a
     * staged release older than the one running is a downgrade nobody asked for (platform-02).
     *
     * @return the manifest now active, or empty when nothing was staged
     */
    public Optional<PartsManifest> promoteStaged() throws IOException {
        Optional<PartsManifest> staged = staged();
        if (staged.isEmpty()) return Optional.empty();
        clearStaged();
        if (!missing(staged.get()).isEmpty()) {
            log.warn("Staged release {} is incomplete on disk - not switching to it", staged.get().version());
            return Optional.empty();
        }
        Optional<PartsManifest> current = active();
        if (current.isPresent() && !Versions.isNewer(staged.get().version(), current.get().version())) {
            log.warn("Staged release {} is not newer than {} - not switching to it",
                    staged.get().version(), current.get().version());
            return Optional.empty();
        }
        activate(staged.get());
        return staged;
    }

    /**
     * Goes back to the release before the active one, when every part of it is still installed.
     *
     * @return the manifest now active, or empty when there is nothing to go back to
     */
    public Optional<PartsManifest> rollback() throws IOException {
        Optional<PartsManifest> previous = previous();
        if (previous.isEmpty() || !missing(previous.get()).isEmpty()) return Optional.empty();
        // Remembered, so the updater does not offer the release that just failed straight back.
        Optional<PartsManifest> failed = active();
        if (failed.isPresent()) {
            Files.writeString(root.resolve(REJECTED), failed.get().version(), StandardCharsets.UTF_8);
        }
        write(ACTIVE, previous.get());
        Files.deleteIfExists(root.resolve(PREVIOUS));
        return previous;
    }

    /**
     * Deletes every part directory no manifest refers to, and leftovers of interrupted installs.
     *
     * <p>Only ever run by the launcher before the application starts: the updater writes into this
     * directory while the application runs, and a download in progress is not referenced by any
     * manifest yet.
     */
    public void collectGarbage() {
        Set<Path> keep = new HashSet<>();
        for (Optional<PartsManifest> m : List.of(active(), previous(), staged())) {
            m.ifPresent(manifest -> manifest.parts().forEach(p -> keep.add(dir(p).toAbsolutePath().normalize())));
        }
        if (!Files.isDirectory(root)) return;
        try (var names = Files.list(root)) {
            for (Path nameDir : names.filter(Files::isDirectory).toList()) {
                if (nameDir.getFileName().toString().equals(DOWNLOADS)) continue;
                try (var ids = Files.list(nameDir)) {
                    for (Path idDir : ids.toList()) {
                        if (keep.contains(idDir.toAbsolutePath().normalize())) continue;
                        log.info("Removing unused part {}", root.relativize(idDir));
                        deleteRecursively(idDir);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", root, e.getMessage());
        }
        // Partial downloads of parts no manifest wants any more. A partial download of a part one
        // DOES want is kept, so it resumes.
        Set<String> wanted = new HashSet<>();
        for (Optional<PartsManifest> m : List.of(active(), previous(), staged())) {
            m.ifPresent(manifest -> manifest.parts().forEach(p -> wanted.add(p.file() + ".part")));
        }
        if (Files.isDirectory(downloads())) {
            try (var files = Files.list(downloads())) {
                for (Path f : files.toList()) {
                    if (!wanted.contains(f.getFileName().toString())) deleteRecursively(f);
                }
            } catch (IOException e) {
                log.debug("Could not clean up {}: {}", downloads(), e.getMessage());
            }
        }
    }

    private Optional<PartsManifest> read(String name) {
        Path file = root.resolve(name);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(PartsManifest.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // A file this class wrote and cannot read back is treated as absent: for active.txt that
            // means "download again", which is always a correct answer, if a slow one.
            log.warn("Ignoring unreadable {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String name, PartsManifest manifest) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve(name + ".tmp");
        Files.writeString(tmp, manifest.format(), StandardCharsets.UTF_8);
        moveReplacing(tmp, root.resolve(name));
    }

    static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(all::add);
        } catch (IOException e) {
            log.debug("Could not list {}: {}", path, e.getMessage());
        }
        for (Path p : all) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.debug("Could not delete {}: {}", p, e.getMessage());
            }
        }
    }
}
