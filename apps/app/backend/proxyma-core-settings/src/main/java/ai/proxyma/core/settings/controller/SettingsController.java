package ai.proxyma.core.settings.controller;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.access.CallerRoles;
import ai.proxyma.core.settings.dto.SettingsSecrets;
import ai.proxyma.core.settings.dto.ProxySettings;
import ai.proxyma.core.settings.dto.SaveFieldResponse;
import ai.proxyma.core.settings.dto.SettingsGroupResponse;
import ai.proxyma.core.settings.service.DemoSecretMasker;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    static final Set<String> VIEWER_PREFIXES = Set.of(
            "jira", "confluence", "bitbucket", "svn", "sharepoint", "teams",
            // "microsoft" WAS here and must not come back under any name. It held a tenant-wide
            // Entra ID app registration - tenant id, client id, client secret - and a user-scoped
            // prefix makes every field the caller's own, so any user could replace the client and
            // secret that everybody else's Microsoft sign-in goes through. Worse, the token those
            // credentials bought was app-only, which reads every site and mailbox in the tenant, so
            // a user who could register one SharePoint source could index the whole organization.
            // It is the "m365" prefix now (MicrosoftSettings, in Proxyma Enterprise), deliberately
            // renamed rather than re-scoped so a value some user already saved for themselves can
            // never be read back as the deployment's app registration, and it takes the ordinary
            // system-config path below: SUPER_ADMIN writes, ADMIN reads, no per-user override. What
            // IS per user is the sign-in itself, which is a row and not a setting - the Microsoft 365
            // connection's.
            //
            // "sharepoint" and "teams" stay here: those prefixes hold only that user's own sync
            // interval, which has no deployment-wide meaning.
            // Not a connector credential like its neighbors above, but reuses the same fully
            // user-scoped read/write access level - there's no "global" value for whether one
            // particular user has completed the onboarding walkthrough (see OnboardingSettings /
            // the tour feature).
            //
            // "interests" sat here too, for a user's computed chat greeting and topic suggestions.
            // Both the writer (the memory digest) and the reader (the chat hero subtitle) are gone,
            // so the prefix is no longer served. Rows already written into a user's settings blob
            // are simply never read again - harmless, and deleting them would be a migration for no
            // gain.
            "onboarding",
            // Same reasoning again - what one particular user permits the chat agent to do with a
            // connector on their behalf has no "global" value either (see ConnectorToolSettings /
            // McpGuardService). This is now the ONLY gate on those tools: the deployment-wide "mcp"
            // prefix that used to sit in front of it is gone, and so is the org-mode carve-out that
            // outlived it.
            // The per-user `smb` prefix (each user's domain account for network shares) is GONE
            // since 2026-10-01: that account is a Network share CONNECTION now, owned and encrypted
            // like every other connection. Its admin-only sibling "network-share" (the optional
            // list of allowed file servers) takes the system-config path: an ADMIN reads it, only
            // a SUPER_ADMIN changes it.
            "connector-tools");

    // EVERY @EncryptedField is WRITE-ONLY from the browser: GET returns a placeholder in place of a
    // saved value, and that placeholder PATCHed back is ignored rather than stored. Applied on BOTH
    // read branches below.
    //
    // It was a list of prefixes, holding m365 alone: the Entra client secret, which every ADMIN
    // could read until 2026-09-21 though only a SUPER_ADMIN may set it (SEC-41). The web search key
    // beside it had exactly that shape and stayed readable (settings-core-03) - a list is what one
    // fix covers, and the annotation is what every secret carries.

    // There is deliberately NO "deployment-wide fields inside a user-scoped prefix" set any more.
    //
    // "base-url" was the only member. A connector prefix used to mix the two scopes - the token was
    // per-user, the server was one Jira for everybody - so writes to base-url fell through to the
    // system-config check while writes to the token beside it landed in the caller's own blob.
    // Since 2026-09-01 a connector's base URL is per-user too: a user's Connectors settings are now
    // their own DEFAULTS, which a source may then override individually (see
    // ConnectorEndpointResolver, which owns that chain). Nothing about a connector prefix is
    // deployment-wide, so every field in one now takes the ordinary user-scoped path and this
    // mechanism has nothing left to protect.
    //
    // If a genuinely deployment-wide field ever appears inside a user-scoped prefix again, it needs
    // separating on BOTH saveField and deleteField - a field anyone can CLEAR is not protected,
    // however carefully its PATCH is guarded - and in checkAccess, which must classify a prefix
    // identically for reads and writes.

    // System-config prefixes ("proxy", "mcp", "quota", provider configs, etc.) all fall through
    // to the split read/write check in checkAccess below: an ADMIN may READ them, only a
    // SUPER_ADMIN may CHANGE them. These knobs are deployment-wide - an embedding model, a proxy,
    // a provider credential, a spend cap - so one admin's edit silently changes behavior, cost and
    // reachability for every user of the platform, which is a narrower thing than the per-user
    // administration an ADMIN otherwise does. Read stays open at ADMIN so they can still diagnose
    // ("which model is this pointed at?") without being able to reconfigure the deployment.
    //
    // Prefixes listed here are narrower still - SUPER_ADMIN for reads as well, because the value
    // itself is sensitive rather than merely powerful. "evaluation" gates the cross-user
    // Evaluation Mode dashboard/API (see EvaluationSettings), which exposes other users' private
    // conversation content.
    //
    // "m365" is the deployment's Entra app registration - tenant, client id, client secret, redirect
    // URI, scopes. Every user's Microsoft sign-in goes through it, so it is SUPER_ADMIN to read as
    // well as to change (maintainer's decision, 2026-09-21). The secret is also write-only on top of
    // this (WRITE_ONLY_SECRET_PREFIXES), so not even a super admin reads it back.
    // cost-centers: who pays for each kind of model call decides whose quota it spends, so it is a
    // super admin's decision, made on the Administration page (CostCenterSettings).
    static final Set<String> SUPER_ADMIN_PREFIXES = Set.of("evaluation", "m365", "cost-centers");

    // Prefixes that live under this generic settings API but are NOT part of the Settings page's
    // system configuration tabs - they are driven from the Administration page, which is an
    // ADMIN surface, and they are operational rather than declarative: when the next backup runs,
    // what the maintenance banner says. Writing them stays at ADMIN.
    //
    // This list exists because the write gate is keyed on the storage mechanism (a settings
    // prefix) while the rule is about the surface, and those two do not line up by themselves.
    // Without it, tightening system config to SUPER_ADMIN would silently 403 an ADMIN saving a
    // backup schedule or posting a maintenance announcement - a break on a completely different
    // page, with no failure visible in the Settings UI at all. Adding a new prefix that an
    // ADMIN-level page writes means adding it here too.
    private static final Set<String> ADMIN_WRITABLE_PREFIXES =
            Set.of("backup-schedule", "maintenance-announcement");

    // Deploy-time-only settings: never readable/writable via this generic API, regardless of
    // role. Tenant branding (product name, logo, colors, tenant id) is fixed by
    // proxyma.branding.* in application.properties/env vars at startup - see
    // ProductIdentity (this module's own config package).
    private static final Set<String> BLOCKED_PREFIXES = Set.of("appearance", "branding");

    // The settings prefixes that hold a LICENSED connector's credential, and the connector each
    // belongs to. Only the Entra app registration is left: every other connector's credential is a
    // CONNECTION since 2026-10-01, and since the two-tier model (2026-10-14) only Microsoft 365 needs
    // a license at all.
    static final java.util.Map<String, ai.proxyma.core.connector.spi.Edition> CREDENTIAL_PREFIXES = java.util.Map.of(
            "m365", ai.proxyma.core.connector.spi.Edition.ENTERPRISE);

    private final SettingsService settingsService;
    private final FeatureGateService featureGateService;

    // Mirrors RestClientConfig.resolveMode() in proxyma-core-network - same property, same
    // "absent key means never explicitly saved" semantics - so the Settings page displays the
    // mode that's actually in effect instead of always showing "None" for an unconfigured
    // deployment whose default-proxy-mode is "system". No Java dependency on the
    // network module: it's just a shared property key convention, not a shared type.
    @Value("${proxyma.network.default-proxy-mode:none}")
    private String defaultProxyMode;

    // The public Enterprise sandbox. The values this endpoint returns have already been DECRYPTED
    // by SettingsService, and the sandbox's accounts are SHARED, so a token one visitor saves under
    // the Member account is otherwise read back by the next visitor who signs in as it. False on
    // every real install, where an operator reading their own credentials back is the entire point
    // of the screen.
    @Value("${proxyma.sandbox:false}")
    private boolean sandboxMode;

    // Desktop mode has no SecurityWebFilterChain at all (proxyma-core-auth, the only module that
    // defines one, is server-profile-only) - Spring Boot's reactive security auto-configuration
    // only ever registers a fallback deny-all ReactiveAuthenticationManager bean when none is
    // present, never a filter that populates ReactiveSecurityContextHolder, so isAdmin()/
    // isSuperAdmin() below would always resolve false there with no bypass. Desktop is
    // single-user/no-auth by design, so role checks are meaningless in that mode.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    public SettingsController(SettingsService settingsService, FeatureGateService featureGateService) {
        this.settingsService = settingsService;
        this.featureGateService = featureGateService;
    }

    @GetMapping("/{prefix}")
    public Mono<ResponseEntity<SettingsGroupResponse>> getSettingsGroup(
            @PathVariable String prefix, ServerWebExchange exchange) {
        return checkAccess(prefix, null, false).flatMap(allowed -> {
            if (!allowed) return Mono.just(ResponseEntity.status(403).<SettingsGroupResponse>build());
            // boundedElastic: the user-scoped read is a repository call every time, and the system
            // one is on a cold cache (settings-core-23).
            if (isUserScoped(prefix)) {
                return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
                    String uid = userId.isEmpty() ? null : userId;
                    return ResponseEntity.ok(new SettingsGroupResponse(sandboxMasked(prefix,
                            redactWriteOnly(prefix, settingsService.getSettingsFieldsForUser(prefix, uid)))));
                }).subscribeOn(Schedulers.boundedElastic()));
            }
            return Mono.fromCallable(() -> ResponseEntity.ok(
                    new SettingsGroupResponse(sandboxMasked(prefix, redactWriteOnly(prefix, resolvedFields(prefix))))))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * On the public Enterprise sandbox, take the credentials out before they leave.
     *
     * <p>Applied to BOTH branches above. The user-scoped one is the branch that matters most - it
     * is where the connector credentials live - and it would have been the easy one to miss, which
     * is why this is a method rather than a line written twice.
     *
     * <p>Off everywhere else, so a real install still shows an operator their own values. See
     * DemoSecretMasker for why the annotation alone is not the test.
     */
    private java.util.Map<String, Object> sandboxMasked(String prefix, java.util.Map<String, Object> fields) {
        return sandboxMode ? DemoSecretMasker.mask(prefix, fields) : fields;
    }

    /**
     * A saved secret leaves as the placeholder; a blank one stays blank ("not set"). The map comes
     * back as it was when it holds no secret.
     */
    // Public for the modules that add a prefix of their own (SettingsPrefixContributor), whose tests
    // pin that their secret leaves as the placeholder too.
    public static java.util.Map<String, Object> redactWriteOnly(String prefix, java.util.Map<String, Object> fields) {
        if (fields == null || fields.keySet().stream().noneMatch(f -> SettingsSecrets.isEncryptedField(prefix, f))) {
            return fields;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(fields.size());
        fields.forEach((field, value) -> {
            boolean present = value != null && !String.valueOf(value).isBlank();
            out.put(field, present && SettingsSecrets.isEncryptedField(prefix, field) ? DemoSecretMasker.MASK : value);
        });
        return out;
    }

    /**
     * True when a save is only the placeholder coming back - a form re-submitted without the
     * secret being retyped. Storing it would replace the real key with eight asterisks.
     */
    public static boolean isPlaceholderEcho(String prefix, String field, String value) {
        return SettingsSecrets.isEncryptedField(prefix, field) && DemoSecretMasker.MASK.equals(value);
    }

    private java.util.Map<String, Object> resolvedFields(String prefix) {
        java.util.Map<String, Object> fields = settingsService.getSettingsFields(prefix);
        if (ProxySettings.PREFIX.equals(prefix) && !fields.containsKey("mode")) {
            fields = new java.util.LinkedHashMap<>(fields);
            fields.put("mode", defaultProxyMode);
        }
        return fields;
    }

    @PatchMapping("/{prefix}/{field}")
    public Mono<ResponseEntity<SaveFieldResponse>> saveField(
            @PathVariable String prefix,
            @PathVariable String field,
            @RequestBody String value,
            ServerWebExchange exchange) {
        return checkAccess(prefix, field, true).flatMap(allowed -> {
            // Each refusal says WHY, since 2026-09-29: an empty 403 reached the Settings save bar as
            // "Failed to save." and nothing the reader could act on. The reason travels as the
            // ProblemDetail's `detail` (GlobalExceptionHandler.handleErrorResponse).
            if (!allowed) return Mono.error(forbidden(refusalReason(prefix)));
            // A connector the license does not include takes no credential, since 0.10.0 - since the
            // two tiers, Microsoft 365 without Enterprise. The settings screen already locked the
            // field, but this endpoint accepted the token, and with it the agent and MCP tools
            // switched on - see McpGuardService. Asked of the annotation (SettingsSecrets), never of a
            // field name: SEC-31. Clearing one stays allowed (deleteField), so a lapsed license can
            // still remove a secret it no longer uses.
            ai.proxyma.core.connector.spi.Edition locked = CREDENTIAL_PREFIXES.get(prefix);
            if (locked != null && SettingsSecrets.isEncryptedField(prefix, field)
                    && !featureGateService.getFeatures().isConnectorAllowed(locked)) {
                return Mono.error(forbidden(FeatureGateService.MICROSOFT_365_MESSAGE));
            }
            if (isPlaceholderEcho(prefix, field, value)) {
                return Mono.just(ResponseEntity.ok(new SaveFieldResponse(false)));
            }
            String invalid = invalidWrite(prefix, field, value);
            if (invalid != null) {
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, invalid));
            }
            if (isUserScopedField(prefix, field)) {
                return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
                    String uid = userId.isEmpty() ? null : userId;
                    settingsService.saveUserField(prefix, field, value, uid);
                    auditSaved(uid, prefix, field, value, false);
                    return ResponseEntity.ok(new SaveFieldResponse(false));
                }).subscribeOn(Schedulers.boundedElastic()));
            }
            return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
                boolean requiresRestart = settingsService.saveAiProviderField(prefix, field, value);
                auditSaved(userId.isEmpty() ? null : userId, prefix, field, value, false);
                return ResponseEntity.ok(new SaveFieldResponse(requiresRestart));
            }).subscribeOn(Schedulers.boundedElastic()));
        });
    }

    @DeleteMapping("/{prefix}/{field}")
    public Mono<ResponseEntity<SaveFieldResponse>> deleteField(
            @PathVariable String prefix,
            @PathVariable String field,
            ServerWebExchange exchange) {
        return checkAccess(prefix, field, true).flatMap(allowed -> {
            if (!allowed) return Mono.just(ResponseEntity.status(403).<SaveFieldResponse>build());
            if (isUserScopedField(prefix, field)) {
                return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
                    String uid = userId.isEmpty() ? null : userId;
                    settingsService.deleteUserField(prefix, field, uid);
                    auditSaved(uid, prefix, field, null, true);
                    return ResponseEntity.ok(new SaveFieldResponse(false));
                }).subscribeOn(Schedulers.boundedElastic()));
            }
            return resolveUserId(exchange).flatMap(userId -> Mono.fromCallable(() -> {
                boolean requiresRestart = settingsService.deleteAiProviderField(prefix, field);
                auditSaved(userId.isEmpty() ? null : userId, prefix, field, null, true);
                return ResponseEntity.ok(new SaveFieldResponse(requiresRestart));
            }).subscribeOn(Schedulers.boundedElastic()));
        });
    }

    // A user-scoped write lands in that user's own blob, which every later read of their settings
    // parses whole - so any signed-in user could store unlimited keys of any size there
    // (settings-core-08). A field there must be one the prefix's settings class declares; a value
    // is bounded on either path. The system path's keys are bounded by the store itself.
    static final int MAX_USER_VALUE_CHARS = 16 * 1024;
    static final int MAX_SYSTEM_VALUE_CHARS = 64 * 1024;

    static String invalidWrite(String prefix, String field, String value) {
        boolean userScoped = VIEWER_PREFIXES.contains(prefix);
        if (userScoped && !SettingsSecrets.isKnownField(prefix, field)) return "There is no such setting.";
        int max = userScoped ? MAX_USER_VALUE_CHARS : MAX_SYSTEM_VALUE_CHARS;
        if (value != null && value.length() > max) return "That value is too long to store.";
        return null;
    }

    // Whole-prefix scoping, for the GET path: the response merges global values with the caller's
    // overrides, which is the right answer for a prefix that mixes the two.
    private boolean isUserScoped(String prefix) {
        return VIEWER_PREFIXES.contains(prefix);
    }

    // Field-level scoping, for the write paths. Every field of a user-scoped prefix is now the
    // caller's own - see the comment where the exception set used to be. Kept as a separate method
    // from isUserScoped(), rather than collapsed into it, because the two answer questions that
    // have differed before and could again: "may this prefix be read as mine" and "does this
    // particular write land in my blob". `field` is kept for that reason and currently ignored.
    private boolean isUserScopedField(String prefix, String field) {
        return VIEWER_PREFIXES.contains(prefix);
    }

    // `write` distinguishes a PATCH/DELETE from a GET. It is a parameter rather than two methods
    // because the prefix classification (blocked / user-scoped / super-admin-only / system config)
    // must stay identical between reading and writing - splitting it in two is how the two lists
    // drift apart and a prefix ends up writable through a path its read side would have refused.
    // The sentence for a write checkAccess refused, walking the same rules in the same order.
    private String refusalReason(String prefix) {
        if (BLOCKED_PREFIXES.contains(prefix)) return "This setting cannot be changed through this page.";
        if ("m365".equals(prefix) && !multiUser) return "Microsoft 365 is available on Enterprise Server only.";
        if (ADMIN_WRITABLE_PREFIXES.contains(prefix)) return "Only an administrator can change this setting.";
        return "Only a super administrator can change this setting.";
    }

    private Mono<Boolean> checkAccess(String prefix, String field, boolean write) {
        if (BLOCKED_PREFIXES.contains(prefix)) return Mono.just(false);
        // The Entra app registration exists on Enterprise Server only, like every Microsoft 365
        // surface - see the ENTERPRISE edition on its connector descriptors.
        if ("m365".equals(prefix) && !multiUser) return Mono.just(false);
        if (VIEWER_PREFIXES.contains(prefix)) {
            // The caller only ever reads and writes their OWN row, so there is nothing
            // deployment-wide to protect here and no read/write distinction to draw. A base URL
            // used to be the exception and is not one any more - see the comment where the
            // exception set used to be defined.
            return Mono.just(true);
        }
        if (SUPER_ADMIN_PREFIXES.contains(prefix)) return isSuperAdmin();
        if (ADMIN_WRITABLE_PREFIXES.contains(prefix)) return isAdmin();
        return write ? isSuperAdmin() : isAdmin();
    }

    private Mono<Boolean> isAdmin() {
        return CallerRoles.isAdmin(multiUser);
    }

    private Mono<Boolean> isSuperAdmin() {
        return CallerRoles.isSuperAdmin(multiUser);
    }

    /**
     * The Audit Logs page's record of a setting changed. A credential's value is never written -
     * only that it changed - asked of the annotation (SettingsSecrets) as the write path asks it,
     * and of the field's name as well, since a log is the last place a secret should turn up.
     */
    static void auditSaved(String userId, String prefix, String field, String value, boolean cleared) {
        // The page's own state - the product tour seen - is not configuration anybody audits.
        if (UNAUDITED_PREFIXES.contains(prefix)) return;
        boolean secret = SettingsSecrets.isEncryptedField(prefix, field) || looksSecret(field);
        AuditEntry.of(AuditCategory.CONFIG, cleared ? "setting.cleared" : "setting.saved")
                .user(userId)
                .target(prefix + "." + field)
                .summary((cleared ? "Cleared " : "Changed ") + prefix + "." + field
                        + (secret && !cleared ? " (value not recorded)" : ""))
                .detail("group", prefix)
                .detail("field", field)
                .detail("value", cleared || secret ? null : AuditEntry.cap(value, 2_000))
                .record();
    }

    static final Set<String> UNAUDITED_PREFIXES = Set.of("onboarding");

    // Named like a credential: the field ENDS in one of these words - "api-key", "client-secret",
    // "access-token" - so a switch like "detect-secrets" or "max-output-tokens" is not mistaken for one.
    private static final java.util.regex.Pattern SECRET_NAME = java.util.regex.Pattern.compile(
            "(^|[-_.])(key|token|secret|password|passphrase|credential|credentials)$");

    static boolean looksSecret(String field) {
        return SECRET_NAME.matcher(field.toLowerCase(java.util.Locale.ROOT)).find();
    }

    private Mono<String> resolveUserId(ServerWebExchange exchange) {
        return CallerRoles.userId(exchange);
    }

    private static org.springframework.web.server.ResponseStatusException forbidden(String reason) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, reason);
    }
}
