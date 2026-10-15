package ai.proxyma.core.common.ingest;

import java.util.UUID;

/**
 * Whether a bearer token is an ingest token of one custom source - asked by Desktop's and Home
 * Server's access gate (proxyma-core-network), which has no access to the token table, before it
 * lets an ingestion API request through without the access code. Implemented by the registry
 * (IngestTokenService), which also checks the token, fully, at the endpoint itself.
 *
 * <p>BLOCKING - a database read. Call it off the event loop.
 */
public interface IngestTokenCheck {

    /** True only when {@code rawToken} is an ingest token of exactly {@code sourceId}. */
    boolean admits(UUID sourceId, String rawToken);
}
