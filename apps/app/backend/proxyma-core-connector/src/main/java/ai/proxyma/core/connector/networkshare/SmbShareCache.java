package ai.proxyma.core.connector.networkshare;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Authenticated SMB sessions, kept between calls, plus the memory of which passwords the server has
 * already rejected.
 *
 * <p>WHY A CACHE. A sync of ten thousand files must be ONE logon at the domain controller, not ten
 * thousand. Authenticating per file is not merely slow: it is a burst of identical logons that
 * looks exactly like a password-spray to anything watching the domain, and it is the fastest way to
 * trip an account lockout policy.
 *
 * <p>WHY REJECTIONS ARE REMEMBERED, which is the more important half. Syncs retry on a schedule. A
 * saved password that is wrong therefore produces a failed domain logon every sync interval,
 * forever, and enough of those lock the owner out of WINDOWS - not merely out of this product. So
 * the first rejection is recorded against the password's hash and no further connection is
 * attempted with it. Saving a different password changes that hash and the block clears by itself
 * on the next try, with nothing to reset by hand.
 *
 * <p>See docs/architecture.html#spec-05-7.
 */
@Component
public class SmbShareCache {

    private static final Logger log = LoggerFactory.getLogger(SmbShareCache.class);

    /** Long enough to span a sync, short enough that a share is not pinned open for hours. */
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private record Entry(Connection connection, Session session, DiskShare share, long lastUsed) {}

    private final Map<String, Entry> shares = new ConcurrentHashMap<>();

    /** host-scoped account key -> the password hash that was refused. */
    private final Map<String, String> rejected = new ConcurrentHashMap<>();

    private final SMBClient client = new SMBClient(SmbConfig.builder()
            // DFS OFF, and that is a security setting rather than a capability one: a referral can
            // name ANY server, so following one connects to a host the allow-list never checked.
            // A DFS path surfaces as PATH_NOT_COVERED, which SmbErrors turns into "register the
            // folder on the file server it points to instead".
            .withDfsEnabled(false)
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build());

    private static String cacheKey(UncPath target, SmbAccount account) {
        return target.host() + "|" + target.share().toLowerCase()
                + "|" + account.key(target.host()) + "|" + account.passwordHash();
    }

    /**
     * An authenticated {@link DiskShare} for {@code target}, reused when one is already open.
     *
     * @throws IllegalArgumentException when this password was already refused for this account on
     *         this host - carrying the message the owner sees, which says why nothing was tried.
     */
    public DiskShare openShare(UncPath target, SmbAccount account) {
        String accountKey = account.key(target.host());
        String hash = account.passwordHash();
        if (hash.equals(rejected.get(accountKey))) {
            throw new IllegalArgumentException(
                    "The file server rejected this password on an earlier attempt, so it was not "
                            + "tried again - update this source's Network share connection in Settings > Connections");
        }

        evictIdle();

        String key = cacheKey(target, account);
        Entry existing = shares.get(key);
        if (existing != null) {
            // A cached share whose connection has gone is worse than no cache: every call through
            // it fails in a way that looks like the server refusing, not like a stale handle.
            if (existing.share().isConnected()) {
                shares.put(key, new Entry(existing.connection(), existing.session(),
                        existing.share(), System.nanoTime()));
                return existing.share();
            }
            shares.remove(key, existing);
            closeQuietly(existing);
        }

        Connection connection = null;
        Session session = null;
        Share share = null;
        try {
            connection = client.connect(target.host());
            session = connection.authenticate(new AuthenticationContext(
                    account.user(), account.password().toCharArray(), account.domain()));
            // Authentication succeeded, so any remembered refusal for this account is stale -
            // the password may have been corrected, or the account unlocked.
            rejected.remove(accountKey);

            share = session.connectShare(target.share());
            if (!(share instanceof DiskShare disk)) {
                throw new IllegalArgumentException(
                        "\\\\" + target.host() + "\\" + target.share() + " is not a file share");
            }

            Entry fresh = new Entry(connection, session, disk, System.nanoTime());
            Entry won = shares.putIfAbsent(key, fresh);
            if (won != null && won.share().isConnected()) {
                // Another thread opened the same share while this one was authenticating. Theirs is
                // in the map and other callers already hold it, so this one is the duplicate.
                closeQuietly(fresh);
                return won.share();
            }
            if (won != null) shares.put(key, fresh);
            return disk;
        } catch (Exception e) {
            closeQuietly(new Entry(connection, session, share instanceof DiskShare d ? d : null, 0L));
            if (SmbErrors.isCredentialRejected(e)) {
                rejected.put(accountKey, hash);
                // The MASKED account, and never the hash beside it: together they are an
                // offline-crackable record of the password this line is about.
                log.warn("SMB credential refused for {} on {} - not retrying until it changes",
                        account, target.host());
            }
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e);
        }
    }

    /** Drops the cached session for this target, so the next call authenticates again. */
    /**
     * Signs in to {@code host} as {@code account} and closes again - a connection's Test button. No
     * share is opened and nothing is cached: the question is only whether the server takes these
     * credentials. A success clears a remembered refusal for this account on this host, which is
     * what a corrected password should do; a refusal is NOT remembered here, because a person
     * pressing Test is exactly the one who should be allowed to try again.
     *
     * @throws Exception the SMB library's own, for SmbErrors.describe to word
     */
    public void authenticate(String host, SmbAccount account) throws Exception {
        try (SMBClient once = new SMBClient(SmbConfig.builder()
                .withDfsEnabled(false)
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build());
             Connection connection = once.connect(host)) {
            Session session = connection.authenticate(new AuthenticationContext(
                    account.user(), account.password().toCharArray(), account.domain()));
            session.close();
            rejected.remove(account.key(host));
        }
    }

    public void evict(UncPath target, SmbAccount account) {
        Entry removed = shares.remove(cacheKey(target, account));
        if (removed != null) closeQuietly(removed);
    }

    private void evictIdle() {
        long cutoff = System.nanoTime() - IDLE_TIMEOUT.toNanos();
        for (Iterator<Map.Entry<String, Entry>> it = shares.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().lastUsed() < cutoff) {
                it.remove();
                closeQuietly(e.getValue());
            }
        }
    }

    // Share before session before connection: closing a session first leaves the share's handles
    // pointing at a session the server has already torn down.
    private void closeQuietly(Entry entry) {
        if (entry == null) return;
        try { if (entry.share() != null) entry.share().close(); } catch (Exception ignored) { }
        try { if (entry.session() != null) entry.session().close(); } catch (Exception ignored) { }
        try { if (entry.connection() != null) entry.connection().close(); } catch (Exception ignored) { }
    }

    @PreDestroy
    public void shutdown() {
        for (Entry entry : shares.values()) closeQuietly(entry);
        shares.clear();
        rejected.clear();
        try { client.close(); } catch (Exception ignored) { }
    }
}
