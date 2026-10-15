package ai.proxyma.core.webcontent;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Shared headless-browser render facade, used by both the webFetch tool (proxyma-core-tools) and the
// web-page crawl connector (proxyma-core-connector). Picks the engine per config and returns the
// rendered post-JavaScript HTML (or null when nothing rendered - rendering disabled, no browser
// available, or the render failed - so callers fall back to the static page).
//
// Config (kept under the historical "web-fetch" prefix so existing deployment .env values keep
// working, even though the crawl connector now shares it):
//   proxyma.web-fetch.js-rendering  auto|off  - off is a hard kill switch (no rendering anywhere)
//   proxyma.web-fetch.js-engine     htmlunit|playwright|auto - which engine, default htmlunit
//     htmlunit   -> pure-Java HtmlUnit only (no browser binary; works everywhere incl. desktop)
//     playwright -> real headless Chromium only (needs an installed browser; else no render)
//     auto       -> Playwright first, HtmlUnit fallback
@Component
public class WebRenderService {

    /**
     * The browser User-Agent a fetch that wants to be served like a browser sends - the rendered
     * path (Playwright) and the static one alike, so a site that degrades non-browser clients sees
     * one identity. One string, where there were copies.
     */
    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final PlaywrightRenderService playwright;
    private final JsRenderService htmlUnit;
    private final String jsRenderingMode;
    private final String jsEngine;

    public WebRenderService(PlaywrightRenderService playwright,
                            JsRenderService htmlUnit,
                            @Value("${proxyma.web-fetch.js-rendering:auto}") String jsRenderingMode,
                            @Value("${proxyma.web-fetch.js-engine:htmlunit}") String jsEngine) {
        this.playwright = playwright;
        this.htmlUnit = htmlUnit;
        this.jsRenderingMode = jsRenderingMode;
        this.jsEngine = jsEngine == null ? "htmlunit" : jsEngine.trim().toLowerCase();
    }

    // Rendered HTML for the given url, or null when nothing rendered. Never throws.
    public String render(String url) {
        if (!isRenderingEnabled()) return null;
        // The browsers have their own networking, which the shared client's per-hop check never
        // sees - so the page itself is checked here. What the page then loads (scripts, frames,
        // XHR) is held by the sandbox's firewall rules, not by this; see OutboundAddressPolicy.
        OutboundAddressPolicy.check(url);
        if (playwright.isEnabled()) {
            String html = playwright.render(url);
            if (html != null) return html;
        }
        // Fall back to HtmlUnit unless the engine was pinned strictly to Playwright.
        if (!"playwright".equals(jsEngine)) {
            return htmlUnit.render(url);
        }
        return null;
    }

    // False only when proxyma.web-fetch.js-rendering=off (the hard kill switch).
    public boolean isRenderingEnabled() {
        return !"off".equalsIgnoreCase(jsRenderingMode);
    }

    // True when a real Chromium (not just HtmlUnit) is selected and enabled - lets a caller decide,
    // e.g. whether a JS-heavy crawl is worth attempting on this deployment.
    public boolean isPlaywrightActive() {
        return isRenderingEnabled() && playwright.isEnabled();
    }
}
