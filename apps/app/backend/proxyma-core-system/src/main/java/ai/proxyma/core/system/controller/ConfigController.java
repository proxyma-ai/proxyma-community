package ai.proxyma.core.system.controller;

import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.system.util.SystemInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    @Value("${spring.application.version:0.0.0}")
    private String version;

    @Value("${server.port:4246}")
    private int serverPort;

    // Set by the `sandbox` profile (application-sandbox.properties): the public Enterprise
    // sandbox, writable and wiped nightly. The SPA uses it for its banner and the sign-in page's
    // account list - a courtesy; SandboxGuardFilter is the control.
    @Value("${proxyma.sandbox:false}")
    private boolean sandboxMode;

    // Set by the home server image (PROXYMA_HOME_SERVER in deploy-homeserver/Dockerfile). False
    // everywhere else, including a desktop install of the very same jar.
    @Value("${proxyma.home-server:false}")
    private boolean homeServer;

    private final ai.proxyma.core.registry.service.ConnectorEndpointResolver endpointResolver;
    private final SystemInfo systemInfo;
    private final ProductIdentity productIdentity;
    private final ai.proxyma.core.connector.files.FileSourceMode fileSourceMode;
    // Which types read through a connection, for the readiness map. Setter-injected, so the
    // constructor tests build this with is unchanged.
    private ai.proxyma.core.connector.spi.ConnectorCatalog catalog =
            ai.proxyma.core.connector.spi.ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ai.proxyma.core.connector.spi.ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    // What other modules add to /config (PublicConfigContributor) - none on Desktop and Home Server.
    // Setter-injected for the same reason as the catalog.
    private java.util.List<ai.proxyma.core.settings.config.PublicConfigContributor> contributors = java.util.List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setContributors(java.util.List<ai.proxyma.core.settings.config.PublicConfigContributor> contributors) {
        this.contributors = contributors == null ? java.util.List.of() : java.util.List.copyOf(contributors);
    }

    public ConfigController(ai.proxyma.core.registry.service.ConnectorEndpointResolver endpointResolver,
                            SystemInfo systemInfo,
                             ProductIdentity productIdentity,
                             ai.proxyma.core.connector.files.FileSourceMode fileSourceMode) {
        this.endpointResolver = endpointResolver;
        this.systemInfo = systemInfo;
        this.productIdentity = productIdentity;
        this.fileSourceMode = fileSourceMode;
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfig() {
        return Mono.fromCallable(() -> {
            Map<String, Object> body = new HashMap<>();
            body.put("version",    version);
            body.put("serverPort", serverPort);
            body.put("platform",   systemInfo.isWindows() ? "windows" : systemInfo.isMac() ? "macos" : "linux");
            body.put("branding",   productIdentity.toResponseMap());
            body.put("sandbox",    sandboxMode);
            // HOW A FILE SOURCE IS ADDED HERE: by uploading bytes, or by naming a folder the user
            // already has. Runtime, because one build serves both,
            // so this cannot be the compile-time constant it used to be in the SPA. The desktop
            // bundle and the home server's are the same bundle, and they differ on this.
            //
            // The SPA had it as FEATURES.fileUpload, derived from the build mode - which was right
            // for as long as "server build" and "takes uploads" were the same thing, and became
            // wrong the moment a desktop-built home server started taking them.
            body.put("fileUpload", fileSourceMode.uploadsEnabled());
            // WHICH SINGLE-USER EDITION THIS IS, for the About tab. The home server is the desktop
            // jar in a container, so the SPA's build mode says "desktop" for both. Deliberately its
            // own flag rather than inferred from `fileUpload` above: that one answers how files get
            // in, and reading it as an edition is matching on something other than the contract.
            body.put("homeServer", homeServer);
            // What the edition's own modules add - on Enterprise Server, which Microsoft 365 source
            // types the deployment has switched on. Switches only: see PublicConfigContributor.
            for (ai.proxyma.core.settings.config.PublicConfigContributor c : contributors) c.contribute(body);
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // What each connector still needs before a source of that type can be registered, so the Add
    // Source modal can say so BEFORE the user fills in a form - rather than accepting the form and
    // failing on submit, which is what happened when this reported the credential only.
    //
    // Two separate answers per connector, because they need different sentences: the credential and
    // the base URL. Both are the CALLER's own - ConnectorEndpointResolver has no deployment-wide
    // fallback for the URL, so on Enterprise Server each user sets their own default in Settings >
    // Connectors. (The network-share slot is the exception, and says so below.)
    //
    // Replaces /credential-status, whose two callers both needed the base URL as well. The SPA is
    // baked into the same jar as this controller (see SpaWebConfig), so there is no client out
    // there still asking for the old path.
    @GetMapping("/connector-readiness")
    public Mono<ResponseEntity<Map<String, Map<String, Boolean>>>> getConnectorReadiness(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(java.security.Principal::getName).defaultIfEmpty("")
                .flatMap(userId -> Mono.fromCallable(() -> {
                    String uid = userId.isEmpty() ? null : userId;
                    // The caller's DEFAULT connection for every type that reads through one, keyed by
                    // the type's wire name - from the catalog, so a new connector is here the day its
                    // plugin exists. An ENTERPRISE service (Microsoft 365) is deliberately absent: its
                    // readiness is whether the DEPLOYMENT is set up for it and whether THIS user has a
                    // signed-in connection, which the connection list says.
                    Map<String, Map<String, Boolean>> out = new java.util.LinkedHashMap<>();
                    for (var d : catalog.types()) {
                        if (d.protocol() == null || d.capabilities().edition() == ai.proxyma.core.connector.spi.Edition.ENTERPRISE) continue;
                        out.putIfAbsent(d.type().wireName(), readiness(endpointResolver.forType(d.type(), uid)));
                    }
                    // A Network share connection since 2026-10-01 - its "base URL" is the file
                    // server. Also keyed `smb`, as before, for the dialog that reads it.
                    if (out.containsKey(ai.proxyma.core.connector.ConnectorType.NETWORK_SHARE.wireName())) {
                        out.put("smb", out.get(ai.proxyma.core.connector.ConnectorType.NETWORK_SHARE.wireName()));
                    }
                    return ResponseEntity.ok(out);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static Map<String, Boolean> readiness(boolean credential, boolean baseUrl) {
        return Map.of("credential", credential, "baseUrl", baseUrl);
    }

    private static Map<String, Boolean> readiness(ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint e) {
        return readiness(e.hasCredential(), e.hasBaseUrl());
    }
}
