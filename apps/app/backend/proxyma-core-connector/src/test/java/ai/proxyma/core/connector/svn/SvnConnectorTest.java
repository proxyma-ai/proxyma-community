package ai.proxyma.core.connector.svn;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three SVN failures of the 2026-09-18 spec review that a compile could not have caught.
 *
 * <p>Each one looked like working code: a content fetch that threw only in the drain loop, a
 * listing that reported zero files rather than a failure, and a URL that was only wrong for file
 * names people actually use. None of them is reachable without an svn server, so they are pinned
 * here at the three pure functions the real paths go through.
 */
class SvnConnectorTest {

    private final SvnConnector connector = new SvnConnector();

    // =========================================================================
    // getContent's fallback - spec 04 section 5.6, framework 3.5
    // =========================================================================

    @Test
    @DisplayName("an item rebuilt from the database row resolves from its id and the configured URL")
    void contentTargetFromIdAlone() {
        ItemMetadata drained = new ItemMetadata();
        drained.setId("docs/design/spec.docx");
        drained.setSourceType(ConnectorType.SVN);
        drained.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        SvnConnector.SvnTarget target =
                SvnConnector.resolveTarget(drained, "https://host/svn/Repo");

        assertEquals("https://host/svn/Repo", target.repoUrl());
        assertEquals("", target.repoPath());
        assertEquals("docs/design/spec.docx", target.filePath());
    }

    @Test
    @DisplayName("the item's own extras still win over the configured URL")
    void extrasBeatConfiguration() {
        ItemMetadata listed = new ItemMetadata();
        listed.setId("spec.docx");
        listed.putExtra("repoUrl", "https://host/svn/Other");
        listed.putExtra("repoPath", "trunk");
        listed.putExtra("filePath", "docs/spec.docx");

        SvnConnector.SvnTarget target =
                SvnConnector.resolveTarget(listed, "https://host/svn/Repo");

        assertEquals("https://host/svn/Other", target.repoUrl());
        assertEquals("trunk", target.repoPath());
        assertEquals("docs/spec.docx", target.filePath());
    }

    @Test
    @DisplayName("an item with no id and no configured URL says so rather than fetching nothing")
    void nothingToResolve() {
        ItemMetadata empty = new ItemMetadata();
        empty.setId("spec.docx");
        assertThrows(IllegalArgumentException.class, () -> SvnConnector.resolveTarget(empty, null));
    }

    // =========================================================================
    // A listing that cannot be read is an error - spec 04 section 5.1, test 2
    // =========================================================================

    private static final String VALID_LISTING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <lists>
              <list path="https://host/svn/Repo">
                <entry kind="file">
                  <name>docs/spec.md</name>
                  <size>12</size>
                  <commit revision="42"><date>2026-09-01T10:00:00.000000Z</date></commit>
                </entry>
                <entry kind="dir"><name>docs</name></entry>
                <entry kind="file"><name>build/output.bin</name></entry>
                <entry kind="file"><name>params/tune.cfg</name></entry>
              </list>
            </lists>
            """;

    @Test
    @DisplayName("a DOCTYPE in the listing fails the sync instead of emptying the source")
    void doctypeListingThrows() {
        String withDoctype = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE lists [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <lists><list><entry kind="file"><name>docs/spec.md</name></entry></list></lists>
                """;
        assertThrows(IOException.class, () -> connector.parseListXml(withDoctype, null));
        assertThrows(IOException.class, () -> connector.parseTreeXml(withDoctype));
    }

    @Test
    @DisplayName("output cut off mid-element fails the sync too")
    void truncatedListingThrows() {
        String truncated = "<lists><list><entry kind=\"file\"><name>docs/spec.md</name>";
        assertThrows(IOException.class, () -> connector.parseListXml(truncated, null));
    }

    @Test
    @DisplayName("a good listing still parses, and still drops directories and unindexable files")
    void validListingParses() throws IOException {
        List<SvnConnector.SvnFileEntry> entries = connector.parseListXml(VALID_LISTING, null);

        assertEquals(1, entries.size());
        assertEquals("docs/spec.md", entries.get(0).filePath());
        assertEquals("42", entries.get(0).revision());
    }

    @Test
    @DisplayName("svn.indexed-extensions replaces the built-in list rather than adding to it")
    void configuredExtensionsReplaceTheDefault() throws IOException {
        Set<String> configured = SvnConnector.parseIndexedExtensions(".cfg, CFG , ");
        List<SvnConnector.SvnFileEntry> entries = connector.parseListXml(VALID_LISTING, configured);

        assertEquals(1, entries.size());
        assertEquals("params/tune.cfg", entries.get(0).filePath());
        assertNull(SvnConnector.parseIndexedExtensions("  "),
                "a blank setting leaves the built-in list in place");
    }

    // =========================================================================
    // The source's own scope - file types and subfolders, as a folder has
    // =========================================================================

    @Test
    @DisplayName("a source's own file types decide what a sync keeps, over the svn setting")
    void sourceFileTypesWinOverTheSetting() {
        RecordingSvn svn = new RecordingSvn();
        assertEquals(List.of("params/tune.cfg"), ids(svn.sync("cfg", "md", null)));
    }

    @Test
    @DisplayName("with no file types of its own, the setting and then the built-in list still decide")
    void blankFileTypesKeepTheTypeDefault() {
        RecordingSvn svn = new RecordingSvn();
        assertEquals(List.of("params/tune.cfg"), ids(svn.sync("  ", "cfg", null)));

        assertEquals(List.of("docs/spec.md"), ids(svn.sync(null, null, null)));
        assertNull(svn.allowed, "no filter anywhere should mean the built-in list");
    }

    @Test
    @DisplayName("a second source with no file types never inherits the first one's filter")
    void fileTypesDoNotLeakBetweenSources() {
        RecordingSvn svn = new RecordingSvn();
        assertEquals(List.of("build/output.bin", "params/tune.cfg"), ids(svn.sync(".CFG, bin", null, "false")));
        assertFalse(svn.recursive);

        // Poorer than the first: no file types, no recursion setting. Everything the first source
        // set has to be gone.
        assertEquals(List.of("docs/spec.md"), ids(svn.sync(null, null, null)));
        assertTrue(svn.recursive, "an absent recursive setting must mean the whole repository");
    }


    private static List<String> ids(Iterator<ItemMetadata> it) {
        List<String> out = new ArrayList<>();
        while (it.hasNext()) out.add(it.next().getId());
        return out;
    }

    /**
     * The real listing of a session over a canned svn answer: only the svn CLI call is replaced, and
     * what the session asked it for - the extension set and the depth - is recorded. Each sync is a
     * session of its own, with the owner's {@code svn.indexed-extensions} as its preferences say.
     */
    private static final class RecordingSvn extends SvnConnector {
        Set<String> allowed;
        boolean recursive;
        String listedUrl;

        Iterator<ItemMetadata> sync(String fileTypes, String indexedExtensions, String recursive) {
            setPreferences(owner -> indexedExtensions);
            SourceScope scope = new SourceScope(fileTypes, recursive == null || Boolean.parseBoolean(recursive), null);
            return open(new SourceContext(null, ConnectorType.SVN, null, null,
                    Map.of(REPO_PATH, "https://host/svn/Repo"), null, null, null, scope, null, null))
                    .list(ListRequest.full());
        }

        @Override
        public List<SvnFileEntry> listFiles(String repoUrl, String repoPath, Set<String> allowedExtensions,
                                            boolean recursive, String username, String password) throws IOException {
            this.allowed = allowedExtensions;
            this.recursive = recursive;
            this.listedUrl = repoUrl;
            return parseListXml(VALID_LISTING, allowedExtensions);
        }
    }

    // =========================================================================
    // The plugin - what a source is called and where it reads
    // =========================================================================

    @Test
    @DisplayName("a source is named by the name given, else by its repository's last folder")
    void displayName() {
        assertEquals("Repo", connector.displayName(Map.of(SvnConnector.REPO_PATH, "https://host/svn/Repo/")));
        assertEquals("my files", connector.displayName(Map.of(SvnConnector.REPO_PATH, "https://host/svn/my%20files")));
        assertEquals("Docs", connector.displayName(Map.of(SvnConnector.REPO_PATH, "https://host/svn/Repo", "repoName", "Docs")));
        assertEquals("https://host/svn/Repo", connector.webUrl(Map.of(SvnConnector.REPO_PATH, "https://host/svn/Repo"), null));
    }

    @Test
    @DisplayName("a stored whole URL is read as it is; a relative path is joined onto the connection's root")
    void repositoryUrl() {
        RecordingSvn svn = new RecordingSvn();
        ids(svn.open(new SourceContext(null, ConnectorType.SVN, null, null,
                Map.of(SvnConnector.REPO_PATH, "https://host/svn/Repo"), null, null,
                ai.proxyma.core.connector.spi.ResolvedCredentials.token("https://other/svn", null),
                SourceScope.defaults(), null, null)).list(ListRequest.full()));
        assertEquals("https://host/svn/Repo", svn.listedUrl);

        ids(svn.open(new SourceContext(null, ConnectorType.SVN, null, null,
                Map.of(SvnConnector.REPO_PATH, "Repo/view/head/trunk"), null, null,
                ai.proxyma.core.connector.spi.ResolvedCredentials.token("https://host/svn/", null),
                SourceScope.defaults(), null, null)).list(ListRequest.full()));
        assertEquals("https://host/svn/Repo/trunk", svn.listedUrl);
    }

    // =========================================================================
    // Per-segment encoding - spec 04 section 4.1, test 5
    // =========================================================================

    @Test
    @DisplayName("a file name with a space and a '#' is encoded segment by segment")
    void fileUrlEncodesEachSegment() {
        assertEquals("https://host/svn/Repo/docs/design%20spec%20%232.docx",
                SvnConnector.fileUrl("https://host/svn/Repo", "", "docs/design spec #2.docx"));
    }

    @Test
    @DisplayName("the repository URL is left alone and the separators stay separators")
    void fileUrlKeepsStructure() {
        assertEquals("https://host/svn/Repo/01%20Docs/a%25b/read%2Bme.txt",
                SvnConnector.fileUrl("https://host/svn/Repo/", "01 Docs", "a%b/read+me.txt"));
        assertEquals("https://host/svn/Repo/trunk/main.c",
                SvnConnector.fileUrl("https://host/svn/Repo", "/trunk/", "main.c"));
    }
}
