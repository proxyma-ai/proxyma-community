package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorPluginProvider;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The services that predate the connector framework, each as a {@link ConnectionPlugin} over the
 * {@link ConnectionTester} that tests it - so the Test button reaches every service through the
 * catalog, the old ones and the new alike. The testers themselves are unchanged; each is retired
 * when its service gets a plugin of its own.
 */
@Component
public class LegacyConnectionPlugins implements ConnectorPluginProvider {

    private final List<ConnectionTester> testers;

    public LegacyConnectionPlugins(List<ConnectionTester> testers) {
        this.testers = testers;
    }

    @Override
    public List<ConnectionPlugin> connectionPlugins() {
        List<ConnectionPlugin> out = new ArrayList<>();
        for (ProtocolDescriptor d : CoreDescriptors.protocols()) {
            testers.stream().filter(t -> t.supports(d.protocol())).findFirst()
                    .ifPresent(t -> out.add(new Adapter(d, t)));
        }
        return out;
    }

    /**
     * One older service. Tested with the whole {@link ResolvedConnection} the tester was written
     * for (ConnectorConnectionController asks {@link #test(ResolvedConnection)}); the SPI's
     * {@link #test(ResolvedCredentials, ConnectorHttp)} rebuilds it for any other caller.
     */
    public static final class Adapter implements ConnectionPlugin {
        private final ProtocolDescriptor descriptor;
        private final ConnectionTester tester;

        // Public for a module that keeps a tester of its own beside its own descriptor - Proxyma
        // Enterprise's Microsoft 365 connection, whose descriptor is not a core one.
        public Adapter(ProtocolDescriptor descriptor, ConnectionTester tester) {
            this.descriptor = descriptor;
            this.tester = tester;
        }

        @Override
        public ProtocolDescriptor descriptor() {
            return descriptor;
        }

        public ConnectionTestResult test(ResolvedConnection connection) {
            ConnectionTester.Result r = tester.test(connection);
            return new ConnectionTestResult(r.ok(), r.message(), r.template(), r.params());
        }

        @Override
        public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) {
            String secret = c.secret("password") != null ? c.secret("password")
                    : c.secret("token") != null ? c.secret("token") : c.soleSecret();
            ConnectionProtocol protocol = c.protocol() == null ? descriptor.protocol() : c.protocol();
            return test(new ResolvedConnection(c.connectionId(), ConnectorConnection.LOCAL_OWNER, protocol.name(),
                    protocol, c.baseUrl(), c.username(), secret, false, true, false, null, null, null));
        }
    }
}
