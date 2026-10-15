package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import ai.proxyma.core.connector.networkshare.SmbAccount;
import ai.proxyma.core.connector.networkshare.SmbErrors;
import ai.proxyma.core.connector.networkshare.SmbShareCache;
import org.springframework.stereotype.Component;

/**
 * Test for a Network share connection: sign in to its file server as its domain account, and say
 * whether that worked. No share is opened - a connection names a server, not a folder; a folder is
 * a source's business, and registering one probes it (NetworkShareSession.verifyAccess).
 *
 * <p>Since 2026-10-01, when network shares became a connection. The refusal is worded by the same
 * SmbErrors the sync uses, so Test and a failed sync say the same thing about the same password.
 */
@Component
public class SmbConnectionTester implements ConnectionTester {

    private final SmbShareCache shares;

    public SmbConnectionTester(SmbShareCache shares) {
        this.shares = shares;
    }

    @Override
    public boolean supports(ConnectionProtocol protocol) {
        return protocol == ConnectionProtocol.NETWORK_SHARE;
    }

    @Override
    public Result test(ResolvedConnection c) {
        if (!c.hasBaseUrl()) return Result.failed("Set the file server first.");
        SmbAccount account = SmbAccount.parse(c.username(), c.secret());
        if (account == null) return Result.failed("Enter a username and password first.");
        try {
            shares.authenticate(c.baseUrl(), account);
            return Result.ok("Signed in as {user}", "user", c.username());
        } catch (Exception e) {
            return Result.failed(SmbErrors.describe(e, c.baseUrl(), ""));
        }
    }
}
