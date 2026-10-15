package ai.proxyma.contracts.feedback;

/**
 * What a file attached to a {@link FeedbackSubmission} is, declared by the sender.
 *
 * The receiving service cannot tell a diagnostic log from a screenshot from a document the user
 * picked by hand, and the difference matters: a log is the one attachment kind the product itself
 * assembles rather than the user choosing a file, so it is the one that can pick up content the
 * user never deliberately handed over. Naming the kind lets the telemetry console show it as what
 * it is, and lets retention be decided per kind rather than for "attachments" as an undifferentiated
 * bucket.
 *
 * Sent as a String field on each multipart part's own metadata rather than being inferred from the
 * filename, which is user-controlled and proves nothing.
 */
public enum FeedbackAttachmentKind {

    /**
     * The installation's most recent log file, attached because the user ticked "include my latest
     * log file". Assembled by the product, not chosen by the user - which is exactly why the modal
     * has to say plainly what a log can contain before the box is ticked.
     */
    LOG,

    /** A screenshot or file the user chose themselves. */
    USER_FILE
}
