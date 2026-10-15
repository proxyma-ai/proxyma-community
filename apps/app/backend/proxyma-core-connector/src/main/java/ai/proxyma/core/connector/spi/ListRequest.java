package ai.proxyma.core.connector.spi;

import java.time.Instant;

/**
 * What a sync pass asks a session to list.
 *
 * @param mode     {@link Mode#FULL}: every item - the listing a complete pass purges by absence
 *                 against. {@link Mode#CHANGES}: items changed since {@code since} (and after
 *                 {@code cursor}), asked only of a connector whose capabilities say incremental
 * @param since    when the last complete pass STARTED - null on a full pass. A connector subtracts
 *                 its own overlap for clock skew
 * @param cursor   what the last complete pass returned from {@link Listing#nextCursor()}, or null
 * @param maxItems a ceiling the source's scope sets, or null for none
 */
public record ListRequest(Mode mode, Instant since, String cursor, Integer maxItems) {

    public enum Mode { FULL, CHANGES }

    public ListRequest {
        if (mode == null) mode = Mode.FULL;
    }

    public static ListRequest full() {
        return new ListRequest(Mode.FULL, null, null, null);
    }

    public static ListRequest changes(Instant since, String cursor) {
        return new ListRequest(Mode.CHANGES, since, cursor, null);
    }

    public boolean isFull() {
        return mode == Mode.FULL;
    }
}
