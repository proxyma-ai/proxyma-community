package ai.proxyma.core.update;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.system.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the updater has done, kept across restarts.
 *
 * <p>A FILE, NOT AN ENTITY, and that is the whole design decision. A JPA entity would have to be
 * added to BOTH editions' datasource package lists - server's in {@code ServerDatabaseConfig},
 * desktop's in {@code LocalDataSourceConfig} - and missing one of them does not fail a compile, it
 * fails a BOOT, in one edition, at runtime. proxyma-core-guardrail avoided owning an entity for the
 * same reason. The use case here is "what happened to my install last month", which a bounded
 * append-only file answers exactly as well.
 *
 * <p>One JSON object per line, newest last, capped at {@link #MAX_ENTRIES}. Line-delimited rather
 * than one JSON array so an append is an append: a crash midway through writing leaves the previous
 * lines intact and loses at most the entry being written, where a rewritten array can be truncated
 * into something no parser will read - and losing the history because the app was killed while
 * recording that it had been killed would be a poor joke.
 *
 * <p>NOTHING HERE IS A SECRET, and it must stay that way. Versions, timestamps, an outcome and a
 * short message. No URLs with tokens in them, no paths outside the data root, no exception stack
 * that might carry either.
 */
@Component
@Profile("!server")
public class UpdateHistory {

    private static final Logger log = LoggerFactory.getLogger(UpdateHistory.class);

    /** Roughly two years of daily checks that found nothing, or a decade of real updates. */
    private static final int MAX_ENTRIES = 200;

    public enum Event {
        /** A check ran and the installed version was current. */
        CHECKED,
        /** A check ran and found something newer. */
        FOUND,
        /** A newer version was downloaded and staged, ready to apply. */
        DOWNLOADED,
        /** The user asked to apply a staged update; the process is about to exit. */
        APPLIED,
        /** Anything that went wrong, with a one-line reason. */
        FAILED,
        /** The user stopped a download before it finished; nothing was staged. */
        CANCELLED,
    }

    public record Entry(String at, String event, String fromVersion, String toVersion, String detail) {}

    private final AppPaths appPaths;

    public UpdateHistory(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    private Path file() {
        return appPaths.updateStagingDir().resolve("history.jsonl");
    }

    /**
     * Appends one entry. NEVER THROWS: this is called from the update path, and an updater that
     * fails because it could not write its own diary would trade a working feature for a record of
     * one. A failure to record is logged at debug and dropped.
     */
    public synchronized void record(Event event, String fromVersion, String toVersion, String detail) {
        // Applying and cancelling are only ever somebody's click; the rest is the scheduler's
        // unless the caller says otherwise.
        record(event, fromVersion, toVersion, detail, event == Event.APPLIED || event == Event.CANCELLED);
    }

    /**
     * As {@link #record(Event, String, String, String)}, saying whether a person started it - for a
     * download, which is either the Download button or auto-update.
     */
    public synchronized void record(Event event, String fromVersion, String toVersion, String detail,
                                    boolean byUser) {
        audit(event, fromVersion, toVersion, detail, byUser);
        // The event kind only, into the daily diagnostics: how often an update is found, downloaded,
        // applied or fails, across installations. The versions travel in the report header and
        // appVersions already; the detail is free text and stays in this local file.
        if (event != null) {
            ai.proxyma.core.common.diagnostics.Diagnostics.count(ai.proxyma.core.common.diagnostics.DiagCounter.UPDATE_EVENT,
                    "event", event.name().toLowerCase(java.util.Locale.ROOT));
        }
        try {
            Files.createDirectories(file().getParent());
            String line = "{"
                    + "\"at\":\"" + Instant.now() + "\","
                    + "\"event\":\"" + event.name() + "\","
                    + "\"fromVersion\":\"" + json(fromVersion) + "\","
                    + "\"toVersion\":\"" + json(toVersion) + "\","
                    + "\"detail\":\"" + json(detail) + "\"}\n";
            Files.writeString(file(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            trim();
        } catch (Exception e) {
            log.debug("Could not record update history: {}", e.getMessage());
        }
    }

    // The last FOUND or FAILED the audit log was given, until something else happens.
    private String lastAuditedRepeat;

    // The audit log's copy. Not CHECKED, which finds nothing every fifteen minutes and would bury
    // the rest - and not the same FOUND or FAILED again: with auto-update off every check finds the
    // same release, and an offline machine fails the same check, every fifteen minutes. One entry
    // says so. A person is filed as the local user: in place updates exist on Desktop alone.
    private void audit(Event event, String fromVersion, String toVersion, String detail, boolean byUser) {
        if (event == null || event == Event.CHECKED) return;
        if (event == Event.FOUND || event == Event.FAILED) {
            String key = event + "|" + toVersion + "|" + detail;
            if (key.equals(lastAuditedRepeat)) return;
            lastAuditedRepeat = key;
        } else {
            lastAuditedRepeat = null;
        }
        AuditEntry.Builder entry = AuditEntry.of(AuditCategory.UPDATES,
                        "update." + event.name().toLowerCase(java.util.Locale.ROOT))
                .outcome(event == Event.FAILED ? AuditEntry.FAILED : AuditEntry.OK)
                .target(toVersion == null || toVersion.isBlank() ? null : toVersion)
                .summary(switch (event) {
                    case FOUND -> "Update found: " + toVersion;
                    case DOWNLOADED -> "Update downloaded: " + toVersion;
                    case APPLIED -> "Update applied: " + toVersion;
                    case CANCELLED -> "Update download cancelled: " + toVersion;
                    default -> "Update failed";
                })
                .detail("from", fromVersion)
                .detail("to", toVersion == null || toVersion.isBlank() ? null : toVersion)
                .detail("detail", detail);
        if (byUser) entry.user(null);
        else entry.system();
        entry.record();
    }

    /** Newest first, because that is the order it is read in. Empty when nothing has happened. */
    public synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.exists(file())) return out;
            List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
            Collections.reverse(lines);
            for (String line : lines) {
                if (out.size() >= limit) break;
                if (line.isBlank()) continue;
                out.add(new Entry(
                        field(line, "at"), field(line, "event"),
                        field(line, "fromVersion"), field(line, "toVersion"), field(line, "detail")));
            }
        } catch (Exception e) {
            log.debug("Could not read update history: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Forgets every entry, at the user's request. Deletes the file rather than truncating it: the
     * next {@link #record} recreates it, and an empty file and a missing one read the same.
     */
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file());
    }

    /** Keeps the file bounded. A history that grows forever is a disk leak with a friendly name. */
    private void trim() throws IOException {
        List<String> lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        if (lines.size() <= MAX_ENTRIES) return;
        List<String> kept = lines.subList(lines.size() - MAX_ENTRIES, lines.size());
        Files.write(file(), kept, StandardCharsets.UTF_8);
    }

    /**
     * Enough escaping for the four values this writes, and no more.
     *
     * <p>These are a version string, an enum name and a message this class composes - not user
     * input - but a message can carry an exception's text, and one quotation mark in there would
     * produce a line no reader can parse. Escaping is cheaper than trusting that it never will.
     */
    private static String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private static String field(String line, String key) {
        String search = "\"" + key + "\":\"";
        int idx = line.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) { sb.append(line.charAt(++i)); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
