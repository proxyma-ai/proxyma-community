package ai.proxyma.core.chat.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The free allowance an anonymous visitor gets on a server deployment: N chat turns per rolling
 * window, counted per guest-session cookie.
 *
 * <p>WHY MESSAGES AND NOT DOLLARS. Every other ceiling in this product is a $ spend ceiling (see
 * RollingWindowChatQuotaGate), and those are derived from configured per-model rates - so on a
 * deployment where nothing is priced they are all zero and enforce nothing. A turn count is the
 * one meter that works before anybody has typed a rate in, and it is also the only one a guest
 * can be told about honestly on the banner: "5 of 20 left today" needs no explanation,
 * "$0.40 of $2.00" needs a paragraph.
 *
 * <p>WHY IN MEMORY. A guest is a browser cookie, not a row - there is no account to attach a
 * ledger to, and adding one would mean a nullable column on a live table for a guest allowance.
 * The cost of holding it in memory is that a restart or a deploy hands every guest a fresh
 * allowance. That is a real limitation and it is the accepted one: the allowance exists to keep an
 * open server from being farmed for free tokens, not to bill anybody.
 *
 * <p>OFF BY DEFAULT, and that default is the whole point of this paragraph. Letting anonymous
 * visitors chat is right for a public showcase and wrong for most private deployments, and the two
 * are the same binary - so the one that WIDENS access on an upgrade cannot be the one you get by
 * saying nothing. 0.1.4 shipped it defaulting to on, which meant a customer upgrading from 0.1.3
 * had their server start answering strangers without having asked for it. Set
 * {@code PROXYMA_GUEST_CHAT_ENABLED=true} to turn it on.
 *
 * <p>OFF MEANS REFUSED, NOT UNMETERED, and that distinction is this class's whole security value.
 * {@code /api/v1/chat/**} is permitAll in SecurityConfig, so nothing above this decides whether an
 * anonymous caller may spend a turn - which means "the meter is inactive" and "the door is shut"
 * cannot be the same answer. Until 0.2.2 they were: {@link #checkAllowed} returned empty (ALLOWED)
 * whenever the meter was off, so turning the switch off left anonymous chat open AND uncounted -
 * the exact inverse of what .env.example and INSTALL.md promised. {@link #isAnonymousChatAllowed}
 * is now the door and {@link #checkAllowed} is only the meter behind it; ChatController asks both.
 *
 * <p>SERVER MODE ONLY, by way of {@code proxyma.multi-user}. From 2026-09-02 the public demo - the
 * DESKTOP edition on a public address - activated it too; that demo was removed on 2026-10-09.
 * See {@link #isActive}.
 *
 * <p><b>THE SENTENCE THAT USED TO BE HERE WAS WRONG, and it is worth knowing why.</b> It said
 * desktop has "no cookies and no guests, so ... ChatController's guest branch never runs". Half of
 * that was never true: ChatController mints a {@code PROXYMA-GUEST-ID} cookie UNCONDITIONALLY and
 * passes it to {@code chatService.chat}, and {@code SmartChatMemory.ensureConversation} records it
 * on any conversation whose userId is null - which on desktop is every conversation. So desktop
 * conversations have always been scoped to the browser that created them, and the public demo
 * (removed 2026-10-09) inherited that isolation rather than needing it built. What genuinely did
 * not run there is THIS class's meter, which the demo switched on there. (Since 0.16.0 neither
 * half holds on Desktop and Home Server: the binding is skipped there, and the cookie is minted
 * only on a multi-user server or where this meter is active.)
 *
 * <p>Growth of the map is bounded two ways: expired entries are swept hourly, and
 * {@link #MAX_TRACKED_SESSIONS} caps it outright. Minting cookies faster than that is already
 * limited upstream by ApiRateLimitFilter's per-IP chat bucket (proxyma-core-network).
 */
@Service
public class GuestChatQuotaService {

    private static final Logger log = LoggerFactory.getLogger(GuestChatQuotaService.class);

    // Evicting a live guest resets their allowance rather than locking them out - deliberately the
    // generous failure. This is a memory bound, not the abuse control; the per-IP rate limiter is.
    private static final int MAX_TRACKED_SESSIONS = 50_000;

    /**
     * What the guest banner renders. remaining is never negative and never above limit.
     *
     * <p>{@code allowed} is whether an anonymous visitor may chat here AT ALL, and it is a separate
     * field from {@code enabled} on purpose: the banner has three states to tell apart, not two.
     * Reading "not metered" as "unlimited" is what had it offering a free preview on a deployment
     * that refuses anonymous turns outright.
     */
    public record GuestAllowance(boolean allowed, boolean enabled, int limit, int used,
                                 int remaining, int windowHours) {}

    private final boolean multiUser;
    private final boolean enabled;
    private final int maxMessages;
    private final Duration window;

    // guestSessionId -> the epoch-milli of each turn still inside the window. Mutated only inside
    // ConcurrentHashMap.compute, which holds the bin lock for the duration, so two writers never
    // meet; the deque is a concurrent one because readers (countWithin) do not take that lock.
    private final ConcurrentHashMap<String, Deque<Long>> turns = new ConcurrentHashMap<>();

    public GuestChatQuotaService(@Value("${proxyma.multi-user:false}") boolean multiUser,
                                 @Value("${proxyma.guest.chat.enabled:false}") boolean enabled,
                                 @Value("${proxyma.guest.chat.max-messages:20}") int maxMessages,
                                 @Value("${proxyma.guest.chat.window-hours:24}") int windowHours) {
        this.multiUser = multiUser;
        this.enabled = enabled;
        this.maxMessages = Math.max(0, maxMessages);
        this.window = Duration.ofHours(Math.max(1, windowHours));
    }

    /**
     * Whether guest turns are metered at all on this deployment.
     *
     * <p>Off on desktop: one local user, nobody to meter.
     *
     * <p>The DOOR below ({@link #isAnonymousChatAllowed}) is unaffected and still opens on desktop
     * whatever these switches say.
     */
    public boolean isActive() {
        return multiUser && enabled && maxMessages > 0;
    }

    /**
     * Whether an anonymous caller may open a chat turn here at all - the door, not the meter.
     *
     * <p>Desktop is always true: there is one local user, no sign-in to require and no cookie to
     * meter, so every caller is anonymous and refusing them would refuse the product. On a server
     * it is exactly the operator's switch, which is what makes {@code PROXYMA_GUEST_CHAT_ENABLED}
     * mean what its documentation says.
     *
     * <p>A limit of ZERO refuses rather than waves through, for the same reason: the operator who
     * writes {@code PROXYMA_GUEST_CHAT_MAX_MESSAGES=0} is asking for no free messages, and the
     * generous reading of that is the one nobody wants.
     */
    public boolean isAnonymousChatAllowed() {
        return !multiUser || (enabled && maxMessages > 0);
    }

    /**
     * Empty when this guest may send another turn. Never consumes anything - {@link #admit} does
     * that - so a rejected turn costs the guest nothing and a caller may ask as often as it likes.
     */
    public Optional<QuotaRejection> checkAllowed(String guestSessionId) {
        if (!isActive() || guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();

        Instant now = Instant.now();
        Deque<Long> recent = recentFor(guestSessionId);
        int used = countWithin(recent, now);
        if (used < maxMessages) return Optional.empty();
        return Optional.of(rejection(oldestWithin(recent, now), now));
    }

    /**
     * The check and the count in ONE step: the turn is counted if the guest has one left, and
     * refused - counting nothing - if not. What a caller that is about to start a turn uses. It was
     * a check and a separate record, and turns submitted together by one guest at their last free
     * message all read the same count, all passed and all counted.
     */
    public Optional<QuotaRejection> admit(String guestSessionId) {
        if (!isActive() || guestSessionId == null || guestSessionId.isBlank()) return Optional.empty();

        Instant now = Instant.now();
        AtomicReference<QuotaRejection> refused = new AtomicReference<>();
        turns.compute(guestSessionId, (key, existing) -> {
            Deque<Long> deque = existing != null ? existing : new ConcurrentLinkedDeque<>();
            pruneInPlace(deque, now);
            if (deque.size() >= maxMessages) {
                refused.set(rejection(oldestWithin(deque, now), now));
            } else {
                deque.addLast(now.toEpochMilli());
            }
            return deque;
        });
        if (turns.size() > MAX_TRACKED_SESSIONS) evictDown(now);
        return Optional.ofNullable(refused.get());
    }

    private QuotaRejection rejection(Optional<Instant> oldest, Instant now) {
        // The earliest moment the window could drop below the limit: when the oldest turn still
        // inside it falls out. Same approximation, and the same reason for it, as
        // RollingWindowChatQuotaGate's own resetsAt - a guest who keeps trying pushes it no
        // further, since a refused turn is never recorded.
        Instant resetsAt = oldest.map(t -> t.plus(window)).orElse(now.plus(window));
        return new QuotaRejection("guest", resetsAt,
                "You have used all " + maxMessages + " free messages for now. "
                        + "Sign in to keep going - an account has its own, much larger allowance.");
    }

    /** What the banner shows - see {@link GuestAllowance} for why `allowed` and `enabled` are two
     *  fields and not one. */
    public GuestAllowance allowance(String guestSessionId) {
        if (!isActive())
            return new GuestAllowance(isAnonymousChatAllowed(), false, 0, 0, 0, (int) window.toHours());
        int used = Math.min(maxMessages, countWithin(recentFor(guestSessionId), Instant.now()));
        return new GuestAllowance(true, true, maxMessages, used, maxMessages - used,
                (int) window.toHours());
    }

    // - internals -

    // A guest with no cookie yet has spent nothing, and this is the COMMON case for the allowance
    // read: the banner asks before the visitor has sent anything, and that read deliberately does
    // not mint a cookie (see ChatController.guestAllowance). ConcurrentHashMap.get(null) throws
    // NullPointerException rather than returning null, so the absence has to be handled before the
    // lookup, not after - which is what a boot against a real request found the hard way.
    private Deque<Long> recentFor(String guestSessionId) {
        return guestSessionId == null || guestSessionId.isBlank() ? null : turns.get(guestSessionId);
    }

    // Read-only: callers that only want a count must not mutate a deque outside compute(), so this
    // walks past the expired head rather than removing it. The removal happens on the next admit()
    // for this guest, or in the sweep below.
    private int countWithin(Deque<Long> deque, Instant now) {
        if (deque == null) return 0;
        long cutoff = now.minus(window).toEpochMilli();
        int count = 0;
        // A concurrent deque, read while admit() may be changing it on another thread:
        // its iteration never fails and never yields a hole. (An ArrayDeque copied mid-change could
        // hold a null, and List.copyOf threw on it - a guest's POST /chat answered 500.) A miscount
        // here is a decoration being one turn stale, never a wrong decision - admit() decides
        // inside compute().
        for (long at : deque) {
            if (at >= cutoff) count++;
        }
        return count;
    }

    private Optional<Instant> oldestWithin(Deque<Long> deque, Instant now) {
        if (deque == null) return Optional.empty();
        long cutoff = now.minus(window).toEpochMilli();
        return deque.stream().filter(at -> at >= cutoff).min(Long::compare).map(Instant::ofEpochMilli);
    }

    private void pruneInPlace(Deque<Long> deque, Instant now) {
        long cutoff = now.minus(window).toEpochMilli();
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) deque.pollFirst();
    }

    // Oldest-last-seen first, down to 90% of the cap so this is not re-triggered by the very next
    // request. Only reachable on a deployment being hammered for new cookies faster than the hourly
    // sweep clears them.
    private void evictDown(Instant now) {
        int target = (MAX_TRACKED_SESSIONS * 9) / 10;
        List<Map.Entry<String, Deque<Long>>> byAge = turns.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> {
                    Long last = e.getValue().peekLast();
                    return last != null ? last : 0L;
                }))
                .toList();
        int removed = 0;
        for (Map.Entry<String, Deque<Long>> entry : byAge) {
            if (turns.size() <= target) break;
            turns.remove(entry.getKey());
            removed++;
        }
        log.warn("[GuestQuota] evicted {} guest sessions to stay under {} (swept at {})",
                removed, MAX_TRACKED_SESSIONS, now);
    }

    // Hourly rather than on every request: the window is measured in hours, so an entry lingering
    // for one extra hour costs a few dozen bytes and nothing else.
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void sweepExpired() {
        if (!isActive()) return;
        Instant now = Instant.now();
        turns.entrySet().removeIf(entry -> countWithin(entry.getValue(), now) == 0);
    }
}
