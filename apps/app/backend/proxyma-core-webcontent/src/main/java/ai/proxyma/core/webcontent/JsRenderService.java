package ai.proxyma.core.webcontent;

import org.htmlunit.BrowserVersion;
import org.htmlunit.ProxyConfig;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.security.NoSuchAlgorithmException;

// Pure-Java headless-browser fallback for WebFetchTool: when a static fetch returns a near-empty
// SPA shell (see WebFetchTool.looksJsRendered), this re-fetches the URL with HtmlUnit, executes
// the page's JavaScript, and hands the resulting rendered DOM back as HTML for the normal
// jsoup -> Readability -> flexmark pipeline. No external browser binary is downloaded (unlike
// Playwright/Selenium), so it works behind the registry-blocking corporate proxy and inside the
// bundled desktop app.
//
// Two parity notes with the rest of the app's outbound HTTP:
//  - Proxy: HtmlUnit has its own HTTP stack and does NOT read the http(s).proxyHost JVM system
//    properties the blocking RestClients set, so the proxy is applied explicitly from
//    RestClientConfig - otherwise a proxied deployment (or any intranet fetch that needs the
//    corporate proxy) would silently fail here while succeeding on the static path.
//    Whether a URL goes direct is RestClientConfig's own decision (WebProxyProvider.bypassesProxy),
//    not this class's reading of the noProxy list.
//  - TLS: VALIDATES, with the JVM's default SSL context - which RestClientConfig makes Java's CAs
//    plus the operating system's plus any CA set in Settings > Network. It trusted every
//    certificate "to match every other client" long after every other client stopped (SEC-29), so
//    anyone on the path could put words into a rendered page the model then read, or into a
//    crawled page everyone who can read its source is then served.
@Component
class JsRenderService {

    private static final Logger log = LoggerFactory.getLogger(JsRenderService.class);

    // getPage() connect/read timeout and the post-load settle window for late background JS
    // (XHR-driven content). Deliberately short: this is a best-effort enhancement, not the primary
    // fetch path, and must never hang a chat tool call.
    private static final int PAGE_TIMEOUT_MS = 20_000;
    private static final long JS_BACKGROUND_WAIT_MS = 4_000;

    private final ObjectProvider<WebProxyProvider> proxyProvider;

    JsRenderService(ObjectProvider<WebProxyProvider> proxyProvider) {
        this.proxyProvider = proxyProvider;
    }

    // Rendered post-JavaScript HTML, or null on any failure (the caller keeps its static result).
    // Never throws - a JS-render failure must degrade to the static page, not fail the whole fetch.
    String render(String url) {
        try (WebClient webClient = new WebClient(BrowserVersion.CHROME)) {
            configure(webClient, url);
            HtmlPage page = webClient.getPage(url);
            webClient.waitForBackgroundJavaScript(JS_BACKGROUND_WAIT_MS);
            String html = page.asXml();
            return (html == null || html.isBlank()) ? null : html;
        } catch (Exception e) {
            log.debug("[JsRenderService] JS render failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    // Package-private for JsRenderTlsAndProxyTest.
    void configure(WebClient webClient, String url) {
        var options = webClient.getOptions();
        options.setCssEnabled(false);                         // only the DOM is needed, not layout
        options.setDownloadImages(false);                     // media is captured from the static fetch, not here
        options.setThrowExceptionOnScriptError(false);        // third-party JS errors must not fail the render
        options.setThrowExceptionOnFailingStatusCode(false);  // a 4xx/5xx yields whatever HTML came back, or null
        options.setUseInsecureSSL(false);
        try {
            options.setSSLContext(SSLContext.getDefault());   // the trust every other client has - see the header
        } catch (NoSuchAlgorithmException e) {
            log.debug("[JsRenderService] no default SSL context, HtmlUnit keeps its own: {}", e.getMessage());
        }
        options.setTimeout(PAGE_TIMEOUT_MS);
        options.setRedirectEnabled(true);
        applyProxy(webClient, url);
    }

    // The proxy for this URL, or none when the rule every other client follows says it goes direct.
    private void applyProxy(WebClient webClient, String url) {
        WebProxyProvider provider = proxyProvider.getIfAvailable();
        if (provider == null) return;
        Proxy proxy = provider.getJavaProxy();
        if (proxy == null || proxy == Proxy.NO_PROXY || !(proxy.address() instanceof InetSocketAddress addr)) {
            return;
        }
        String host = hostOf(url);
        if (host != null && provider.bypassesProxy(host)) return;
        // 3-arg ctor (host, port, scheme); null scheme lets HtmlUnit default to HTTP, matching how
        // RestClientConfig treats the resolved proxy (java.net.Proxy.Type.HTTP).
        webClient.getOptions().setProxyConfig(new ProxyConfig(addr.getHostString(), addr.getPort(), null));
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
