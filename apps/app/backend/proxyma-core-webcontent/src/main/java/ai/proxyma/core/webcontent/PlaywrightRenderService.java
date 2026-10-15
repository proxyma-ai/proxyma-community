package ai.proxyma.core.webcontent;

import ai.proxyma.core.system.util.AppPaths;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.locks.ReentrantLock;

// Optional high-fidelity JS-rendering engine for WebFetchTool, an alternative to the pure-Java
// HtmlUnit JsRenderService. A real headless Chromium executes modern JS (RequireJS/ES6/framework
// runtimes HtmlUnit chokes on), follows hash-fragment routing (it navigates to the full URL
// INCLUDING the '#', which a server GET drops), and exposes iframe documents - so a Flare-style
// "shell + #topic iframe" page, or any SPA that HtmlUnit can't drive, renders to real content.
//
// Deliberately optional and self-disabling, because it needs a downloaded browser binary that the
// codebase otherwise avoids (see JsRenderService's header): it is gated by proxyma.web-fetch.js-engine
// (only "playwright" or "auto" ever start it) and, when started, initializes the browser LAZILY on
// first use. If Playwright or the Chromium binary is missing (the desktop bundle, a registry-blocked
// proxied host), the one-time launch fails, the engine marks itself permanently unavailable, and
// render() returns null forever after - WebFetchTool then degrades to HtmlUnit or the static page.
// Nothing here runs, or is even initialized, on a deployment that doesn't opt in.
//
// Playwright's objects are single-threaded; a single ReentrantLock serializes all render() calls
// over one reused Browser (a fresh BrowserContext/Page per call for isolation). Chat tool calls are
// infrequent and already bounded, so serialization is acceptable and far cheaper than a browser per
// call. Proxy/TLS parity with the rest of the app's outbound HTTP mirrors JsRenderService: the
// corporate proxy from RestClientConfig is applied to the browser, with its noProxy list written the
// way Chromium reads it (chromiumBypass), and certificates are VALIDATED - by Chromium, against the
// operating system's store, which is where a company's intercepting CA is installed for every other
// program on the machine. They were ignored "to match every other client" after every other client
// had started validating (SEC-29). A CA set only in Settings > Network is unknown to Chromium: such a
// page fails here and is rendered by HtmlUnit, which has it.
@Component
class PlaywrightRenderService {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightRenderService.class);

    // navigate() budget and the post-load network-idle settle window for late XHR-driven content.
    // Short on purpose: this is a best-effort enhancement and must never hang a chat tool call.
    private static final double NAVIGATE_TIMEOUT_MS = 20_000;
    private static final double NETWORKIDLE_TIMEOUT_MS = 6_000;


    private final ObjectProvider<WebProxyProvider> proxyProvider;
    private final AppPaths appPaths;
    // true only when proxyma.web-fetch.js-engine selects an engine that includes Playwright.
    private final boolean enabled;

    private final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock. unavailable is a latch: once a launch attempt fails we never retry (avoids a
    // slow failing launch on every fetch for a deployment that simply has no browser installed).
    private Playwright playwright;
    private Browser browser;
    private boolean unavailable;

    PlaywrightRenderService(ObjectProvider<WebProxyProvider> proxyProvider,
                            AppPaths appPaths,
                            @Value("${proxyma.web-fetch.js-engine:htmlunit}") String jsEngine) {
        this.appPaths = appPaths;
        this.proxyProvider = proxyProvider;
        String engine = jsEngine == null ? "" : jsEngine.trim().toLowerCase();
        this.enabled = engine.equals("playwright") || engine.equals("auto");
    }

    // Whether this engine is even selected - lets WebFetchTool skip it without a lock/branch when the
    // deployment didn't opt in. (Selection only; actual browser availability is discovered on first
    // render and reflected by render() returning null.)
    boolean isEnabled() {
        return enabled;
    }

    // Rendered post-JavaScript HTML with iframe content stitched in, or null on anything short of a
    // clean result (disabled, browser unavailable, navigation/render failure) so the caller keeps
    // its static/HtmlUnit result. Never throws.
    String render(String url) {
        if (!enabled) return null;
        lock.lock();
        try {
            if (!ensureBrowser()) return null;
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(WebRenderService.BROWSER_USER_AGENT));
            try {
                Page page = context.newPage();
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(NAVIGATE_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                // Best-effort settle for XHR-driven content; a timeout here is fine, the DOM so far
                // is still used.
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(NETWORKIDLE_TIMEOUT_MS));
                } catch (Exception settleTimeout) {
                    log.debug("[PlaywrightRenderService] network-idle wait timed out for {}", url);
                }
                return stitchFrames(page, url);
            } finally {
                context.close();
            }
        } catch (Exception e) {
            log.debug("[PlaywrightRenderService] render failed for {}: {}", url, e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    // Merges the main document with every child frame's document into one HTML string: an <iframe>'s
    // real content is a SEPARATE document that page.content() does not include, so the topic pane of
    // a Flare-style shell (or any framed page) would otherwise be lost. Each frame's relative URLs
    // are absolutized against that frame's own base before its nodes are folded into the merged doc.
    private static String stitchFrames(Page page, String url) {
        Document merged = Jsoup.parse(page.content(), url);
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue;
            try {
                String frameHtml = frame.content();
                if (frameHtml == null || frameHtml.isBlank()) continue;
                String frameBase = frame.url() != null && !frame.url().isBlank() ? frame.url() : url;
                Document fd = Jsoup.parse(frameHtml, frameBase);
                if (fd.body() == null) continue;
                HtmlContentExtractor.absolutizeUrls(fd);
                merged.body().appendChildren(fd.body().childNodes());
            } catch (Exception frameFailed) {
                log.debug("[PlaywrightRenderService] frame content unavailable: {}", frameFailed.getMessage());
            }
        }
        String html = merged.outerHtml();
        return html == null || html.isBlank() ? null : html;
    }

    // Lazily creates Playwright + a headless Chromium once, reused across calls. Must be called under
    // lock. Latches unavailable on first failure so a browser-less host fails fast exactly once.
    //
    // A browser that crashed or lost its connection is launched again: it was kept because it was
    // not null, and every later render returned null for the life of the process.
    private boolean ensureBrowser() {
        if (unavailable) return false;
        if (browser != null) {
            if (browser.isConnected()) return true;
            log.warn("[PlaywrightRenderService] headless Chromium is no longer connected - starting it again");
            cleanup();
        }
        try {
            // PLAYWRIGHT_BROWSERS_PATH pinned to Proxyma's own data directory, passed through
            // CreateOptions because Playwright reads it as an ENVIRONMENT variable and a JVM cannot
            // set one for itself.
            //
            // Without it Playwright uses the machine-wide user cache (%LOCALAPPDATA%\ms-playwright,
            // ~/.cache/ms-playwright) - shared with every other Playwright on that machine. Proxyma
            // would be launching a browser it did not install and cannot vouch for, and on a host
            // where it did the installing it would leave several hundred megabytes behind after an
            // uninstall. Proxyma brings its own runtime and leaves the user's alone; the browser is
            // no different from the bundled JRE and Python in that respect.
            Map<String, String> env = new HashMap<>();
            env.put("PLAYWRIGHT_BROWSERS_PATH", appPaths.browsersDir().toString());
            // Playwright.create() runs the driver's `install` step, and that step DOWNLOADS whatever
            // it finds missing under PLAYWRIGHT_BROWSERS_PATH - not just the browser we are about to
            // launch. Measured against an empty directory it fetched full Chromium (173 MB), Firefox
            // (110 MB) and WebKit (59 MB) before failing for an unrelated reason: roughly 340 MB,
            // unannounced, on the first web fetch of a deployment that had simply not opted in to
            // shipping a browser.
            //
            // That is the opposite of both promises this product makes about its runtime - that it
            // brings its own and that it works offline. With the flag set, a missing browser makes
            // launch() fail, which ensureBrowser already handles by latching unavailable and letting
            // WebFetchTool fall back to HtmlUnit. A browser Proxyma did not ship is not one it should
            // reach out and install.
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            com.microsoft.playwright.options.Proxy proxy = buildProxy();
            if (proxy != null) options.setProxy(proxy);
            browser = playwright.chromium().launch(options);
            log.info("[PlaywrightRenderService] headless Chromium ready for JS rendering");
            return true;
        } catch (Exception e) {
            unavailable = true;
            log.warn("[PlaywrightRenderService] Playwright/Chromium unavailable ({}); WebFetch will use "
                    + "HtmlUnit or the static page instead", e.getMessage());
            cleanup();
            return false;
        }
    }

    // Corporate proxy from the WebProxyProvider (network's RestClientConfig), translated to
    // Playwright's proxy option. noProxy hosts become Playwright's comma-separated bypass list.
    private com.microsoft.playwright.options.Proxy buildProxy() {
        WebProxyProvider provider = proxyProvider.getIfAvailable();
        if (provider == null) return null;
        Proxy javaProxy = provider.getJavaProxy();
        if (javaProxy == null || javaProxy == Proxy.NO_PROXY
                || !(javaProxy.address() instanceof InetSocketAddress addr)) {
            return null;
        }
        com.microsoft.playwright.options.Proxy proxy =
                new com.microsoft.playwright.options.Proxy("http://" + addr.getHostString() + ":" + addr.getPort());
        String bypass = chromiumBypass(provider.getNoProxyHosts());
        if (!bypass.isEmpty()) proxy.setBypass(bypass);
        return proxy;
    }

    /**
     * A noProxy list as Chromium's bypass rules, meaning what it means to every other client
     * (RestClientConfig.shouldBypass): a bare "corp.example" is that host AND every name under it,
     * as curl reads it, where Chromium reads it as the host alone; ".corp" and "*.corp" are both
     * the domain and everything under it. Wildcards elsewhere ("10.*") are passed as they are.
     * Loopback needs no entry - Chromium bypasses it itself.
     */
    static String chromiumBypass(String noProxy) {
        if (noProxy == null || noProxy.isBlank()) return "";
        java.util.LinkedHashSet<String> rules = new java.util.LinkedHashSet<>();
        for (String raw : noProxy.split("[,|;\\s]+")) {
            String entry = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (entry.isEmpty()) continue;
            String domain = entry.startsWith("*.") ? entry.substring(2)
                    : entry.startsWith(".") ? entry.substring(1)
                    : entry.indexOf('*') < 0 ? entry : null;
            if (domain == null || domain.isEmpty()) {
                rules.add(entry);
            } else {
                rules.add(domain);
                rules.add("*." + domain);
            }
        }
        return String.join(",", rules);
    }

    @PreDestroy
    void shutdown() {
        lock.lock();
        try {
            cleanup();
        } finally {
            lock.unlock();
        }
    }

    // Closes browser then Playwright, tolerating either being null/already-closed. Caller holds lock.
    private void cleanup() {
        try {
            if (browser != null) browser.close();
        } catch (Exception ignore) {
            // best-effort teardown
        } finally {
            browser = null;
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception ignore) {
            // best-effort teardown
        } finally {
            playwright = null;
        }
    }
}
