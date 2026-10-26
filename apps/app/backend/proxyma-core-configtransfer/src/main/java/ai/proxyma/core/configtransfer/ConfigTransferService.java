package ai.proxyma.core.configtransfer;

import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.SystemPromptService;
import ai.proxyma.core.chat.service.WorkflowService;
import ai.proxyma.core.guardrail.policy.PolicyDocument;
import ai.proxyma.core.guardrail.policy.PolicyLibrary;
import ai.proxyma.core.settings.connection.ConnectionAuthKind;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ConnectorConnectionView;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.pricing.ModelPricing;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.GatewayApi;
import ai.proxyma.core.settings.provider.ProviderHeaders;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderInstanceView;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.dto.SettingsSecrets;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles a deployment's whole configuration into one file, and puts one back.
 *
 * <p>Five stores make up "how this deployment is configured", and before this class there was no
 * way to see them together: the GLOBAL_SETTINGS table, the provider_instances table, the skills
 * directory, the workflows directory, and the system-prompt file. This reads all five and writes
 * all five, through each owner's own public API rather than behind its back - so every rule those
 * services enforce (validation, encryption, the model-role reinit event, name uniqueness) still
 * applies to an imported value exactly as it would to a typed one.
 *
 * <p>SINCE 2026-09-27 IT IS SEVEN STORES, AND THE READER CHOOSES AMONG THEM. The model pricing
 * table and the guardrail policy documents joined, so everything travels except the license, the
 * API tokens, the account and the connectors' Read/Write switches (a consent record) - and every export and import takes a selection of {@link TransferGroups},
 * named after the Settings tabs. Not choosing means everything, as before.
 *
 * <p>IMPORT IS A MERGE, NEVER A REPLACE. Only what the file contains is written; nothing is deleted
 * and nothing is reset to a default. A configuration file is a statement about some settings, not a
 * claim to be the whole truth about a deployment - and the alternative, wiping what the file does
 * not mention, turns a partial export into a destructive act.
 */
@Service
public class ConfigTransferService {

    private static final Logger log = LoggerFactory.getLogger(ConfigTransferService.class);

    /** Bumped only for a shape change an older build could not read past. */
    public static final int FORMAT_VERSION = 1;

    private static final String SECTION_SETTINGS = "settings";
    private static final String SECTION_PROVIDERS = "providers";
    private static final String SECTION_SKILLS = "skills";
    private static final String SECTION_WORKFLOWS = "workflows";
    private static final String SECTION_PROMPT = "systemPrompt";
    private static final String SECTION_PRICING = "modelPricing";
    private static final String SECTION_POLICIES = "guardrailPolicies";
    private static final String SECTION_CONNECTIONS = "connections";

    /** Settings groups the settings API serves on Enterprise Server only - the Entra app registration. */
    private static final Set<String> SERVER_ONLY_PREFIXES = Set.of("m365");

    private final SettingsService settingsService;
    private final ProviderInstanceService providerInstanceService;
    private final ChatSkillService chatSkillService;
    private final WorkflowService workflowService;
    private final SystemPromptService systemPromptService;
    private final ModelPricingService modelPricingService;
    private final PolicyLibrary policyLibrary;
    private final ConnectorConnectionService connectionService;
    private final ObjectMapper objectMapper;

    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    // spring.application.version is what the build sets and every other version reader uses;
    // proxyma.version is set nowhere, so every file said "unknown" (settings-core-22).
    @Value("${spring.application.version:unknown}")
    private String appVersion;

    public ConfigTransferService(SettingsService settingsService,
                                 ProviderInstanceService providerInstanceService,
                                 ChatSkillService chatSkillService,
                                 WorkflowService workflowService,
                                 SystemPromptService systemPromptService,
                                 ModelPricingService modelPricingService,
                                 PolicyLibrary policyLibrary,
                                 ConnectorConnectionService connectionService,
                                 ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.providerInstanceService = providerInstanceService;
        this.chatSkillService = chatSkillService;
        this.workflowService = workflowService;
        this.systemPromptService = systemPromptService;
        this.modelPricingService = modelPricingService;
        this.policyLibrary = policyLibrary;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
    }

    // How each connection's service signs in - its descriptor. Setter-injected, so the constructor
    // tests build this with is unchanged.
    private ai.proxyma.core.connector.spi.ConnectorCatalog catalog =
            ai.proxyma.core.connector.spi.ConnectorCatalog.descriptorsOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalog(ai.proxyma.core.connector.spi.ConnectorCatalog catalog) {
        this.catalog = catalog;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * @param passphrase null or blank to export WITHOUT secrets - every secret becomes
     *                   {@code ***omitted***} and the file is safe to attach to a ticket. With a
     *                   passphrase, secrets travel in the sealed envelope instead.
     * @param groups     the {@link TransferGroups} to include; null or empty for all of them.
     */
    public ConfigExport export(String passphrase, String requesterId, List<String> groups) {
        boolean withSecrets = passphrase != null && !passphrase.isBlank();
        Set<String> chosen = TransferGroups.selection(groups);
        // path -> plaintext value, collected as the sections are walked and sealed once at the end.
        Map<String, String> secrets = new LinkedHashMap<>();

        Map<String, Map<String, String>> settings = exportSettings(secrets, chosen);
        List<ConfigExport.Provider> providers = chosen.contains(TransferGroups.PROVIDERS)
                ? exportProviders(secrets) : List.of();
        List<ConfigExport.Skill> skills = chosen.contains(TransferGroups.SKILLS)
                ? exportSkills(requesterId) : List.of();
        List<ConfigExport.Workflow> workflows = chosen.contains(TransferGroups.WORKFLOWS)
                ? exportWorkflows(requesterId) : List.of();
        String systemPrompt = chosen.contains(TransferGroups.AGENTS)
                ? blankToNull(systemPromptService.readSaved()) : null;
        List<ConfigExport.ModelPrice> pricing = chosen.contains(TransferGroups.USAGE)
                ? exportPricing() : List.of();
        List<ConfigExport.PolicyFile> policies = chosen.contains(TransferGroups.GUARDRAILS)
                ? exportPolicies() : List.of();
        List<ConfigExport.Connection> connections = chosen.contains(TransferGroups.CONNECTORS)
                ? exportConnections(requesterId, secrets) : List.of();

        SecretEnvelope.Sealed sealed = null;
        if (withSecrets && !secrets.isEmpty()) {
            sealed = SecretEnvelope.seal(secrets, passphrase, objectMapper);
        } else if (!withSecrets) {
            // Rewrite the placeholders that were written on the assumption a passphrase was coming.
            settings = replacePlaceholder(settings);
            providers = providers.stream()
                    .map(p -> new ConfigExport.Provider(p.name(), p.protocol(), p.baseUrl(), omitted(p.headers()),
                            ConfigExport.SECRET_PLACEHOLDER.equals(p.apiKey()) ? ConfigExport.OMITTED_PLACEHOLDER
                                    : p.apiKey(), p.sourceId(), p.api(), p.blockingCalls()))
                    .toList();
            connections = connections.stream()
                    .map(c -> ConfigExport.SECRET_PLACEHOLDER.equals(c.secret())
                            ? new ConfigExport.Connection(c.name(), c.protocol(), c.baseUrl(), c.username(),
                                    ConfigExport.OMITTED_PLACEHOLDER, c.isDefault())
                            : c)
                    .toList();
        }

        log.info("[ConfigTransfer] exported {} settings prefix(es), {} provider(s), {} skill(s), "
                        + "{} workflow(s), {} price(s), {} policy document(s), {} secret(s){}",
                settings.size(), providers.size(), skills.size(), workflows.size(), pricing.size(),
                policies.size(), secrets.size(), withSecrets ? " (sealed)" : " (omitted)");

        return new ConfigExport(
                new ConfigExport.Meta(FORMAT_VERSION, Instant.now().toString(), appVersion,
                        multiUser ? "server" : "desktop", sealed != null),
                sealed, settings, providers, skills, workflows, systemPrompt, pricing, policies, connections);
    }

    private Map<String, Map<String, String>> exportSettings(Map<String, String> secrets, Set<String> chosen) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String prefix : SettingsPrefixRegistry.exportablePrefixes()) {
            if (!chosen.contains(TransferGroups.ofPrefix(prefix))) continue;
            // Already decrypted where the store happened to hold ciphertext - and already plaintext
            // where it did not, which is exactly why the SECRET test below asks the registry rather
            // than looking at the value.
            Map<String, Object> fields = settingsService.getSettingsFields(prefix);
            if (fields.isEmpty()) continue;
            Map<String, String> group = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                if (SettingsPrefixRegistry.isSecret(prefix, e.getKey())) {
                    if (value.isBlank()) continue;
                    secrets.put(SECTION_SETTINGS + "/" + prefix + "/" + e.getKey(), value);
                    group.put(e.getKey(), ConfigExport.SECRET_PLACEHOLDER);
                } else {
                    group.put(e.getKey(), value);
                }
            }
            if (!group.isEmpty()) out.put(prefix, group);
        }
        return out;
    }

    private List<ConfigExport.ModelPrice> exportPricing() {
        List<ConfigExport.ModelPrice> out = new ArrayList<>();
        for (ModelPricing p : modelPricingService.listAll()) {
            out.add(new ConfigExport.ModelPrice(p.getProvider(), p.getModel(), p.getInputPricePerMillion(),
                    p.getOutputPricePerMillion(), p.getCacheReadPricePerMillion(),
                    p.getCacheWritePricePerMillion(), p.getCacheWriteLongPricePerMillion(),
                    p.getLongContextThresholdTokens(), p.getLongInputPricePerMillion(),
                    p.getLongOutputPricePerMillion(), p.getLongCacheReadPricePerMillion(),
                    p.getLongCacheWritePricePerMillion(), p.getPricingSchedule()));
        }
        return out;
    }

    private List<ConfigExport.PolicyFile> exportPolicies() {
        List<ConfigExport.PolicyFile> out = new ArrayList<>();
        for (PolicyDocument d : policyLibrary.list()) {
            // A document with no stored file (one added from a URL before that was removed) has
            // nothing to carry; it is left out rather than exported as an empty policy.
            policyLibrary.readContent(d.id()).ifPresent(bytes -> out.add(new ConfigExport.PolicyFile(
                    d.kindOrDefault().name(), d.displayName(), Base64.getEncoder().encodeToString(bytes))));
        }
        return out;
    }

    private List<ConfigExport.Provider> exportProviders(Map<String, String> secrets) {
        List<ConfigExport.Provider> out = new ArrayList<>();
        for (ResolvedProvider p : providerInstanceService.resolveAll()) {
            String apiKey = null;
            if (p.hasApiKey()) {
                secrets.put(SECTION_PROVIDERS + "/" + p.name() + "/apiKey", p.apiKey());
                apiKey = ConfigExport.SECRET_PLACEHOLDER;
            }
            // A header the page treats as a credential (Authorization, X-Api-Key, a gateway's tenant
            // key) travels as a secret, like the key beside it (settings-core-04).
            Map<String, String> headers = null;
            if (p.headers() != null && !p.headers().isEmpty()) {
                headers = new LinkedHashMap<>();
                for (Map.Entry<String, String> h : p.headers().entrySet()) {
                    if (ProviderHeaders.isSecret(h.getKey()) && h.getValue() != null && !h.getValue().isBlank()) {
                        secrets.put(headerPath(p.name(), h.getKey()), h.getValue());
                        headers.put(h.getKey(), ConfigExport.SECRET_PLACEHOLDER);
                    } else {
                        headers.put(h.getKey(), h.getValue());
                    }
                }
            }
            out.add(new ConfigExport.Provider(p.name(), p.protocol().name(), p.baseUrl(),
                    headers, apiKey, p.id().toString(),
                    p.api() == null ? null : p.api().name(), p.blockingCalls() ? Boolean.TRUE : null));
        }
        return out;
    }

    private static String headerPath(String provider, String header) {
        return SECTION_PROVIDERS + "/" + provider + "/headers/" + header;
    }

    // A file exported without a passphrase: a credential header says so instead of carrying "$secret".
    private static Map<String, String> omitted(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, ConfigExport.SECRET_PLACEHOLDER.equals(v) ? ConfigExport.OMITTED_PLACEHOLDER : v));
        return out;
    }

    // The file's headers as the target stores them: a sealed credential opened, one the file does
    // not carry left out rather than written as a placeholder that would be sent to the provider.
    private static Map<String, String> importedHeaders(ConfigExport.Provider p, Map<String, String> secrets) {
        if (p.headers() == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        p.headers().forEach((name, value) -> {
            String resolved = resolveSecret(value, headerPath(p.name(), name), secrets);
            if (resolved != null) out.put(name, resolved);
        });
        return out;
    }

    // The REQUESTER's own connections - a connection is a person's, so an export carries the
    // exporting person's and nobody else's, exactly as the connections page shows them.
    private List<ConfigExport.Connection> exportConnections(String requesterId, Map<String, String> secrets) {
        List<ConfigExport.Connection> out = new ArrayList<>();
        for (ConnectorConnectionView v : connectionService.list(requesterId)) {
            ResolvedConnection c = connectionService.resolveOwned(requesterId, v.id()).orElse(null);
            if (c == null) continue;
            // NOT CARRIED, deliberately: a local MCP server's settings are a command to run, and a
            // configuration file is something people send each other. Importing one should never
            // be a way to make another machine start a program. Added again by hand, on purpose.
            if (c.protocol() == ConnectionProtocol.MCP_SERVER) continue;
            String secret = null;
            // An OAuth refresh token never travels - see ConfigExport.Connection.
            if (ConnectionAuthKind.of(c.authKind()) != ConnectionAuthKind.OAUTH && c.hasSecret()) {
                secrets.put(SECTION_CONNECTIONS + "/" + c.name() + "/secret", c.secret());
                secret = ConfigExport.SECRET_PLACEHOLDER;
            }
            out.add(new ConfigExport.Connection(c.name(), c.protocol().name(), c.baseUrl(), c.username(),
                    secret, c.isDefault()));
        }
        return out;
    }

    private List<ConfigExport.Skill> exportSkills(String requesterId) {
        List<ConfigExport.Skill> out = new ArrayList<>();
        for (ChatSkillService.SkillSummary s : chatSkillService.list(requesterId, true)) {
            // A built-in product skill is seeded on every deployment and is not editable, so
            // carrying one would at best be a no-op and at worst re-create a stale copy of
            // something the target already ships. Only what a person actually wrote travels.
            if (s.official()) continue;
            ChatSkillService.SkillDetail d = chatSkillService.get(s.id(), requesterId, true);
            out.add(new ConfigExport.Skill(d.name(), d.shortDescription(), d.detail(),
                    d.tags(), d.tools(), d.connectorNames(), d.shared()));
        }
        return out;
    }

    private List<ConfigExport.Workflow> exportWorkflows(String requesterId) {
        List<ConfigExport.Workflow> out = new ArrayList<>();
        for (WorkflowService.WorkflowSummary s : workflowService.list(requesterId, true)) {
            WorkflowService.WorkflowDetail d = workflowService.get(s.id(), requesterId, true);
            List<ConfigExport.Workflow.Step> steps = d.steps().stream()
                    .map(st -> new ConfigExport.Workflow.Step(st.title(), st.instruction()))
                    .toList();
            out.add(new ConfigExport.Workflow(d.name(), d.shortDescription(), d.preamble(), steps,
                    d.tags(), d.tools(), d.connectorNames(), d.shared(), d.provider(), d.model()));
        }
        return out;
    }

    private static Map<String, Map<String, String>> replacePlaceholder(Map<String, Map<String, String>> in) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        in.forEach((prefix, group) -> {
            Map<String, String> copy = new LinkedHashMap<>();
            group.forEach((k, v) -> copy.put(k,
                    ConfigExport.SECRET_PLACEHOLDER.equals(v) ? ConfigExport.OMITTED_PLACEHOLDER : v));
            out.put(prefix, copy);
        });
        return out;
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Walks the file and reports what would change. {@code apply == false} writes nothing.
     *
     * <p>One method for preview and apply, on purpose: a preview assembled by different code from
     * the apply is a promise the button does not have to keep, and the two drift the moment either
     * gains a rule the other does not.
     */
    public ConfigImportReport importConfig(ConfigExport file, String passphrase, boolean apply,
                                           String requesterId, List<String> groups) {
        ConfigImportReport report = new ConfigImportReport();
        if (file == null) return report;
        Set<String> chosen = TransferGroups.selection(groups);

        Map<String, String> secrets = Map.of();
        boolean fileHasSecrets = file.secrets() != null;
        if (fileHasSecrets) {
            // Throws WrongPassphraseException, which the controller maps to a 400 carrying the one
            // sentence worth reading. Done BEFORE anything is written, so a bad passphrase costs a
            // rejected request rather than a half-applied configuration.
            secrets = SecretEnvelope.open(file.secrets(), passphrase, objectMapper);
            report.markSecretsApplied();
        }

        // Providers are walked even when they were not chosen: the remap they produce is what
        // re-points a chosen model role at a provider of the same name that is ALREADY here. Not
        // chosen, nothing is created and nothing is reported - only the names are matched.
        Map<String, String> providerIdRemap = importProviders(file, secrets, apply, report,
                chosen.contains(TransferGroups.PROVIDERS));
        importSettings(file, secrets, providerIdRemap, apply, report, chosen);
        if (chosen.contains(TransferGroups.SKILLS)) importSkills(file, requesterId, apply, report);
        if (chosen.contains(TransferGroups.WORKFLOWS)) {
            importWorkflows(file, requesterId, providerIdRemap, apply, report);
        }
        if (chosen.contains(TransferGroups.AGENTS)) importSystemPrompt(file, apply, report);
        if (chosen.contains(TransferGroups.USAGE)) importPricing(file, apply, report);
        if (chosen.contains(TransferGroups.GUARDRAILS)) importPolicies(file, apply, report);
        if (chosen.contains(TransferGroups.CONNECTORS)) importConnections(file, secrets, requesterId, apply, report);

        if (apply) {
            log.info("[ConfigTransfer] import applied: {} created, {} updated, {} unchanged, {} skipped",
                    report.count(ConfigImportReport.Outcome.CREATED),
                    report.count(ConfigImportReport.Outcome.UPDATED),
                    report.count(ConfigImportReport.Outcome.UNCHANGED),
                    report.count(ConfigImportReport.Outcome.SKIPPED));
        }
        return report;
    }

    /**
     * Providers go FIRST, and by NAME.
     *
     * <p>The ids in the file belong to the deployment that wrote it. Every model role - chat,
     * embedding, query expansion, reranking, sub-agent, graph, ocr - stores a provider id, so
     * importing the settings without re-pointing them restores a configuration that names providers
     * this deployment has never heard of and therefore cannot run. The returned map is
     * {@code sourceId -> targetId} and is applied to every settings value in importSettings below.
     */
    private Map<String, String> importProviders(ConfigExport file, Map<String, String> secrets,
                                                boolean apply, ConfigImportReport report, boolean chosen) {
        Map<String, String> remap = new LinkedHashMap<>();
        if (file.providers() == null) return remap;

        Map<String, ProviderInstanceView> existingByName = new LinkedHashMap<>();
        for (ProviderInstanceView v : providerInstanceService.list()) {
            existingByName.put(v.name().toLowerCase(Locale.ROOT), v);
        }

        for (ConfigExport.Provider p : file.providers()) {
            if (p.name() == null || p.name().isBlank()) continue;
            ProviderInstanceView existing = existingByName.get(p.name().toLowerCase(Locale.ROOT));
            if (existing != null) {
                // A provider with this name is already configured here. Left exactly as it is -
                // overwriting it would silently re-point every role that uses it, and its
                // credential is the kind of thing someone set on purpose. The remap still records
                // it, so the imported roles attach to the provider that IS here.
                if (p.sourceId() != null) remap.put(p.sourceId(), existing.id().toString());
                if (chosen) {
                    report.add(SECTION_PROVIDERS, TransferGroups.PROVIDERS, p.name(),
                            ConfigImportReport.Outcome.SKIPPED,
                            "A provider with this name is already configured; kept as it is.");
                }
                continue;
            }
            if (!chosen) continue;
            String apiKey = resolveSecret(p.apiKey(), SECTION_PROVIDERS + "/" + p.name() + "/apiKey", secrets);
            Map<String, String> headers = importedHeaders(p, secrets);
            boolean headerMissing = headers != null && headers.size() < p.headers().size();
            boolean credentialMissing = apiKey == null || headerMissing;
            ProviderProtocol protocol = parseProtocol(p.protocol());
            if (protocol == null) {
                report.add(SECTION_PROVIDERS, TransferGroups.PROVIDERS, p.name(), ConfigImportReport.Outcome.SKIPPED,
                        "Unknown protocol '" + p.protocol() + "' - this build cannot speak it.");
                continue;
            }
            if (!apply) {
                report.add(SECTION_PROVIDERS, TransferGroups.PROVIDERS, p.name(), ConfigImportReport.Outcome.CREATED,
                        credentialMissing ? "Will be created without a credential." : "Will be created.");
                continue;
            }
            try {
                ProviderInstanceView created = providerInstanceService.create(
                        new ProviderInstanceService.ProviderInstanceInput(
                                // nativeRerank is null: the transfer format has never carried a
                                // provider's capability flags, so an imported instance gets the
                                // default and the operator re-ticks it. There used to be three such
                                // flags; reportsCost and local went with the settings behind them.
                                // A gateway's API and call mode are configuration, not capability,
                                // and travel.
                                p.name(), protocol, p.baseUrl(), apiKey, false,
                                headers, null, GatewayApi.fromValue(p.api()), p.blockingCalls()));
                if (p.sourceId() != null) remap.put(p.sourceId(), created.id().toString());
                report.add(SECTION_PROVIDERS, TransferGroups.PROVIDERS, p.name(), ConfigImportReport.Outcome.CREATED,
                        credentialMissing ? "Created without a credential - add one before use." : "Created.");
            } catch (RuntimeException e) {
                // Provider validation is real (Azure and self-hosted runtimes are rejected without
                // a base URL, names must be unique) and its messages are written to be shown.
                report.add(SECTION_PROVIDERS, TransferGroups.PROVIDERS, p.name(), ConfigImportReport.Outcome.SKIPPED, e.getMessage());
            }
        }
        return remap;
    }

    /**
     * The file's connections, created as the IMPORTING person's own. One already here by that name
     * is left exactly as it is, as a provider is: its credential is the kind of thing somebody set
     * on purpose, and sources may be reading through it.
     *
     * <p>Read and Write are not in the file (see ConfigExport.Connection), so a created connection
     * starts at the defaults: the agent may read through it and may not write.
     */
    private void importConnections(ConfigExport file, Map<String, String> secrets, String requesterId,
                                   boolean apply, ConfigImportReport report) {
        if (file.connections() == null) return;
        Set<String> existing = new LinkedHashSet<>();
        for (ConnectorConnectionView v : connectionService.list(requesterId)) {
            existing.add(v.name().toLowerCase(Locale.ROOT));
        }
        for (ConfigExport.Connection c : file.connections()) {
            if (c.name() == null || c.name().isBlank()) continue;
            if (existing.contains(c.name().toLowerCase(Locale.ROOT))) {
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.SKIPPED,
                        "You already have a connection with this name; kept as it is.");
                continue;
            }
            ConnectionProtocol protocol = ConnectionProtocol.fromValue(c.protocol());
            if (protocol == ConnectionProtocol.MCP_SERVER) {
                // See exportConnections: never written to a file, and never read from one.
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.SKIPPED,
                        "MCP server connections are not imported. Add them in Settings > Connections.");
                continue;
            }
            if (protocol == null || !connectionService.offeredProtocols().contains(protocol)) {
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.SKIPPED,
                        "'" + c.protocol() + "' connections are not available here.");
                continue;
            }
            String secret = resolveSecret(c.secret(), SECTION_CONNECTIONS + "/" + c.name() + "/secret", secrets);
            var descriptor = catalog.protocol(protocol);
            ConnectionAuthKind kind = ConnectionAuthKind.of(descriptor == null ? null : descriptor.primaryAuth().kind());
            String note = kind == ConnectionAuthKind.OAUTH
                    ? "Sign in to it before use."
                    : secret == null ? "Add its " + (kind == ConnectionAuthKind.BASIC ? "password" : "token")
                            + " before use." : null;
            if (!apply) {
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.CREATED,
                        note == null ? "Will be created." : "Will be created. " + note);
                continue;
            }
            try {
                connectionService.create(requesterId, new ConnectorConnectionService.ConnectionInput(
                        c.name(), protocol, c.baseUrl(), c.username(), secret, false, c.isDefault(),
                        null, null));
                existing.add(c.name().toLowerCase(Locale.ROOT));
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.CREATED,
                        note == null ? "Created." : "Created. " + note);
            } catch (RuntimeException e) {
                // The service's validation messages are written to be shown - a missing URL, a tier
                // that does not include the service.
                report.add(SECTION_CONNECTIONS, TransferGroups.CONNECTORS, c.name(), ConfigImportReport.Outcome.SKIPPED,
                        e.getMessage());
            }
        }
    }

    private void importSettings(ConfigExport file, Map<String, String> secrets,
                                Map<String, String> providerIdRemap, boolean apply,
                                ConfigImportReport report, Set<String> chosen) {
        if (file.settings() == null) return;
        // The provider ids the FILE knows - so a role naming one that has no counterpart here can be
        // told from an ordinary value (settings-core-05).
        Set<String> fileProviderIds = new LinkedHashSet<>();
        if (file.providers() != null) {
            for (ConfigExport.Provider p : file.providers()) {
                if (p.sourceId() != null) fileProviderIds.add(p.sourceId());
            }
        }

        for (Map.Entry<String, Map<String, String>> group : file.settings().entrySet()) {
            String prefix = SettingsPrefixRegistry.normalize(group.getKey());
            String transferGroup = TransferGroups.ofPrefix(prefix);
            if (!chosen.contains(transferGroup)) continue;
            boolean restart = SettingsService.requiresRestart(prefix);
            if (SettingsPrefixRegistry.EXCLUDED_PREFIXES.contains(prefix)) {
                report.add(SECTION_SETTINGS, transferGroup, prefix, ConfigImportReport.Outcome.SKIPPED,
                        "This group is fixed at deploy time or belongs to one user, not the deployment.");
                continue;
            }
            if (SettingsPrefixRegistry.classFor(prefix) == null) {
                report.add(SECTION_SETTINGS, transferGroup, prefix, ConfigImportReport.Outcome.SKIPPED,
                        "This build has no settings group by that name.");
                continue;
            }
            // The settings API refuses this prefix off Enterprise Server (SettingsController), and an
            // import is the same write by another door - so it refuses it too.
            if (!multiUser && SERVER_ONLY_PREFIXES.contains(prefix)) {
                report.add(SECTION_SETTINGS, transferGroup, prefix, ConfigImportReport.Outcome.SKIPPED,
                        "This group exists on Enterprise Server only.");
                continue;
            }
            Map<String, Object> current = settingsService.getSettingsFields(prefix);
            Map<String, String> writes = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : group.getValue().entrySet()) {
                String key = field.getKey();
                String incoming = resolveSecret(field.getValue(),
                        SECTION_SETTINGS + "/" + prefix + "/" + key, secrets);
                String label = prefix + "." + key;

                if (!SettingsSecrets.isKnownField(prefix, key)) {
                    report.add(SECTION_SETTINGS, transferGroup, label, ConfigImportReport.Outcome.SKIPPED,
                            "This build has no setting by that name.");
                    continue;
                }
                if (incoming == null) {
                    report.add(SECTION_SETTINGS, transferGroup, label, ConfigImportReport.Outcome.SKIPPED,
                            ConfigExport.OMITTED_PLACEHOLDER.equals(field.getValue())
                                    ? "Exported without secrets - the existing value is kept."
                                    : "No value for this field in the file's secrets.");
                    continue;
                }
                // Re-point a role that names a provider from the source deployment. One whose
                // provider was neither matched here nor created would name an instance this
                // deployment does not have, and switch the role off - so it is not written.
                String remapped = providerIdRemap.get(incoming);
                if (remapped != null) {
                    incoming = remapped;
                } else if (fileProviderIds.contains(incoming)) {
                    report.add(SECTION_SETTINGS, transferGroup, label, ConfigImportReport.Outcome.SKIPPED,
                            "Its provider is not on this deployment - import AI Providers too, or choose one here.");
                    continue;
                }

                String existing = current.get(key) == null ? null : String.valueOf(current.get(key));
                if (Objects.equals(existing, incoming)) {
                    report.add(SECTION_SETTINGS, transferGroup, label, ConfigImportReport.Outcome.UNCHANGED, null);
                    continue;
                }
                ConfigImportReport.Outcome outcome = existing == null || existing.isBlank()
                        ? ConfigImportReport.Outcome.CREATED : ConfigImportReport.Outcome.UPDATED;
                writes.put(key, incoming);
                // A secret's VALUE is never put in the report - the report is rendered on screen
                // and travels back over the wire. Only that it changed.
                report.add(SECTION_SETTINGS, transferGroup, label, outcome,
                        SettingsPrefixRegistry.isSecret(prefix, key) ? "Credential set." : null, restart);
            }
            if (apply && !writes.isEmpty()) {
                // The one write path, for secrets too: it encrypts by the @EncryptedField annotation
                // since SEC-31. One write and one model-role reinit per group, not per field.
                settingsService.saveFields(prefix, writes);
                if (restart) report.markRequiresRestart();
            }
        }
    }

    private void importSkills(ConfigExport file, String requesterId, boolean apply,
                              ConfigImportReport report) {
        if (file.skills() == null) return;
        Set<String> taken = lowercaseNames(chatSkillService.list(requesterId, true).stream()
                .map(ChatSkillService.SkillSummary::name).toList());
        Set<String> workflowNames = workflowService.visibleNamesLowercase(requesterId);

        for (ConfigExport.Skill s : file.skills()) {
            if (s.name() == null || s.name().isBlank()) continue;
            String lower = s.name().toLowerCase(Locale.ROOT);
            if (taken.contains(lower)) {
                report.add(SECTION_SKILLS, TransferGroups.SKILLS, s.name(), ConfigImportReport.Outcome.SKIPPED,
                        "A skill with this name already exists here; it was left alone.");
                continue;
            }
            if (workflowNames.contains(lower)) {
                // "/" is one namespace, so a skill may not take a name a workflow holds. Enforced
                // here rather than left to fail inside create(): this is a bulk operation and a
                // refusal with no explanation in the report would look like a dropped item.
                report.add(SECTION_SKILLS, TransferGroups.SKILLS, s.name(), ConfigImportReport.Outcome.SKIPPED,
                        "A workflow here already uses this name, and \"/\" is one namespace.");
                continue;
            }
            // Sharing is a multi-user idea; on Enterprise Server a skill shared where it was written
            // arrives shared, owned by whoever imported it (settings-core-27). create() makes every
            // skill private, so the flag is applied by the owner's own update right after.
            boolean share = multiUser && s.shared();
            if (apply) {
                ChatSkillService.SkillDetail created = chatSkillService.create(s.name(), s.shortDescription(),
                        s.detail(), requesterId, orEmpty(s.tags()), orEmpty(s.tools()), orEmpty(s.connectorNames()));
                if (share) {
                    chatSkillService.update(created.id(), s.name(), s.shortDescription(), s.detail(), requesterId,
                            false, orEmpty(s.tags()), orEmpty(s.tools()), orEmpty(s.connectorNames()), true);
                }
            }
            taken.add(lower);
            report.add(SECTION_SKILLS, TransferGroups.SKILLS, s.name(), ConfigImportReport.Outcome.CREATED,
                    share ? "Shared with everyone, as in the file." : null);
        }
    }

    private void importWorkflows(ConfigExport file, String requesterId,
                                 Map<String, String> providerIdRemap, boolean apply,
                                 ConfigImportReport report) {
        if (file.workflows() == null) return;
        Set<String> taken = workflowService.visibleNamesLowercase(requesterId);
        Set<String> skillNames = lowercaseNames(chatSkillService.list(requesterId, true).stream()
                .map(ChatSkillService.SkillSummary::name).toList());

        for (ConfigExport.Workflow w : file.workflows()) {
            if (w.name() == null || w.name().isBlank()) continue;
            String lower = w.name().toLowerCase(Locale.ROOT);
            if (taken.contains(lower)) {
                report.add(SECTION_WORKFLOWS, TransferGroups.WORKFLOWS, w.name(), ConfigImportReport.Outcome.SKIPPED,
                        "A workflow with this name already exists here; it was left alone.");
                continue;
            }
            if (skillNames.contains(lower)) {
                report.add(SECTION_WORKFLOWS, TransferGroups.WORKFLOWS, w.name(), ConfigImportReport.Outcome.SKIPPED,
                        "A skill here already uses this name, and \"/\" is one namespace.");
                continue;
            }
            List<WorkflowService.WorkflowStep> steps = new ArrayList<>();
            List<ConfigExport.Workflow.Step> incoming = orEmpty(w.steps());
            for (int i = 0; i < incoming.size(); i++) {
                steps.add(new WorkflowService.WorkflowStep(i, incoming.get(i).title(),
                        incoming.get(i).instruction()));
            }
            // The provider id in the file belongs to the source deployment, so it goes through the
            // same remap the model roles do. A workflow whose provider is NOT in that map names an
            // instance this import did not create - the model name is meaningless without it, so
            // both are dropped and the workflow arrives running on the target's chat model. Said in
            // the report rather than silently, because the author chose that model on purpose.
            String provider = blankToNull(w.provider());
            String model = blankToNull(w.model());
            String modelNote = null;
            if (provider != null) {
                String mapped = providerIdRemap.get(provider);
                if (mapped == null) {
                    provider = null;
                    model = null;
                    modelNote = "The provider it runs on is not in this file - it will use the main "
                            + "chat model.";
                } else {
                    provider = mapped;
                }
            }
            boolean share = multiUser && w.shared();
            if (apply) {
                try {
                    WorkflowService.WorkflowDetail created = workflowService.create(w.name(), w.shortDescription(),
                            w.preamble(), steps, requesterId, orEmpty(w.tags()), orEmpty(w.tools()),
                            orEmpty(w.connectorNames()), provider, model);
                    // As for a skill: create() makes it private, the owner's update shares it.
                    if (share) {
                        workflowService.update(created.id(), w.name(), w.shortDescription(), w.preamble(), steps,
                                requesterId, false, orEmpty(w.tags()), orEmpty(w.tools()),
                                orEmpty(w.connectorNames()), true, provider, model);
                    }
                } catch (WorkflowService.InvalidWorkflowException e) {
                    // The step-count and empty-step rules are the one place WorkflowService refuses
                    // input, and its messages are written to be shown to an author verbatim.
                    report.add(SECTION_WORKFLOWS, TransferGroups.WORKFLOWS, w.name(), ConfigImportReport.Outcome.SKIPPED, e.getMessage());
                    continue;
                }
            }
            taken.add(lower);
            String detail = steps.size() + " step" + (steps.size() == 1 ? "" : "s")
                    + (share ? ", shared with everyone" : "");
            report.add(SECTION_WORKFLOWS, TransferGroups.WORKFLOWS, w.name(), ConfigImportReport.Outcome.CREATED,
                    modelNote == null ? detail : detail + ". " + modelNote);
        }
    }

    private void importSystemPrompt(ConfigExport file, boolean apply, ConfigImportReport report) {
        String incoming = blankToNull(file.systemPrompt());
        if (incoming == null) {
            // Null means the SOURCE was on the built-in default. Importing that as "no custom
            // prompt" would clear a prompt somebody wrote here, which a merge must not do.
            return;
        }
        if (!systemPromptService.isCustomizable()) {
            // Desktop and Home Server run the built-in prompt of their build. A file from an
            // Enterprise Server must not become the one way to change it.
            report.add(SECTION_PROMPT, TransferGroups.AGENTS, "Chat system prompt", ConfigImportReport.Outcome.SKIPPED,
                    "This edition always uses the built-in system prompt.");
            return;
        }
        String existing = blankToNull(systemPromptService.readSaved());
        if (Objects.equals(existing, incoming)) {
            report.add(SECTION_PROMPT, TransferGroups.AGENTS, "Chat system prompt", ConfigImportReport.Outcome.UNCHANGED, null);
            return;
        }
        if (apply) systemPromptService.write(incoming);
        report.add(SECTION_PROMPT, TransferGroups.AGENTS, "Chat system prompt",
                existing == null ? ConfigImportReport.Outcome.CREATED : ConfigImportReport.Outcome.UPDATED,
                existing == null ? null : "The prompt already customized here will be replaced.");
    }

    /**
     * Prices by provider name and model, case-insensitively. An existing price is OVERWRITTEN when
     * it differs, like a settings field and unlike a provider: a price is a number somebody copied
     * from a rate card, not a configured thing other settings point at.
     */
    private void importPricing(ConfigExport file, boolean apply, ConfigImportReport report) {
        if (file.modelPricing() == null) return;
        Map<String, ModelPricing> existing = new LinkedHashMap<>();
        for (ModelPricing p : modelPricingService.listAll()) existing.put(pricingKey(p.getProvider(), p.getModel()), p);

        for (ConfigExport.ModelPrice p : file.modelPricing()) {
            if (p.provider() == null || p.provider().isBlank() || p.model() == null || p.model().isBlank()) continue;
            String item = p.provider() + " / " + p.model();
            ModelPricing current = existing.get(pricingKey(p.provider(), p.model()));
            if (current != null && current.getInputPricePerMillion() == p.input()
                    && current.getOutputPricePerMillion() == p.output()
                    && current.getCacheReadPricePerMillion() == p.cacheRead()
                    && current.getCacheWritePricePerMillion() == p.cacheWrite()
                    && Objects.equals(current.getCacheWriteLongPricePerMillion(), p.cacheWriteLong())
                    && Objects.equals(current.getLongContextThresholdTokens(), p.longContextThresholdTokens())
                    && Objects.equals(current.getLongInputPricePerMillion(), p.longInput())
                    && Objects.equals(current.getLongOutputPricePerMillion(), p.longOutput())
                    && Objects.equals(current.getLongCacheReadPricePerMillion(), p.longCacheRead())
                    && Objects.equals(current.getLongCacheWritePricePerMillion(), p.longCacheWrite())
                    && Objects.equals(current.getPricingSchedule(), p.schedule())) {
                report.add(SECTION_PRICING, TransferGroups.USAGE, item, ConfigImportReport.Outcome.UNCHANGED, null);
                continue;
            }
            if (apply) {
                // Written under the name ALREADY here when one matches, so a price never forks
                // into a second row that differs only by the case of its provider's name.
                String provider = current != null ? current.getProvider() : p.provider();
                String model = current != null ? current.getModel() : p.model();
                modelPricingService.save(provider, model, new ModelPricingService.RateInput(p.input(), p.output(),
                        p.cacheRead(), p.cacheWrite(), p.cacheWriteLong(), p.longContextThresholdTokens(),
                        p.longInput(), p.longOutput(), p.longCacheRead(), p.longCacheWrite(), p.schedule()));
            }
            report.add(SECTION_PRICING, TransferGroups.USAGE, item,
                    current == null ? ConfigImportReport.Outcome.CREATED : ConfigImportReport.Outcome.UPDATED, null);
        }
    }

    private static String pricingKey(String provider, String model) {
        return provider.toLowerCase(Locale.ROOT) + "\n" + model.toLowerCase(Locale.ROOT);
    }

    /**
     * Policy documents by name and kind. One already here is left alone - the document is the
     * same whatever its name, or it is a different document somebody gave that name here, and
     * neither is this import's to replace. A new one is added like any upload and indexed on its
     * own thread.
     */
    private void importPolicies(ConfigExport file, boolean apply, ConfigImportReport report) {
        if (file.guardrailPolicies() == null) return;
        Set<String> taken = new LinkedHashSet<>();
        for (PolicyDocument d : policyLibrary.list()) {
            taken.add(d.kindOrDefault().name() + "\n" + String.valueOf(d.displayName()).toLowerCase(Locale.ROOT));
        }
        for (ConfigExport.PolicyFile f : file.guardrailPolicies()) {
            if (f.name() == null || f.name().isBlank() || f.contentBase64() == null) continue;
            PolicyDocument.Kind kind = parseKind(f.kind());
            String key = kind.name() + "\n" + f.name().toLowerCase(Locale.ROOT);
            if (taken.contains(key)) {
                report.add(SECTION_POLICIES, TransferGroups.GUARDRAILS, f.name(), ConfigImportReport.Outcome.SKIPPED,
                        "A policy document with this name is already here; it was left alone.");
                continue;
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(f.contentBase64());
            } catch (IllegalArgumentException e) {
                report.add(SECTION_POLICIES, TransferGroups.GUARDRAILS, f.name(), ConfigImportReport.Outcome.SKIPPED,
                        "The file's copy of this document is damaged.");
                continue;
            }
            if (apply) {
                try {
                    policyLibrary.addFile(kind, f.name(), content);
                } catch (IllegalArgumentException | java.io.IOException e) {
                    // The size cap and the empty-file refusal are written to be shown.
                    report.add(SECTION_POLICIES, TransferGroups.GUARDRAILS, f.name(),
                            ConfigImportReport.Outcome.SKIPPED, e.getMessage());
                    continue;
                }
            }
            taken.add(key);
            report.add(SECTION_POLICIES, TransferGroups.GUARDRAILS, f.name(), ConfigImportReport.Outcome.CREATED,
                    kind == PolicyDocument.Kind.EXAMPLES ? "Examples. Indexed after the import." : "Indexed after the import.");
        }
    }

    private static PolicyDocument.Kind parseKind(String raw) {
        try {
            return raw == null ? PolicyDocument.Kind.RULES : PolicyDocument.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PolicyDocument.Kind.RULES;
        }
    }

    /**
     * The groups a file actually holds something for - what an import offers to choose from. A
     * group the file is silent on is not offered, since choosing it could do nothing.
     */
    public List<String> groupsIn(ConfigExport file) {
        Set<String> present = new LinkedHashSet<>();
        if (file == null) return List.of();
        if (file.settings() != null) {
            file.settings().forEach((prefix, fields) -> {
                if (fields != null && !fields.isEmpty()) {
                    present.add(TransferGroups.ofPrefix(SettingsPrefixRegistry.normalize(prefix)));
                }
            });
        }
        if (file.providers() != null && !file.providers().isEmpty()) present.add(TransferGroups.PROVIDERS);
        if (file.connections() != null && !file.connections().isEmpty()) present.add(TransferGroups.CONNECTORS);
        if (file.skills() != null && !file.skills().isEmpty()) present.add(TransferGroups.SKILLS);
        if (file.workflows() != null && !file.workflows().isEmpty()) present.add(TransferGroups.WORKFLOWS);
        if (blankToNull(file.systemPrompt()) != null) present.add(TransferGroups.AGENTS);
        if (file.modelPricing() != null && !file.modelPricing().isEmpty()) present.add(TransferGroups.USAGE);
        if (file.guardrailPolicies() != null && !file.guardrailPolicies().isEmpty()) {
            present.add(TransferGroups.GUARDRAILS);
        }
        return TransferGroups.ALL.stream().filter(present::contains).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Turns a stored placeholder back into its real value.
     *
     * <p>Returns null when the value cannot be supplied - the file was exported without secrets, or
     * its envelope does not carry this path - and every caller treats null as "skip this field and
     * leave what is already here", never as "write an empty value".
     */
    private static String resolveSecret(String written, String path, Map<String, String> secrets) {
        if (written == null) return null;
        if (ConfigExport.OMITTED_PLACEHOLDER.equals(written)) return null;
        if (ConfigExport.SECRET_PLACEHOLDER.equals(written)) return secrets.get(path);
        return written;
    }

    private static ProviderProtocol parseProtocol(String name) {
        if (name == null) return null;
        try {
            return ProviderProtocol.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> lowercaseNames(List<String> names) {
        Set<String> out = new java.util.HashSet<>();
        for (String n : names) if (n != null) out.add(n.toLowerCase(Locale.ROOT));
        return out;
    }

    private static <T> List<T> orEmpty(List<T> in) {
        return in != null ? in : List.of();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Exposed for the controller's Optional-shaped 404 path. */
    public Optional<ConfigExport> parse(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, ConfigExport.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
