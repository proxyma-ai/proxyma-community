package ai.proxyma.core.common.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public sandbox's refusal of private destinations. Literal addresses only, so no test depends
 * on DNS - InetAddress.getByName on a literal never touches the network.
 */
class OutboundAddressPolicyTest {

    @AfterEach
    void off() {
        OutboundAddressPolicy.setEnabled(false);
        OutboundAddressPolicy.setMetadataBlocked(true);
        OutboundAddressPolicy.setAllowedHosts(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.10",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "224.0.0.1", "255.255.255.255", "198.18.0.1",
            "::1", "::", "fe80::1", "fd00::1", "fc12::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
            "::ffff:169.254.169.254", "64:ff9b::a00:1"})
    @DisplayName("every private, loopback, link-local, CGNAT and metadata address is refused - IPv4-mapped too")
    void refusesPrivateRanges(String literal) throws Exception {
        assertTrue(OutboundAddressPolicy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "93.184.216.34", "100.128.0.1", "172.32.0.1", "2606:4700::1111"})
    @DisplayName("public addresses - including the ones just outside each private range - are allowed")
    void allowsPublicAddresses(String literal) throws Exception {
        assertFalse(OutboundAddressPolicy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @Test
    @DisplayName("OFF BY DEFAULT: a customer's install reaches its own intranet as it always did")
    void offByDefault() {
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://127.0.0.1:4246/"));
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://192.168.1.10/wiki"));
    }

    @Test
    @DisplayName("on, a URL naming a private host is refused with a sentence a user can read")
    void refusesUrls() {
        OutboundAddressPolicy.setEnabled(true);
        var e = assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("http://169.254.169.254/latest/meta-data"));
        assertTrue(e.getMessage().contains("169.254.169.254"), e.getMessage());
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("http://[::1]:8080/"));
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("http://localhost:4246/api"));
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("http://gitlab.internal/"));
    }

    @Test
    @DisplayName("on, anything but http and https is refused - svn://, file: and friends carry no host check")
    void refusesOtherSchemes() {
        OutboundAddressPolicy.setEnabled(true);
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("file:///etc/passwd"));
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("svn://8.8.8.8/repo"));
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("gopher://1.1.1.1/"));
    }

    @Test
    @DisplayName("an operator-named host - the deployment's own model server - is exempt, and nothing else is")
    void allowedHostsAreExempt() {
        OutboundAddressPolicy.setEnabled(true);
        OutboundAddressPolicy.setAllowedHosts(java.util.List.of("127.0.0.1", " Ollama.LAN "));
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://127.0.0.1:11434/api/tags"));
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://ollama.lan:11434/"));
        assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check("http://127.0.0.2/"));
    }

    @Test
    @DisplayName("on, a public literal address passes")
    void allowsPublicUrl() {
        OutboundAddressPolicy.setEnabled(true);
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("https://1.1.1.1/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/role",
            "http://169.254.170.2/v2/credentials", "http://[fe80::1]/", "http://[fd00:ec2::254]/latest/",
            "http://100.100.100.200/latest/meta-data", "http://[::ffff:169.254.169.254]/",
            "http://metadata.google.internal/computeMetadata/v1/", "http://Metadata.Google.Internal./"})
    @DisplayName("EVERY EDITION refuses link-local and cloud metadata destinations, with the full rule off")
    void metadataRefusedEverywhere(String url) {
        var e = assertThrows(OutboundAddressPolicy.BlockedDestinationException.class,
                () -> OutboundAddressPolicy.check(url));
        assertTrue(e.getMessage().contains("metadata"), e.getMessage());
    }

    @Test
    @DisplayName("the metadata rule leaves the intranet alone, and the operator can turn it off")
    void metadataRuleIsNarrowAndCanBeTurnedOff() {
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://10.0.0.5/wiki"));
        assertDoesNotThrow(() -> OutboundAddressPolicy.checkHost("192.168.1.10"));
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://100.100.100.201/"));
        OutboundAddressPolicy.setMetadataBlocked(false);
        assertDoesNotThrow(() -> OutboundAddressPolicy.check("http://169.254.169.254/latest/meta-data"));
    }
}
