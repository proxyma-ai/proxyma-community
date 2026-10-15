package ai.proxyma.desktop.launcher;

import ai.proxyma.core.system.parts.PartsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Reset everything" on Desktop and the Home Server: the marker the app writes, and the deletion
 * that happens at the next start, before anything opens the databases.
 *
 * <p>THE DELETION HAPPENS BEFORE THE APPLICATION OPENS ITS FILES, because H2 holds them open and on
 * Windows an open file cannot be deleted. The launcher runs it before it starts the application,
 * and the application's own main() runs it again before Spring - so it also happens where there is
 * no launcher: the Home Server, whose container simply restarts, and a jar run by hand. Until
 * 2026-10-14 only the launcher did it, and only on an exit-3 restart: on the Home Server nothing
 * read the marker at all, and on Desktop a marker that missed its restart (a crash, a shutdown in
 * the five seconds between) waited for the next UPDATE to wipe everything without asking
 * (editions-assembly-04, -05).
 *
 * <p>A MARKER OLDER THAN {@link #MAX_AGE} IS CANCELLED, not applied. Somebody who chose Reset and
 * then watched their machine crash is better served by seeing their data still there - and
 * choosing Reset again - than by having it vanish at a start days later.
 *
 * <p>A DELETION LIST, not "empty the directory". Several things in the data directory must survive
 * a reset and would be silently expensive or unrecoverable to lose: license.dat (the user is still
 * a customer), the credential encryption key (deleting it does not remove stored credentials, it
 * makes them permanently undecryptable), the access code, and parts/ + runtime/ - the downloaded
 * release and the Python built from it, several hundred megabytes that hold no user data. Listing
 * what goes means a directory added later survives by default, which is the safe direction to fail.
 *
 * <p>WHAT LIVES OUTSIDE THE DATA DIRECTORY is listed in the marker by the app, which is the only
 * thing that knows where its configuration put it: conversations, skills and workflows default to
 * ~/.proxyma, and a Home Server's uploads are under its storage root. Each is checked again here
 * before it is deleted.
 */
public final class PendingReset {

    private static final Logger log = LoggerFactory.getLogger(PendingReset.class);

    /** The marker file in the data directory. Its presence is the request. */
    public static final String MARKER = "reset-pending";

    static final Duration MAX_AGE = Duration.ofMinutes(15);

    // In the data directory. evaluation-media holds COPIES OF CHAT MEDIA keyed by anonymous
    // conversation id; repos the Bitbucket snapshots; guardrail-policies the uploaded policy corpus.
    static final List<String> DATA_DIRECTORIES = List.of(
            "chromadb", "lucene", "markdown", "conversations", "temp", "evaluation-media", "repos",
            "guardrail-policies");

    private static final String REQUESTED = "requested=";
    private static final String DELETE = "delete=";

    private PendingReset() {}

    /** Writes the request. {@code alsoDelete} are directories outside the data root. */
    public static void request(Path dataRoot, List<Path> alsoDelete, Instant now) throws IOException {
        StringBuilder text = new StringBuilder()
                .append("# Written by Proxyma when the user chose Reset everything.\n")
                .append("# The next start deletes the indexed data and what is listed below, then this file.\n")
                .append("# Deleting this file by hand cancels the reset.\n")
                .append(REQUESTED).append(now).append('\n');
        for (Path dir : alsoDelete) text.append(DELETE).append(dir.toAbsolutePath().normalize()).append('\n');
        Files.writeString(dataRoot.resolve(MARKER), text, StandardCharsets.UTF_8);
    }

    /**
     * Carries out a requested reset, if there is one and it is recent.
     *
     * @return true when data was deleted
     */
    public static boolean applyIfRequested(Path dataRoot, Instant now) {
        Path marker = dataRoot.resolve(MARKER);
        if (!Files.isRegularFile(marker)) return false;

        Instant requested = null;
        List<Path> alsoDelete = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.startsWith(REQUESTED)) {
                    try {
                        requested = Instant.parse(line.substring(REQUESTED.length()).trim());
                    } catch (DateTimeParseException ignored) {
                        // Falls back to the file's own time below.
                    }
                } else if (line.startsWith(DELETE)) {
                    alsoDelete.add(Path.of(line.substring(DELETE.length()).trim()));
                }
            }
            // A marker from a build that wrote no time - its file time is the same fact.
            if (requested == null) requested = Files.getLastModifiedTime(marker).toInstant();
        } catch (Exception e) {
            log.warn("Reset requested, but its marker at {} could not be read ({}) - not resetting", marker, e.getMessage());
            removeMarker(marker);
            return false;
        }

        if (Duration.between(requested, now).compareTo(MAX_AGE) > 0) {
            log.warn("A reset requested at {} was never carried out, and is too old to carry out without asking "
                    + "- cancelled. Choose Reset everything again to reset.", requested);
            removeMarker(marker);
            return false;
        }

        log.warn("Reset requested - deleting indexed data from {}", dataRoot);
        for (String dir : DATA_DIRECTORIES) PartsStore.deleteRecursively(dataRoot.resolve(dir));

        // Every H2 database, by extension rather than by name - the set of databases is declared in
        // application-desktop.properties, and a list repeated here would be one more thing to keep
        // in step with it.
        try (var entries = Files.list(dataRoot)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".mv.db") || n.endsWith(".trace.db") || n.endsWith(".lock.db");
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Reset could not delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Reset could not list {}: {}", dataRoot, e.getMessage());
        }

        for (Path dir : alsoDelete) {
            if (safeToDelete(dir, dataRoot)) {
                PartsStore.deleteRecursively(dir);
            } else {
                log.warn("Reset left {} alone: not a directory it may delete", dir);
            }
        }

        // Last, so that a crash anywhere above leaves the marker in place and the reset is
        // completed on the next start instead of being half-done and forgotten.
        removeMarker(marker);
        log.warn("Reset complete - starting with an empty data directory");
        return true;
    }

    /**
     * Whether a directory named in the marker may go. The marker is a file in the user's own data
     * directory, so this is not a defense against its owner - it is the check that a mistake in a
     * configured path (an empty value resolving to the home directory, a root) costs nothing.
     */
    static boolean safeToDelete(Path dir, Path dataRoot) {
        if (!dir.isAbsolute()) return false;
        Path normalized = dir.normalize();
        if (normalized.getParent() == null || normalized.getNameCount() < 2) return false;
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (normalized.equals(home) || home.startsWith(normalized)) return false;
        Path root = dataRoot.toAbsolutePath().normalize();
        return !root.startsWith(normalized);
    }

    private static void removeMarker(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            log.error("The reset marker at {} could not be removed - delete it by hand", marker, e);
        }
    }
}
