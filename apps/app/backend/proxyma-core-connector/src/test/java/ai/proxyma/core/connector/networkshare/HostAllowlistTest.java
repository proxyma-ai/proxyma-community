package ai.proxyma.core.connector.networkshare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests 3 and 4 of docs/architecture.html#spec-05-11.
 *
 * <p>THIS IS A CREDENTIAL BOUNDARY, so every case here is about what must NOT be reachable.
 * Connecting to an SMB server sends it an NTLM exchange for the source owner's domain account,
 * which can be cracked offline or relayed - so the set of hosts this deployment can be pointed at
 * is the set of places a user's domain credential can be made to go.
 */
class HostAllowlistTest {

    @Nested
    @DisplayName("an empty list means the feature is OFF, never 'any host'")
    class EmptyMeansOff {

        @Test
        void nothingIsAllowedWhenTheListIsEmpty() {
            for (String raw : new String[] {null, "", "   ", ",", " ; , \n "}) {
                HostAllowlist list = HostAllowlist.parse(raw);

                assertThat(list.isEmpty()).as("raw=%s", raw).isTrue();
                assertThat(list.isAllowed("fs1.corp.example")).as("raw=%s", raw).isFalse();
                assertThat(list.isAllowed("anything")).as("raw=%s", raw).isFalse();
            }
        }

        @Test
        void anEmptyListSaysToAddAConnection() {
            // Since 2026-10-01 the list a source is checked against is its connection's server, so
            // an empty one means the source has no usable connection - distinct from "not this
            // server", which names the host it was refused.
            assertThatThrownBy(() -> HostAllowlist.parse("").check("fs1.corp.example"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no usable Network share connection")
                    .hasMessageContaining("Settings > Connections");
        }

        @Test
        void aHostNotOnTheListIsNamedInTheRefusal() {
            assertThatThrownBy(() -> HostAllowlist.parse("fs1.corp.example").check("evil.example"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evil.example")
                    .hasMessageContaining("not the server of this source's Network share connection");
        }
    }

    @Nested
    @DisplayName("wildcards are a statement about DNS names")
    class Wildcards {

        @Test
        void aWildcardCoversHostsUnderTheDomainButNotTheDomainItself() {
            HostAllowlist list = HostAllowlist.parse("*.corp.example");

            assertThat(list.isAllowed("fs1.corp.example")).isTrue();
            assertThat(list.isAllowed("deep.fs1.corp.example")).isTrue();
            // corp.example is a DIFFERENT MACHINE from anything under it, and the wildcard was
            // written about the subdomain space.
            assertThat(list.isAllowed("corp.example")).isFalse();
            assertThat(list.isAllowed("notcorp.example")).isFalse();
            assertThat(list.isAllowed("corp.example.evil.test")).isFalse();
        }

        @Test
        void anIpLiteralMatchesOnlyAnIdenticalEntry() {
            // An address is not "under" any domain. Letting *.example match one would turn a
            // name-shaped rule into an address-shaped permission nobody wrote - and an attacker
            // who controls any DNS name is not thereby given every address.
            assertThat(HostAllowlist.parse("*.corp.example").isAllowed("10.0.0.1")).isFalse();
            assertThat(HostAllowlist.parse("*.corp.example").isAllowed("fe80::1")).isFalse();

            assertThat(HostAllowlist.parse("10.0.0.1").isAllowed("10.0.0.1")).isTrue();
            assertThat(HostAllowlist.parse("10.0.0.1").isAllowed("10.0.0.2")).isFalse();
        }

        @Test
        void aBareWildcardDoesNotBecomeAnythingGoes() {
            assertThat(HostAllowlist.parse("*.").isAllowed("fs1.corp.example")).isFalse();
        }
    }

    @Nested
    @DisplayName("an administrator's list is parsed the way they pasted it")
    class Parsing {

        @Test
        void separatorsAreCommasSemicolonsAndWhitespace() {
            HostAllowlist list = HostAllowlist.parse("fs1.corp.example, fs2.corp.example;\n fs3.corp.example");

            assertThat(list.isAllowed("fs1.corp.example")).isTrue();
            assertThat(list.isAllowed("fs2.corp.example")).isTrue();
            assertThat(list.isAllowed("fs3.corp.example")).isTrue();
            assertThat(list.entries()).hasSize(3);
        }

        @Test
        void caseAndTrailingDotsAreNormalizedOnBothSides() {
            HostAllowlist list = HostAllowlist.parse("FS1.Corp.Example.");

            assertThat(list.isAllowed("fs1.corp.example")).isTrue();
            assertThat(list.isAllowed("FS1.CORP.EXAMPLE")).isTrue();
            assertThat(list.isAllowed("fs1.corp.example.")).isTrue();
        }

        @Test
        void blankAndNullHostsAreNeverAllowed() {
            HostAllowlist list = HostAllowlist.parse("fs1.corp.example");

            assertThat(list.isAllowed(null)).isFalse();
            assertThat(list.isAllowed("")).isFalse();
            assertThat(list.isAllowed("  ")).isFalse();
            assertThat(list.isAllowed(".")).isFalse();
        }
    }
}
