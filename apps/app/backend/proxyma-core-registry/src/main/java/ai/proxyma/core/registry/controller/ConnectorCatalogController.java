package ai.proxyma.core.registry.controller;

import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.Category;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.license.FeatureGateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every source type and every service this deployment offers, as the page renders them - the
 * Sources page's Connect menu, tabs, groups and marks, the source and connection dialogs, the
 * @-mention styles. One answer drawn from the descriptors, so a new connector appears in all of
 * them without a line of the page changing.
 *
 * <p>FILTERED BY EDITION: an ENTERPRISE type or service (Microsoft 365) is absent off Enterprise
 * Server, and on Enterprise Server is listed with {@code licensed: false} while no Enterprise license
 * is in force - the menu shows it locked, with its reason. A type a deployment switch has turned off
 * (Teams, Outlook) is listed with {@code offered: false}, so its existing sources still have a name
 * and a mark.
 */
@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorCatalogController {

    private final ConnectorCatalog catalog;
    private final FeatureGateService featureGate;
    private final boolean multiUser;

    public ConnectorCatalogController(ConnectorCatalog catalog, FeatureGateService featureGate,
                                      @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.catalog = catalog;
        this.featureGate = featureGate;
        this.multiUser = multiUser;
    }

    @GetMapping("/catalog")
    public Mono<ResponseEntity<Map<String, Object>>> catalog() {
        return Mono.fromCallable(() -> ResponseEntity.ok(describe())).subscribeOn(Schedulers.boundedElastic());
    }

    Map<String, Object> describe() {
        List<Map<String, Object>> types = new ArrayList<>();
        java.util.Set<String> wireNames = new java.util.HashSet<>();
        for (ConnectorDescriptor d : catalog.visibleTypes(multiUser)) {
            // One entry per wire name: "files" is three types, and the page knows one - the one this
            // deployment actually runs.
            String wire = d.type().wireName();
            if (catalog.typeForWireName(wire) != d.type() || !wireNames.add(wire)) continue;
            ConnectorPlugin plugin = catalog.plugin(d.type()).orElse(null);
            types.add(type(d, plugin == null || plugin.offered()));
        }
        List<Map<String, Object>> protocols = new ArrayList<>();
        for (ProtocolDescriptor p : catalog.visibleProtocols(multiUser)) protocols.add(protocol(p));
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Category c : Category.values()) categories.add(Map.of("key", c.name(), "label", c.label()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("protocols", protocols);
        out.put("categories", categories);
        return out;
    }

    private Map<String, Object> type(ConnectorDescriptor d, boolean offered) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.type().wireName());
        m.put("displayName", d.displayName());
        m.put("description", d.description());
        m.put("noun", d.noun());
        m.put("nounPlural", d.nounPlural());
        m.put("addTitle", d.addTitle());
        m.put("order", d.order());
        m.put("category", d.category().name());
        m.put("mark", d.mark());
        m.put("protocol", d.protocol() == null ? null : d.protocol().name());
        // Every service whose connections may serve it, its own first - an Atlassian Cloud connection
        // made as Jira serves a Confluence space. Which connection of those actually does is the
        // connection's own sourceTypes (it depends on how it signs in).
        m.put("connectionProtocols", catalog.servingProtocols(d.type()).stream().map(Enum::name).toList());
        m.put("fields", d.fields().stream().map(ConnectorCatalogController::field).toList());
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("incremental", d.capabilities().incremental());
        caps.put("fileTypeFilter", d.capabilities().fileTypeFilter());
        caps.put("recursiveScope", d.capabilities().recursiveScope());
        caps.put("shareable", d.capabilities().shareable());
        caps.put("edition", d.capabilities().edition().name());
        caps.put("customDialog", d.capabilities().customDialog());
        caps.put("liveRead", d.capabilities().liveRead());
        caps.put("push", d.capabilities().push());
        m.put("capabilities", caps);
        m.put("docsAnchor", d.docsAnchor());
        m.put("offered", offered);
        m.put("licensed", featureGate.getFeatures().isConnectorAllowed(d.capabilities().edition()));
        return m;
    }

    private Map<String, Object> protocol(ProtocolDescriptor p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocol", p.protocol().name());
        m.put("displayName", p.displayName());
        m.put("mark", p.mark());
        List<Map<String, Object>> methods = new ArrayList<>();
        for (AuthMethod a : p.authMethods()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("kind", a.kind().name());
            am.put("label", a.label());
            // A sign-in's OAuth client is the deployment's on Enterprise Server - set by an
            // administrator - so the person adding a connection is asked for nothing but the sign-in.
            boolean deploymentClient = multiUser && a.kind() == AuthKind.OAUTH2_CODE;
            am.put("fields", deploymentClient ? List.of() : a.fields().stream().map(ConnectorCatalogController::field).toList());
            // What a connection signed in this way serves - fewer than the service's where the
            // sign-in reaches less (a Data Center token, one product's server).
            am.put("sourceTypes", catalog.sourceTypes(p.protocol(), a.kind()).stream().map(t -> t.wireName())
                    .distinct().toList());
            methods.add(am);
        }
        m.put("authMethods", methods);
        m.put("baseUrl", p.baseUrl() == null ? null : field(p.baseUrl()));
        m.put("sourceTypes", catalog.sourceTypes(p.protocol()).stream().map(t -> t.wireName()).distinct().toList());
        m.put("supportsWrite", p.supportsWrite());
        m.put("edition", p.edition().name());
        m.put("docsAnchor", p.docsAnchor());
        m.put("customDialog", p.customDialog());
        m.put("licensed", featureGate.getFeatures().isConnectorAllowed(p.edition()));
        return m;
    }

    static Map<String, Object> field(FieldSpec f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key());
        m.put("label", f.label());
        m.put("type", f.type().name());
        m.put("required", f.required());
        m.put("placeholder", f.placeholder());
        m.put("help", f.help());
        m.put("defaultValue", f.defaultValue());
        m.put("options", f.options().stream().map(o -> Map.of("value", o.value(), "label", o.label())).toList());
        m.put("pattern", f.pattern());
        m.put("advanced", f.advanced());
        m.put("min", f.min());
        m.put("max", f.max());
        return m;
    }
}
