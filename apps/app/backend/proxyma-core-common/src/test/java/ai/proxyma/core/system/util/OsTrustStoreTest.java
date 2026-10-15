package ai.proxyma.core.system.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Proxyma trusts what the machine trusts. A company CA installed into Windows' store by group
 * policy, or into the Linux system bundle, signed an internal model gateway that curl reached and
 * Proxyma refused with "PKIX path building failed" - because Java only ever read its own list.
 *
 * <p>Asserted on the two platforms Proxyma ships for, each against its real store: the Windows
 * PC this is developed on and the Linux runner the pipeline uses. What is in those stores differs
 * by machine, so the assertion is that they are READ - an empty result is exactly what the bug
 * looked like.
 */
class OsTrustStoreTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("on Windows, the certificate store's roots are read")
    void readsTheWindowsStore() {
        assertFalse(OsTrustStore.loadUncached().isEmpty(),
                "Windows-ROOT-LOCALMACHINE and -CURRENTUSER yielded nothing");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("on Linux, the system CA bundle is read")
    void readsTheLinuxBundle() {
        assertFalse(OsTrustStore.loadUncached().isEmpty(),
                "none of the system CA bundle paths yielded a certificate");
    }

    @Test
    @DisplayName("a root present in more than one store counts once")
    void noDuplicates() {
        List<X509Certificate> certificates = OsTrustStore.loadUncached();
        HashSet<String> keys = new HashSet<>();
        for (X509Certificate c : certificates) {
            keys.add(c.getSubjectX500Principal().getName() + "#" + c.getSerialNumber());
        }
        assertEquals(certificates.size(), keys.size());
    }
}
