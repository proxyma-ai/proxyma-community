package ai.proxyma.core.connector.networkshare;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.spi.SourceScope;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.share.DiskShare;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A network share's file-type filter, which is a folder's filter read over SMB.
 *
 * <p>The real {@code Walk} runs; only the share it lists is a stand-in. What is pinned is that the
 * stored list narrows the walk with FileTypeSupport's semantics - blank is every supported type, a
 * typed extension outside the built-in list is honored - that the next source's session does not
 * inherit the previous one's list (recurring-failure-classes 24), and that the deployment's ceiling
 * on file servers is asked again by every session.
 */
class NetworkShareFileTypeFilterTest {

    private static final String ROOT = "\\\\fs1.corp.example\\share\\docs";

    private NetworkShareConnector connector;
    private SmbShareCache shares;

    @BeforeEach
    void setUp() {
        DiskShare share = mock(DiskShare.class);
        List<FileIdBothDirectoryInformation> top = List.of(
                file("report.pdf"), file("notes.txt"), file("data.json"), file("~$report.pdf"), dir("sub"));
        List<FileIdBothDirectoryInformation> below = List.of(file("deep.pdf"), file("deep.txt"));
        when(share.list(anyString())).thenAnswer(a -> {
            String path = a.getArgument(0);
            return path.endsWith("sub") ? below : top;
        });
        shares = mock(SmbShareCache.class);
        when(shares.openShare(any(), any())).thenReturn(share);
        connector = new NetworkShareConnector(shares, host -> true);
    }

    @Test
    @DisplayName("the source's own file types decide what the walk emits, custom extensions included")
    void fileTypesNarrowTheWalk() {
        // data.json is not a built-in type and is still honored: a typed extension is an
        // instruction, exactly as it is for a local folder.
        assertEquals(List.of("report.pdf", "data.json", "deep.pdf"), titles(sync("PDF, .json", true)));
    }

    @Test
    @DisplayName("no file types means every supported type, and a second source never inherits the first's")
    void blankFileTypesMeanEverySupportedType() {
        assertEquals(List.of("data.json"), titles(sync("json", false)));

        // Poorer than the first: no file types at all.
        assertEquals(List.of("report.pdf", "notes.txt", "deep.pdf", "deep.txt"), titles(sync(null, true)));
    }

    @Test
    @DisplayName("subfolders off keeps the walk in the folder itself, with the filter still applied")
    void recursionOffStaysInTheFolder() {
        assertEquals(List.of("report.pdf"), titles(sync("pdf", false)));
    }

    @Test
    @DisplayName("a file server the deployment's ceiling leaves out is refused, by the walk and the probe")
    void theCeilingIsAskedAtEverySession() {
        connector = new NetworkShareConnector(shares, host -> false);

        Iterator<ItemMetadata> it = sync(null, true);
        assertFalse(it.hasNext());
        assertTrue(((SyncErrorAware) it).hasErrored());
        assertTrue(session(null, true).verifyAccess().rejected());
    }

    // One source's session, as SourceContextFactory builds it: the path, the scope, and the owner's
    // Network share connection to the file server.
    private ConnectorSession session(String fileTypes, boolean recursive) {
        ResolvedCredentials connection = new ResolvedCredentials(null, ConnectionProtocol.NETWORK_SHARE, AuthKind.BASIC,
                "fs1.corp.example", "CORP\\ana", Map.of("password", "secret"), Map.of(), null);
        return connector.open(new SourceContext(null, ConnectorType.NETWORK_SHARE, null, null,
                Map.of("uncPath", ROOT), null, null, connection, new SourceScope(fileTypes, recursive, null), null, null));
    }

    private Iterator<ItemMetadata> sync(String fileTypes, boolean recursive) {
        return session(fileTypes, recursive).list(ListRequest.full());
    }

    private static List<String> titles(Iterator<ItemMetadata> it) {
        List<String> out = new ArrayList<>();
        while (it.hasNext()) out.add(it.next().getTitle());
        assertFalse(((SyncErrorAware) it).hasErrored(), () -> ((SyncErrorAware) it).getErrorMessage());
        return out;
    }

    private static FileIdBothDirectoryInformation file(String name) {
        FileIdBothDirectoryInformation entry = mock(FileIdBothDirectoryInformation.class);
        when(entry.getFileName()).thenReturn(name);
        when(entry.getFileAttributes()).thenReturn(FileAttributes.FILE_ATTRIBUTE_ARCHIVE.getValue());
        when(entry.getEndOfFile()).thenReturn(1024L);
        return entry;
    }

    private static FileIdBothDirectoryInformation dir(String name) {
        FileIdBothDirectoryInformation entry = mock(FileIdBothDirectoryInformation.class);
        when(entry.getFileName()).thenReturn(name);
        when(entry.getFileAttributes()).thenReturn(FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue());
        return entry;
    }
}
