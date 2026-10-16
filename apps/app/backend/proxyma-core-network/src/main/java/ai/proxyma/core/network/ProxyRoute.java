package ai.proxyma.core.network;

import ai.proxyma.core.webcontent.WebProxyProvider;

/**
 * Which way a request to a host leaves this machine, and what a failure to connect there should
 * tell the person reading it.
 *
 * <p>WHY THIS EXISTS. A connection failure means different things by route, and saying the wrong
 * one sends the reader to the wrong setting. The 0.36.0 feedback: a LiteLLM gateway reachable only
 * through the corporate proxy, and a No proxy list ending in the company's ".corp.example" added for
 * two GPU servers on that domain. The gateway's name matched, so every request to it went DIRECT,
 * the local DNS did not know the name, and the provider Test said "Connection refused - is the
 * service running at that address?" while the chat said "WebClientRequestException". Nothing
 * mentioned the proxy, and the setting that caused it was the one thing the page did not point at.
 *
 * <p>The graph worker already said it the other way round ("the request went through the proxy;
 * add the host to No proxy if it is on your network"); this is that sentence and its opposite, in
 * one place, for every client that can tell the route.
 */
public enum ProxyRoute {

    /** No proxy is configured: everything goes direct, and there is nothing to say about it. */
    NONE,

    /** Sent to the configured proxy. */
    PROXY,

    /** A proxy is configured, and the host is on the No proxy list, so the request went direct. */
    BYPASSED;

    /** The route as a clause to append to a failure, or "" when it adds nothing. */
    public String note() {
        return switch (this) {
            case NONE -> "";
            case PROXY -> "the request went through the proxy; add the host to No proxy if it is on your network";
            case BYPASSED -> "the request went directly, not through the proxy, because the host is on the "
                    + "No proxy list; take it off No proxy in Settings > Network if it is reachable "
                    + "only through the proxy";
        };
    }

    /** The route for {@code host} by the rule every outbound client follows (RestClientConfig). */
    public static ProxyRoute of(WebProxyProvider proxy, String host) {
        if (proxy == null || host == null || host.isBlank()) return NONE;
        try {
            java.net.Proxy configured = proxy.getJavaProxy();
            if (configured == null || configured == java.net.Proxy.NO_PROXY) return NONE;
            return proxy.bypassesProxy(host) ? BYPASSED : PROXY;
        } catch (RuntimeException e) {
            // Settings that cannot be read say nothing about the route; a failure message must not
            // fail for it.
            return NONE;
        }
    }
}
