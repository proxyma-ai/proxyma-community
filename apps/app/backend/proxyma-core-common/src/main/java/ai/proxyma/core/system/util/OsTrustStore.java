package ai.proxyma.core.system.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The certificate authorities the OPERATING SYSTEM trusts - read so that Proxyma trusts what
 * curl, the browser and every other program on the machine already does.
 *
 * <p>Java trusts only its own bundled list ({@code cacerts}), which holds the public authorities
 * and nothing a company adds. A company's internal CA is installed into Windows' certificate store
 * by group policy, or into the Linux system bundle by {@code update-ca-certificates} - and every
 * internal host it signed (an internal model gateway, Jira, Confluence) then failed in Proxyma
 * with "PKIX path building failed" while curl, which on Windows validates through Schannel against
 * that very store, answered fine. Found in a customer's feedback log: an OpenAI-compatible
 * gateway on an internal host refused, api.openai.com (a public CA) fine. The administrator could
 * already paste the CA into Settings > Network; this makes that the exception rather than a step
 * every corporate install has to discover.
 *
 * <p>Additive only: these are trusted IN ADDITION to Java's own list, never instead of it, so a
 * machine with a sparse store cannot stop the public internet from verifying. Read once and
 * cached - the stores change when IT pushes a policy, not between two requests.
 */
public final class OsTrustStore {

    private static final Logger log = LoggerFactory.getLogger(OsTrustStore.class);

    /**
     * The machine store is where group policy installs a company root; the user store is where a
     * person installs one by hand. Both, because either is "trusted on this machine" to curl.
     * Available from the SunMSCAPI provider since JDK 19.
     */
    private static final List<String> WINDOWS_STORES =
            List.of("Windows-ROOT-LOCALMACHINE", "Windows-ROOT-CURRENTUSER");

    /** The system bundle's usual homes: Debian/Ubuntu, Fedora/RHEL, openSUSE, Alpine/others. */
    private static final List<Path> LINUX_BUNDLES = List.of(
            Path.of("/etc/ssl/certs/ca-certificates.crt"),
            Path.of("/etc/pki/tls/certs/ca-bundle.crt"),
            Path.of("/etc/ssl/ca-bundle.pem"),
            Path.of("/etc/ssl/cert.pem"));

    private static volatile List<X509Certificate> cached;

    private OsTrustStore() {}

    /** The operating system's trusted CAs, or an empty list where there are none to read. */
    public static List<X509Certificate> certificates() {
        List<X509Certificate> result = cached;
        if (result == null) {
            synchronized (OsTrustStore.class) {
                if (cached == null) cached = load();
                result = cached;
            }
        }
        return result;
    }

    private static List<X509Certificate> load() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Keyed by subject + serial so a root present in both Windows stores, or in two bundle
        // files that are links to one another, counts once.
        Map<String, X509Certificate> found = new LinkedHashMap<>();
        if (os.contains("win")) {
            for (String type : WINDOWS_STORES) readKeyStore(type, found);
        } else if (os.contains("linux")) {
            for (Path bundle : LINUX_BUNDLES) {
                if (readBundle(bundle, found)) break;
            }
        }
        // macOS reads nothing here: there is no macOS build, and its Keychain types would need
        // their own care. Java's own list still applies there, as it always did.
        log.info("[TLS] {} certificate authorities trusted by the operating system", found.size());
        return List.copyOf(found.values());
    }

    private static void readKeyStore(String type, Map<String, X509Certificate> into) {
        try {
            KeyStore store = KeyStore.getInstance(type);
            store.load(null, null);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate c = store.getCertificate(aliases.nextElement());
                if (c instanceof X509Certificate x509) into.putIfAbsent(key(x509), x509);
            }
        } catch (Exception e) {
            // A store that cannot be opened (a locked-down machine, a runtime without the
            // SunMSCAPI module) costs its certificates and nothing else.
            log.warn("[TLS] could not read the {} certificate store: {}", type, e.getMessage());
        }
    }

    private static boolean readBundle(Path bundle, Map<String, X509Certificate> into) {
        if (!Files.isReadable(bundle)) return false;
        try (InputStream in = Files.newInputStream(bundle)) {
            Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509").generateCertificates(in);
            for (Certificate c : all) {
                if (c instanceof X509Certificate x509) into.putIfAbsent(key(x509), x509);
            }
            return !all.isEmpty();
        } catch (Exception e) {
            log.warn("[TLS] could not read the system certificate bundle {}: {}", bundle, e.getMessage());
            return false;
        }
    }

    private static String key(X509Certificate c) {
        return c.getSubjectX500Principal().getName() + "#" + c.getSerialNumber();
    }

    /** Exposed for the test: the certificates found, without the cache. */
    static List<X509Certificate> loadUncached() {
        return load();
    }
}
