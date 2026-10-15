package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a source type or a service is looked up: which plugin serves it, and what its
 * descriptor says. Every question that used to be a switch over ConnectorType - is it shareable,
 * does it have a file-type filter, which service does it read through, which edition offers it, how
 * often does it sync - is asked here, so a new type answers all of them from its descriptor and no
 * shared file has a line to add.
 *
 * <p>Built LAZILY on first use from every {@link ConnectorPlugin}, {@link ConnectorPluginProvider}
 * and {@link ConnectionPlugin} bean. Lazily because the connection plugins sit in modules that
 * themselves read the catalog (the connection service validates against a protocol's descriptor),
 * and resolving them at construction would be a cycle.
 *
 * <p>A type or service with no plugin in this deployment - a test that wires none - still has a
 * descriptor if the application's own modules name it ({@link CoreDescriptors}), so a row already in
 * the database is described the same way everywhere; it is simply not offered. One described only by
 * a module this deployment does not have - Microsoft 365 on Desktop, whose plugins are Proxyma
 * Enterprise's - has no descriptor here at all, and every registration of it is refused.
 */
@Component
public class ConnectorCatalog {

    private final ObjectProvider<ConnectorPlugin> pluginBeans;
    private final ObjectProvider<ConnectorPluginProvider> providerBeans;
    private final ObjectProvider<ConnectionPlugin> connectionBeans;
    private final List<ConnectorPlugin> fixedPlugins;
    private final List<ConnectionPlugin> fixedConnections;

    private volatile State state;

    private record State(Map<ConnectorType, ConnectorPlugin> plugins,
                         Map<ConnectionProtocol, ConnectionPlugin> connections) {}

    @Autowired
    public ConnectorCatalog(ObjectProvider<ConnectorPlugin> plugins,
                            ObjectProvider<ConnectorPluginProvider> providers,
                            ObjectProvider<ConnectionPlugin> connections) {
        this.pluginBeans = plugins;
        this.providerBeans = providers;
        this.connectionBeans = connections;
        this.fixedPlugins = null;
        this.fixedConnections = null;
    }

    private ConnectorCatalog(List<ConnectorPlugin> plugins, List<ConnectionPlugin> connections) {
        this.pluginBeans = null;
        this.providerBeans = null;
        this.connectionBeans = null;
        this.fixedPlugins = List.copyOf(plugins);
        this.fixedConnections = List.copyOf(connections);
    }

    /** A catalog of exactly these plugins - for tests and for code built outside a Spring context. */
    public static ConnectorCatalog of(List<ConnectorPlugin> plugins, List<ConnectionPlugin> connections) {
        return new ConnectorCatalog(plugins == null ? List.of() : plugins, connections == null ? List.of() : connections);
    }

    /** No plugins, every core descriptor - what a test of a class that only ASKS needs. */
    public static ConnectorCatalog descriptorsOnly() {
        return of(List.of(), List.of());
    }

    private State state() {
        State s = state;
        if (s != null) return s;
        synchronized (this) {
            if (state != null) return state;
            Map<ConnectorType, ConnectorPlugin> plugins = new EnumMap<>(ConnectorType.class);
            Map<ConnectionProtocol, ConnectionPlugin> connections = new EnumMap<>(ConnectionProtocol.class);
            List<ConnectorPlugin> all = new ArrayList<>();
            if (fixedPlugins != null) {
                all.addAll(fixedPlugins);
            } else {
                providerBeans.orderedStream().forEach(p -> all.addAll(p.plugins()));
                pluginBeans.orderedStream().forEach(all::add);
            }
            for (ConnectorPlugin p : all) {
                ConnectorType type = p.descriptor().type();
                ConnectorPlugin previous = plugins.put(type, p);
                if (previous != null && previous != p) {
                    throw new IllegalStateException("Two plugins serve " + type + ": "
                            + previous.getClass().getName() + " and " + p.getClass().getName());
                }
            }
            List<ConnectionPlugin> conns = new ArrayList<>();
            if (fixedConnections != null) {
                conns.addAll(fixedConnections);
            } else {
                providerBeans.orderedStream().forEach(p -> conns.addAll(p.connectionPlugins()));
                connectionBeans.orderedStream().forEach(conns::add);
            }
            for (ConnectionPlugin c : conns) {
                ConnectionProtocol protocol = c.descriptor().protocol();
                ConnectionPlugin previous = connections.put(protocol, c);
                if (previous != null && previous != c) {
                    throw new IllegalStateException("Two connection plugins serve " + protocol);
                }
            }
            state = new State(Map.copyOf(plugins), Map.copyOf(connections));
            return state;
        }
    }

    // -- types --------------------------------------------------------------------------------

    public Optional<ConnectorPlugin> plugin(ConnectorType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(state().plugins().get(type));
    }

    /** Whether this deployment has a plugin for the type at all. */
    public boolean hasPlugin(ConnectorType type) {
        return plugin(type).isPresent();
    }

    /** The type's descriptor - its plugin's, else the core one; null for a type nobody describes. */
    public ConnectorDescriptor descriptor(ConnectorType type) {
        if (type == null) return null;
        ConnectorPlugin plugin = state().plugins().get(type);
        return plugin != null ? plugin.descriptor() : CoreDescriptors.of(type);
    }

    /** Every type this deployment has a plugin for, in the order they are offered. */
    public List<ConnectorDescriptor> types() {
        return state().plugins().values().stream()
                .map(ConnectorPlugin::descriptor)
                .sorted(Comparator.comparingInt(ConnectorDescriptor::order).thenComparing(ConnectorDescriptor::displayName))
                .toList();
    }

    /** The types this edition offers - listed, with a plugin here, and of an edition it carries. */
    public List<ConnectorDescriptor> visibleTypes(boolean enterpriseServer) {
        return types().stream()
                .filter(ConnectorDescriptor::listed)
                .filter(d -> d.capabilities().edition().offeredOn(enterpriseServer))
                .toList();
    }

    /**
     * The type a wire name means here. "files" is three types; the one with a plugin in this
     * deployment wins (Desktop's local folders, a server's uploads), else the first declared.
     */
    public ConnectorType typeForWireName(String wire) {
        List<ConnectorType> candidates = ConnectorType.forWireName(wire);
        for (ConnectorType t : candidates) if (hasPlugin(t)) return t;
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    // -- the questions every caller used to answer with its own switch ------------------------

    public boolean isShareable(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d != null && d.capabilities().shareable();
    }

    public boolean hasFileTypeFilter(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d != null && d.capabilities().fileTypeFilter();
    }

    public boolean hasRecursiveScope(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d != null && d.capabilities().recursiveScope();
    }

    public boolean isIncremental(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d != null && d.capabilities().incremental();
    }

    /** The edition that offers the type; COMMUNITY for one nobody describes. */
    public Edition edition(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d == null ? Edition.COMMUNITY : d.capabilities().edition();
    }

    /** Enterprise Server only, and an Enterprise license - Microsoft 365 is the case it was made for. */
    public boolean requiresEnterprise(ConnectorType type) {
        return edition(type) == Edition.ENTERPRISE;
    }

    /** The service a source of {@code type} reads through, or null for one that needs none. */
    public ConnectionProtocol protocolFor(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d == null ? null : d.protocol();
    }

    // -- services ------------------------------------------------------------------------------

    public Optional<ConnectionPlugin> connectionPlugin(ConnectionProtocol protocol) {
        return protocol == null ? Optional.empty() : Optional.ofNullable(state().connections().get(protocol));
    }

    /** The service's descriptor - its plugin's, else the core one; null for one nobody describes. */
    public ProtocolDescriptor protocol(ConnectionProtocol protocol) {
        if (protocol == null) return null;
        ConnectionPlugin plugin = state().connections().get(protocol);
        return plugin != null ? plugin.descriptor() : CoreDescriptors.of(protocol);
    }

    /** Every service described here - by a plugin or as a core one - in declaration order. */
    public List<ProtocolDescriptor> protocols() {
        List<ProtocolDescriptor> out = new ArrayList<>();
        for (ConnectionProtocol p : ConnectionProtocol.values()) {
            ProtocolDescriptor d = protocol(p);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** The services this edition offers. */
    public List<ProtocolDescriptor> visibleProtocols(boolean enterpriseServer) {
        return protocols().stream().filter(p -> p.edition().offeredOn(enterpriseServer)).toList();
    }

    /** The source types that read through {@code protocol} - its descriptor's, and every type naming it. */
    public Set<ConnectorType> sourceTypes(ConnectionProtocol protocol) {
        Set<ConnectorType> out = new LinkedHashSet<>();
        ProtocolDescriptor d = protocol(protocol);
        if (d != null) out.addAll(d.sourceTypes());
        for (ConnectorType t : ConnectorType.values()) {
            ConnectorDescriptor td = descriptor(t);
            if (td != null && td.protocol() == protocol) out.add(t);
        }
        return out;
    }

    /**
     * Whether SOME connection of {@code protocol} can serve a source of {@code type}: the service the
     * type reads through, or one whose descriptor lists the type among its
     * {@link ProtocolDescriptor#sourceTypes()} for at least one way of signing in.
     */
    public boolean serves(ConnectionProtocol protocol, ConnectorType type) {
        if (protocol == null || type == null) return false;
        if (protocol == protocolFor(type)) return true;
        ProtocolDescriptor d = protocol(protocol);
        return d != null && d.sourceTypes().contains(type) && d.authMethods().stream().anyMatch(m -> m.serves(type));
    }

    /**
     * Whether a connection of {@code protocol}, signed in with {@code kind} (null: the service's first
     * way), can serve a source of {@code type}. Its own service's types always; another's only where
     * the service lists the type AND the sign-in reaches it - one Atlassian Cloud site serves Jira
     * and Confluence, a Data Center token only the server it was made on. This is the ONE test of
     * which connections a source may read through: registration, a move, the resolver's default and
     * the connections the page offers all ask it.
     */
    public boolean serves(ConnectionProtocol protocol, AuthKind kind, ConnectorType type) {
        if (protocol == null || type == null) return false;
        if (protocol == protocolFor(type)) return true;
        ProtocolDescriptor d = protocol(protocol);
        return d != null && d.sourceTypes().contains(type) && d.auth(kind).serves(type);
    }

    /**
     * The services a source of {@code type} may read through: its own first, then every other that
     * serves it for some way of signing in.
     */
    public List<ConnectionProtocol> servingProtocols(ConnectorType type) {
        List<ConnectionProtocol> out = new ArrayList<>();
        ConnectionProtocol own = protocolFor(type);
        if (own == null) return out;
        out.add(own);
        for (ConnectionProtocol p : ConnectionProtocol.values()) {
            if (p != own && serves(p, type)) out.add(p);
        }
        return out;
    }

    /** The source types a connection of {@code protocol} signed in with {@code kind} serves. */
    public Set<ConnectorType> sourceTypes(ConnectionProtocol protocol, AuthKind kind) {
        Set<ConnectorType> out = new LinkedHashSet<>();
        for (ConnectorType t : sourceTypes(protocol)) if (serves(protocol, kind, t)) out.add(t);
        return out;
    }

    public String displayName(ConnectionProtocol protocol) {
        ProtocolDescriptor d = protocol(protocol);
        return d == null ? String.valueOf(protocol) : d.displayName();
    }

    public String displayName(ConnectorType type) {
        ConnectorDescriptor d = descriptor(type);
        return d == null ? String.valueOf(type) : d.displayName();
    }
}
