package ai.proxyma.core.network;

import java.time.Duration;

/**
 * The bounds every outbound HTTP client uses - one place, so a client that builds its own (the
 * Gemini and Azure SDKs) cannot drift from the ones RestClientConfig builds. Each of those used to
 * carry its own copy of 300 s, commented "matches RestClientConfig", and none of them carried the
 * 1800 s an LLM call was meant to get.
 */
public final class HttpTimeouts {

    private HttpTimeouts() {}

    /** TCP connect and TLS handshake, for every client. */
    public static final Duration CONNECT = Duration.ofSeconds(30);

    /**
     * Read bound for infrastructure: connectors, ChromaDB, telemetry, the update check. A long
     * silence from one of those is a stuck downstream, so the calling thread is let go.
     */
    public static final Duration INFRA_READ = Duration.ofSeconds(300);

    /**
     * Read bound for a model call. A blocking call to a model says nothing until the whole answer
     * is generated, and a healthy local model working through a large prompt can take longer than
     * {@link #INFRA_READ} - so a long delegated step died at exactly 300 s with a timeout that read
     * like a provider fault. See RestClientConfig's llmClientHttpRequestFactory.
     */
    public static final Duration LLM_READ = Duration.ofSeconds(1800);
}
