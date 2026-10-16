package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A running Proxyma, booted from the jar a customer would install, with a fake model behind it.
 *
 * <h2>Out of process, on purpose</h2>
 *
 * It would be less code to start Spring in the test JVM. That would also test something other than
 * the product. The jar is the artifact: {@code frontend-maven-plugin} bakes the SPA into it,
 * {@code SpaWebConfig} serves it from the classpath, the launcher sets properties on a command
 * line, and the whole desktop/server split is two jars rather than two bean graphs. A suite
 * holding the classes directly proves none of that, and would keep passing against a jar that
 * serves a blank page - which this project has built before.
 *
 * <h2>Isolation, and the three traps that make it fake</h2>
 *
 * An instance that writes into the real installation is worse than no test: it corrupts the data
 * of whoever ran the suite. {@code CLAUDE.md} records that this has already happened once - a
 * "scratch" run that started ChromaDB against the REAL vector store and ran a sync through it. All
 * three known traps are closed here, and none of them is obvious:
 *
 * <ol>
 *   <li><b>{@code --proxyma.data.dir} does not redirect the data root, despite its name.</b>
 *       {@code AppPaths} reads {@code LOCALAPPDATA} / {@code XDG_DATA_HOME} / {@code HOME} from the
 *       ENVIRONMENT and never looks at the property. Passing it moves the H2 URLs and nothing
 *       else, leaving ChromaDB, Lucene, conversations, markdown, temp, logs and the credential key
 *       on the real install. So this sets the environment, and passes the property too.
 *   <li><b>Skills, workflows and conversations escape anyway.</b> All three default to
 *       {@code ${user.home}/.proxyma/...}, which no data-root setting touches, so two "isolated"
 *       instances share them with each other AND with the installed app. All three are overridden
 *       here - including {@code proxyma.conversations-dir}, which {@code tools/dev/run.bat}
 *       still misses.
 *   <li><b>Ports collide.</b> A second instance on 4246 or 8000 talks to the installed app's
 *       ChromaDB. Every port here is allocated free, per instance.
 * </ol>
 *
 * <h2>It can never phone home</h2>
 *
 * Telemetry and the update check are blanked, not redirected. An empty telemetry URL is a
 * supported configuration meaning "send nothing anywhere", and an empty releases URL makes
 * UpdateService report up-to-date and stop. A suite that ran a hundred times a day against the
 * real endpoints would be indistinguishable from a broken installation flooding them.
 */
public final class ProxymaApp implements AutoCloseable {

    /**
     * The three ways the product is deployed.
     *
     * <p>HOME_SERVER is the DESKTOP jar, not a separate build: it is that jar with uploads switched
     * on and the home-server flag set (what {@code deploy-homeserver}'s Dockerfile does). Testing it
     * is therefore a matter of booting the same artifact the way that deployment boots it.
     */
    public enum Mode { DESKTOP, SERVER, HOME_SERVER }

    /**
     * What first-run setup asks for on every server this suite boots. A real install prints a
     * generated one in its log; PROXYMA_SETUP_TOKEN chooses it, which is what an automated install
     * does and what this suite does.
     */
    public static final String SETUP_TOKEN = "it-setup-token";

    private final Mode mode;
    // Replaced by restart(), with the command and environment it was started with.
    private volatile ManagedProcess process;
    private volatile List<String> command;
    private volatile Map<String, String> env;
    private final Http api;
    private final FakeLlm llm;
    private final Path dataDir;
    private final int port;

    private ProxymaApp(Mode mode, ManagedProcess process, Http api, FakeLlm llm, Path dataDir, int port) {
        this.mode = mode;
        this.process = process;
        this.api = api;
        this.llm = llm;
        this.dataDir = dataDir;
        this.port = port;
    }

    public Mode mode() { return mode; }
    public Http api() { return api; }
    public FakeLlm llm() { return llm; }
    public Path dataDir() { return dataDir; }
    public int port() { return port; }
    public String baseUrl() { return "http://127.0.0.1:" + port; }
    public ManagedProcess process() { return process; }

    /**
     * Stops the process, unless the product has already ended it - POST /api/v1/system/restart
     * exits for the launcher or the supervisor to start it again - and starts the same command
     * again: the same port, data and fake provider. Whatever the old process held only in memory
     * is gone, as after a restart from Settings, an update or a crash; whatever it stored is not.
     */
    public void restart() {
        process.stop();
        process = ManagedProcess.start(mode.name().toLowerCase(java.util.Locale.ROOT), command, dataDir, env);
        process.awaitReady("HTTP on port " + port + " again", () -> api.healthy("/api/v1/health"),
                Duration.ofMinutes(2));
    }

    /**
     * The access code of a Desktop or Home Server instance, or null on Enterprise Server.
     *
     * <p>Every API call of the single-user editions needs it (AccessGate), as the cookie a handshake
     * sets or as the install's token. The suite chooses it - PROXYMA_ACCESS_CODE, the same variable
     * a Home Server operator sets - so no test has to read it out of the data directory.
     */
    public String accessCode() { return accessCode; }

    /**
     * A second client against the same app - a different browser, with its own cookie jar. On the
     * single-user editions it has presented the access code, as a browser does on the access screen.
     */
    public Http newClient() {
        Http client = new Http(baseUrl());
        if (accessCode != null) enter(client, accessCode);
        return client;
    }

    // POST /api/v1/access, which sets the access cookie in the client's jar.
    private static void enter(Http client, String code) {
        Http.Response response = client.post("/api/v1/access", Map.of("code", code));
        if (response.status() != 204) {
            throw new AssertionError("the access code was refused: " + response.status() + " " + response.body());
        }
    }

    // -- Shared instances ------------------------------------------------------

    private static final Map<Mode, ProxymaApp> SHARED = new ConcurrentHashMap<>();

    /**
     * The instance every test class shares, booted on first use and kept for the JVM's lifetime.
     *
     * <p>Booting Proxyma takes tens of seconds and the suite has many test classes, so a fresh
     * instance per class would dominate the run time and buy very little: these journeys create
     * their own connectors, conversations and settings, and clean up after themselves.
     *
     * <p>A test that genuinely needs a virgin installation - first-run onboarding, the setup
     * wizard, a migration - calls {@link #bootDesktop} or {@link #bootServer} for one of its own
     * and closes it. That is the exception, and it should say in a comment why it is one.
     */
    public static ProxymaApp shared(Mode mode) {
        return SHARED.computeIfAbsent(mode, m -> {
            ProxymaApp app = switch (m) {
                case DESKTOP -> bootDesktop();
                case SERVER -> bootServer();
                case HOME_SERVER -> bootHomeServer();
            };
            // Stopped on JVM exit rather than by any test, since no test owns it. Failsafe runs
            // the whole module in one reused fork, so this fires once at the end of the suite.
            Runtime.getRuntime().addShutdownHook(new Thread(app::close));
            return app;
        });
    }

    // -- Booting ---------------------------------------------------------------

    /**
     * A desktop edition against its own H2 files, and a containerized ChromaDB when there is one.
     *
     * <p><b>Docker is not required to boot.</b> Most of what a user does - settings, providers,
     * chat, guardrails, cost, the whole UI - never touches the vector store, and making all of it
     * unrunnable on a laptop with Docker stopped would mean it mostly did not get run. So a
     * missing Docker costs the retrieval journeys and nothing else; they ask for it themselves
     * through {@link #requireVectorStore}.
     *
     * <p>The cost of booting without one is real and is paid once: {@code ChromaStoreService}
     * spends its full 60-second startup timeout probing a port nothing will answer, logs a
     * warning, and carries on with search and indexing unavailable. That is the product's own
     * documented behavior for an unreachable externally-managed instance, not a test path.
     */
    public static ProxymaApp bootDesktop() {
        return bootDesktopJar(Mode.DESKTOP, "desktop", List.of(), Map.of());
    }

    /**
     * The Home Server edition: the desktop jar booted the way {@code deploy-homeserver}'s
     * Dockerfile boots it - uploads on, the home-server flag set, and an upload root of its own.
     *
     * <p>Community, like desktop: no license, and no license code at all (since 2026-10-14).
     *
     * <p>{@code proxyma.home-server} itself only changes what the instance REPORTS; everything that
     * behaves differently hangs off {@code proxyma.file-upload.enabled} - sources become uploads,
     * the desktop folder picker disappears, and registering a folder is confined to the upload
     * root. Both are set because a customer's install sets both.
     */
    public static ProxymaApp bootHomeServer() {
        return bootDesktopJar(Mode.HOME_SERVER, "homeserver", List.of(
                "-Dproxyma.home-server=true",
                "-Dproxyma.file-upload.enabled=true",
                "-Dproxyma.storage.local.root={data}/storage"), Map.of());
    }

    /**
     * A desktop of its own, for the journeys that remove every source after each test - the
     * shared desktop's sources belong to every other desktop journey.
     */
    public static ProxymaApp bootDesktopOwn() {
        return bootDesktopOwn("desktop-community");
    }

    /** A desktop of its own under a scratch directory of its own, for a journey that restarts it. */
    public static ProxymaApp bootDesktopOwn(String name) {
        return bootDesktopJar(Mode.DESKTOP, name, List.of(), Map.of());
    }

    /**
     * Boots the desktop jar as one of the editions built from it.
     *
     * <p>NO LICENSOR, since 2026-10-14. Desktop and the Home Server are Community - every
     * connector, no limits - and load no license code at all, so there is nothing to license and
     * nothing to trust; they are started with {@code java -jar}, exactly as a customer starts them.
     *
     * @param extraProperties added after the common ones; {@code {data}} is replaced by the data root
     */
    private static ProxymaApp bootDesktopJar(Mode mode, String name, List<String> extraProperties,
                                             Map<String, String> extraEnv) {
        Path jar = Env.requireJar(Env.App.DESKTOP);
        boolean withChroma = Env.dockerAvailable();
        int chromaPort = withChroma ? Stores.chromaPort() : freePort();
        Path scratch = Env.scratch(name);
        Path home = scratch.resolve("home");

        // THE DATA ROOT IS <LOCALAPPDATA>/proxyma, NOT <LOCALAPPDATA>, and getting that wrong
        // SPLITS the installation in two rather than failing.
        //
        // Measured, not assumed. Setting LOCALAPPDATA=<scratch> and also passing
        // -Dproxyma.data.dir=<scratch> put the ten H2 databases directly in <scratch> - the
        // property is read by application-desktop.properties for the JDBC URLs - while AppPaths,
        // which appends "proxyma" to LOCALAPPDATA and never reads that property, put
        // .secret.dpapi, chromadb/, lucene/, conversations/, markdown/, temp/ and
        // update-staging/ in <scratch>/proxyma. One instance, two roots, no error.
        //
        // So the property is NOT passed at all. ProxymaDesktopApplication.main derives it from
        // AppPaths when it is absent, which is exactly what a real installation does, and leaves
        // one root that every subsystem agrees on.
        Path data = scratch.resolve("proxyma");
        mkdirs(home, data);

        Map<String, String> env = new HashMap<>(isolatedEnvironment(scratch, home));
        String accessCode = "suite-" + UUID.randomUUID();
        env.put("PROXYMA_ACCESS_CODE", accessCode);
        List<String> properties = new ArrayList<>(commonProperties(data));
        properties.add("-Dproxyma.chromadb.port=" + chromaPort);
        // The product already supports an externally-managed ChromaDB. Without this the app would
        // hunt for the bundled Python interpreter, fail to find one in a scratch data directory,
        // and then spend the full 60-second startup timeout probing a port nothing was ever going
        // to answer on.
        properties.add("-Dproxyma.chromadb.managed=false");
        properties.add("-Dproxyma.desktop.open-browser=false");
        properties.add("-Dproxyma.desktop.system-tray=false");
        for (String property : extraProperties) properties.add(property.replace("{data}", data.toString()));
        env.putAll(extraEnv);

        ProxymaApp app = boot(mode, jar, data, env, properties);
        app.accessCode = accessCode;
        enter(app.api, accessCode);
        app.vectorStore = withChroma;
        return app;
    }

    private volatile ServiceApp licensor;

    /** The suite's license service this server trusts and activates against; null on the single-user editions. */
    public ServiceApp licensor() { return licensor; }

    /**
     * Issues a fresh Enterprise license from the suite's licensor and activates it through the
     * product's own endpoint, as {@code caller}. Returns the activation response unasserted, so a
     * test can assert a REFUSAL as readily as a success.
     */
    public Http.Response activateEnterprise(Http caller) {
        Env.require(licensor != null, "this instance has no licensor to issue a license from."
                + " Start Docker - the license service needs PostgreSQL.");
        var issued = DevLicense.issueEnterprise(licensor, licensorAdmin(licensor));
        return caller.post("/api/v1/license/activate", Map.of("licenseKey", issued.licenseKey()));
    }

    private static final Map<ServiceApp, Http> LICENSOR_ADMINS = new ConcurrentHashMap<>();

    /**
     * ONE signed-in license-service admin per run, on an address of its own, patient with a 429.
     * Public so a journey that issues from the console - a key bound to a deployment code - spends
     * the same allowance rather than a sign-in of its own.
     *
     * <p>The license service allows twenty admin requests a minute per address, and every license
     * this suite issued cost two - a fresh sign-in, then the issue. The tier journeys issue a dozen
     * in a minute, which spent the allowance LicenseJourneyIT then needed and failed it with 429s.
     * The limiter is right; the harness was wasteful. Signing in once halves the cost, its own
     * address keeps it out of the journeys' bucket, and patience covers whatever is left.
     */
    public static Http licensorAdmin(ServiceApp licensor) {
        return LICENSOR_ADMINS.computeIfAbsent(licensor, l ->
                l.signInAsAdmin("it-admin", "it-password-123").forwardedFor("10.98.0.1").patient());
    }

    /**
     * Makes this instance trust the suite's own license issuer instead of the production one, by
     * shadowing the classpath resource the build reads its trusted key from.
     *
     * <h2>Why it has to be done this way</h2>
     *
     * <b>There is no configuration that adds a trusted issuer, in either edition.</b>
     * {@code LicenseActivationService} injects {@code LicenseJwtVerifier}, which verifies
     * offline against {@code EmbeddedLicenseKeys} and has no fallback of any kind, so a license
     * signed by anyone else is refused with {@code Unknown kid} and nothing can be set to change
     * that. Which issuers a build trusts is decided when the build is made - see
     * {@code EmbeddedLicenseKeys} for why that list is a literal rather than a scan or a fetch.
     *
     * <p>This suite found that out the hard way. A {@code proxyma.license.jwks-url} property and
     * a second, JWKS-fetching verifier existed for months with NO CALLER, plumbed through both
     * deploy packages and documented as the rotation route; set correctly, activation still
     * failed with {@code Unknown kid: <ours> (trusted: 8ad17e2262c156ad)}. All of it was deleted
     * on 2026-09-23 rather than wired up, because wiring it up would have let a URL widen which
     * issuers can mint a license a build accepts.
     *
     * <p>So shadowing the classpath resource for ONE INSTANCE is the honest option: it says
     * "this deployment trusts this issuer", it touches neither the product nor the jar, and the
     * instance stops trusting production licenses, which is correct for a test instance.
     *
     * <p><b>The cost, stated plainly:</b> a server instance is launched through
     * {@code JarLauncher} with a classpath rather than by {@code java -jar}, so it is the one
     * place this suite does not start the artifact exactly as a customer does. Desktop has no
     * license code and is never launched this way.
     */
    private static Path trustOnly(ServiceApp licensor, Path data) {
        Path trust = data.resolve("trusted-issuer");
        Path keys = trust.resolve("keys");
        mkdirs(keys);
        try {
            // The name is the one EmbeddedLicenseKeys names. Anything else is ignored: the list is
            // a fixed path, not a scan, so a second key file adds nothing.
            Files.copy(licensor.publicKeyPem(), keys.resolve("proxyma-cloud-public.pem"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the test issuer's public key", e);
        }
        return trust;
    }

    private volatile boolean vectorStore = true;
    private volatile String accessCode;

    /**
     * Whether this instance has a working vector store.
     *
     * <p>A retrieval journey calls this rather than {@link Env#requireDocker} directly, so that
     * the skip message names what is actually lost rather than naming a container.
     */
    public void requireVectorStore() {
        Env.require(vectorStore, "this instance booted without a vector store, so nothing can be"
                + " indexed or searched. Start Docker and run again - ChromaDB is started as a"
                + " container (PROXYMA_IT_CHROMA_IMAGE overrides the image).");
    }

    /**
     * The shared Enterprise Server: its own database, and anonymous chat switched ON, so the guest
     * journeys have something to exercise. The default is off; {@link #bootServerUnlicensed} keeps
     * the default and is where "a guest is refused" is asserted.
     */
    public static ProxymaApp bootServer() {
        return bootServer("proxyma", "server", true, false);
    }

    /**
     * A second Enterprise Server that is never licensed, on a database of its own - for what an
     * install does BEFORE a license exists: one account, guests refused. Its trial is switched off,
     * as an operator of a server with no internet access switches it off. Its own class owns it and
     * closes it.
     */
    public static ProxymaApp bootServerUnlicensed() {
        return bootServer("proxyma_unlicensed", "server-unlicensed", false, false);
    }

    /**
     * An Enterprise Server as a customer first starts one: the 30-day trial switched ON, so it asks
     * the suite's license service for it on its first start. On a database of its own - the trial
     * is once per deployment, and the deployment is its database.
     */
    public static ProxymaApp bootServerWithTrial() {
        return bootServer("proxyma_trial", "server-trial", false, true);
    }

    /**
     * An Enterprise Server with the trial switched off and no license, for the air-gapped path: a
     * trial key asked for with its deployment code, pasted in and verified with no network call.
     */
    public static ProxymaApp bootServerAirGapped() {
        return bootServer("proxyma_airgapped", "server-airgapped", false, false);
    }

    /**
     * A server edition against a containerized PostgreSQL with pgvector, and a real licensor.
     *
     * @param trial whether it asks for its Enterprise trial on first start. Off unless a journey is
     *              about the trial: a licensed or deliberately unlicensed instance must not acquire
     *              a trial behind the test's back.
     */
    private static ProxymaApp bootServer(String database, String name, boolean guestChat, boolean trial) {
        Path jar = Env.requireJar(Env.App.SERVER);
        var postgres = Stores.postgres();
        String jdbcUrl = Stores.database(database);
        // The licensor is the suite's OWN license service, booted like any other app. See
        // DevLicense for why a license has to exist at all, and why it is minted per run rather
        // than committed.
        ServiceApp licensor = ServiceApp.shared(Env.App.LICENSE);
        // A license service answers nothing under /api/ - the trial and trial-key endpoints
        // included - until its operator has set it up (SetupGuardFilter). Production's is; the
        // suite's is fresh, and only another test's sign-in set it up. ServerTrialIT passed when
        // one ran first and failed on the host, where it ran first itself (2026-10-15). Set it up
        // here, whatever runs first. Idempotent.
        licensor.signInAsAdmin("it-admin", "it-password-123");
        Path data = Env.scratch(name);
        Path home = data.resolve("home");
        mkdirs(home, data.resolve("keys"), data.resolve("skills"), data.resolve("conversations"));
        writeJwtKeyPair(data.resolve("keys"));

        Map<String, String> env = new HashMap<>(isolatedEnvironment(data, home));
        env.put("SPRING_PROFILES_ACTIVE", "server");
        env.put("PROXYMA_DB_URL", jdbcUrl);
        env.put("PROXYMA_DB_USER", postgres.getUsername());
        env.put("PROXYMA_DB_PASSWORD", postgres.getPassword());
        // Every path server mode defaults to /data, which on a developer machine is either absent
        // or - on Linux - a directory nobody can write. All of them move together.
        env.put("PROXYMA_SERVER_DATA_DIR", data.toString());
        env.put("PROXYMA_STORAGE_ROOT", data.toString());
        env.put("PROXYMA_CONVERSATIONS_DIR", data.resolve("conversations").toString());
        env.put("PROXYMA_SKILLS_DIR", data.resolve("skills").toString());
        env.put("PROXYMA_EVALUATION_MEDIA_DIR", data.resolve("evaluation-media").toString());
        env.put("PROXYMA_JWT_PRIVATE_KEY_FILE", data.resolve("keys/server-jwt-private.pem").toString());
        env.put("PROXYMA_JWT_PUBLIC_KEY_FILE", data.resolve("keys/server-jwt-public.pem").toString());
        env.put("PROXYMA_TELEMETRY_SERVER_URL", "");

        List<String> properties = new ArrayList<>(commonProperties(data));
        // Overrides the dead port from commonProperties. This is where the app ACTIVATES; what
        // makes it TRUST the JWT activation hands back is trustOnly() below, and nothing else -
        // without that, activation succeeds at the license service and then fails in the app
        // with "Unknown kid", because the classpath carries the production public key and this
        // run's issuer is a throwaway.
        properties.add("-Dproxyma.license.server.url=" + licensor.baseUrl());
        // Anonymous chat is a deployment switch, not a setting - a property the operator sets.
        properties.add("-Dproxyma.guest.chat.enabled=" + guestChat);
        properties.add("-Dproxyma.license.trial.enabled=" + trial);
        properties.add("-Dproxyma.setup.token=" + SETUP_TOKEN);
        // Off by default; the journeys register their own users, as a deployment that opened it does.
        properties.add("-Dproxyma.auth.self-registration=true");

        ProxymaApp app = boot(Mode.SERVER, jar, data, env, properties, trustOnly(licensor, data));
        app.licensor = licensor;
        return app;
    }

    private static ProxymaApp boot(Mode mode, Path jar, Path data,
                                   Map<String, String> env, List<String> properties) {
        return boot(mode, jar, data, env, properties, null);
    }

    /**
     * @param classpathPrefix a directory placed AHEAD of the jar on the classpath, or null. Used
     *                        only to shadow the trusted license key - see bootServer.
     */
    private static ProxymaApp boot(Mode mode, Path jar, Path data, Map<String, String> env,
                                   List<String> properties, Path classpathPrefix) {
        FakeLlm llm = FakeLlm.start();
        int port = freePort();

        List<String> command = new ArrayList<>();
        command.add(Env.java());
        if (classpathPrefix != null) {
            // -cp and -jar are mutually exclusive, so a classpath prefix means launching Spring
            // Boot's loader by name instead. Same jar, same Start-Class, same everything the
            // manifest says - the only difference is that one directory sits ahead of it.
            command.add("-cp");
            command.add(classpathPrefix + File.pathSeparator + jar);
        }
        // EVERY -D BEFORE THE MAIN CLASS. After it they are program arguments, which Spring reads
        // as nothing at all - the app starts with every one of these properties at its default and
        // looks like it started fine.
        command.addAll(properties);
        command.add("-Dserver.port=" + port);
        if (classpathPrefix != null) {
            command.add("org.springframework.boot.loader.launch.JarLauncher");
        } else {
            command.add("-jar");
            command.add(jar.toString());
        }

        ManagedProcess process = ManagedProcess.start(
                mode.name().toLowerCase(java.util.Locale.ROOT), command, data, env);
        Http api = new Http("http://127.0.0.1:" + port);

        try {
            // Two minutes, which is generous, and it is the FIRST boot that needs it: Hibernate
            // creates every table, the settings defaults are seeded, the default chat skills are
            // written and Lucene opens an index that does not exist yet.
            process.awaitReady("HTTP on port " + port,
                    () -> api.healthy("/api/v1/health"), Duration.ofMinutes(2));
        } catch (RuntimeException e) {
            process.stop();
            llm.close();
            throw e;
        }
        ProxymaApp app = new ProxymaApp(mode, process, api, llm, data, port);
        app.command = List.copyOf(command);
        // A copy that keeps a null value: it REMOVES that variable from the child (ManagedProcess).
        app.env = new HashMap<>(env);
        return app;
    }

    /**
     * The environment a child gets: the data root redirected four ways, and nothing inherited that
     * could point at the real installation.
     *
     * <p>All four names are set rather than the one this platform uses, because {@code AppPaths}
     * consults them in order and inheriting a stale one from the parent shell would win over the
     * one that was set. {@code USERPROFILE} is in there because Java resolves {@code user.home}
     * from it on Windows, and {@code user.home} is what the skills, workflows and conversations
     * directories default under.
     */
    private static Map<String, String> isolatedEnvironment(Path data, Path home) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LOCALAPPDATA", data.toString());
        env.put("XDG_DATA_HOME", data.toString());
        env.put("HOME", home.toString());
        env.put("USERPROFILE", home.toString());
        env.put("PROXYMA_TELEMETRY_SERVER_URL", "");
        return env;
    }

    private static List<String> commonProperties(Path data) {
        return List.of(
                "-Dproxyma.log.dir=" + data.resolve("logs"),
                // The three that escape a data-root override. conversations-dir matters for more
                // than tidiness: it is the root the `read` and `grep` agent tools are confined to,
                // so an instance sharing it with the real app can reach files from another
                // installation.
                "-Dproxyma.skills-dir=" + data.resolve("skills"),
                "-Dproxyma.workflows-dir=" + data.resolve("workflows"),
                "-Dproxyma.conversations-dir=" + data.resolve("conversations"),
                // Blank, not redirected. See the class comment.
                "-Dproxyma.telemetry.server.url=",
                "-Dproxyma.updates.releases-api-url=",
                // THE THIRD OUTBOUND PATH, and the one that is not blankable. Telemetry and
                // updates treat an empty URL as "send nothing"; proxyma.license.server.url has no
                // such reading and DEFAULTS TO THE PRODUCTION ISSUER, https://license.proxyma.ai -
                // so every activation and every scheduled recheck in this suite would have gone
                // there. Pointed at a dead local port instead, so the failure mode is a connection
                // refused to 127.0.0.1 rather than traffic to a live service. bootServer
                // overrides it with the suite's own license service.
                "-Dproxyma.license.server.url=http://127.0.0.1:1",
                "-Dspring.main.banner-mode=off",
                // The log is the failure message when a boot times out, and Spring's default
                // startup output does not include the bean that could not be created.
                "-Dlogging.level.ai.proxyma=INFO"
        );
    }

    /**
     * Server mode refuses to start without an RSA key pair to sign user JWTs with - it does not
     * generate one, it throws at startup. Same two encodings {@code tools/dev/GenerateJwtKeys.java}
     * writes: PKCS#8 private, X.509 public.
     */
    private static void writeJwtKeyPair(Path keyDir) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.writeString(keyDir.resolve("server-jwt-private.pem"),
                    pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.UTF_8);
            Files.writeString(keyDir.resolve("server-jwt-public.pem"),
                    pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not write a JWT key pair into " + keyDir, e);
        }
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    // -- Pointing the app at the fake provider ---------------------------------

    /**
     * Registers the fake provider and selects it for chat, embedding and reranking - the three
     * settings a user fills in before Proxyma can do anything at all.
     *
     * <p>Done through the PUBLIC API, exactly as the Settings screen does it: one POST to create
     * the instance, then a PATCH per role. Nothing is written to the database directly and no bean
     * is reached past its front door, so this call also IS a test of provider registration - if it
     * breaks, every journey fails at setup and says so.
     *
     * @return the provider instance id, which a test needs to assert attribution
     */
    private volatile String fakeProviderId;

    /**
     * {@link #useFakeProvider()}, done once per instance however many test classes ask.
     *
     * <p>Registering twice would fail on the duplicate name, which is correct product behavior and
     * a confusing way for an unrelated test to break during setup.
     */
    public synchronized String ensureFakeProvider() {
        return ensureFakeProvider(api);
    }

    /**
     * {@link #ensureFakeProvider()} as a particular signed-in caller.
     *
     * <p>SERVER MODE NEEDS THIS. Configuring a provider is an administrator action, so
     * {@code POST /api/v1/providers/instances} answers 401 to an anonymous client - correctly, and
     * invisibly if a harness written for desktop's no-auth edition is pointed at it. That is
     * exactly how ServerAuthIT failed on the host: "expected 2xx but got 401" inside @BeforeAll,
     * reported against a line that only said `ensureFakeProvider()`.
     */
    public synchronized String ensureFakeProvider(Http client) {
        if (fakeProviderId == null) fakeProviderId = useFakeProvider(client);
        return fakeProviderId;
    }

    public String useFakeProvider() {
        return useFakeProvider(api);
    }

    public String useFakeProvider(Http client) {
        String instanceId = client.post("/api/v1/providers/instances", Map.of(
                "name", "Fake",
                "protocol", "OPENAI_COMPATIBLE",
                "baseUrl", llm.baseUrl(),
                "apiKey", "it-not-required",
                // The one genuinely vendor-shaped flag in the product: /v1/rerank 404s on most
                // OpenAI-compatible services, so it cannot be inferred from the protocol. The fake
                // provider implements it, so this instance declares it.
                "nativeRerank", true
        )).ok().text("/id");

        selectProvider(client, "chat", instanceId, FakeLlm.CHAT_MODEL);
        selectProvider(client, "embedding", instanceId, FakeLlm.EMBEDDING_MODEL);
        selectProvider(client, "reranking", instanceId, FakeLlm.RERANK_MODEL);
        return instanceId;
    }

    /** Points one role's settings group at a provider instance and model. */
    public void selectProvider(String settingsPrefix, String instanceId, String model) {
        selectProvider(api, settingsPrefix, instanceId, model);
    }

    public void selectProvider(Http client, String settingsPrefix, String instanceId, String model) {
        client.patchText("/api/v1/settings/" + settingsPrefix + "/provider", instanceId).ok();
        client.patchText("/api/v1/settings/" + settingsPrefix + "/model", model).ok();
    }

    /** Saves one settings field, whatever its group. The body is the raw value - see patchText. */
    public void setting(String prefix, String field, String value) {
        api.patchText("/api/v1/settings/" + prefix + "/" + field, value).ok();
    }

    /**
     * Clears one settings field.
     *
     * <p>DELETE, not a PATCH with an empty body. {@code saveField} is declared
     * {@code @RequestBody String value}, and a request body is required by default - an empty one
     * cannot be decoded, so Spring answers 400 "Invalid request content" before the handler runs.
     * The product has {@code deleteField} for exactly this, and a test that cleared a setting the
     * other way fails in its own teardown and leaves the PREVIOUS setting in place, which then
     * breaks a later test for a reason that has nothing to do with it.
     */
    public void clearSetting(String prefix, String field) {
        api.delete("/api/v1/settings/" + prefix + "/" + field).ok();
    }

    /** Reads one settings field back, for asserting a save actually landed. */
    public JsonNode settings(String prefix) {
        return api.get("/api/v1/settings/" + prefix).ok().json();
    }

    // -- Ports -----------------------------------------------------------------

    /**
     * A port nothing is listening on.
     *
     * <p>There is an unavoidable race between closing this socket and the app binding it. It is
     * narrow, and the alternative - a fixed port - is not a race but a certainty of collision, on
     * a machine where the real Proxyma may be running on 4246 and a previous suite's socket may
     * still be in TIME_WAIT.
     */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void mkdirs(Path... dirs) {
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() {
        process.stop();
        llm.close();
    }
}
