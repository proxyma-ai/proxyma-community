package ai.proxyma.core.connector;

/**
 * The CA certificates an administrator added in Settings > Network, as PEM - for a client that
 * cannot be handed the JVM's TLS context.
 *
 * <p>Every HTTP client in the product trusts Java's CAs, the operating system's and these
 * (RestClientConfig). The svn command line is a separate process: it trusts the operating
 * system's CAs and whatever file it is pointed at, so this is what it is pointed at. An interface
 * here because the settings live in a module that depends on this one.
 */
@FunctionalInterface
public interface CustomCaCertificates {

    /** The PEM text, or null/blank when none is configured. */
    String pem();
}
