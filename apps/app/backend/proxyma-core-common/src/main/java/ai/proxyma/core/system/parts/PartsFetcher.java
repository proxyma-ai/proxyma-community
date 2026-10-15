package ai.proxyma.core.system.parts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads parts, checks each against the manifest's SHA-256, and installs it into the store.
 *
 * <p>Built for a slow or flaky connection, because a first start downloads several hundred
 * megabytes on whatever network the user has:
 * <ul>
 *   <li><b>Resumes.</b> A download that stops is kept as {@code .download/<file>.part} and continued
 *       with a Range request - on the next attempt, or the next start of Proxyma.</li>
 *   <li><b>Retries</b> a few times with a growing pause before giving up.</li>
 *   <li><b>Notices a stall.</b> The JDK's client has no read timeout for a body, so a connection
 *       that stops sending would wait forever; a watchdog closes it after a minute without a byte.</li>
 * </ul>
 *
 * <p>THE DIGEST IS CHECKED BEFORE ANYTHING IS EXTRACTED. Extracting first would write
 * attacker-chosen paths before deciding whether to trust the archive at all.
 */
public final class PartsFetcher {

    private static final Logger log = LoggerFactory.getLogger(PartsFetcher.class);

    private static final int ATTEMPTS = 5;
    private static final long STALL_MILLIS = 60_000;

    /** Progress across a whole install: bytes of all the parts being fetched, not of one. */
    @FunctionalInterface
    public interface Progress {
        void update(PartsManifest.Part part, long doneBytes, long totalBytes);
    }

    /** Thrown when the caller asked to stop. */
    public static final class Cancelled extends IOException {
        public Cancelled() { super("cancelled"); }
    }

    private final PartsStore store;
    private final PartsTransport transport;
    /** The first pause between attempts, doubling after each. A field so a test need not wait. */
    long retryPauseMillis = 2_000;

    public PartsFetcher(PartsStore store, PartsTransport transport) {
        this.store = store;
        this.transport = transport;
    }

    /** Downloads and installs every part of {@code manifest} that is not installed yet. */
    public void installMissing(PartsManifest manifest, Progress progress, BooleanSupplier cancelled) throws IOException {
        List<PartsManifest.Part> missing = store.missing(manifest);
        if (missing.isEmpty()) return;
        if (manifest.base() == null) throw new IOException("the parts manifest names no download location");
        long total = missing.stream().mapToLong(PartsManifest.Part::size).sum();
        long before = 0;
        for (PartsManifest.Part part : missing) {
            final long offset = before;
            Path archive = download(manifest, part, done -> progress.update(part, offset + done, total), cancelled);
            if (cancelled.getAsBoolean()) throw new Cancelled();
            install(part, archive);
            before += part.size();
            progress.update(part, before, total);
        }
    }

    private Path download(PartsManifest manifest, PartsManifest.Part part, java.util.function.LongConsumer done,
                          BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(store.downloads());
        Path file = store.downloads().resolve(part.file() + ".part");
        URI url = URI.create(manifest.base() + part.file());
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (cancelled.getAsBoolean()) throw new Cancelled();
            try {
                long have = Files.isRegularFile(file) ? Files.size(file) : 0;
                if (have > part.size()) { Files.delete(file); have = 0; }
                if (have < part.size()) fetchInto(url, file, have, done, cancelled);
                done.accept(Files.size(file));
                if (Files.size(file) != part.size()) {
                    throw new IOException("got " + Files.size(file) + " of " + part.size() + " bytes");
                }
                String actual = sha256(file);
                if (!actual.equals(part.sha256())) {
                    // Bad bytes are never resumed from - the next attempt starts clean.
                    Files.deleteIfExists(file);
                    throw new IOException(part.file() + " failed its SHA-256 check");
                }
                return file;
            } catch (Cancelled e) {
                throw e;
            } catch (IOException e) {
                last = e;
                log.warn("Downloading {} (attempt {}/{}) failed: {}", part.file(), attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS) sleep(Math.min(30_000, retryPauseMillis << (attempt - 1)), cancelled);
            }
        }
        throw new IOException("Could not download " + part.name() + ": " + (last == null ? "unknown error" : last.getMessage()), last);
    }

    private void fetchInto(URI url, Path file, long have, java.util.function.LongConsumer done,
                           BooleanSupplier cancelled) throws IOException {
        transport.get(url, have, (status, length, body) -> {
            boolean append;
            if (status == 206 && have > 0) append = true;
            else if (status == 200) append = false;   // the server ignored the Range: start over
            else throw new IOException("HTTP " + status + " for " + url);
            AtomicLong lastByte = new AtomicLong(System.currentTimeMillis());
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parts-stall-watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleAtFixedRate(() -> {
                if (System.currentTimeMillis() - lastByte.get() > STALL_MILLIS || cancelled.getAsBoolean()) {
                    try { body.close(); } catch (IOException ignored) {}
                }
            }, 5, 5, TimeUnit.SECONDS);
            try (OutputStream out = append
                    ? Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(file)) {
                long written = append ? have : 0;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = body.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new Cancelled();
                    out.write(buffer, 0, n);
                    written += n;
                    lastByte.set(System.currentTimeMillis());
                    done.accept(written);
                }
            } catch (IOException e) {
                if (cancelled.getAsBoolean()) throw new Cancelled();
                throw e;
            } finally {
                watchdog.shutdownNow();
            }
            return null;
        });
    }

    /** Extracts a verified archive beside its final place, then renames it in. */
    private void install(PartsManifest.Part part, Path archive) throws IOException {
        Path target = store.dir(part);
        Path tmp = target.resolveSibling(part.id() + ".tmp");
        PartsStore.deleteRecursively(tmp);
        Files.createDirectories(tmp);
        String name = part.file();
        if (name.endsWith(".zip")) {
            unzip(archive, tmp);
        } else if (name.endsWith(".tar.gz")) {
            untar(archive, tmp);
        } else {
            throw new IOException("unknown archive type: " + name);
        }
        try (var entries = Files.list(tmp)) {
            if (entries.findAny().isEmpty()) throw new IOException(name + " extracted to nothing");
        }
        PartsStore.deleteRecursively(target);
        Files.move(tmp, target);
        Files.deleteIfExists(archive);
        log.info("Installed part {} {}", part.name(), part.id());
    }

    // Entry paths are resolved and re-checked against the destination: an entry named ..\..\x
    // would otherwise write outside it.
    public static void unzip(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Refusing to extract '" + entry.getName() + "' - it resolves outside " + root);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // The system tar, for the executable bits java.util.zip cannot carry. Every Linux that can run a
    // desktop has it. GNU tar refuses absolute names and strips leading "../" by default.
    private static void untar(Path archive, Path dest) throws IOException {
        Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        try {
            if (p.waitFor() != 0) throw new IOException("tar failed: " + output.strip());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while extracting " + archive.getFileName());
        }
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis, BooleanSupplier cancelled) throws IOException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cancelled.getAsBoolean()) throw new Cancelled();
            try {
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
        }
    }
}
