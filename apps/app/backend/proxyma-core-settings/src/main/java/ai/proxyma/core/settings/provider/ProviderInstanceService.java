package ai.proxyma.core.settings.provider;

import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD for provider instances, plus the one place a stored credential is turned back into a usable
 * one.
 *
 * Two rules this type exists to keep:
 *
 * <ul>
 *   <li><b>The plaintext key leaves here only inside a {@link ResolvedProvider}.</b> Everything the
 *       API layer touches is a {@link ProviderInstanceView}, which has no field that could hold it.
 *   <li><b>A blank key on update means "keep what is stored", never "clear it".</b> The client is
 *       never sent the real key, so it cannot send one back - treating blank as a clear would erase
 *       the credential every time someone edited the base URL. Clearing is an explicit flag.
 *   <li><b>Custom headers are credentials too</b> (isd-02): stored encrypted like the key, a
 *       credential header returned by its ends only (ProviderHeaders), and its mask - or a blank -
 *       sent back by an edit keeps the stored value.
 * </ul>
 */
@Service
public class ProviderInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ProviderInstanceService.class);

    private static final int MAX_HEADERS = 20;
    // The headers' JSON before encryption; encrypted it stays inside the column's 8000 characters.
    private static final int MAX_HEADERS_JSON_BYTES = 4000;
    private static final int MAX_NAME_CHARS = 100;

    private final ProviderInstanceRepository repository;
    private final CredentialEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ModelPricingService modelPricingService;

    public ProviderInstanceService(ProviderInstanceRepository repository,
                                   CredentialEncryptionService encryption,
                                   ObjectMapper objectMapper,
                                   ApplicationEventPublisher eventPublisher,
                                   ModelPricingService modelPricingService) {
        this.repository = repository;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.modelPricingService = modelPricingService;
    }

    /** Everything a caller may specify. A null field means "unchanged" on update and "unset" on
     *  create, which is why these are boxed rather than primitives. */
    public record ProviderInstanceInput(String name, ProviderProtocol protocol, String baseUrl,
            String apiKey, boolean clearApiKey, Map<String, String> headers, Boolean nativeRerank,
            GatewayApi api, Boolean blockingCalls) {

        /** Without a gateway's API or call mode - "unchanged" on update, the defaults on create. */
        public ProviderInstanceInput(String name, ProviderProtocol protocol, String baseUrl,
                String apiKey, boolean clearApiKey, Map<String, String> headers, Boolean nativeRerank) {
            this(name, protocol, baseUrl, apiKey, clearApiKey, headers, nativeRerank, null, null);
        }
    }

    public static class InvalidProviderException extends RuntimeException {
        public InvalidProviderException(String message) {
            super(message);
        }
    }

    // - reads -

    @Transactional(readOnly = true)
    public List<ProviderInstanceView> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ProviderInstanceView get(UUID id) {
        return toView(require(id));
    }

    /**
     * The decrypted form, for building a client. Empty rather than throwing when the id names
     * nothing: a role still pointing at a deleted instance is a configuration problem to report as
     * "no model configured", not an exception on every model build.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedProvider> resolve(UUID id) {
        if (id == null) return Optional.empty();
        return repository.findById(id).map(this::toResolved);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedProvider> resolve(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return resolve(UUID.fromString(id.trim()));
        } catch (IllegalArgumentException e) {
            // A role holding something that is not a uuid at all - a value from before roles
            // referenced instances, or a hand-edited settings row. Same outcome as a missing
            // instance, deliberately: nothing to build a client from.
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ResolvedProvider> resolveAll() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResolved).toList();
    }

    // - writes -

    @Transactional
    public ProviderInstanceView create(ProviderInstanceInput input) {
        validate(input, null);
        ProviderInstance entity = new ProviderInstance();
        apply(entity, input);
        repository.save(entity);
        log.info("[Providers] created instance '{}' ({})", entity.getName(), entity.getProtocol());
        publishChanged(entity);
        return toView(entity);
    }

    @Transactional
    public ProviderInstanceView update(UUID id, ProviderInstanceInput input) {
        ProviderInstance entity = require(id);
        validate(input, id);
        // Captured before apply() overwrites it. Per-model rates are keyed on the provider NAME
        // (that is what the cost ledger records), so a rename would otherwise orphan every rate
        // this provider has and quietly price its calls at $0 from then on. See
        // ModelPricingService.renameProvider for why rates follow a rename while spend history
        // does not.
        String previousName = entity.getName();
        apply(entity, input);
        repository.save(entity);
        modelPricingService.renameProvider(previousName, entity.getName());
        log.info("[Providers] updated instance '{}' ({})", entity.getName(), entity.getProtocol());
        publishChanged(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(UUID id) {
        ProviderInstance entity = require(id);
        repository.delete(entity);
        log.info("[Providers] deleted instance '{}'", entity.getName());
        // Roles pointing here are deliberately NOT rewritten to some other instance. Silently
        // repointing a role at a different provider is a worse outcome than the role reporting no
        // model until someone chooses one: the second is visible, the first bills a stranger.
        publishChanged(entity);
    }

    private ProviderInstance require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No such provider instance: " + id));
    }

    private void validate(ProviderInstanceInput input, UUID selfId) {
        if (input.name() == null || input.name().isBlank()) {
            throw new InvalidProviderException("A provider needs a name.");
        }
        if (input.name().trim().length() > MAX_NAME_CHARS) {
            throw new InvalidProviderException("A provider name can be at most " + MAX_NAME_CHARS + " characters.");
        }
        if (input.protocol() == null) {
            throw new InvalidProviderException("A provider needs a protocol.");
        }
        repository.findByNameIgnoreCase(input.name().trim())
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new InvalidProviderException(
                            "There is already a provider called '" + existing.getName() + "'.");
                });
        // Azure embeds the deployment in the URL and a LiteLLM proxy is wherever somebody runs it -
        // neither has a shared host to default to, so an instance without one cannot be pointed
        // anywhere. Every other protocol has a real default (OpenAI, Anthropic, Gemini, Ollama,
        // OpenRouter) or is self-hosted, where a blank URL fails loudly on first use.
        if (input.protocol() == ProviderProtocol.AZURE_OPENAI
                && (input.baseUrl() == null || input.baseUrl().isBlank())) {
            throw new InvalidProviderException("Azure OpenAI needs a base URL - it has no default host.");
        }
        if (input.protocol() == ProviderProtocol.LITELLM
                && (input.baseUrl() == null || input.baseUrl().isBlank())) {
            throw new InvalidProviderException("LiteLLM needs the address of its proxy - it has no default host.");
        }
        if (input.headers() != null && input.headers().size() > MAX_HEADERS) {
            throw new InvalidProviderException("At most " + MAX_HEADERS + " custom headers.");
        }
        if (input.headers() != null) {
            for (Map.Entry<String, String> h : input.headers().entrySet()) {
                if (h.getKey() == null || h.getKey().isBlank()) {
                    throw new InvalidProviderException("A custom header needs a name.");
                }
                // A newline in a header value is header injection, not a typo worth repairing.
                if (h.getKey().contains("\n") || h.getKey().contains("\r")
                        || (h.getValue() != null && (h.getValue().contains("\n") || h.getValue().contains("\r")))) {
                    throw new InvalidProviderException("Header names and values cannot contain line breaks.");
                }
            }
        }
    }

    private void apply(ProviderInstance entity, ProviderInstanceInput input) {
        entity.setName(input.name().trim());
        entity.setProtocol(input.protocol());
        entity.setBaseUrl(input.baseUrl() == null || input.baseUrl().isBlank()
                ? null : input.baseUrl().trim());
        if (input.clearApiKey()) {
            entity.setApiKey(null);
        } else if (input.apiKey() != null && !input.apiKey().isBlank()) {
            entity.setApiKey(encryption.encrypt(input.apiKey().trim()));
        }
        if (input.headers() != null) {
            entity.setHeadersJson(writeHeaders(
                    ProviderHeaders.merge(readHeaders(entity.getHeadersJson()), input.headers())));
        }
        if (input.nativeRerank() != null) {
            entity.setNativeRerank(input.nativeRerank());
        }
        // A gateway's API and call mode belong to the protocols that have them, and nothing else
        // keeps either - so a provider moved to another protocol cannot carry one along unseen.
        if (input.protocol().choosesApi()) {
            if (input.api() != null) entity.setApi(input.api());
            if (entity.getApi() == null) entity.setApi(GatewayApi.ANTHROPIC_MESSAGES);
        } else {
            entity.setApi(null);
        }
        if (!input.protocol().mayCallWithoutStreaming()) {
            entity.setBlockingCalls(false);
        } else if (input.blockingCalls() != null) {
            entity.setBlockingCalls(input.blockingCalls());
        }
    }

    // Reuses the existing "credentials" reinit path, which re-builds every model role whose stored
    // provider matches - so editing an instance's key immediately re-points every role using it,
    // with no restart and no second mechanism. The id is what roles store, so the id is what goes
    // in the event.
    private void publishChanged(ProviderInstance entity) {
        eventPublisher.publishEvent(
                ProviderSettingsChangedEvent.forCredentials(entity.getId().toString()));
    }

    // - mapping -

    private ProviderInstanceView toView(ProviderInstance e) {
        String key = decryptQuietly(e.getApiKey());
        return new ProviderInstanceView(
                e.getId(), e.getName(), e.getProtocol(), e.getProtocol().displayName(),
                e.getBaseUrl(), ProviderInstanceView.mask(key), key != null && !key.isBlank(),
                ProviderHeaders.masked(readHeaders(e.getHeadersJson())), e.isNativeRerank(), e.getApi(),
                e.isBlockingCalls());
    }

    private ResolvedProvider toResolved(ProviderInstance e) {
        return new ResolvedProvider(e.getId(), e.getName(), e.getProtocol(), e.getBaseUrl(),
                decryptQuietly(e.getApiKey()), readHeaders(e.getHeadersJson()),
                e.isNativeRerank(), e.getApi(), e.isBlockingCalls());
    }

    // A key that will not decrypt is treated as absent rather than fatal - see
    // CredentialEncryptionService.decryptOrNull.
    private String decryptQuietly(String stored) {
        return CredentialEncryptionService.decryptOrNull(encryption, stored, "a provider's key");
    }

    // Encrypted since isd-02; a row written before then holds plain JSON, which reads as itself
    // (decrypt passes an unmarked value through) and is encrypted on its next save.
    private Map<String, String> readHeaders(String stored) {
        String json = CredentialEncryptionService.decryptOrNull(encryption, stored, "a provider's headers");
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("[Providers] unreadable headers json, ignoring: {}", e.getMessage());
            return Map.of();
        }
    }

    private String writeHeaders(Map<String, String> headers) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey() == null || h.getKey().isBlank()) continue;
            cleaned.put(h.getKey().trim(), h.getValue() == null ? "" : h.getValue().trim());
        }
        if (cleaned.isEmpty()) return null;
        String json;
        try {
            json = objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new InvalidProviderException("Could not store custom headers: " + e.getMessage());
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_HEADERS_JSON_BYTES) {
            throw new InvalidProviderException("The custom headers are too long to store.");
        }
        return encryption.encrypt(json);
    }
}
