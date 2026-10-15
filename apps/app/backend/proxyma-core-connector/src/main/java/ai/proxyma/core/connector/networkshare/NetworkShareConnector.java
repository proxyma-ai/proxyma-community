package ai.proxyma.core.connector.networkshare;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A folder on a Windows file server, over SMB2/3, read as THE SOURCE OWNER'S OWN domain account.
 *
 * <p>The share's ACLs decide what is indexed, exactly as they decide what the owner can open in
 * Explorer. A subfolder they cannot read is skipped, never read with someone else's rights - which
 * is why this connector authenticates per owner instead of holding one service account, and why
 * its descriptor does not offer it for sharing.
 *
 * <p>THE ALLOW-LIST IS CHECKED THREE TIMES - at registration, at the start of every walk, and
 * before every fetch - and re-read from settings each time. Checking once at registration would
 * mean narrowing the list never stops a source that is already running, and the list exists to
 * bound which servers this deployment's users can be made to send an NTLM exchange to.
 *
 * <p>See docs/architecture.html#spec-05.
 */
@Component
public class NetworkShareConnector implements ConnectorPlugin {

    private static final Logger log = LoggerFactory.getLogger(NetworkShareConnector.class);

    private final SmbShareCache shares;
    private final FileServerPolicy policy;

    /**
     * @param policy the deployment's file-server ceiling; null only where nothing sets one (a test) -
     *               on every deployment ConnectorConnectionService is that bean
     */
    public NetworkShareConnector(SmbShareCache shares, @Nullable FileServerPolicy policy) {
        this.shares = shares;
        this.policy = policy;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.NETWORK_SHARE);
    }

    /**
     * A session as the source's OWNER: their own domain account, and the ONE host the source may
     * reach - the connection's file server, handed over as its allow-list, under the Enterprise
     * ceiling re-read here. A path that no longer parses is a configuration error the listing
     * reports, not a crash.
     */
    @Override
    public ConnectorSession open(SourceContext context) {
        UncPath root = null;
        String raw = context.get("uncPath");
        if (raw != null && !raw.isBlank()) {
            try {
                root = UncPath.parse(raw);
            } catch (IllegalArgumentException e) {
                log.warn("network share source has an unparseable path: {}", e.getMessage());
            }
        }
        ResolvedCredentials connection = context.connection();
        String password = connection.secret("password") != null ? connection.secret("password") : connection.soleSecret();
        SmbAccount account = connection.username() == null || connection.username().isBlank()
                || password == null || password.isBlank()
                ? null : SmbAccount.parse(connection.username(), password);
        String host = connection.baseUrl();
        boolean allowed = host != null && !host.isBlank() && (policy == null || policy.allows(host));
        return new NetworkShareSession(shares, root, context.scope().recursive(), account,
                HostAllowlist.parse(allowed ? host : ""),
                FileTypeSupport.parseFileTypes(context.scope().fileTypes()));
    }

    /**
     * Closes the SMB handle when the consumer closes the stream OR when it reads to the end.
     *
     * <p>AN OPEN HANDLE COUNTS AGAINST THE SERVER'S OPEN FILES, and a consumer that reads a stream
     * to EOF and forgets {@code close()} is common enough that relying on it leaks handles on the
     * file server rather than in this process - somewhere nobody here can see, and somewhere an
     * administrator eventually notices as a resource limit.
     */
    static final class ClosingOnEofStream extends InputStream {
        private final File handle;
        private final InputStream delegate;
        private boolean closed;

        ClosingOnEofStream(File handle) {
            this.handle = handle;
            this.delegate = handle.getInputStream();
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b < 0) closeQuietly();
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n < 0) closeQuietly();
            return n;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            closeQuietly();
        }

        private void closeQuietly() {
            if (closed) return;
            closed = true;
            try { delegate.close(); } catch (Exception ignored) { }
            try { handle.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * A depth-first walk that lists ONE DIRECTORY PER REFILL.
     *
     * <p>A share with a hundred thousand files is never held in memory as a list: the iterator
     * keeps a stack of directories still to visit and a small buffer of the entries from the last
     * one listed. The alternative - collect everything, then return it - is what makes a walk of a
     * large share fail as an out-of-memory error rather than as a slow sync.
     *
     * <p>It captures its configuration at construction - the session's, which no other source can
     * reach.
     */
    static final class Walk implements Iterator<ItemMetadata>, SyncErrorAware {

        private final SmbShareCache shares;
        private final UncPath root;
        private final boolean recursive;
        private final SmbAccount account;
        private final HostAllowlist allowlist;
        private final Set<String> enabledExtensions;

        private final Deque<UncPath> pending = new ArrayDeque<>();
        private final Deque<ItemMetadata> ready = new ArrayDeque<>();
        private DiskShare share;
        private boolean started;
        private String error;

        Walk(SmbShareCache shares, UncPath root, boolean recursive,
             SmbAccount account, HostAllowlist allowlist, Set<String> enabledExtensions) {
            this.shares = shares;
            this.root = root;
            this.recursive = recursive;
            this.account = account;
            this.allowlist = allowlist;
            this.enabledExtensions = enabledExtensions;
        }

        @Override
        public boolean hasErrored() {
            return error != null;
        }

        @Override
        public String getErrorMessage() {
            return error;
        }

        private void begin() {
            started = true;
            if (root == null) {
                error = "Network path is missing";
                return;
            }
            try {
                allowlist.check(root.host());
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                return;
            }
            if (account == null) {
                error = "No network share account is configured - add your domain user name and "
                        + "password - add a Network share connection in Settings > Connections";
                return;
            }
            try {
                share = shares.openShare(root, account);
            } catch (Exception e) {
                fail(root, e);
                return;
            }
            pending.push(root);
        }

        @Override
        public boolean hasNext() {
            if (!started) begin();
            while (ready.isEmpty() && error == null && !pending.isEmpty()) {
                listDirectory(pending.pop());
            }
            return !ready.isEmpty();
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            return ready.poll();
        }

        private void listDirectory(UncPath dir) {
            List<FileIdBothDirectoryInformation> entries;
            try {
                entries = share.list(dir.path());
            } catch (Exception e) {
                // A DENIED SUBFOLDER IS NOT A FAILED SYNC. The owner cannot read it either, so
                // skipping it is the correct result rather than an error to report - and anything
                // indexed under it before is purged by the normal full-sync purge, which is also
                // correct. A denied ROOT is a real failure: the source can never produce anything.
                if (SmbErrors.isAccessDenied(e) && !dir.equals(root)) {
                    log.debug("skipping folder the owner cannot read: {}", dir.toUnc());
                    return;
                }
                fail(dir, e);
                return;
            }

            List<ItemMetadata> found = new ArrayList<>();
            for (FileIdBothDirectoryInformation entry : entries) {
                String name = entry.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;

                long attrs = entry.getFileAttributes();
                // HIDDEN and SYSTEM are the server's own furniture - recycle bins, thumbnail
                // caches, DFS metadata. A REPARSE_POINT is a junction or symlink, which can point
                // anywhere including back up the tree; following one is how a walk loops forever.
                // OFFLINE means the bytes are on tape or in cloud tier, and reading one drags it
                // back at a cost the owner did not ask for.
                if (isSet(attrs, FileAttributes.FILE_ATTRIBUTE_HIDDEN)
                        || isSet(attrs, FileAttributes.FILE_ATTRIBUTE_SYSTEM)
                        || isSet(attrs, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT)
                        || isSet(attrs, FileAttributes.FILE_ATTRIBUTE_OFFLINE)) {
                    continue;
                }

                UncPath child = dir.resolve(name);
                if (isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
                    if (recursive) pending.push(child);
                    continue;
                }

                // "~$Report.docx" is the lock file Word leaves beside an open document. It is a
                // few bytes of nothing and it appears and vanishes as people work, so indexing it
                // produces churn and no content.
                if (name.startsWith("~$")) continue;
                // The source's own file types when it chose some, every supported type when not -
                // the same question, answered by the same method, as a local folder's walk asks.
                if (!FileTypeSupport.isSupportedFileName(name, enabledExtensions)) continue;

                String id = child.toSourceId();
                if (id.length() > UncPath.MAX_SOURCE_ID_LENGTH) {
                    log.warn("skipping file whose id exceeds {} characters: {}",
                            UncPath.MAX_SOURCE_ID_LENGTH, child.toUnc());
                    continue;
                }

                ItemMetadata item = new ItemMetadata(
                        id, name, ConnectorType.NETWORK_SHARE,
                        toInstant(entry.getLastWriteTime()),
                        FileTypeSupport.resolveMimeType(name));
                item.setSizeBytes(entry.getEndOfFile());
                item.getExtraProperties().put("url", child.toUnc());
                found.add(item);
            }
            ready.addAll(found);
        }

        private void fail(UncPath at, Exception e) {
            if (SmbErrors.isSessionBroken(e)) shares.evict(root, account);
            error = e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : SmbErrors.describe(e, at.host(), at.share());
            log.warn("network share sync of {} failed: {}", at.toUnc(), error);
        }

        private static boolean isSet(long attributes, FileAttributes flag) {
            return (attributes & flag.getValue()) != 0;
        }

        private static Instant toInstant(com.hierynomus.msdtyp.FileTime fileTime) {
            return fileTime == null ? Instant.now() : Instant.ofEpochMilli(fileTime.toEpochMillis());
        }
    }
}
