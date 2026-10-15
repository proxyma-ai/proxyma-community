package ai.proxyma.contracts.feedback;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One piece of user feedback, on its way from a Proxyma installation to proxyma-telemetry.
 *
 * WHY THIS TRAVELS AT ALL. Feedback used to be written to the local proxyma-server's own database
 * and stop there. That is fine for a deployment we host and useless for every other one: a customer
 * running the server edition on their own hardware, and every desktop user, was filling in a form
 * whose contents nobody at Proxyma would ever read. The submission is shipped to the telemetry
 * service now, which is the one component we operate that every installation can reach.
 *
 * ALWAYS ANONYMOUS. There is deliberately no userId, no display name and no account identifier
 * anywhere in this type, and no way for a caller to add one - unlike the local feedback row it
 * replaces, which had an "submit anonymously" checkbox and therefore a non-anonymous mode. Feedback
 * crossing a network boundary out of someone else's deployment is a different proposition from
 * feedback stored inside it, and "anonymous unless the user chooses otherwise" is the wrong default
 * on the far side of that boundary. {@link #contactEmail} is the single exception, and it is
 * optional, self-supplied, and used for nothing but replying.
 *
 * WHAT MAY BE ATTACHED is the user's decision, taken per submission, and every field that can carry
 * their content is null unless they ticked the box for it - see {@link #conversationTranscript}.
 * Log files and screenshots ride alongside this object as separate multipart parts rather than
 * being inlined here, so a large attachment never has to be base64'd through a JSON body.
 */
public class FeedbackSubmission {

    /** BAD | NEUTRAL | GOOD. A plain String, not an enum, so a value added on one side does not
     *  become an unparseable payload on the other - see the shared-contracts note in CLAUDE.md
     *  about which changes break whichever side deploys second. */
    @NotNull
    private String mood;

    /** GENERAL | QUOTA_MILESTONE. Same reasoning as {@link #mood}. */
    private String source;

    /** What the user actually wrote. Optional - a mood on its own is still signal. */
    @Size(max = 20_000)
    private String comment;

    /**
     * Optional, and the ONLY identifying field this type carries. Supplied by the user in a field
     * that says what it is for, so that someone reporting a problem can be answered. Empty means
     * they did not want to be contacted, which must stay a first-class outcome rather than a
     * degraded one.
     */
    @Size(max = 320)
    private String contactEmail;

    /** The per-question ratings of a structured survey (see QUOTA_MILESTONE). Null for ordinary
     *  feedback, which has one overall mood and no questionnaire. */
    private Map<String, String> ratings;

    /** "desktop" or "server". Which edition this came from changes what the report even means -
     *  a desktop bug and a server bug with the same description are rarely the same bug. */
    private String edition;

    /** App version string (e.g. "1.0.0"), so a report can be tied to the build that produced it. */
    private String appVersion;

    /** Active license tier (e.g. "COMMUNITY"). Coarse, and not identifying on its own. */
    private String tier;

    /**
     * The installation this came from - the same opaque per-install id the telemetry client already
     * sends. NOT a user id: it identifies a copy of the software, not a person, and it exists so
     * that several reports from one struggling installation can be recognised as one story instead
     * of read as several unrelated ones.
     */
    private String instanceId;

    /** When the user submitted, per their own clock. */
    private Instant submittedAt;

    /**
     * The conversation the feedback is about, verbatim, and null unless the user explicitly ticked
     * "include this conversation".
     *
     * This is the single most sensitive thing this type can carry: a transcript can contain
     * anything the user discussed, including content pulled out of their indexed documents. It is
     * inlined here rather than attached because it is text the reader needs in order to make sense
     * of the complaint, and because a transcript the user consented to send should be visible in
     * the same place as the words they wrote about it.
     */
    private String conversationTranscript;

    /** Remaining forward-compatible fields, the way the retired per-event telemetry payload was - lets a
     *  newer client send something a deployed telemetry service does not know about yet without
     *  the submission being rejected outright. */
    private Map<String, Object> payload = new HashMap<>();

    public FeedbackSubmission() {}

    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public Map<String, String> getRatings() { return ratings; }
    public void setRatings(Map<String, String> ratings) { this.ratings = ratings; }

    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public String getConversationTranscript() { return conversationTranscript; }
    public void setConversationTranscript(String conversationTranscript) {
        this.conversationTranscript = conversationTranscript;
    }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
