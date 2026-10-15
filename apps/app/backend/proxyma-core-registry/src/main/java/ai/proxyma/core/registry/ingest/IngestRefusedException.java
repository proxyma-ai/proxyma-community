package ai.proxyma.core.registry.ingest;

/**
 * A request the ingestion API will not take, with the sentence that says why - answered 400, or 413
 * for {@link TooLarge}. The message is written to be read by whoever wrote the script - and, through
 * the server-message catalog, by whoever reads it on the page; constructed with {@code new} at every
 * refusal so tools/i18n/server-messages.mjs finds each sentence.
 */
public class IngestRefusedException extends IllegalArgumentException {

    public IngestRefusedException(String message) {
        super(message);
    }

    /** The HTTP status the refusal is answered with. */
    public int status() {
        return 400;
    }

    /** Something larger than its limit. */
    public static class TooLarge extends IngestRefusedException {

        public TooLarge(String message) {
            super(message);
        }

        @Override
        public int status() {
            return 413;
        }
    }
}
