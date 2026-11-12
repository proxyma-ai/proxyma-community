package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A DAILY BUDGET PER INSTALL FOR A BUILT-IN OAUTH CLIENT (2026-11-08). The built-in YouTube client
 * (BuiltInOAuthClients) is ONE Google Cloud project, whose YouTube Data API quota - 10,000 units a day by
 * default - every Desktop install shares. A desktop client's secret can be read out of the app, so the
 * spending cannot be metered on a server: each install meters itself, and stops before its share.
 *
 * <ul>
 *   <li><b>Counted as YouTube counts</b>: each request sent with a connection that signs in through the
 *       built-in client costs what its plugin says (ConnectionPlugin.requestUnits - a list 1, a reply 50),
 *       a refused or retried one included, per day in Pacific time, when YouTube's own quota resets.</li>
 *   <li><b>Stops before the budget</b> - {@code proxyma.oauth.builtin.<service>.daily-units}, 0 for none -
 *       with the answer YouTube itself gives a spent quota ({@link #SPENT_BODY}, a 403 {@code quotaExceeded}),
 *       so everything that already holds a reply for the reset and retries it after midnight Pacific
 *       (ApprovalHold, the comment queue's next pass) does so unchanged, while the reason {@link #REASON}
 *       says the limit is the shared sign-in's.</li>
 *   <li><b>Never a person's own client</b>: their project, their quota - OAuth2ConnectionService asks only
 *       for a connection with no client typed on it.</li>
 *   <li><b>Kept across restarts</b> in one GLOBAL_SETTINGS row per service ({@link #KEY_PREFIX}), written
 *       behind SettingsService on purpose: it is a counter, not a setting - never shown, exported or
 *       audited.</li>
 * </ul>
 */
@Component
public class BuiltInQuota {

    private static final Logger log = LoggerFactory.getLogger(BuiltInQuota.class);

    /** Where YouTube's daily quota resets: midnight Pacific time. */
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    /** The GLOBAL_SETTINGS key of a service's counter: {@code builtin-oauth-usage.<service>} = "date units". */
    static final String KEY_PREFIX = "builtin-oauth-usage.";
    /** The reason beside {@code quotaExceeded} in the answer given in place of a request. */
    public static final String REASON = "proxymaSharedQuota";
    static final String MESSAGE = "The daily YouTube limit for Proxyma's shared sign-in is reached on this computer. "
            + "It resets at midnight Pacific time.";
    /** The body of that answer: Google's error shape, so every reader of a spent quota reads it as one. */
    public static final String SPENT_BODY = "{\"error\":{\"code\":403,\"message\":\"" + MESSAGE + "\",\"errors\":["
            + "{\"reason\":\"quotaExceeded\",\"domain\":\"youtube.quota\",\"message\":\"" + MESSAGE + "\"},"
            + "{\"reason\":\"" + REASON + "\",\"domain\":\"proxyma\"}]}}";

    private record Day(LocalDate date, long units) {}

    private final Environment env;
    private final ObjectProvider<GlobalSettingRepository> store;
    private final Clock clock;
    private final Map<String, Day> days = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BuiltInQuota(Environment env, ObjectProvider<GlobalSettingRepository> store) {
        this(env, store, Clock.systemUTC());
    }

    BuiltInQuota(Environment env, ObjectProvider<GlobalSettingRepository> store, Clock clock) {
        this.env = env;
        this.store = store;
        this.clock = clock;
    }

    /** A service's budget, in its units per Pacific day; 0 for none. */
    public long budget(ConnectionProtocol protocol) {
        if (env == null || protocol == null) return 0;
        Long v = env.getProperty(BuiltInOAuthClients.PREFIX + service(protocol) + ".daily-units", Long.class, 0L);
        return v == null || v < 0 ? 0 : v;
    }

    /**
     * Spends {@code units} of today's budget for one request, or refuses it: false when it would go past
     * the budget, in which case nothing is spent. A free request (0 units) always goes.
     */
    public synchronized boolean spend(ConnectionProtocol protocol, int units) {
        return spend(protocol, units, null);
    }

    /**
     * The same, the request named by {@code call} ("commentThreads by the source sync") for the day's tally: WHERE THE
     * UNITS GO (2026-11-11, the maintainer: one channel spent its share early every day). The tally is logged at every
     * {@link #TALLY_EVERY} units, when the budget is reached and for the day before when a new day starts - in memory
     * only, so a restart starts it again.
     */
    public synchronized boolean spend(ConnectionProtocol protocol, int units, String call) {
        if (units <= 0) return true;
        long budget = budget(protocol);
        if (budget <= 0) return true;
        String service = service(protocol);
        Day day = today(service);
        if (day.units() + units > budget) {
            if (day.units() < budget) {
                log.info("[OAuth] {}'s built-in sign-in has spent {} of its {} units today; the rest wait for "
                        + "midnight Pacific time. Spent on: {}", service, day.units(), budget, tallyLine(service));
            }
            // Remembered as spent, so the log line is written once a day.
            remember(service, new Day(day.date(), Math.max(day.units(), budget)));
            return false;
        }
        tally(service, day.date(), call == null ? "other" : call, units);
        remember(service, new Day(day.date(), day.units() + units));
        if ((day.units() + units) / TALLY_EVERY > day.units() / TALLY_EVERY) {
            log.info("[OAuth] {}'s built-in sign-in has spent {} of its {} units today, on: {}", service,
                    day.units() + units, budget, tallyLine(service));
        }
        return true;
    }

    /** How often the day's tally is logged, in units. */
    static final long TALLY_EVERY = 250;

    private record Tally(LocalDate date, Map<String, Long> units) {}

    private final Map<String, Tally> tallies = new HashMap<>();

    private void tally(String service, LocalDate date, String call, int units) {
        Tally t = tallies.get(service);
        if (t != null && !t.date().equals(date)) {
            log.info("[OAuth] {}'s built-in sign-in spent on {}: {}", service, t.date(), tallyLine(service));
            t = null;
        }
        if (t == null) {
            t = new Tally(date, new HashMap<>());
            tallies.put(service, t);
        }
        t.units().merge(call, (long) units, Long::sum);
    }

    // The day's tally as one line, the costliest call first: "commentThreads by the source sync 812, ...".
    String tallyLine(String service) {
        Tally t = tallies.get(service);
        if (t == null || t.units().isEmpty()) return "nothing counted since Proxyma started";
        return t.units().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * What a request that only asks whose account a connection is (ConnectionPlugin.identityRequest) may spend in a day
     * once the share is spent: a few channels named, never a sync.
     */
    static final long IDENTITY_RESERVE = 20;
    /** The reserve's counter, kept as a service's own is under {@link #KEY_PREFIX}. */
    static final String RESERVE_PREFIX = "reserve.";

    /**
     * Spends {@code units} of today's identity reserve for one such request, or refuses it. Kept across restarts beside
     * the day's count (a restart granted a new reserve, 2026-11-11 review).
     */
    public synchronized boolean spendReserve(ConnectionProtocol protocol, int units) {
        if (units <= 0) return true;
        String service = service(protocol);
        String key = RESERVE_PREFIX + service;
        Day day = today(key);
        if (day.units() + units > IDENTITY_RESERVE) return false;
        remember(key, new Day(day.date(), day.units() + units));
        log.info("[OAuth] {}'s built-in sign-in is past its daily share; an account's name was read from the reserve",
                service);
        return true;
    }

    /** Today's units spent so far, for a service. */
    public synchronized long spentToday(ConnectionProtocol protocol) {
        return today(service(protocol)).units();
    }

    /** When the budget starts again: the next midnight, Pacific time. */
    public ZonedDateTime resetsAt() {
        return ZonedDateTime.now(clock.withZone(QUOTA_ZONE)).toLocalDate().plusDays(1).atStartOfDay(QUOTA_ZONE);
    }

    // The counter for today - from memory, else the stored row, else zero; a row from another day is zero.
    private Day today(String service) {
        LocalDate date = LocalDate.now(clock.withZone(QUOTA_ZONE));
        Day known = days.get(service);
        if (known == null) known = load(service);
        if (known == null || !known.date().equals(date)) known = new Day(date, 0);
        days.put(service, known);
        return known;
    }

    private void remember(String service, Day day) {
        days.put(service, day);
        GlobalSettingRepository repo = store == null ? null : store.getIfAvailable();
        if (repo == null) return;
        try {
            repo.save(new GlobalSetting(KEY_PREFIX + service, day.date() + " " + day.units()));
        } catch (RuntimeException e) {
            // The count in memory still holds the line for this run.
            log.debug("[OAuth] the built-in sign-in's daily count could not be stored: {}", e.getMessage());
        }
    }

    private Day load(String service) {
        GlobalSettingRepository repo = store == null ? null : store.getIfAvailable();
        if (repo == null) return null;
        try {
            return repo.findById(KEY_PREFIX + service).map(GlobalSetting::getSettingValue).map(BuiltInQuota::parse)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Day parse(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) return null;
        try {
            return new Day(LocalDate.parse(parts[0]), Math.max(0, Long.parseLong(parts[1])));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String service(ConnectionProtocol protocol) {
        return protocol.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
