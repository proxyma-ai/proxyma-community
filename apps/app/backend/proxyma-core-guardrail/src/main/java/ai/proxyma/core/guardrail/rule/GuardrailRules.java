package ai.proxyma.core.guardrail.rule;

import ai.proxyma.core.guardrail.GuardrailStage;
import ai.proxyma.core.settings.dto.GuardrailSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ai.proxyma.core.guardrail.GuardrailStage.INPUT;
import static ai.proxyma.core.guardrail.GuardrailStage.OUTPUT;
import static ai.proxyma.core.guardrail.GuardrailStage.RETRIEVAL;

/**
 * The shipped rule set, and how a settings object turns into the subset that runs.
 *
 * <p>All of it is regular expressions and a deny-list. There is no model here, no network call and
 * no credential, which is the point: this layer works on an air-gapped install, adds no latency
 * worth measuring, and cannot be turned off by a provider outage.
 *
 * <p>WHAT THIS LAYER IS AND IS NOT. It catches shapes. A credential has a shape, a card number has
 * a shape and a checksum, an absolute path has a shape. Harm does not have a shape, which is what
 * the optional classifier is for. Anything here that reaches for meaning - the injection rules -
 * is a speed bump against the copy-pasted attempt and is documented as one rather than sold as a
 * defense.
 */
public final class GuardrailRules {

    private GuardrailRules() {}

    public static final String CATEGORY_SECRET = "secret";
    public static final String CATEGORY_PII = "pii";
    public static final String CATEGORY_INJECTION = "prompt-injection";
    public static final String CATEGORY_PATH = "server-path";
    public static final String CATEGORY_DENY = "deny-list";
    public static final String CATEGORY_CANARY = "system-prompt-leak";

    // -- Secrets ---------------------------------------------------------------
    // Vendor-specific prefixes first: they are unambiguous, so they carry no false-positive cost
    // and they catch the overwhelming majority of real leaks.
    //
    // ALL THREE STAGES. The retrieval case is the one nobody types: a credential committed to a
    // repository, pasted into a Jira comment or left in a log file that the agent then reads. It
    // is the single most common real leak this rule set sees, and until RETRIEVAL existed the only
    // thing standing between it and the model was the OUTPUT stage - which fires only if the model
    // chose to repeat it back, and does nothing about the copy already in the context window.

    private static final List<GuardrailRule> SECRET_RULES = List.of(
            GuardrailRule.of("secret.aws-access-key", CATEGORY_SECRET,
                    "\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|ANPA|ANVA)[0-9A-Z]{16}\\b", INPUT, OUTPUT, RETRIEVAL),
            GuardrailRule.of("secret.github-token", CATEGORY_SECRET,
                    "\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b", INPUT, OUTPUT, RETRIEVAL),
            GuardrailRule.of("secret.slack-token", CATEGORY_SECRET,
                    "\\bxox[baprse]-[A-Za-z0-9-]{10,}\\b", INPUT, OUTPUT, RETRIEVAL),
            GuardrailRule.of("secret.openai-key", CATEGORY_SECRET,
                    "\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b", INPUT, OUTPUT, RETRIEVAL),
            // Our own. An MCP token is `pxm_` plus 64 hex characters and is shown exactly once, on
            // generation - so a copy of one turning up in a chat message is a real event, and this
            // is the only rule here that can recognize it.
            GuardrailRule.of("secret.proxyma-mcp-token", CATEGORY_SECRET,
                    "\\bpxm_[0-9a-f]{64}\\b", INPUT, OUTPUT, RETRIEVAL),
            GuardrailRule.of("secret.jwt", CATEGORY_SECRET,
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}",
                    INPUT, OUTPUT, RETRIEVAL),
            GuardrailRule.of("secret.private-key", CATEGORY_SECRET,
                    "-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----",
                    INPUT, OUTPUT, RETRIEVAL),
            // The generic shape, and the only secret rule that needs a validator. Group 1 is the
            // VALUE only, so the `api_key =` part survives redaction and the reader can see what
            // was removed. "Looks like a credential" is then decided by
            // GuardrailRules::looksRandom rather than by the regex.
            new GuardrailRule("secret.assignment", CATEGORY_SECRET,
                    Pattern.compile("(?i)\\b(?:api[_-]?key|secret|passwd|password|token|access[_-]?key"
                            + "|client[_-]?secret|authorization)\\b\\s*[:=]\\s*[\"']?([A-Za-z0-9._\\-+/]{16,})"),
                    List.of(INPUT, OUTPUT, RETRIEVAL), GuardrailRules::looksRandom));

    // -- Personal data ---------------------------------------------------------
    //
    // NOT ON RETRIEVAL, deliberately, and this is the one applicability decision here that is a
    // judgement rather than a fact. Indexed corporate documents are FULL of legitimate contact
    // details - a support ticket is a name, an email and a phone number by construction - so
    // redacting personal data out of retrieved content would blank the answer to most of the
    // questions people ask this product, on the way in, every turn. The place that decision
    // belongs is the OUTPUT stage, which sees the same data at the point where it would actually
    // reach a reader, and which stays on.

    private static final List<GuardrailRule> PII_RULES = List.of(
            GuardrailRule.of("pii.email", CATEGORY_PII,
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}\\b", INPUT, OUTPUT),
            // Luhn is what makes this rule usable at all. Without it, "13 to 19 digits" redacts
            // every order number, invoice reference and long identifier in every answer, and a
            // guardrail that fires constantly is one an operator switches off.
            GuardrailRule.of("pii.payment-card", CATEGORY_PII,
                    "\\b(?:\\d[ -]?){12,18}\\d\\b", GuardrailRules::passesLuhn, INPUT, OUTPUT),
            // Phone numbers are the least reliable thing here and the pattern is kept deliberately
            // narrow: an explicit country code, or a separator-bearing run of the right length.
            // A bare 10-digit string with no separators is NOT matched - too much of the world's
            // ordinary numeric data looks exactly like that.
            GuardrailRule.of("pii.phone", CATEGORY_PII,
                    "(?<![\\w.])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{2,4}\\)[ .-]?)?"
                            + "\\d{2,4}[ .-]\\d{3,4}[ .-]\\d{2,4}(?![\\w.])",
                    GuardrailRules::plausiblePhone, INPUT, OUTPUT));

    // -- Prompt injection (input and retrieval; never output) ------------------
    // A model writing "ignore all previous instructions" in an ANSWER is quoting or explaining,
    // which is why none of these run on the output side.
    //
    // RETRIEVAL IS WHERE THESE WERE ALWAYS AIMED. A user typing "ignore all previous instructions"
    // is attacking a system they are already allowed to talk to; the same sentence arriving inside
    // a fetched web page, a Jira description or an MCP tool's result was planted for a reader who
    // is not the person asking, and neither the user nor the operator gets to see it first. That
    // is indirect prompt injection, and it is the case these patterns describe best - the
    // turn-delimiter rule below has said so in its own comment since it was written, while running
    // on a stage where retrieved content never appears.
    //
    // Still a speed bump rather than a lock, exactly as on INPUT. What actually holds the line is
    // that tool output is never treated as instruction, which the default system prompt states in
    // its ABSOLUTE RULES. This layer catches the copy-pasted attempt underneath that.

    private static final List<GuardrailRule> INJECTION_RULES = List.of(
            GuardrailRule.of("injection.instruction-override", CATEGORY_INJECTION,
                    "(?i)\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,40}"
                            + "\\b(?:previous|prior|above|earlier|all|any)\\b[^.\\n]{0,24}"
                            + "\\b(?:instructions?|prompts?|rules?|directions?|guidelines?)\\b",
                    INPUT, RETRIEVAL),
            GuardrailRule.of("injection.role-hijack", CATEGORY_INJECTION,
                    "(?i)\\b(?:you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be|from\\s+now\\s+on\\s+you)"
                            + "\\s+(?:an?\\s+)?(?:DAN\\b|jailbroken|unrestricted|uncensored"
                            + "|developer\\s+mode|without\\s+(?:any\\s+)?(?:restrictions?|filters?|rules?))",
                    INPUT, RETRIEVAL),
            // A chat message has no business carrying turn delimiters. This is the shape an
            // injection takes when it arrives inside retrieved content rather than typed.
            GuardrailRule.of("injection.turn-delimiter", CATEGORY_INJECTION,
                    "(?i)<\\s*/?\\s*(?:system|assistant|\\|im_start\\|)\\s*>"
                            + "|\\[\\s*(?:SYSTEM|INST|/INST)\\s*\\]", INPUT, RETRIEVAL),
            GuardrailRule.of("injection.reveal-prompt", CATEGORY_INJECTION,
                    "(?i)\\b(?:reveal|repeat|print|show|output|dump)\\b[^.\\n]{0,30}"
                            + "\\b(?:system\\s+prompt|initial\\s+instructions|your\\s+instructions"
                            + "|the\\s+prompt\\s+above)\\b", INPUT, RETRIEVAL));

    // -- Absolute server paths (output only) -----------------------------------
    //
    // AND NOT ON RETRIEVAL, which is not a judgement call but a correctness one. A tool result is
    // where absolute paths are SUPPOSED to be: `read` returns one, a directory listing is nothing
    // else, and an offloaded result is a `filePath` the agent has to quote back character for
    // character on its next call. Redacting them here would not leak less - the OUTPUT stage and
    // SensitivePaths.scrub() both still stand between those paths and the reader - it would simply
    // break the agent loop, one tool call before anything reached anyone.

    /**
     * A drive-letter path, with the lookbehind that this project has already paid for once.
     *
     * <p>In {@code https://host} the {@code s} is a letter, {@code :} is a colon and what follows
     * is a separator - a textbook drive-letter match. A rule without {@code (?<![A-Za-z0-9])} ate
     * every URL in every answer. Here the candidate letter in {@code https:} is preceded by
     * {@code p}, so the lookbehind rejects it, while a real {@code C:\} at a word start does not
     * have a letter in front of it and matches.
     *
     * <p>Any regex meant to match filesystem paths in free text must be tested against a URL
     * FIRST. That rule is in {@code .claude/rules/security.md} because it was learned the
     * expensive way.
     */
    // Neither pattern allows a SPACE inside a path segment, which is a deliberate accuracy
    // trade-off in the safe direction. Allowing spaces (so that "C:\Program Files\..." matches
    // whole) also lets the pattern run through "C:\logs and then a sentence about it" to the end
    // of the line, redacting the prose along with the path. Excluding spaces truncates a path that
    // contains one - "C:\Program" is removed and " Files\app" is not - which still takes out the
    // root and the drive, still tells the reader a path was there, and never eats a sentence.
    //
    // SensitivePaths.scrub() at the client boundary remains the primary control; this rule is a
    // second look from the other end, so erring toward under-matching here is right.
    private static final List<GuardrailRule> PATH_RULES = List.of(
            GuardrailRule.of("path.drive-letter", CATEGORY_PATH,
                    "(?<![A-Za-z0-9])[A-Za-z]:[\\\\/](?:[^\\\\/:*?\"<>|\\r\\n ]+[\\\\/])*"
                            + "[^\\\\/:*?\"<>|\\r\\n ]*", OUTPUT),
            // Same trap from the other direction: the lookbehind stops this matching the path
            // portion of an ordinary https:// URL, where the slash is preceded by a word character
            // or by another slash. Anchored to a known root as well, which is what keeps it off
            // ordinary prose containing a slash.
            GuardrailRule.of("path.unix-absolute", CATEGORY_PATH,
                    "(?<![\\w:/.])/(?:home|root|opt|var|etc|usr|srv|mnt|data|Users)"
                            + "(?:/[\\w.@\\-]+)+", OUTPUT));

    /**
     * The rules that actually run, given a settings object and a stage.
     *
     * <p>Order is stable and matches the declaration order above, so a findings list reads the
     * same way twice.
     */
    public static List<GuardrailRule> enabledFor(GuardrailSettings settings, GuardrailStage stage) {
        List<GuardrailRule> out = new ArrayList<>();
        if (settings.isDetectSecrets()) addApplicable(out, SECRET_RULES, stage);
        if (settings.isDetectPii()) addApplicable(out, PII_RULES, stage);
        if (settings.isDetectPromptInjection()) addApplicable(out, INJECTION_RULES, stage);
        if (settings.isDetectPathLeak()) addApplicable(out, PATH_RULES, stage);
        GuardrailRule deny = denyListRule(settings.getDenyTerms());
        if (deny != null && deny.appliesTo(stage)) out.add(deny);
        if (settings.isCanaryEnabled()) {
            GuardrailRule canary = canaryRule(settings.getCanaryToken());
            if (canary != null && canary.appliesTo(stage)) out.add(canary);
        }
        return out;
    }

    /**
     * The canary marker, or null when there is nothing to look for.
     *
     * <p>OUTPUT ONLY, and it is the only rule here that cannot produce a false positive. The token
     * is random and exists in exactly one place - this deployment's system prompt - so an answer
     * containing it did not invent it. There is no pattern to tune and no threshold to pick: it is
     * there or it is not.
     *
     * <p>Quoted with {@link Pattern#quote} like the deny-list, for the same reason. A generated
     * token has no regex metacharacters in it today, and relying on that is how a control quietly
     * stops compiling the day somebody changes the alphabet.
     *
     * <p>Deliberately NOT tolerant of reformatting - no optional whitespace, no case folding. A
     * loose canary pattern is a canary that can fire on something else, and the whole value of this
     * check is that when it fires there is nothing to discuss.
     */
    public static GuardrailRule canaryRule(String token) {
        if (token == null || token.isBlank()) return null;
        return GuardrailRule.of("canary.system-prompt", CATEGORY_CANARY,
                Pattern.quote(token.trim()), OUTPUT);
    }

    private static void addApplicable(List<GuardrailRule> out, List<GuardrailRule> rules, GuardrailStage stage) {
        for (GuardrailRule r : rules) {
            if (r.appliesTo(stage)) out.add(r);
        }
    }

    /**
     * The operator's own terms as one alternation, or null when there are none.
     *
     * <p>Every term is quoted with {@link Pattern#quote}. A deny-list is free text typed into a
     * settings box, so treating it as a regular expression means one stray bracket turns a policy
     * control into a startup exception - and, worse, that a term someone believed was blocked
     * silently never compiled.
     */
    public static GuardrailRule denyListRule(String denyTerms) {
        if (denyTerms == null || denyTerms.isBlank()) return null;
        List<String> terms = denyTerms.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Pattern::quote)
                .toList();
        if (terms.isEmpty()) return null;
        // Word boundaries so "acme" does not fire inside "acmestore". \b is applied outside the
        // alternation rather than per term, which is the same thing and keeps the pattern short.
        String regex = "(?i)\\b(?:" + String.join("|", terms) + ")\\b";
        // All three stages. An operator who typed a code name in here means it should not be in a
        // conversation, and a document that carries it is the likeliest way it gets there - the
        // deny-list is the one control on this tab whose terms nobody but the operator can guess,
        // so leaving retrieved content out of it would exempt exactly the path they were worried
        // about.
        return GuardrailRule.of("deny.term", CATEGORY_DENY, regex, INPUT, OUTPUT, RETRIEVAL);
    }

    // -- Validators ------------------------------------------------------------

    /**
     * Whether a matched assignment value is plausibly a credential rather than prose.
     *
     * <p>The test is character mix and the absence of the things a sentence has. "password: please
     * ask the platform team" is 16-plus characters and is not a secret; "password: hunter2hunter2X9"
     * is. Requiring both letters and digits, and rejecting anything with whitespace, separates the
     * two without a dictionary.
     */
    static boolean looksRandom(String value) {
        if (value == null || value.length() < 16) return false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) return false;
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    /** The Luhn checksum every payment card carries, and almost no other long number does. */
    static boolean passesLuhn(String candidate) {
        int sum = 0;
        int digits = 0;
        boolean alternate = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (!Character.isDigit(c)) continue;
            digits++;
            int d = c - '0';
            if (alternate) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alternate = !alternate;
        }
        return digits >= 13 && digits <= 19 && sum % 10 == 0;
    }

    /**
     * A digit count in the range real telephone numbers occupy.
     *
     * <p>E.164 tops out at 15 digits and nothing dialable is shorter than 7. This is what stops the
     * pattern claiming a date or a version string, both of which have separators in the same
     * places.
     */
    static boolean plausiblePhone(String candidate) {
        int digits = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isDigit(candidate.charAt(i))) digits++;
        }
        return digits >= 7 && digits <= 15;
    }
}
