package ai.proxyma.core.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Regenerates the chat home's astronomy fact every 30 minutes - but only when somebody has read it
// since the last run.
//
// The demand flag is the point of this class. Without it an idle deployment (a desktop install
// nobody opened today, a sandbox between visitors) would spend 48 LLM calls a day on a sentence
// nothing displayed. With it, the cost tracks use: the first hero load in a window arms the flag,
// this job spends one call, and a window with no visitors spends nothing.
//
// It is also why the generation lives here rather than on the read path. The hero must paint
// immediately - GET /api/v1/chat/space-fact only ever reads memory (see SpaceFactService.current)
// - so the model call has to happen somewhere nobody is waiting, and a scheduler is that place.
//
// fixedDelay, not fixedRate: a slow or hung provider call must not queue a second refresh behind
// itself.
//
// THE TICK IS ONE MINUTE, THE CADENCE IS STILL HALF AN HOUR. The two used to be the same number,
// which was fine while a seed fact filled the gap before the first generation. There is no seed
// any more (see SpaceFactService's header), so a deployment that has never generated shows an
// empty hero until this job first succeeds - and half an hour of that reads as a missing feature.
// So the job wakes every minute and decides for itself: EMPTY_RETRY_MS apart while there is no
// fact at all, NORMAL_INTERVAL_MS apart once there is one.
//
// EMPTY_RETRY_ATTEMPTS caps the fast path. A deployment with no model configured, or one whose
// every candidate source fails the reachability check, would otherwise call out every two minutes
// forever for a decoration - so after a few tries it drops to the ordinary half-hourly cadence and
// stays there. The demand flag still gates all of it: a deployment nobody is looking at spends
// nothing either way.
@Component
class SpaceFactRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(SpaceFactRefreshJob.class);

    private static final long NORMAL_INTERVAL_MS = 1_800_000L;
    private static final long EMPTY_RETRY_MS = 120_000L;
    private static final int EMPTY_RETRY_ATTEMPTS = 5;

    private final SpaceFactService spaceFactService;

    private long lastAttemptAt;
    private int emptyAttempts;

    SpaceFactRefreshJob(SpaceFactService spaceFactService) {
        this.spaceFactService = spaceFactService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void refresh() {
        try {
            if (!due()) return;
            // Consumed only once this tick has decided it would actually refresh - checking it
            // first would clear the flag on every minute that then declines to do anything, so a
            // visitor's page load could be swallowed by a tick and never reach the tick that
            // could have acted on it.
            if (!spaceFactService.consumeDemand()) {
                log.debug("[SpaceFact] nobody read the fact this window, skipping refresh");
                return;
            }
            lastAttemptAt = System.currentTimeMillis();
            if (!spaceFactService.hasFact()) emptyAttempts++;
            spaceFactService.refresh();
        } catch (Exception e) {
            // refresh() already swallows its own failures; this only catches the unexpected, and a
            // scheduled method that throws is silently unscheduled by some executors.
            log.warn("[SpaceFact] refresh job failed: {}", e.getMessage());
        }
    }

    private boolean due() {
        long interval = !spaceFactService.hasFact() && emptyAttempts < EMPTY_RETRY_ATTEMPTS
                ? EMPTY_RETRY_MS
                : NORMAL_INTERVAL_MS;
        return System.currentTimeMillis() - lastAttemptAt >= interval;
    }
}
