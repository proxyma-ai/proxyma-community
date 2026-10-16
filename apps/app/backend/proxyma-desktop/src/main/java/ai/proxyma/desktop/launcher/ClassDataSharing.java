package ai.proxyma.desktop.launcher;

import ai.proxyma.core.system.parts.PartsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class data sharing for the application JVM: the classes a start loads, parsed and verified once
 * and mapped from an archive at every start after that, instead of read out of the jars again.
 *
 * <p>WHY. The 0.36.0 feedback: "the app restarting usually takes more than 15s and sometimes over a
 * minute". Measured 2026-10-16 on the PC, the same build and data: 10.8 seconds from the JVM's start
 * to the application answering /api/v1/health/ready, 9.8 once the jar is extracted, 6.9 with the
 * archive - a third of every start, and the work it removes (finding, reading, parsing and verifying
 * some 22,000 classes) is the work a slow, antivirus-laden laptop is slowest at; the maintainer's
 * starts there were 77 and 88 seconds.
 *
 * <p>HOW, one step per start of a release, each in the background once the application answers -
 * nothing here is ever on the way to a start:
 * <ol>
 *   <li>{@link Stage#PACKED} - no extracted copy yet: the application runs from its part's jar as it
 *       always did, and afterwards the jar is extracted ({@code -Djarmode=tools extract}, Spring Boot's
 *       own layout: a thin jar beside {@code lib/}), then read through once ({@link #readThrough}).
 *       An archive can only hold classes the JVM's own class loader read from a jar on the class
 *       path, never from a jar nested in another.</li>
 *   <li>{@link Stage#RECORDING} - the application runs from the extracted copy and the JVM writes the
 *       name of every class it loads ({@code -XX:DumpLoadedClassList}, which costs nothing measurable).
 *       {@link #RECORD_FOR} after it answers, that list is taken and a separate JVM builds the archive
 *       from it ({@code -Xshare:dump}) - a process that loads classes and runs none of the
 *       application, so it touches no database, port or setting. 69 seconds on the PC.</li>
 *   <li>{@link Stage#SHARED} - every start after that maps the archive.</li>
 * </ol>
 *
 * <p>NOT THE JVM'S OWN "ARCHIVE AT EXIT" ({@code -XX:ArchiveClassesAtExit}, or
 * {@code -XX:+AutoCreateSharedArchive}), which needs no list and no second JVM: the start it records
 * runs in a mode that made it five times slower on the PC (60 seconds against 11), and that start
 * would be the user's.
 *
 * <p>NEVER A REASON NOT TO START. Everything is keyed by the app part's id, so an update starts the
 * three steps again for the new release, and the directories of every other release are deleted. A
 * start from the extracted copy that dies before answering deletes this release's directory and is
 * made again from the packed jar (ProxymaLauncher). The
 * archive is used only by the JVM that made it ({@link #ARCHIVE_JVM}): another one would refuse it and
 * run with no sharing at all, slower than without the option. A missing, corrupt or stale archive is
 * ignored by the JVM itself under {@code -Xshare:auto} - checked by truncating one. A dump that fails
 * is not tried again by the same JVM ({@link #FAILED}); the application then runs from the extracted
 * copy, which is still faster than the packed jar. Anything else that goes wrong here is logged and
 * leaves the application starting the way it did before this existed.
 *
 * <p>THE PATHS ARE THE PLATFORM'S OWN, never rewritten. The archive records the class path it was
 * made with, and the application has to be started with the same string: measured with
 * {@code C:/...} for a jar that the archive knew as {@code C:\...}, none of the application's classes
 * were taken ("Unsupported location"), only the JDK's.
 *
 * <p>The cost is disk: the extracted copy is as large as the app part's jar (about 350 MB), once, for
 * the active release only.
 */
final class ClassDataSharing {

    private static final Logger log = LoggerFactory.getLogger(ClassDataSharing.class);

    /** Under the data root, one directory per app part, named by the part's id. Survives a reset. */
    static final String DIR = "class-data";

    static final String EXTRACTED = "app";
    /** Written last, once the extracted copy is complete. */
    static final String EXTRACTED_MARKER = "extracted";
    /** The list the running application writes. */
    static final String RECORDING = "classes.recording";
    /** The copy of it the archive is made from. */
    static final String CLASS_LIST = "classes.lst";
    static final String ARCHIVE = "app.jsa";
    /** The JVM that made the archive. */
    static final String ARCHIVE_JVM = "app.jsa.jvm";
    /** The JVM that could not make one. */
    static final String FAILED = "app.jsa.failed";

    /**
     * How long the application has run when its list is taken: every class of the start is in it by
     * the time the application answers, and this adds what the window's first pages load.
     */
    static final Duration RECORD_FOR = Duration.ofSeconds(30);

    private static final long EXTRACT_TIMEOUT_MINUTES = 10;
    private static final long DUMP_TIMEOUT_MINUTES = 30;

    /** One job at a time: a restart while a dump runs must not start a second one. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private ClassDataSharing() {}

    enum Stage { PACKED, RECORDING, SHARED, EXTRACTED }

    /**
     * How to start the application of one release.
     *
     * @param dir        this release's directory under {@link #DIR}
     * @param jar        the jar to run
     * @param jvmOptions placed before {@code -jar}
     */
    record Launch(Stage stage, Path dir, Path packedJar, Path jar, List<String> jvmOptions) {}

    /** This release's directory: the app part's id under {@link #DIR}. */
    static Path dirFor(Path dataRoot, Path packedJar) {
        return dataRoot.resolve(DIR).resolve(packedJar.getParent().getFileName().toString());
    }

    /** Which jar and options this start uses. Never throws: anything unexpected is a packed start. */
    static Launch plan(Path dir, Path packedJar, String jvm) {
        try {
            Path thin = dir.resolve(EXTRACTED).resolve(packedJar.getFileName().toString());
            if (!Files.isRegularFile(dir.resolve(EXTRACTED_MARKER)) || !Files.isRegularFile(thin)) {
                return new Launch(Stage.PACKED, dir, packedJar, packedJar, List.of());
            }
            Path archive = dir.resolve(ARCHIVE);
            if (Files.isRegularFile(archive) && jvm.equals(read(dir.resolve(ARCHIVE_JVM)))) {
                return new Launch(Stage.SHARED, dir, packedJar, thin,
                        List.of("-XX:SharedArchiveFile=" + archive, "-Xshare:auto"));
            }
            if (jvm.equals(read(dir.resolve(FAILED)))) {
                return new Launch(Stage.EXTRACTED, dir, packedJar, thin, List.of());
            }
            return new Launch(Stage.RECORDING, dir, packedJar, thin,
                    List.of("-XX:DumpLoadedClassList=" + dir.resolve(RECORDING)));
        } catch (Exception e) {
            log.debug("Class data sharing not planned ({}) - starting from the packed jar", e.getMessage());
            return new Launch(Stage.PACKED, dir, packedJar, packedJar, List.of());
        }
    }

    /**
     * The next step for this release, on a thread of its own, once the application answers. Returns
     * at once.
     */
    static void prepareInBackground(Launch launch, Process app, String java, String jvm) {
        if (launch == null || launch.stage() == Stage.SHARED || launch.stage() == Stage.EXTRACTED) return;
        if (!BUSY.compareAndSet(false, true)) return;
        Thread job = new Thread(() -> {
            try {
                prepare(launch, app, java, jvm);
            } catch (Exception e) {
                log.warn("Class data sharing: {} - starts stay as they are", e.getMessage());
            } finally {
                BUSY.set(false);
            }
        }, "class-data-sharing");
        job.setDaemon(true);
        job.start();
    }

    static void prepare(Launch launch, Process app, String java, String jvm) throws Exception {
        deleteOtherReleases(launch.dir());
        if (launch.stage() == Stage.PACKED) {
            extract(launch, java);
        } else if (launch.stage() == Stage.RECORDING) {
            // An application that stopped meanwhile is a restart under way, or a quit: a dump now
            // would compete with the next start, and the next start records again anyway.
            if (app != null && app.waitFor(RECORD_FOR.toMillis(), TimeUnit.MILLISECONDS)) return;
            // A job of an earlier start may have made it since this start was planned.
            if (plan(launch.dir(), launch.packedJar(), jvm).stage() != Stage.RECORDING) return;
            dump(launch, java, jvm);
        }
    }

    /** Deletes this release's copy and archive, for a start from them that failed. */
    static void discard(Launch launch) {
        delete(launch.dir());
    }

    /**
     * Deletes a file or a tree, read-only files included.
     *
     * <p>THE JVM WRITES ITS ARCHIVE READ-ONLY, and Windows refuses to delete a read-only file - so
     * a plain recursive delete left every old release's 150 MB archive behind, and the directory
     * holding it (found 2026-10-16, the first time a second release came along).
     */
    static void delete(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.filter(Files::isRegularFile).forEach(f -> f.toFile().setWritable(true));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not list {}: {}", path, e.getMessage());
        }
        PartsStore.deleteRecursively(path);
    }

    /** The directories of every release but this one: an update leaves the old copy behind. */
    static void deleteOtherReleases(Path dir) {
        Path parent = dir.getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        try (var entries = Files.list(parent)) {
            for (Path other : entries.filter(p -> !p.getFileName().equals(dir.getFileName())).toList()) {
                log.info("Class data sharing: removing {}, left by another release", other.getFileName());
                delete(other);
            }
        } catch (IOException e) {
            log.debug("Could not list {}: {}", parent, e.getMessage());
        }
    }

    private static void extract(Launch launch, String java) throws Exception {
        Path dir = launch.dir();
        // Everything, the archive included: an archive names the jars it was made from by their
        // size and time, and a new copy's are not those.
        delete(dir);
        Files.createDirectories(dir);
        Path target = dir.resolve(EXTRACTED);
        Path partial = dir.resolve(EXTRACTED + ".partial");
        if (Files.exists(dir.resolve(ARCHIVE))) throw new IOException("could not clear " + dir);

        long started = System.nanoTime();
        int rc = run(List.of(java, "-Djarmode=tools", "-jar", launch.packedJar().toString(),
                "extract", "--destination", partial.toString()), dir.resolve("extract.log"), EXTRACT_TIMEOUT_MINUTES);
        Path thin = partial.resolve(launch.packedJar().getFileName().toString());
        if (rc != 0 || !Files.isRegularFile(thin) || !Files.isDirectory(partial.resolve("lib"))) {
            delete(partial);
            throw new IOException("extracting the application failed (exit " + rc + ", see " + dir.resolve("extract.log") + ")");
        }
        Files.move(partial, target);
        readThrough(target);
        Files.writeString(dir.resolve(EXTRACTED_MARKER), "", StandardCharsets.US_ASCII);
        log.info("Class data sharing: the application is extracted ({} ms) - the next start records its classes",
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static void dump(Launch launch, String java, String jvm) throws Exception {
        Path dir = launch.dir();
        Path list = dir.resolve(CLASS_LIST);
        if (!snapshot(dir.resolve(RECORDING), list)) {
            throw new IOException("the application recorded no classes at " + dir.resolve(RECORDING));
        }
        Path partial = dir.resolve(ARCHIVE + ".partial");
        delete(partial);

        long started = System.nanoTime();
        int rc = run(List.of(java, "-Xshare:dump",
                "-XX:SharedClassListFile=" + list,
                "-XX:SharedArchiveFile=" + partial,
                // The same string the application is started with - see the class comment.
                "-cp", launch.jar().toString()), dir.resolve("dump.log"), DUMP_TIMEOUT_MINUTES);
        if (rc != 0 || !Files.isRegularFile(partial) || Files.size(partial) == 0) {
            delete(partial);
            Files.writeString(dir.resolve(FAILED), jvm, StandardCharsets.UTF_8);
            throw new IOException("making the archive failed (exit " + rc + ", see " + dir.resolve("dump.log")
                    + ") - not tried again by this Java runtime");
        }
        delete(dir.resolve(ARCHIVE));
        Files.move(partial, dir.resolve(ARCHIVE), StandardCopyOption.REPLACE_EXISTING);
        readThrough(dir.resolve(ARCHIVE));
        Files.writeString(dir.resolve(ARCHIVE_JVM), jvm, StandardCharsets.UTF_8);
        Files.deleteIfExists(dir.resolve(FAILED));
        log.info("Class data sharing: archive of {} classes made ({} ms) - used from the next start",
                countClasses(list), Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /**
     * Reads every byte of what was just written, once, here in the background.
     *
     * <p>THE FIRST START FROM NEW FILES PAID FOR THEM. Measured 2026-10-16 on the PC, Windows Defender
     * on: a start from a copy extracted a moment before took 5.1 seconds before Spring began, against
     * 0.4 from one whose files had been read through once after extracting - the antivirus examines a
     * file the first time it is opened, and the start opened 254 of them. Reading them took 4.9
     * seconds, here, where nobody waits for it.
     */
    static void readThrough(Path path) {
        byte[] buffer = new byte[1 << 16];
        try (var files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try (var in = Files.newInputStream(file)) {
                    while (in.read(buffer) >= 0) {
                        // Only the reading matters.
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not read through {}: {}", path, e.getMessage());
        }
    }

    /**
     * Copies the list the application is still writing, up to its last complete line - a class name
     * cut in half would name a class that does not exist.
     *
     * @return false when there is nothing to copy
     */
    static boolean snapshot(Path recording, Path list) throws IOException {
        if (!Files.isRegularFile(recording)) return false;
        byte[] bytes = Files.readAllBytes(recording);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') end--;
        if (end == 0) return false;
        byte[] complete = new byte[end];
        System.arraycopy(bytes, 0, complete, 0, end);
        Files.write(list, complete);
        return true;
    }

    private static long countClasses(Path list) {
        try (var lines = Files.lines(list, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("@")).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Runs a JVM to completion, its output to {@code output}; ended with the launcher if it outlives it. */
    private static int run(List<String> command, Path output, long timeoutMinutes) throws Exception {
        Process p = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        Thread stop = new Thread(p::destroyForcibly, "class-data-sharing-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                p.waitFor(30, TimeUnit.SECONDS);
                return -1;
            }
            return p.exitValue();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException alreadyExiting) {
                // The hook is running or about to.
            }
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
