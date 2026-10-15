package ai.proxyma.core.common.spend;

/**
 * WHO a model call is spent on behalf of.
 *
 * <p>Three kinds, and the distinction is the whole point of recording one at all:
 *
 * <ul>
 *   <li>{@code USER} - a signed-in account, carrying its id, so "what has this person cost" has an
 *       answer.</li>
 *   <li>{@code ANONYMOUS} - a visitor with no account. One SHARED id for all of them rather than a
 *       per-session one: the question anybody asks is "what are guests costing us", and keeping a
 *       pseudonymous per-browser identifier in a table that outlives the browser answers a question
 *       nobody asked while creating a privacy obligation nobody wanted.</li>
 *   <li>{@code SYSTEM} - work with no person behind it at all. Rare by design: ingest is attributed
 *       to the connector's OWNER, because someone registering a hundred sources is exactly the cost
 *       worth being able to trace to a person. This is the fallback for the genuinely ownerless -
 *       a scheduled sweep, the home-screen fact, a startup probe.</li>
 * </ul>
 *
 * <p>An id is always present, never null. A null in a ledger reads as "we failed to record it",
 * which is a different fact from "there was nobody", and the two must not share a representation.
 */
public record SpendSubject(Kind kind, String id) {

    public enum Kind { USER, ANONYMOUS, SYSTEM }

    /** The id every anonymous visitor's spend is filed under. */
    public static final String ANONYMOUS_ID = "anonymous";

    /** The id used when no person is behind the call. */
    public static final String SYSTEM_ID = "system";

    public static final SpendSubject ANONYMOUS = new SpendSubject(Kind.ANONYMOUS, ANONYMOUS_ID);
    public static final SpendSubject SYSTEM = new SpendSubject(Kind.SYSTEM, SYSTEM_ID);

    /**
     * The one person on Desktop and Home Server. Same value as the other single-user sentinels
     * ({@code ToolUserContext.DESKTOP_USER_ID}, {@code MicrosoftAccountService.SINGLE_USER_ID}).
     */
    public static final String LOCAL_ID = "_local";

    /**
     * What every call is filed under on a single-user edition. There, a chat turn arrives with no
     * account (ANONYMOUS) and ingest with no owner (SYSTEM), and splitting one person's spend
     * across "anonymous" and "system" answers a question nobody on that edition can ask.
     */
    public static final SpendSubject LOCAL = new SpendSubject(Kind.USER, LOCAL_ID);

    /** This subject as the ledgers file it: itself on Enterprise Server, {@link #LOCAL} otherwise. */
    public SpendSubject forEdition(boolean multiUser) {
        return multiUser ? this : LOCAL;
    }

    /** A signed-in account, or ANONYMOUS when the id is absent - a blank user id is not a user. */
    public static SpendSubject user(String userId) {
        return userId == null || userId.isBlank() ? ANONYMOUS : new SpendSubject(Kind.USER, userId);
    }

    /**
     * The owner of some resource - a connector, a workflow - or SYSTEM when it has none.
     *
     * <p>Distinct from {@link #user} in intent rather than in shape: an absent owner means nobody
     * is responsible for this work, not that an anonymous visitor asked for it.
     */
    public static SpendSubject owner(String userId) {
        return userId == null || userId.isBlank() ? SYSTEM : new SpendSubject(Kind.USER, userId);
    }
}
