package ai.proxyma.core.webcontent;

import java.net.Proxy;

/**
 * Supplies the corporate/HTTP proxy to the headless-browser render engines (HtmlUnit, Playwright)
 * without proxyma-core-webcontent having to depend on proxyma-core-network.
 *
 * <p>Dependency inversion, on purpose: webcontent is used by BOTH proxyma-core-tools and
 * proxyma-core-connector. If webcontent depended on network directly it would pull network's
 * transitive chain (network -> settings -> license -> connector), and since connector depends on
 * webcontent that would form a Maven reactor cycle
 * (connector -> webcontent -> network -> settings -> license -> connector). Instead webcontent
 * declares this interface and depends only on proxyma-core-common; network's RestClientConfig
 * implements it, so the render engines still route through the exact same proxy as every other
 * outbound client, resolved at runtime. When no implementation is on the context (webcontent used in
 * isolation), the engines simply run without a proxy.
 */
public interface WebProxyProvider {

    /** The effective outbound proxy, or {@link Proxy#NO_PROXY}/null when none is configured. */
    Proxy getJavaProxy();

    /** Comma/semicolon/whitespace-separated no-proxy host list, or null/blank when none. */
    String getNoProxyHosts();

    /**
     * Whether a request to {@code host} goes direct rather than through the proxy - the SAME rule
     * every other outbound client applies to the list above, so a render engine does not read it a
     * third way. HtmlUnit turned a bare "corp.example" into an exact match and quoted "10.*" and
     * "*" literally, so an intranet page every other client fetched directly was sent to the proxy
     * when rendered, and failed without a word.
     */
    boolean bypassesProxy(String host);
}
