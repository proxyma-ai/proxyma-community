package ai.proxyma.it.harness;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * The two data stores the product cannot be asked to fake.
 *
 * <p>Everything else in this suite is either the real thing (H2, Lucene, the jar itself) or a
 * deliberate stand-in reached over its real protocol (the fake provider). Vectors are neither: the
 * vector store is chosen by Spring profile at boot, both implementations talk to a server process,
 * and the one bug this project has had in that layer was a CONFIGURATION disagreement - the tenant
 * and database constants in {@code ChromaStoreService} versus the ones {@code ChromaVectorStore}'s
 * builder defaults to - which no in-memory substitute could ever have reproduced, because the
 * substitute would have had one set of constants by construction.
 *
 * <p>So these are real servers, in containers, and the assertions about them are worth something.
 *
 * <h2>Started once, shared by every test that asks</h2>
 *
 * A container per test class would cost more than the app boots do. Both are held as singletons
 * and reused; Ryuk removes them when the JVM exits, however it exits. That last part is why this
 * uses Testcontainers rather than {@code docker run} - see the note in tests/pom.xml.
 */
public final class Stores {

    private Stores() {}

    /**
     * Overridable, because neither pin can be verified against what a customer runs.
     *
     * <p>The desktop installer and the Home Server Dockerfile both do {@code pip install chromadb}
     * with NO version pin, so an install made today carries whatever was current today, and this
     * image is a guess at the same thing. The pin buys reproducibility for the suite, not fidelity
     * to the field.
     *
     * <p><b>It was 0.6.3 and that was too old to be a guess at anything.</b> The app connected and
     * created its collections, then every upsert failed with
     * {@code Failed to initialize ChromaVectorStore: Collection <id> does not exist.: 400
     * InvalidCollection} - the tenant-and-database namespace behaving differently between the 0.6
     * line and the 1.x line the product actually runs against. Five journeys spent three minutes
     * each timing out on it (job 492). When that symptom returns, this pin is the first thing to
     * look at, and the override is here so it needs no code change.
     */
    private static final String CHROMA_IMAGE =
            System.getenv().getOrDefault("PROXYMA_IT_CHROMA_IMAGE", "chromadb/chroma:1.1.0");

    /** pgvector, not plain postgres: server mode creates the {@code vector} extension itself and a
     *  stock image has nothing to create. */
    private static final String POSTGRES_IMAGE =
            System.getenv().getOrDefault("PROXYMA_IT_POSTGRES_IMAGE", "pgvector/pgvector:pg16");

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> chroma;

    /** A PostgreSQL with pgvector, for server mode. Started on first use. */
    public static synchronized PostgreSQLContainer<?> postgres() {
        Env.requireDocker("server mode needs PostgreSQL with pgvector");
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("proxyma")
                    .withUsername("proxyma")
                    .withPassword("proxyma-it")
                    .withStartupTimeout(Duration.ofMinutes(3));
            postgres.start();
        }
        return postgres;
    }

    /**
     * The JDBC URL of a database named {@code name} in the shared container, created on first ask.
     *
     * <p>A second server instance needs a database of its own - two servers on one schema would
     * share accounts, settings and the license, and the unlicensed one would not be unlicensed. A
     * database per instance in ONE container rather than a container per instance: the cost of a
     * second PostgreSQL is a minute of startup, the cost of a second database is a statement.
     */
    public static synchronized String database(String name) {
        PostgreSQLContainer<?> pg = postgres();
        if (!"proxyma".equals(name) && CREATED.add(name)) {
            try {
                var result = pg.execInContainer("createdb", "-U", pg.getUsername(), name);
                if (result.getExitCode() != 0 && !result.getStderr().contains("already exists")) {
                    throw new IllegalStateException("createdb " + name + " failed: " + result.getStderr());
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + name;
    }

    private static final java.util.Set<String> CREATED = new java.util.HashSet<>();

    /**
     * A ChromaDB for desktop mode, which the app is told NOT to manage.
     *
     * <p>{@code proxyma.chromadb.managed=false} is a product property that already exists for
     * externally-managed instances, so pointing the desktop edition at this container needs no
     * test-only branch anywhere in the product. What it replaces is the bundled Python runtime,
     * which lives in an installed app's data directory and is several hundred megabytes that no
     * test should have to reproduce.
     */
    public static synchronized GenericContainer<?> chroma() {
        Env.requireDocker("desktop mode needs a ChromaDB to index into");
        if (chroma == null) {
            chroma = new GenericContainer<>(DockerImageName.parse(CHROMA_IMAGE))
                    .withExposedPorts(8000)
                    // The v2 heartbeat, which is the endpoint ChromaStoreService itself probes.
                    // Waiting on the same path the product waits on means the app never starts
                    // against a container that is listening but not yet answering.
                    .waitingFor(Wait.forHttp("/api/v2/heartbeat").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(3));
            chroma.start();
        }
        return chroma;
    }

    public static int chromaPort() { return chroma().getMappedPort(8000); }
}
