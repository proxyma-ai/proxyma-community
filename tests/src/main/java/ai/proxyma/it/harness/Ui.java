package ai.proxyma.it.harness;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.LoadState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real browser, driven against a real Proxyma.
 *
 * <h2>Why a browser suite exists at all when the API is fully covered</h2>
 *
 * Because the worst bug this project has shipped was invisible to every other kind of test. A CORS
 * origin pattern of {@code https://host:*} REQUIRES the origin to carry a port, so every
 * installation behind a reverse proxy on 443 was refused its own JavaScript and served a blank
 * page. Vite marks its bundles {@code crossorigin}, which makes same-origin asset loads CORS
 * requests; {@code index.html} is a plain navigation with no {@code Origin} header and kept
 * answering 200. It survived a green deploy and every check anyone ran by hand, and
 * {@code CLAUDE.md} records the reason in four words: <b>curl cannot reproduce it</b>.
 *
 * <p>A browser can. That is the whole argument for this class, and it is why the API journeys and
 * the UI journeys are not alternatives to each other.
 *
 * <h2>Playwright for Java, not @playwright/test</h2>
 *
 * The driver is already a managed dependency of {@code apps/app/backend} - the product renders
 * pages with it - so these tests add a Maven coordinate instead of a second toolchain: no
 * package.json, no node_modules in CI, one runner, one {@code mvn verify}. The cost is real and
 * worth naming: no trace viewer UI, no codegen, and auto-waiting has to be asked for explicitly
 * rather than being wrapped around every assertion. {@link #assertNoPageErrors} exists partly to
 * make up for the last one.
 */
public final class Ui implements AutoCloseable {

    private static Playwright playwright;
    private static Browser browser;

    private final BrowserContext context;
    private final Page page;
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    private final List<String> failedRequests = new CopyOnWriteArrayList<>();

    private Ui(BrowserContext context, Page page) {
        this.context = context;
        this.page = page;
    }

    /**
     * Headless unless {@code PROXYMA_IT_HEADED=1}, which is how a person watches a failing journey
     * happen rather than reading a screenshot of its aftermath.
     */
    private static synchronized Browser browser() {
        if (browser == null) {
            playwright = Playwright.create();
            boolean headed = "1".equals(System.getenv("PROXYMA_IT_HEADED"));
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(!headed)
                    // Slowed down only when a person is watching. Zero otherwise: a delay that is
                    // always on turns a suite into a screensaver and hides real waiting bugs.
                    .setSlowMo(headed ? 250 : 0));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (browser != null) browser.close();
                if (playwright != null) playwright.close();
            }));
        }
        return browser;
    }

    /**
     * A fresh browser context - a new profile, with no cookies or storage from any other test -
     * already at the app's root.
     *
     * <p>A context rather than a whole browser per test: contexts are cheap and isolated, and
     * sharing one would make a sign-in in one journey leak into the next, which is precisely the
     * kind of hidden dependency that produces a suite that only passes in order.
     */
    // On Desktop and the Home Server the first page is the access handshake, /?access=<code>, which
    // sets the cookie and redirects to the app - the way the Desktop window opens it.
    public static Ui open(ProxymaApp app) {
        return open(app.baseUrl(), app.accessCode() == null ? "/" : "/?access=" + app.accessCode());
    }

    public static Ui open(String baseUrl) {
        return open(baseUrl, "/");
    }

    private static Ui open(String baseUrl, String firstPage) {
        BrowserContext context = browser().newContext(new Browser.NewContextOptions()
                .setBaseURL(baseUrl)
                // A fixed, ordinary desktop size. The app has responsive breakpoints, so a
                // default that differed between machines would make a locator resolve to a mobile
                // control on one and a desktop one on another.
                .setViewportSize(1440, 900)
                .setLocale("en-US"));
        Page page = context.newPage();
        Ui ui = new Ui(context, page);

        // Console errors and failed asset loads are collected for every page, always. The blank
        // page this class exists to catch produces exactly these two signals and NO failed
        // assertion - the test would otherwise pass while looking at nothing.
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) ui.consoleErrors.add(describe(message));
        });
        page.onPageError(error -> ui.consoleErrors.add("uncaught: " + error));
        page.onRequestFailed(request -> {
            if (worthReporting(request, baseUrl)) ui.failedRequests.add(describe(request));
        });

        page.navigate(firstPage);
        OPEN.get().add(ui);
        return ui;
    }

    /**
     * Every {@link Ui} the current test has opened and not yet closed.
     *
     * <p>{@link UiLifecycle} needs this to photograph a failure, and cannot get it any other way:
     * the page belongs to a local variable inside the test method. Per-thread because failsafe
     * runs one JVM for the whole module and JUnit may one day run classes in parallel - a shared
     * list would then photograph another test's browser.
     */
    static final ThreadLocal<List<Ui>> OPEN = ThreadLocal.withInitial(ArrayList::new);

    static List<Ui> openInstances() { return new ArrayList<>(OPEN.get()); }

    static void forgetAll() { OPEN.get().clear(); }

    private static String describe(ConsoleMessage message) {
        String location = message.location();
        return message.text() + (location == null || location.isBlank() ? "" : " @ " + location);
    }

    private static String describe(Request request) {
        return request.method() + " " + request.url()
                + " - " + (request.failure() == null ? "failed" : request.failure());
    }

    /**
     * Whether a failed request means anything, or is just the browser tidying up.
     *
     * <p>Two whole categories are noise, and leaving them in made every navigation test fail for
     * reasons that had nothing to do with the product:
     *
     * <ul>
     *   <li><b>{@code net::ERR_ABORTED} is CANCELLATION, not failure.</b> It is what Chromium
     *       reports for every in-flight request when the page navigates, reloads or closes - and
     *       this SPA lazy-loads a route's chunks and holds a permanent event stream open, so a
     *       navigation reliably cancels a dozen of them. Nothing went wrong.
     *   <li><b>Anything off-origin is somebody else's network.</b> The app asks Google Fonts for
     *       two woff2 files; whether that succeeds depends on the machine's DNS and whatever a
     *       CI runner is allowed to reach. A suite that failed on it would be testing the
     *       internet.
     * </ul>
     *
     * <p>What survives the filter is the case this whole class exists for: a SAME-ORIGIN asset the
     * browser genuinely could not load. A bundle refused by a CORS rule fails that way - not
     * aborted, not off-origin - and takes the whole page down with it while the navigation itself
     * still answers 200.
     */
    private static boolean worthReporting(Request request, String baseUrl) {
        String failure = request.failure() == null ? "" : request.failure();
        if (failure.contains("ERR_ABORTED")) return false;
        return request.url().startsWith(baseUrl);
    }

    public Page page() { return page; }

    /**
     * Waits until the app has actually painted something.
     *
     * <p><b>Never {@code waitForLoadState(NETWORKIDLE)} against this product.</b> The SPA
     * subscribes to {@code EventStreamController} for as long as the tab is open, and a
     * server-sent-event stream is a request that never completes - so the network is never idle,
     * by design, and every such wait burns its full timeout and then fails. The product's own
     * {@code application-desktop.properties} says as much where it cuts the graceful-shutdown
     * timeout to five seconds for exactly this reason.
     *
     * <p>So readiness is asked of the DOM instead: the document has loaded and {@code #root} has
     * children. That is the earliest moment at which React has rendered, which is the only thing a
     * UI assertion actually needs.
     */
    public Ui ready() {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForFunction("() => { const r = document.getElementById('root');"
                + " return r && r.children.length > 0; }",
                null, new Page.WaitForFunctionOptions().setTimeout(60_000));
        return this;
    }

    /** Navigates and waits for the app to render. */
    public Ui goTo(String path) {
        page.navigate(path);
        return ready();
    }

    /** Errors the page's own JavaScript reported. */
    public List<String> consoleErrors() { return new ArrayList<>(consoleErrors); }

    /** Requests the browser could not complete - a blocked bundle shows up here. */
    public List<String> failedRequests() { return new ArrayList<>(failedRequests); }

    /**
     * Asserts the page is not quietly broken.
     *
     * <p>Called at the end of every UI journey. A React app that throws during render shows an
     * empty body and a console error, and every locator assertion against it fails with "timed out
     * waiting for selector" - which reads as a moved button rather than as a dead application.
     * This turns it into the message it actually is.
     */
    public void assertNoPageErrors() {
        if (consoleErrors.isEmpty() && failedRequests.isEmpty()) return;
        throw new AssertionError("the page reported errors:\n  console: "
                + String.join("\n           ", consoleErrors)
                + "\n  failed requests: " + String.join("\n                   ", failedRequests));
    }

    /**
     * Writes a screenshot into {@code tests/target/screenshots}.
     *
     * <p>Kept in the build directory rather than a temp folder so CI can publish the whole
     * directory as an artifact and a failure comes with a picture of what the browser saw.
     */
    public Path screenshot(String name) {
        Path file = Env.repoRoot().resolve("tests/target/screenshots").resolve(name + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
        return file;
    }

    @Override
    public void close() {
        OPEN.get().remove(this);
        context.close();
    }
}
