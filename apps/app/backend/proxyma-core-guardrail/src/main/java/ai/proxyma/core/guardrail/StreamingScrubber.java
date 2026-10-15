package ai.proxyma.core.guardrail;

import ai.proxyma.core.guardrail.rule.GuardrailRule;
import ai.proxyma.core.guardrail.rule.RuleScanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The output guardrail as it runs DURING a stream, one flush at a time.
 *
 * <p>WHY THIS EXISTS RATHER THAN A CHECK AT THE END. An answer is streamed to the client token by
 * token. Checking only the finished answer means a leaked credential has already been rendered in
 * front of the user and is already in their scrollback; retracting the message afterwards does not
 * unsee it. So the cheap deterministic rules run on the way past, before any text becomes a client
 * event, and the expensive whole-answer check still runs at the end for everything that needs the
 * complete text to judge.
 *
 * <p>THE PROBLEM THIS SOLVES, AND THE PRICE IT PAYS. Deltas arrive in arbitrary pieces, so a
 * credential can straddle two of them: {@code sk-abc} in one flush and {@code def123...} in the
 * next. Scanning each flush on its own finds neither half. This class therefore holds back the
 * last {@link #HOLDBACK} characters of everything it has been given and only releases text once
 * enough has arrived after it that no pattern shorter than the holdback could still be growing
 * into it.
 *
 * <p>The price is that the tail of an answer appears when the stream ends rather than as it is
 * generated. At 512 characters that is the last sentence or two, arriving a few hundred
 * milliseconds early rather than late, and it is the whole cost of not streaming secrets.
 *
 * <p>NOT THREAD-SAFE, and it does not need to be. It is driven from one Flux subscription's
 * {@code doOnNext} and {@code doFinally}, which the Reactive Streams specification requires to be
 * called sequentially and never concurrently - the same reason the text buffer beside it in
 * ChatService is a plain StringBuilder.
 */
public final class StreamingScrubber {

    /**
     * How much text is kept back so a pattern cannot be split across an emission boundary.
     *
     * <p>Must exceed the longest span any shipped rule can match. The longest are a JWT and a
     * generic credential assignment, both of which are bounded in practice by the first whitespace
     * character; 512 is comfortably past both and is still a fraction of a screen.
     */
    private static final int HOLDBACK = 512;

    /**
     * A ceiling on how far the buffer may grow while a straddling match refuses to resolve.
     *
     * <p>Without it, a pathological input - a single unbroken 10 MB token stream with no
     * whitespace - could hold the entire answer in memory waiting for a match to end. At the
     * ceiling the scrubber stops extending and releases what it can, which for a genuinely
     * unterminated match means the redaction simply happens on the next flush instead.
     */
    private static final int MAX_PENDING = 64 * 1024;

    private final List<GuardrailRule> rules;
    private final StringBuilder pending = new StringBuilder();
    private final Map<String, int[]> hitsByRule = new LinkedHashMap<>();
    private final Map<String, String> categoryByRule = new LinkedHashMap<>();

    public StreamingScrubber(List<GuardrailRule> rules) {
        this.rules = rules;
    }

    /**
     * Takes the next piece of generated text and returns the part that is now safe to emit,
     * redacted. Returns an empty string while everything so far is still inside the holdback.
     */
    public String accept(String delta) {
        if (rules.isEmpty()) return delta;
        if (delta != null && !delta.isEmpty()) pending.append(delta);
        if (pending.length() <= HOLDBACK) return "";

        String buffered = pending.toString();
        int cut = buffered.length() - HOLDBACK;

        RuleScanner.Result scan = RuleScanner.scan(buffered, rules);
        record(scan);

        // Pull the cut back in front of any MATCH that straddles it, so a match is never half
        // emitted and half held. The whole match, not only the part redacted: a credential
        // assignment redacts its value alone, and a cut inside "password=" or inside the value
        // released the key and kept the value back alone - where, on the next flush, the pattern no
        // longer matched it and it went out in clear. Repeated until nothing moves it, because
        // pulling it in front of one match can land it inside an earlier, overlapping one.
        boolean moved = true;
        while (moved && cut > 0) {
            moved = false;
            for (GuardrailRule.Span s : scan.spans()) {
                int from = Math.min(s.start(), s.matchStart());
                int to = Math.max(s.end(), s.matchEnd());
                if (from < cut && to > cut) {
                    cut = from;
                    moved = true;
                }
            }
        }
        boolean atCeiling = false;
        if (cut <= 0) {
            if (pending.length() < MAX_PENDING) return "";
            // At the ceiling, release up to the holdback anyway rather than growing without bound -
            // redacting what straddles the cut rather than letting its first half out in clear.
            cut = buffered.length() - HOLDBACK;
            atCeiling = true;
        }

        List<GuardrailRule.Span> emitted = new ArrayList<>();
        for (GuardrailRule.Span s : scan.spans()) {
            if (s.end() <= cut) {
                emitted.add(s);
            } else if (atCeiling && s.start() < cut) {
                emitted.add(new GuardrailRule.Span(s.start(), cut, s.rule(), s.category()));
            }
        }

        String head = buffered.substring(0, cut);
        pending.delete(0, cut);
        return RuleScanner.redact(head, emitted);
    }

    /**
     * Everything still held back, scanned and redacted. Call once when the stream ends; the
     * scrubber is empty afterwards.
     */
    public String drain() {
        if (pending.length() == 0) return "";
        String tail = pending.toString();
        pending.setLength(0);
        if (rules.isEmpty()) return tail;
        RuleScanner.Result scan = RuleScanner.scan(tail, rules);
        record(scan);
        return RuleScanner.redact(tail, scan.spans());
    }

    /** Whether anything was redacted over the whole stream. */
    public boolean fired() { return !hitsByRule.isEmpty(); }

    /**
     * What fired, aggregated across the whole stream.
     *
     * <p>Counts are approximate and deliberately so. The buffer is rescanned on every flush, so a
     * match sitting in the held-back tail is seen again on the next pass and counted twice. Making
     * that exact would mean tracking absolute offsets across an arbitrarily long stream to
     * de-duplicate matches nobody reads the count of - the categories are what the UI shows and
     * what the log line is for, and those are unaffected.
     */
    public List<GuardrailFinding> findings() {
        List<GuardrailFinding> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : hitsByRule.entrySet()) {
            out.add(GuardrailFinding.of(e.getKey(), categoryByRule.get(e.getKey()),
                    "redacted while streaming"));
        }
        return out;
    }

    private void record(RuleScanner.Result scan) {
        for (GuardrailFinding f : scan.findings()) {
            hitsByRule.computeIfAbsent(f.rule(), k -> new int[1])[0]++;
            categoryByRule.put(f.rule(), f.category());
        }
    }
}
