package ai.proxyma.connectors.imap;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocketFactory;
import java.util.List;

/**
 * Any mail host reached over IMAP - one mailbox per connection: the server, its port, how the
 * connection is protected (TLS by default, STARTTLS, or none for a server on this computer), a user
 * name and a password. Most mail services want an APP password here rather than the account's own -
 * Gmail with two-step verification, iCloud, Yahoo, Zoho - and Microsoft has turned passwords off for
 * IMAP altogether, Outlook.com (September 2024) and Exchange Online alike, so a Microsoft mailbox is
 * read through the Outlook source; proxyma.ai/docs/manual/#imap says which. Signing in with a token from
 * another connection (XOAUTH2) is not offered.
 *
 * <p>There is no address to type as a URL: the server is a host name and a port, kept as the
 * connection's settings ({@link ImapAccount}).
 *
 * <p>Test signs in and lists the folders: "Signed in as ana@example.com on imap.example.com".
 */
@Component
public class ImapConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.IMAP)
            .displayName("IMAP mail")
            .mark("imap")
            .auth(AuthMethod.of(AuthKind.BASIC, "User name and password",
                    FieldSpec.text(ImapAccount.HOST, "Mail server").asRequired()
                            .placeholder("imap.example.com")
                            .help("The IMAP server's host name, as your mail service gives it."),
                    FieldSpec.select(ImapAccount.SECURITY, "Security", List.of(
                                    new FieldSpec.Option("tls", "TLS"),
                                    new FieldSpec.Option("starttls", "STARTTLS"),
                                    new FieldSpec.Option("none", "None (this computer only)")))
                            .defaultValue("tls")
                            .help("TLS on port 993 for almost every mail service. STARTTLS is port 143."),
                    FieldSpec.number(ImapAccount.PORT, "Port").range(1, 65535).placeholder("993")
                            .help("Leave it empty for the usual port: 993 with TLS, 143 otherwise.").asAdvanced(),
                    FieldSpec.text(AuthMethod.USERNAME, "User name").asRequired()
                            .placeholder("ana@example.com")
                            .help("Usually your whole email address."),
                    FieldSpec.secret(ImapAccount.PASSWORD, "Password").asRequired()
                            .help("An app password where your mail service offers them. "
                                    + "Microsoft 365 and Outlook.com mailboxes accept no password over IMAP.")))
            .sourceTypes(ConnectorType.IMAP)
            .docsAnchor("imap")
            .build();

    private final SSLSocketFactory sockets;

    public ImapConnectionPlugin() {
        this(null);
    }

    /** A plugin whose TLS trusts what {@code sockets} trusts - for a test's own server. */
    ImapConnectionPlugin(SSLSocketFactory sockets) {
        this.sockets = sockets;
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        ImapAccount.Settings account = null;
        Store store = null;
        try {
            account = ImapAccount.of(connection);
            store = ImapAccount.connect(account, sockets);
            Folder[] folders = store.getDefaultFolder().list("*");
            if (folders.length == 0) return ConnectionTestResult.failed("The mail server signed in but lists no folders.");
            return ConnectionTestResult.ok("Signed in as {user} on {site}", "user", account.username(), "site", account.host());
        } catch (AuthenticationFailedException e) {
            return ConnectionTestResult.failed("{service} did not accept the username or password.", "service", account.host());
        } catch (MessagingException | ConnectorAuthException e) {
            return ConnectionTestResult.failed(ImapAccount.sentence(e, account));
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(ImapAccount.sentence(e, account));
        } finally {
            ImapSession.closeQuietly(store);
        }
    }
}
