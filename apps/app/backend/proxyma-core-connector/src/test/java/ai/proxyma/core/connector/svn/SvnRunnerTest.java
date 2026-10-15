package ai.proxyma.core.connector.svn;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The svn process runner and what it sends, against {@link FakeSvn} - a real process that, like
 * svn, reads the password before it writes a byte.
 *
 * <ul>
 *   <li>Every command used to queue its password writer on one shared three-thread pool behind its
 *       readers: a sync's listing held two threads, so a tool call's password was never written and
 *       it failed after two minutes; two calls at once deadlocked.</li>
 *   <li>A source could name any host, and its credential was sent there.</li>
 *   <li>Every command accepted an unknown CA and a name mismatch.</li>
 *   <li>A missing svn surfaced as the JVM's "Cannot run program".</li>
 * </ul>
 */
class SvnRunnerTest {

    private static final String ROOT = "https://svn.example.test/svn";

    private SvnConnector svn;

    @BeforeEach
    void setUp() {
        svn = new SvnConnector();
        String java = ProcessHandle.current().info().command().orElse("java");
        Path classes = Path.of(FakeSvn.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                .replaceFirst("^/([A-Za-z]:)", "$1"));
        svn.setLauncher(List.of(java, "-cp", classes.toString(), FakeSvn.class.getName()));
    }

    // A session over one repository through a connection to ROOT, as SourceContextFactory builds it.
    private ConnectorSession source(String url) {
        ResolvedCredentials connection = new ResolvedCredentials(null, ConnectionProtocol.SVN, AuthKind.BASIC, ROOT,
                "ana", Map.of("password", "secret"), Map.of(), null);
        Map<String, String> config = url == null ? Map.of() : Map.of(SvnConnector.REPO_PATH, url);
        return svn.open(new SourceContext(null, ConnectorType.SVN, null, null, config, null, null, connection,
                SourceScope.defaults(), null, null));
    }

    @Test
    @DisplayName("credentialed reads finish while a long listing runs, and two at once do not deadlock")
    void commandsDoNotWaitForEachOther() throws Exception {
        CompletableFuture<List<SvnConnector.SvnTreeEntry>> slowListing = CompletableFuture.supplyAsync(() -> {
            try {
                return svn.listFilesAs(ROOT + "/slow", "", "ana", "secret", true, 100);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(300);

        CompletableFuture<SvnConnector.SvnText> first = CompletableFuture.supplyAsync(() -> cat(ROOT + "/one"));
        CompletableFuture<SvnConnector.SvnText> second = CompletableFuture.supplyAsync(() -> cat(ROOT + "/two"));

        assertThat(first.get(20, TimeUnit.SECONDS).text()).isEqualTo("file content");
        assertThat(second.get(20, TimeUnit.SECONDS).text()).isEqualTo("file content");
        assertThat(slowListing).isNotDone();
        assertThat(slowListing.get(30, TimeUnit.SECONDS)).hasSize(4);
    }

    private SvnConnector.SvnText cat(String url) {
        try {
            return svn.catFileAs(url, "", "README", "ana", "secret", 1024);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("a read is stopped at its bound rather than read whole into memory")
    void readStopsAtTheBound() throws Exception {
        long started = System.nanoTime();
        SvnConnector.SvnText text = svn.catFileAs(ROOT + "/big", "", "installer.bin", "ana", "secret", 4096);
        assertThat(text.truncated()).isTrue();
        assertThat(text.text()).hasSize(4096);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(30);
    }

    @Test
    @DisplayName("a listing is stopped one entry past what the tool will show")
    void listingStopsPastTheBound() throws Exception {
        List<SvnConnector.SvnTreeEntry> entries = svn.listFilesAs(ROOT, "", "ana", "secret", true, 2);
        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("a source's credential is never sent to a server other than its connection's")
    void credentialStaysOnTheConnectionsServer() {
        ConnectorSession session = source("https://svn.attacker.example/repos/x");

        Iterator<ItemMetadata> listing = session.list(ListRequest.full());
        assertThat(listing.hasNext()).isFalse();
        assertThat(((SyncErrorAware) listing).getErrorMessage()).contains("not on the server of its SVN connection");

        AccessCheck probe = session.verifyAccess();
        assertThat(probe.detail()).contains("not on the server of its SVN connection");

        // The same check for a fetch the drain makes, by the item's id under the stored URL.
        ItemMetadata item = new ItemMetadata();
        item.setId("trunk/build.sh");
        assertThrows(RuntimeException.class, () -> session.fetch(item));
    }

    @Test
    @DisplayName("a source on its connection's server syncs, and its listing is streamed into items")
    void sourceOnTheRootSyncs() {
        Iterator<ItemMetadata> listing = source(ROOT + "/Repo").list(ListRequest.full());
        assertThat(listing.hasNext()).isTrue();
        assertThat(listing.next().getId()).isEqualTo("a.md");
    }

    @Test
    @DisplayName("certificates are validated: no trust override, and the Settings CA is handed to svn")
    void certificatesAreValidated() throws Exception {
        svn.setCustomCaCertificates(() -> "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
        String args = svn.catFileAs(ROOT + "/args", "", "x", "ana", "secret", 64 * 1024).text();
        assertThat(args).doesNotContain("trust-server-cert");
        assertThat(args).contains("servers:global:ssl-trust-default-ca=yes");
        assertThat(args).contains("servers:global:ssl-authority-files=");
        // The password goes on stdin, never on the command line.
        assertThat(args).doesNotContain("secret");
    }

    @Test
    @DisplayName("svn's own error reaches the reader as a sentence")
    void svnFailureIsDescribed() {
        IOException e = assertThrows(IOException.class, () -> svn.probeAs(ROOT + "/fail", "ana", "secret"));
        assertThat(SvnConnector.describeFailure(e, ROOT, true)).startsWith("Could not connect to the SVN server");
    }

    @Test
    @DisplayName("a server without svn says so, not \"Cannot run program\"")
    void missingSvnIsNamed() {
        svn.setLauncher(List.of("proxyma-no-such-svn-binary"));
        IOException e = assertThrows(IOException.class, () -> svn.probeAs(ROOT, null, null));
        assertThat(SvnConnector.describeFailure(e, ROOT, false)).isEqualTo(SvnConnector.NOT_INSTALLED);
    }

    @Test
    @DisplayName("a session with no repository says so, whatever another source's session read")
    void nullConfigurationClearsThePreviousSource() {
        assertThat(source(ROOT + "/Repo").list(ListRequest.full()).hasNext()).isTrue();
        Iterator<ItemMetadata> listing = source(null).list(ListRequest.full());
        assertThat(listing.hasNext()).isFalse();
        assertThat(((SyncErrorAware) listing).getErrorMessage()).contains("No SVN repository URL");
    }
}
