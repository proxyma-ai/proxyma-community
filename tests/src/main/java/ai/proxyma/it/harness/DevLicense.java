package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/**
 * An Enterprise license for the instance under test, issued by the license service this same
 * suite boots.
 *
 * <h2>Why a license has to exist at all</h2>
 *
 * An Enterprise Server with no license in force has exactly ONE account
 * ({@code FeatureGateService.isNewAccountAllowed} is {@code orgModeAllowed || existingAccounts ==
 * 0}). So the two-user conversation-isolation journey - the most valuable test in the server suite
 * - is not merely unrun on an unlicensed instance, it is unrunnable. The product is right and the
 * test cannot pass. (The shared server's trial is switched off, so the license is this one.)
 *
 * <h2>Issued at runtime, never committed</h2>
 *
 * The obvious alternative - a pre-signed license JWT checked into the repository - was rejected,
 * and not only for the usual reason that a credential in a repository is a credential in every
 * clone of it. It would not even work: {@code EmbeddedLicenseKeys} is a deliberate literal list
 * of the issuers a build trusts, containing exactly one production key, and widening it to admit
 * a dev key would ship that trust to every customer. A committed license would need a committed
 * key in the product, which is the whole problem.
 *
 * <p>So the license is minted per run, by the real issuer, through its real admin API - and the
 * instance under test is made to trust that issuer by shadowing the classpath key the build
 * reads, which is the only route there is: see {@code ProxymaApp.trustOnly}. Nothing is
 * committed, nothing outlives the run, and the signing key is a throwaway generated in
 * {@link ServiceApp}.
 *
 * <h2>It is also the best test in the suite</h2>
 *
 * This is the commercial chain, end to end and for real: an administrator generates a license,
 * pastes the key into the product, the product calls the license server, registers the device,
 * receives a signed JWT, verifies the signature against a key it already trusts, caches it, and
 * the paid features turn on. Every one of those steps is code this project owns, and until
 * now none of them had ever been exercised together - the license service's own tests stop at its
 * API, and the app's stop at a mocked verifier.
 *
 * <p>{@code .claude/memory/licensing-model.md} recorded that none of it had ever run against a
 * live server. It has now.
 */
public final class DevLicense {

    private DevLicense() {}

    /** What a test asserts against, and what the admin console shows. */
    public record Issued(String licenseKey, String purchaseOrder, String tier) {}

    /**
     * Generates an Enterprise license on the license service.
     *
     * <p>A fresh purchase order each time: the service refuses a duplicate with 409, which is
     * correct - one order is one sale - and would otherwise make a second run of the suite fail
     * on setup.
     */
    public static Issued issueEnterprise(ServiceApp licenseService, Http admin) {
        return issue(licenseService, admin, "ENTERPRISE");
    }

    /**
     * Any tier name - though the license service issues ENTERPRISE alone since 2026-10-14, and
     * refuses the retired PERSONAL and PROFESSIONAL with a 400 (LicenseJourneyIT asserts that).
     */
    public static Issued issue(ServiceApp licenseService, Http admin, String tier) {
        String order = "IT-DEV-LICENSE-" + UUID.randomUUID();
        JsonNode issued = admin.post("/api/v1/admin/licenses/generate", Map.of(
                "purchaseOrder", order,
                "tier", tier,
                // Long enough that a slow suite cannot expire it mid-run, short enough that a
                // license escaping this process is worthless by the time anyone finds it.
                "expireDays", 1)).ok().json();

        String key = issued.path("licenseKey").asText();
        if (key.isBlank()) {
            throw new AssertionError("the license service issued nothing: " + issued);
        }
        return new Issued(key, order, issued.path("tier").asText());
    }

    /**
     * Activates a license on a running Proxyma, as the signed-in super administrator, and asserts
     * the paid features actually turned on.
     *
     * <p>{@code /api/v1/license/**} is {@code hasRole("SUPER_ADMIN")} in server mode, so this
     * takes the admin's client rather than the app's anonymous one - the same distinction that
     * made provider configuration fail with a bare 401 the first time this suite ran against a
     * server.
     *
     * <p>The assertion is on {@code orgModeAllowed} rather than on the tier name alone. The tier
     * is what the response says; org mode is what actually decides whether a second account may
     * be created, and a license that reported ENTERPRISE without lifting the gate would be
     * exactly the silent half-success this suite exists to catch.
     */
    public static void activate(ProxymaApp app, Http admin, Issued license) {
        Http.Response activation = admin.post("/api/v1/license/activate",
                Map.of("licenseKey", license.licenseKey()));

        if (activation.status() != 200) {
            throw new AssertionError("activating the dev license failed with "
                    + activation.status() + ": " + activation.body()
                    + "\nThe usual causes, in order of likelihood:"
                    + "\n  503 LICENSE_SERVER_UNREACHABLE - proxyma.license.server.url is not"
                    + " pointing at the suite's own license service (it defaults to the PRODUCTION"
                    + " one, which this suite must never reach)"
                    + "\n  400 Unknown kid - the instance was not given this run's issuer key, so it"
                    + " trusts only the production one baked into the jar. Nothing can be"
                    + " configured to change that - ProxymaApp.trustOnly shadows the classpath"
                    + " resource, and it is the only way there is");
        }

        JsonNode status = admin.get("/api/v1/license/status").ok().json();
        if (!"ENTERPRISE".equals(status.path("tier").asText())
                || !status.path("orgModeAllowed").asBoolean()) {
            throw new AssertionError("the license activated but the features did not turn on: "
                    + status);
        }
    }
}
