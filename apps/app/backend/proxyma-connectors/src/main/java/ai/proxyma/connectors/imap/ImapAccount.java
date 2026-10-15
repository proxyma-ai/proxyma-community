package ai.proxyma.connectors.imap;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * One IMAP account as a connection holds it - a host, a port, how the connection is protected, a
 * user name and a password - and the one way it is signed in to, for the Test and for every session.
 *
 * <p><b>TLS.</b> Implicit TLS (port 993) by default, or STARTTLS (port 143) where the server is set
 * up that way - REQUIRED, so a server that does not offer it is refused rather than spoken to in
 * clear text. "None" is accepted only for a server on this machine (a loopback address), where the
 * password never crosses a network. The certificate is checked against the JVM's trust, which the
 * application extends with the operating system's CAs and those added in Settings > Network, and the
 * server's name is checked against it ({@code ssl.checkserveridentity}).
 *
 * <p><b>Where it may connect.</b> The host goes through the same outbound address policy as every
 * HTTP connector request (OutboundAddressPolicy): never a link-local or cloud metadata address, and
 * on the public sandbox nothing private or local. There is no allow-list as the network share has:
 * that list guards a DOMAIN credential handed to whatever server answers, while the password here is
 * the mailbox's own, typed by the person for the server they named.
 *
 * <p><b>Timeouts</b>: 15 seconds to connect, 60 to wait for any answer or to write a command. A hung
 * server fails the pass; it never holds a sync for ever.
 */
final class ImapAccount {

    static final String HOST = "host";
    static final String PORT = "port";
    static final String SECURITY = "security";
    static final String PASSWORD = "password";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    // A host name or an IPv4 address; an IPv6 address in brackets. Never a URL, a path or a port.
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+]");

    enum Security {
        TLS("tls", 993, "imaps"), STARTTLS("starttls", 143, "imap"), NONE("none", 143, "imap");

        final String value;
        final int defaultPort;
        final String protocol;

        Security(String value, int defaultPort, String protocol) {
            this.value = value;
            this.defaultPort = defaultPort;
            this.protocol = protocol;
        }

        static Security of(String value) {
            if (value == null || value.isBlank()) return TLS;
            for (Security s : values()) if (s.value.equalsIgnoreCase(value.strip())) return s;
            return TLS;
        }
    }

    record Settings(String host, int port, Security security, String username, String password) {
        @Override
        public String toString() {
            return "ImapAccount[" + username + "@" + host + ":" + port + ", " + security + "]";
        }
    }

    private ImapAccount() {}

    /** The account a connection names, checked; a sentence to act on where something is missing. */
    static Settings of(ResolvedCredentials connection) {
        String host = connection.setting(HOST);
        host = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) throw new ConnectorAuthException("This connection has no mail server. Add it in Settings > Connections.");
        if (!HOST_NAME.matcher(host).matches()) {
            throw new ConnectorAuthException("The mail server must be a host name such as imap.example.com, "
                    + "without imap:// or a port.");
        }
        Security security = Security.of(connection.setting(SECURITY));
        int port = security.defaultPort;
        String typed = connection.setting(PORT);
        if (typed != null && !typed.isBlank()) {
            try {
                port = Integer.parseInt(typed.strip());
            } catch (NumberFormatException e) {
                throw new ConnectorAuthException("The mail server's port must be a number, such as 993.");
            }
            if (port < 1 || port > 65535) throw new ConnectorAuthException("The mail server's port must be a number, such as 993.");
        }
        String username = connection.username();
        String password = connection.secret(PASSWORD) != null ? connection.secret(PASSWORD) : connection.soleSecret();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new ConnectorAuthException("This connection has no user name or password. Add them in Settings > Connections.");
        }
        return new Settings(host, port, security, username.strip(), password);
    }

    /**
     * Signs in. {@code sockets} is the TLS socket factory - null for the JVM's default, which is what
     * the application runs with; a test hands in one that trusts its own server.
     */
    static Store connect(Settings s, SSLSocketFactory sockets) throws MessagingException {
        if (s.security() == Security.NONE && !isLoopback(s.host())) {
            throw new ConnectorAuthException("A connection without TLS is allowed only to a mail server on this computer. "
                    + "Choose TLS or STARTTLS.");
        }
        try {
            OutboundAddressPolicy.checkHost(s.host().replaceAll("^\\[|]$", ""));
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            throw new ConnectorAuthException(e.getMessage());
        }
        String p = s.security().protocol;
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", p);
        props.setProperty("mail." + p + ".host", s.host());
        props.setProperty("mail." + p + ".port", String.valueOf(s.port()));
        props.setProperty("mail." + p + ".connectiontimeout", String.valueOf(CONNECT_TIMEOUT.toMillis()));
        props.setProperty("mail." + p + ".timeout", String.valueOf(READ_TIMEOUT.toMillis()));
        props.setProperty("mail." + p + ".writetimeout", String.valueOf(READ_TIMEOUT.toMillis()));
        // Reading a message must never mark it read: folders are opened read-only, and bodies are
        // fetched with BODY.PEEK besides.
        props.setProperty("mail." + p + ".peek", "true");
        if (s.security() != Security.NONE) {
            props.put("mail." + p + ".ssl.socketFactory", sockets != null ? sockets : defaultSockets());
            props.setProperty("mail." + p + ".ssl.checkserveridentity", "true");
        }
        if (s.security() == Security.TLS) props.setProperty("mail." + p + ".ssl.enable", "true");
        if (s.security() == Security.STARTTLS) {
            props.setProperty("mail." + p + ".starttls.enable", "true");
            props.setProperty("mail." + p + ".starttls.required", "true");
        }
        Store store = Session.getInstance(props).getStore(p);
        store.connect(s.host(), s.port(), s.username(), s.password());
        return store;
    }

    private static SSLSocketFactory defaultSockets() {
        try {
            return SSLContext.getDefault().getSocketFactory();
        } catch (NoSuchAlgorithmException e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    /** Whether every address the host has is this machine's. */
    static boolean isLoopback(String host) {
        String bare = host.replaceAll("^\\[|]$", "");
        if (bare.equalsIgnoreCase("localhost")) return true;
        try {
            InetAddress[] all = InetAddress.getAllByName(bare);
            if (all.length == 0) return false;
            for (InetAddress a : all) if (!a.isLoopbackAddress()) return false;
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The sentence for a failure to reach or sign in to the server - never the password, never a stack trace. */
    static String sentence(Exception e, Settings s) {
        if (e instanceof ConnectorAuthException) return e.getMessage();
        if (e instanceof AuthenticationFailedException) {
            return "The mail server did not accept the user name or password. If your mail service offers app "
                    + "passwords (Gmail, iCloud, Yahoo, Zoho), use one.";
        }
        String server = s == null ? "The mail server" : s.host();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return "The mail server " + server + " could not be found.";
            if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
                return "The mail server " + server + " did not answer on port " + (s == null ? "" : s.port())
                        + ". Check the server, the port and the TLS setting.";
            }
            if (t instanceof CertificateException || t instanceof SSLHandshakeException) {
                return "This computer does not trust the mail server's certificate. If your company uses its own "
                        + "certificate authority, add it in Settings > Network.";
            }
            if (t instanceof SSLException) {
                return "The mail server did not speak TLS on port " + (s == null ? "" : s.port())
                        + ". Check the port and the TLS setting.";
            }
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.toLowerCase(Locale.ROOT).contains("starttls")) {
            return "The mail server does not offer STARTTLS on port " + (s == null ? "" : s.port())
                    + ". Choose TLS on port 993, or ask its administrator.";
        }
        return "The mail server refused the request: " + (message.isBlank() ? e.getClass().getSimpleName() : message);
    }
}
