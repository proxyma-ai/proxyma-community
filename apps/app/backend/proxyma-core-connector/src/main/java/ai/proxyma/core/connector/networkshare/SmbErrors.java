package ai.proxyma.core.connector.networkshare;

import com.hierynomus.mssmb2.SMBApiException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Turns an SMB failure into a sentence the source owner can act on, and answers the two questions
 * the connector asks of one: was the credential rejected, and is the cached session now useless.
 *
 * <p>NT STATUS CODES BY VALUE, because smbj does not name all of them and a numeric comparison
 * against the wire value cannot drift with the library's enum. The codes are the stable half.
 *
 * <p>See docs/architecture.html#spec-05-7-4.
 */
public final class SmbErrors {

    private SmbErrors() {}

    public static final long LOGON_FAILURE          = 0xC000006DL;
    public static final long ACCOUNT_RESTRICTION    = 0xC000006EL;
    public static final long PASSWORD_EXPIRED       = 0xC0000071L;
    public static final long ACCOUNT_DISABLED       = 0xC0000072L;
    public static final long ACCOUNT_EXPIRED        = 0xC0000193L;
    public static final long PASSWORD_MUST_CHANGE   = 0xC0000224L;
    public static final long ACCOUNT_LOCKED_OUT     = 0xC0000234L;
    public static final long ACCESS_DENIED          = 0xC0000022L;
    public static final long BAD_NETWORK_NAME       = 0xC00000CCL;
    public static final long OBJECT_NAME_NOT_FOUND  = 0xC0000034L;
    public static final long OBJECT_PATH_NOT_FOUND  = 0xC000003AL;
    public static final long PATH_NOT_COVERED       = 0xC0000257L;
    public static final long NETWORK_NAME_DELETED   = 0xC00000C9L;
    public static final long USER_SESSION_DELETED   = 0xC0000203L;
    public static final long NETWORK_SESSION_EXPIRED = 0xC000035CL;

    /** The NT status of the first {@link SMBApiException} in the cause chain, or 0 when there is none. */
    public static long statusOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SMBApiException api) return api.getStatusCode();
            if (t.getCause() == t) break;
        }
        return 0L;
    }

    /** Any of the seven codes that mean "this account and password will not get in as they are". */
    public static boolean isCredentialRejected(Throwable e) {
        long status = statusOf(e);
        return status == LOGON_FAILURE
                || status == ACCOUNT_RESTRICTION
                || status == PASSWORD_EXPIRED
                || status == ACCOUNT_DISABLED
                || status == ACCOUNT_EXPIRED
                || status == PASSWORD_MUST_CHANGE
                || status == ACCOUNT_LOCKED_OUT;
    }

    public static boolean isAccessDenied(Throwable e) {
        return statusOf(e) == ACCESS_DENIED;
    }

    /**
     * True when the cached session is no longer usable and must be evicted.
     *
     * <p>DELIBERATELY NARROW. A missing file or a denied folder says NOTHING about the session, and
     * treating those as session failures would log off and re-authenticate on every such fetch -
     * tearing down a session other concurrent fetches are using, and putting one logon per missing
     * file against the domain controller.
     *
     * <p>Status 0 counts because it means the failure was not an SMB protocol answer at all: a
     * socket died, the host went away, a read timed out. There is nothing to reuse. An
     * {@link IllegalArgumentException} is excluded because this connector uses it for its own
     * refusals - a blocked host, a missing account - which never touched the network.
     */
    public static boolean isSessionBroken(Throwable e) {
        if (e instanceof IllegalArgumentException) return false;
        long status = statusOf(e);
        return status == 0L
                || isCredentialRejected(e)
                || status == USER_SESSION_DELETED
                || status == NETWORK_NAME_DELETED
                || status == NETWORK_SESSION_EXPIRED;
    }

    /**
     * The message the owner reads.
     *
     * <p>Every branch names the FIX rather than the fault, because the reader is the person who can
     * apply it: a rejected password points at the settings field that holds it, a locked account
     * says to wait, a DFS link says to register the real server instead.
     */
    public static String describe(Throwable e, String host, String share) {
        long status = statusOf(e);

        if (status == LOGON_FAILURE) {
            return "The file server rejected your user name or password - "
                    + "update this source's Network share connection in Settings > Connections";
        }
        if (status == PASSWORD_EXPIRED || status == PASSWORD_MUST_CHANGE) {
            return "Your domain password has expired - "
                    + "update this source's Network share connection in Settings > Connections after changing it";
        }
        if (status == ACCOUNT_LOCKED_OUT) {
            return "Your domain account is locked out - "
                    + "wait for it to unlock, then check the password in its Network share connection";
        }
        if (status == ACCOUNT_DISABLED || status == ACCOUNT_EXPIRED) {
            return "Your domain account is disabled or expired";
        }
        if (status == ACCOUNT_RESTRICTION) {
            return "Your domain account is not allowed to sign in to this file server";
        }
        if (status == ACCESS_DENIED) {
            return "Your account has no read access to this folder on \\\\" + host + "\\" + share;
        }
        if (status == BAD_NETWORK_NAME) {
            return "Share '" + share + "' does not exist on " + host;
        }
        if (status == OBJECT_NAME_NOT_FOUND || status == OBJECT_PATH_NOT_FOUND) {
            return "Folder not found on \\\\" + host + "\\" + share;
        }
        if (status == PATH_NOT_COVERED) {
            // DFS referrals are off, so this is where a DFS link surfaces. Following it would
            // connect to whatever server the referral named, which the allow-list never saw.
            return "This path is a DFS link - "
                    + "register the folder on the file server it points to instead";
        }

        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return "File server '" + host + "' could not be found (DNS)";
            }
            if (t instanceof SocketTimeoutException) {
                return "File server " + host + " did not answer on port 445 in time";
            }
            if (t instanceof ConnectException) {
                return "Could not connect to file server " + host + " on port 445";
            }
            if (t.getCause() == t) break;
        }

        if (status != 0L) {
            return "File server " + host + " refused the request "
                    + String.format("(NT status 0x%08X)", status);
        }
        String detail = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (detail.length() > 160) detail = detail.substring(0, 160);
        return "Could not read from file server " + host
                + (detail.isEmpty() ? "" : ": " + detail);
    }
}
