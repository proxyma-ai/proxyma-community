package ai.proxyma.it.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where everything is, and what this machine can actually run.
 *
 * <p>The suite drives artifacts rather than classes, so it has to find them on disk, and the four
 * apps are built independently - a developer who built only the desktop edition should be able to
 * run the desktop journeys without being told to build three more jars first.
 *
 * <h2>Missing prerequisites SKIP locally and FAIL in CI</h2>
 *
 * A skipped test is a lie told politely: it is green, it is counted, and it asserted nothing. That
 * is the right answer on a laptop where somebody has not started Docker, and the wrong answer in a
 * pipeline whose whole job is to run these. So {@link #require} consults {@link #ciMode()}: on a
 * developer machine an absent jar or a stopped Docker aborts the test with a message naming the
 * command to fix it, and on CI the same condition fails outright. {@code PROXYMA_IT_CI=1} is set
 * by {@code .gitlab/ci/functional.yml}; nothing else sets it.
 *
 * <p>This matters more here than it would in most projects, because a silent skip is exactly how
 * this repository has lost coverage before: a test in a module missing from the pipeline's
 * {@code -pl} list is a test nobody runs, and it reads as coverage. A suite that skips itself into
 * an all-green run is the same failure wearing a different hat.
 */
public final class Env {

    private Env() {}

    /** {@code PROXYMA_IT_CI=1} means a skip is a failure. Set only by the pipeline. */
    public static boolean ciMode() {
        return "1".equals(System.getenv("PROXYMA_IT_CI"));
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * The repository root, found by walking up from wherever the JVM was started looking for the
     * aggregator pom.
     *
     * <p>Not a relative path from {@code user.dir}: Maven runs this module with {@code user.dir}
     * at {@code tests/}, an IDE usually runs it at the repository root, and a run from a different
     * working directory should not silently resolve a jar path that does not exist.
     */
    public static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("CLAUDE.md")) && Files.isDirectory(candidate.resolve("apps"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find the repository root above " + System.getProperty("user.dir"));
    }

    // -- The four jars ---------------------------------------------------------

    /**
     * The runnable jar for an app, or empty when it has not been built.
     *
     * <p>Resolved by GLOB rather than by composing the version from a pom, because the version
     * lives in the pom and reading it here would be a second place that has to be kept in step -
     * the same duplication {@code tools/dev/common.bat} exists to avoid. Newest wins, so a stale
     * jar from a previous version never shadows the one just built.
     *
     * <p>Always the {@code -exec} classifier. The unclassified jar beside it is thin and
     * {@code java -jar} on it fails with "no main manifest attribute".
     */
    public static Optional<Path> jar(App app) {
        Path targetDir = repoRoot().resolve(app.modulePath).resolve("target");
        if (!Files.isDirectory(targetDir)) return Optional.empty();
        try (Stream<Path> files = Files.list(targetDir)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(app.artifactId + "-") && name.endsWith("-exec.jar");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The four things this suite can boot, and where each one's jar lands. */
    public enum App {
        DESKTOP("apps/app/backend/proxyma-desktop", "proxyma-desktop",
                "mvn -pl :proxyma-desktop -am package -DskipTests -Dskip.installer=true"),
        SERVER("ee/backend/proxyma-server", "proxyma-server",
                "mvn -pl :proxyma-server -am package -DskipTests -Pserver"),
        LICENSE("apps/license/backend", "proxyma-license",
                "mvn -pl :proxyma-license -am package -DskipTests"),
        TELEMETRY("apps/telemetry/backend", "proxyma-telemetry",
                "mvn -pl :proxyma-telemetry -am package -DskipTests");

        public final String modulePath;
        public final String artifactId;
        /** Printed verbatim when the jar is missing, so the fix is the message. */
        public final String buildCommand;

        App(String modulePath, String artifactId, String buildCommand) {
            this.modulePath = modulePath;
            this.artifactId = artifactId;
            this.buildCommand = buildCommand;
        }
    }

    /**
     * The jar for an app, or a skip/failure explaining how to build it.
     *
     * <p>Also asserts the SPA is actually inside it. A jar built with
     * {@code -Dskip.frontend.build=true} starts, serves an empty page and passes every API test in
     * this suite while being completely unusable - the exact artifact this project has shipped
     * before. Checking here, once, turns that into one clear failure rather than a browser test
     * that mysteriously cannot find a button.
     */
    public static Path requireJar(App app) {
        Path jar = jar(app).orElseGet(() -> {
            require(false, "no " + app.artifactId + " jar in " + app.modulePath + "/target."
                    + " Build it with: " + app.buildCommand);
            throw new AssertionError("unreachable");
        });
        if (app == App.DESKTOP || app == App.SERVER) {
            int spaEntries = Jars.countEntriesUnder(jar, "BOOT-INF/classes/static/");
            require(spaEntries > 2,
                    jar.getFileName() + " has only " + spaEntries + " entries under"
                    + " BOOT-INF/classes/static/, so the SPA is not in it - it was built with"
                    + " -Dskip.frontend.build=true and serves no UI. Rebuild without that flag:"
                    + " " + app.buildCommand);
        }
        return jar;
    }

    // -- Tools -----------------------------------------------------------------

    /**
     * The Node binary that runs the fake provider.
     *
     * <p>Prefers the copy {@code frontend-maven-plugin} drops in {@code apps/app/frontend/node}
     * during a backend build, because that one is guaranteed to exist on any machine that has
     * built this project even when nothing is on PATH - which is the normal state of the
     * maintainer's PC. Falls back to PATH.
     */
    public static String node() {
        Path provisioned = repoRoot().resolve("apps/app/frontend/node")
                .resolve(windows() ? "node.exe" : "node");
        if (Files.isExecutable(provisioned)) return provisioned.toString();
        return "node";
    }

    /** The JVM running this suite, used to launch every app jar. Never a bare {@code java}. */
    public static String java() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    /**
     * Whether Docker can be reached <b>by Testcontainers</b>. Cached; the probe is not free.
     *
     * <h2>Asked of Testcontainers, not of the docker CLI, and the difference is not theoretical</h2>
     *
     * The first draft shelled out to {@code docker info} - which is the right question on a
     * laptop, where the CLI working and Testcontainers working are the same fact. On the
     * production host they are NOT the same fact: the CLI is fine (every deploy job runs
     * {@code docker compose} through it) while Testcontainers' own discovery found nothing, so
     * this returned true, twelve container-backed tests skipped their skip and failed with
     * "Could not find a valid Docker environment" instead.
     *
     * <p>{@code DockerClientFactory.isDockerAvailable()} is the same discovery the containers
     * themselves will use, so a true here means the next {@code start()} works. Asking the thing
     * that will actually do the work beats asking something that merely looks like it.
     *
     * <p>It is also deliberately not {@code docker --version}, which answers on a machine where
     * Docker Desktop is installed and not running - the state that most often breaks these tests.
     */
    private static Boolean dockerAvailable;

    public static synchronized boolean dockerAvailable() {
        if (dockerAvailable == null) {
            try {
                dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable e) {
                // isDockerAvailable() is documented not to throw, and a NoClassDefFoundError from
                // a shaded dependency is not something a suite should die of while deciding
                // whether to skip.
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    public static void requireDocker(String whatFor) {
        require(dockerAvailable(), "Docker is not reachable by Testcontainers, and " + whatFor
                + ". Start Docker Desktop (PC) or the daemon (host) and run again."
                + " If the daemon IS running, the strategy chain is the thing to look at: it"
                + " aborts on the first strategy that THROWS rather than falling through, so a"
                + " crash in the Docker Desktop probe hides a perfectly good Unix socket."
                + " Setting DOCKER_HOST explicitly settles it.");
    }

    /**
     * Passes, skips or fails, per {@link #ciMode()}.
     *
     * <p>{@code org.opentest4j.TestAbortedException} is what JUnit reports as a skip; it is thrown
     * directly rather than through {@code Assumptions} so the same call site can raise either
     * outcome from one condition.
     */
    public static void require(boolean condition, String message) {
        if (condition) return;
        if (ciMode()) {
            throw new AssertionError("PROXYMA_IT_CI=1 so this cannot be skipped: " + message);
        }
        throw new org.opentest4j.TestAbortedException(message);
    }

    /**
     * A directory for one test run's data, under {@code tests/target/} so {@code mvn clean} takes
     * it and nothing lands in the system temp directory or - far worse - the real data root.
     */
    public static Path scratch(String name) {
        try {
            Path dir = repoRoot().resolve("tests/target/scratch")
                    .resolve(name + "-" + System.nanoTime());
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
