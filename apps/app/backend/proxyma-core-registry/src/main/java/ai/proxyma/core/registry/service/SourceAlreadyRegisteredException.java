package ai.proxyma.core.registry.service;

/**
 * A source added through a registration dialog is already registered - the same key on the same
 * server, the same path, the same site. Refused rather than quietly re-synced (the maintainer's call,
 * 2026-09-27): a re-sync ignored everything typed into the dialog - its server, token and file
 * types - while looking like it had succeeded.
 *
 * <p>The dialog builds its own sentence from {@link #sourceName()}, in the reader's language; the
 * message is the English one, for the log and for any caller that is not the SPA.
 */
public class SourceAlreadyRegisteredException extends RuntimeException {

    private final String sourceName;

    public SourceAlreadyRegisteredException(String sourceName) {
        super("\"" + sourceName + "\" is already one of your sources. "
                + "Open it on the Sources page to sync it or change its settings.");
        this.sourceName = sourceName;
    }

    public String sourceName() {
        return sourceName;
    }
}
