package ai.proxyma.core.configtransfer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One exported configuration, and the exact shape of the file on disk.
 *
 * <p>WHAT THIS IS FOR, AND WHAT IT IS NOT. This is PORTABILITY: move a configuration onto another
 * deployment, or write one down so it can be reviewed. It is not a backup. BackupService
 * (server-profile only) dumps the whole database and the Lucene tree for disaster recovery and its
 * restore is an operator runbook. Nothing here touches conversations, documents, users or the index.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} throughout: a file written by a NEWER build
 * must import into an older one, dropping what that build does not understand, rather than failing
 * at the first unrecognized key. {@link Meta#format} is the version that would actually be checked
 * if the shape ever changes incompatibly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigExport(
        Meta proxyma,
        /** Absent when the export carried no secrets - see the "omit secrets" option on export. */
        SecretEnvelope.Sealed secrets,
        /** prefix -> field -> value. A secret's value is the literal "$secret". */
        Map<String, Map<String, String>> settings,
        List<Provider> providers,
        List<Skill> skills,
        List<Workflow> workflows,
        /** The SAVED custom prompt, or null when the deployment runs the built-in default. Null
         *  rather than a copy of the default, so importing does not freeze today's default into a
         *  deployment that would otherwise track it. */
        String systemPrompt,
        /** Settings > Usage & Cost's per-model prices. Absent from files written before 2026-09-27. */
        List<ModelPrice> modelPricing,
        /** Settings > Guardrails' policy documents, with their content. Absent from older files. */
        List<PolicyFile> guardrailPolicies,
        /** The exporting user's connections (Settings > Connections). Absent from older files. */
        List<Connection> connections) {

    /** The literal that stands in for a secret in the readable half of the file. */
    public static final String SECRET_PLACEHOLDER = "$secret";

    /** What a secret becomes when the export was taken WITHOUT a passphrase. Never importable -
     *  import skips it and leaves whatever the target already had. */
    public static final String OMITTED_PLACEHOLDER = "***omitted***";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            int format,
            String exportedAt,
            String appVersion,
            /** "desktop" or "server". Recorded for the reader, never enforced: the settings that
             *  differ between the two are simply absent from one side's store, and import writes
             *  only what it is given. */
            String mode,
            boolean hasSecrets) {}

    /**
     * A provider instance WITHOUT its id. The id is deliberately not exported: it is a UUID
     * belonging to the deployment that wrote the file and means nothing on the other side. Import
     * matches by name and re-points the model roles itself - see ConfigTransferService.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provider(
            String name,
            String protocol,
            String baseUrl,
            Map<String, String> headers,
            /** "$secret", "***omitted***", or absent when this provider has no key at all. */
            String apiKey,
            /** The id this provider had in the SOURCE deployment. Carried only so import can
             *  rewrite the model roles that point at it; never written to the target's own row. */
            String sourceId,
            /** A gateway's chosen API (GatewayApi's name); absent for every other protocol, and in
             *  files written before 2026-10-13. */
            String api,
            /** A gateway told to make blocking calls; absent means it streams. */
            Boolean blockingCalls) {

        /** For a protocol with one API, which streams. */
        public Provider(String name, String protocol, String baseUrl, Map<String, String> headers,
                        String apiKey, String sourceId) {
            this(name, protocol, baseUrl, headers, apiKey, sourceId, null, null);
        }
    }

    /**
     * A connection WITHOUT its id, owner or sign-in. The id means nothing on the other side (sources
     * are not exported, so nothing points at it); the owner is whoever imports it. A Microsoft 365
     * sign-in never travels - a refresh token is a grant one person gave one deployment, and the
     * imported connection is signed in again on the target.
     *
     * <p>Nor do its Read and Write switches: they are what the agent may do on somebody's behalf,
     * and the manuals promise they are never exported. An imported connection starts at the
     * defaults - read on, write off.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(
            String name,
            String protocol,
            String baseUrl,
            String username,
            /** "$secret", "***omitted***", or absent when there is no token or password. */
            String secret,
            boolean isDefault) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Skill(
            String name,
            String shortDescription,
            String detail,
            List<String> tags,
            List<String> tools,
            List<String> connectorNames,
            boolean shared) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Workflow(
            String name,
            String shortDescription,
            String preamble,
            List<Step> steps,
            List<String> tags,
            List<String> tools,
            List<String> connectorNames,
            boolean shared,
            /** The id, in the SOURCE deployment, of the provider instance this workflow's steps run
             *  on - the same kind of value {@link Provider#sourceId} carries, and rewritten by the
             *  same remap on import. Null when the workflow runs on whatever the chat role is
             *  pointed at, which is also what it becomes when the file names a provider this import
             *  did not create. */
            String provider,
            /** The model the steps run on, or null to run on the chat role's model. */
            String model) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Step(String title, String instruction) {}
    }

    /** One model's prices, per million tokens. {@code provider} is the provider instance's NAME. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelPrice(
            String provider,
            String model,
            double input,
            double output,
            double cacheRead,
            double cacheWrite,
            // Since 2026-10-22, all optional: a file an older build wrote has none of them.
            Double cacheWriteLong,
            Long longContextThresholdTokens,
            Double longInput,
            Double longOutput,
            Double longCacheRead,
            Double longCacheWrite,
            String schedule) {

        public ModelPrice(String provider, String model, double input, double output, double cacheRead,
                          double cacheWrite) {
            this(provider, model, input, output, cacheRead, cacheWrite, null, null, null, null, null, null, null);
        }
    }

    /**
     * One guardrail policy document. The content travels base64-encoded, whole: a policy is a file
     * somebody wrote, and PolicyLibrary re-converts and re-indexes it on the target like any
     * upload. Bounded by PolicyLibrary.MAX_DOCUMENT_BYTES per document.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PolicyFile(
            /** RULES or EXAMPLES. */
            String kind,
            String name,
            String contentBase64) {}
}
