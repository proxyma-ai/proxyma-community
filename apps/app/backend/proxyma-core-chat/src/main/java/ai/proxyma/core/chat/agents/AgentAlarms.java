package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.model.AgentAlarm;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ALARMS AN AGENT SETS TO WAKE ITSELF - "remind me in an hour to check the build", the way a person
 * sets one. At its time the note reaches the conversation's inbox (kind ALARM) and a turn starts for
 * it if none is running, under the conversation's own permission mode. One-shot, kept across a
 * restart (an alarm due while the application was down goes off at the next tick), listed in the
 * conversation with a Cancel.
 *
 * <p>Bounded, so an agent cannot fill the scheduler: at least a minute ahead, at most 30 days, and
 * no more than 20 pending in one conversation.
 */
@Service
public class AgentAlarms {

    private static final Logger log = LoggerFactory.getLogger(AgentAlarms.class);

    static final Duration MIN_AHEAD = Duration.ofMinutes(1);
    static final Duration MAX_AHEAD = Duration.ofDays(30);
    static final int MAX_PENDING = 20;

    private final AgentAlarmRepository alarms;
    private final AgentInbox inbox;

    public AgentAlarms(AgentAlarmRepository alarms, AgentInbox inbox) {
        this.alarms = alarms;
        this.inbox = inbox;
    }

    /** Sets one: at an ISO-8601 instant ({@code at}) or in a number of minutes. */
    public Map<String, Object> set(String conversationId, String at, Integer inMinutes, String note) {
        if (conversationId == null) return AgentTree.failure("Alarms can only be set inside a conversation.");
        if (note == null || note.isBlank()) return AgentTree.failure("note must say what to do when it goes off.");
        Instant now = Instant.now();
        Instant due;
        if (inMinutes != null) {
            due = now.plus(Duration.ofMinutes(inMinutes));
        } else if (at != null && !at.isBlank()) {
            try {
                due = OffsetDateTime.parse(at.strip()).toInstant();
            } catch (DateTimeParseException e) {
                try {
                    due = Instant.parse(at.strip());
                } catch (DateTimeParseException e2) {
                    return AgentTree.failure("at is an ISO-8601 time with its offset, e.g. 2026-11-02T09:00:00+07:00.");
                }
            }
        } else {
            return AgentTree.failure("Give either at (a time) or inMinutes.");
        }
        if (due.isBefore(now.plus(MIN_AHEAD).minusSeconds(1))) return AgentTree.failure("An alarm is at least a minute ahead.");
        if (due.isAfter(now.plus(MAX_AHEAD))) return AgentTree.failure("An alarm is at most 30 days ahead.");
        if (alarms.countByConversationIdAndFiredAtIsNullAndCanceledAtIsNull(conversationId) >= MAX_PENDING) {
            return AgentTree.failure("This conversation already has " + MAX_PENDING + " alarms pending. Cancel one first.");
        }
        AgentAlarm a = new AgentAlarm();
        a.setConversationId(conversationId);
        a.setNote(AgentInbox.clip(note.strip(), AgentAlarm.NOTE_MAX));
        a.setDueAt(due);
        AgentAlarm saved = alarms.save(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("alarmId", saved.getId().toString());
        out.put("dueAt", due.toString());
        out.put("note", "It goes off as a message beginning \"[Alarm\". End your turn now if there is nothing else to do.");
        return out;
    }

    /** The conversation's pending alarms, soonest first. */
    public List<AgentAlarm> pending(String conversationId) {
        return alarms.findByConversationIdAndFiredAtIsNullAndCanceledAtIsNullOrderByDueAtAsc(conversationId);
    }

    public Map<String, Object> list(String conversationId) {
        return Map.of("success", true, "alarms", pending(conversationId).stream().map(AgentAlarms::view).toList());
    }

    /** Cancels one of this conversation's alarms. */
    public Map<String, Object> cancel(String conversationId, String alarmId) {
        Optional<AgentAlarm> a = byId(alarmId).filter(x -> x.getConversationId().equals(conversationId) && x.isPending());
        if (a.isEmpty()) return AgentTree.failure("No pending alarm with id " + alarmId + " here.");
        a.get().setCanceledAt(Instant.now());
        alarms.save(a.get());
        return Map.of("success", true);
    }

    /** Goes off: every alarm due by now, at most a tick late. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void tick() {
        List<AgentAlarm> due;
        try {
            due = alarms.findByFiredAtIsNullAndCanceledAtIsNullAndDueAtLessThanEqualOrderByDueAtAsc(Instant.now());
        } catch (RuntimeException e) {
            log.warn("[Agents] could not read the alarms due: {}", e.getMessage());
            return;
        }
        for (AgentAlarm a : due) {
            try {
                a.setFiredAt(Instant.now());
                alarms.save(a);
                inbox.post(a.getConversationId(), AgentInbox.Kind.ALARM, null, null,
                        "The alarm you set for " + a.getDueAt() + " went off: " + a.getNote());
            } catch (RuntimeException e) {
                log.warn("[Agents] alarm {} could not go off: {}", a.getId(), e.getMessage());
            }
        }
    }

    static Map<String, Object> view(AgentAlarm a) {
        return Map.of("alarmId", a.getId().toString(), "dueAt", a.getDueAt().toString(), "note", a.getNote());
    }

    private Optional<AgentAlarm> byId(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return alarms.findById(UUID.fromString(raw.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
