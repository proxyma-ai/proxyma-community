package ai.proxyma.core.connector.networkshare;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The domain account a share is read as - the SOURCE OWNER'S own, never a service account.
 *
 * <p>That is the whole design of this connector: the share's ACLs decide what is indexed, exactly
 * as they decide what the owner can open in Explorer. A folder the owner cannot read is skipped,
 * not read with someone else's rights.
 *
 * <p>See docs/architecture.html#spec-05-5.
 */
public record SmbAccount(String user, String domain, String password) {

    /**
     * Splits what the user typed into the shape {@code AuthenticationContext} wants.
     *
     * <table>
     *   <tr><th>Typed</th><th>user</th><th>domain</th></tr>
     *   <tr><td>{@code DOMAIN\name}</td><td>{@code name}</td><td>{@code DOMAIN}</td></tr>
     *   <tr><td>{@code name@domain.example}</td><td>the whole string</td><td>empty</td></tr>
     *   <tr><td>{@code name}</td><td>{@code name}</td><td>empty</td></tr>
     * </table>
     *
     * <p>A UPN KEEPS ITS DOMAIN PART AND SENDS AN EMPTY DOMAIN, which looks wrong and is what
     * Windows does: {@code name@domain.example} is submitted whole as the user name. Splitting it
     * into user and domain produces a NetBIOS-shaped pair the domain controller does not recognise,
     * and the failure comes back as a plain logon failure that reads like a wrong password.
     *
     * @return null when there is nothing to authenticate with - a blank user or an empty password.
     *         An empty password is refused rather than attempted because an anonymous bind is a
     *         different thing from "the owner has not filled this in yet", and only the second is
     *         ever true here.
     */
    public static SmbAccount parse(String rawUser, String password) {
        if (rawUser == null || rawUser.isBlank()) return null;
        if (password == null || password.isEmpty()) return null;

        String typed = rawUser.trim();
        int backslash = typed.indexOf('\\');
        if (backslash > 0) {
            String domain = typed.substring(0, backslash).trim();
            String user = typed.substring(backslash + 1).trim();
            if (user.isEmpty()) return null;
            return new SmbAccount(user, domain, password);
        }
        return new SmbAccount(typed, "", password);
    }

    /** Identifies the account against one host, for the share cache and the rejection memory. */
    public String key(String host) {
        return (domain.isEmpty() ? user : domain + "\\" + user).toLowerCase() + "@" + host;
    }

    /**
     * Hex SHA-256 of {@code domain \ user NUL password}.
     *
     * <p>Two jobs, and both need a value that changes when the password does: it keys the share
     * cache, and it is what {@code SmbShareCache} remembers a rejection against so that saving a
     * new password clears the block by itself.
     *
     * <p>NEVER LOG THIS BESIDE THE USER NAME. On its own it is a hash of an unknown string; next to
     * the account it belongs to it is an offline-crackable record of that account's password.
     */
    public String passwordHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(domain.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\\');
            md.update(user.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Masked. The record's generated {@code toString} would print the password. */
    @Override
    public String toString() {
        String masked = user.length() <= 2 ? "***" : user.charAt(0) + "***" + user.charAt(user.length() - 1);
        return domain.isEmpty() ? masked : domain + "\\" + masked;
    }
}
