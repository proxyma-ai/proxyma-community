package ai.proxyma.core.connector.networkshare;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.Set;

/**
 * One network share source, as its owner - see {@link NetworkShareConnector}. Its path, account and
 * allow-list are this session's own, fixed when it opens, so two shares sync side by side with
 * nothing between them.
 */
final class NetworkShareSession implements ConnectorSession {

    private final SmbShareCache shares;
    private final UncPath root;
    private final boolean recursive;
    private final SmbAccount account;
    private final HostAllowlist allowlist;
    // The source's file-type filter, parsed as a folder's is; null means every supported type.
    private final Set<String> enabledExtensions;

    NetworkShareSession(SmbShareCache shares, UncPath root, boolean recursive, SmbAccount account,
                        HostAllowlist allowlist, Set<String> enabledExtensions) {
        this.shares = shares;
        this.root = root;
        this.recursive = recursive;
        this.account = account;
        this.allowlist = allowlist;
        this.enabledExtensions = enabledExtensions;
    }

    /**
     * Connect, authenticate, open the share, confirm the folder is there and list it. That last
     * step is the point: a share opens fine when the folder inside it is missing or unreadable, and
     * registering a source that can never produce a document is the failure this probe exists to
     * catch while the person who typed the path is still there.
     */
    @Override
    public AccessCheck verifyAccess() {
        if (root == null) return AccessCheck.failed("Network path is missing");
        try {
            allowlist.check(root.host());
        } catch (IllegalArgumentException e) {
            return AccessCheck.failed(e.getMessage());
        }
        if (account == null) {
            return AccessCheck.failed(
                    "This source has no Network share connection with a user name and password - "
                            + "add one in Settings > Connections");
        }
        try {
            DiskShare share = shares.openShare(root, account);
            if (!root.path().isEmpty() && !share.folderExists(root.path())) {
                return AccessCheck.failed("Folder not found on \\\\" + root.host() + "\\" + root.share());
            }
            share.list(root.path());
            return AccessCheck.verified();
        } catch (IllegalArgumentException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (Exception e) {
            if (SmbErrors.isSessionBroken(e)) shares.evict(root, account);
            return AccessCheck.failed(SmbErrors.describe(e, root.host(), root.share()));
        }
    }

    // A full walk every pass: SMB has no change feed this reads, and only a full listing purges.
    @Override
    public Listing list(ListRequest request) {
        return Listings.of(new NetworkShareConnector.Walk(shares, root, recursive, account, allowlist, enabledExtensions));
    }

    /**
     * The bytes, with the share opened as the OWNER of the source this item belongs to - the same
     * account the walk used.
     */
    @Override
    public InputStream fetch(ItemMetadata item) {
        UncPath file = UncPath.parse(item.getId());
        allowlist.check(file.host());
        if (account == null) {
            throw new IllegalStateException(
                    "No network share account is configured for this source's owner");
        }
        try {
            DiskShare share = shares.openShare(file, account);
            // SHARE MODE INCLUDES WRITE AND DELETE, and that is not laxity. People keep the
            // documents they are working on open in Word and Excel; asking for exclusive access
            // fails on exactly those files - the current ones, the ones most worth indexing.
            File handle = share.openFile(
                    file.path(),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ,
                               SMB2ShareAccess.FILE_SHARE_WRITE,
                               SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            return new NetworkShareConnector.ClosingOnEofStream(handle);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (SmbErrors.isSessionBroken(e)) shares.evict(file, account);
            throw new IllegalStateException(SmbErrors.describe(e, file.host(), file.share()), e);
        }
    }
}
