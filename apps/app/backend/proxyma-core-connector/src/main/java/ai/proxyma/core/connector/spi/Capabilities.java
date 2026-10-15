package ai.proxyma.core.connector.spi;

/**
 * What a connector can do, declared once and read wherever the answer matters - instead of a
 * predicate per question on ConnectorType and a switch per caller.
 *
 * @param incremental    {@link ConnectorSession#list} answers {@link ListRequest.Mode#CHANGES} with a
 *                       genuine subset (items changed since the last complete pass). A pass of that
 *                       kind never purges by absence; a full one is forced at least daily.
 * @param cursor         the connector hands back {@link Listing#nextCursor()}, stored on the source
 *                       after a complete pass and passed to the next one
 * @param fileTypeFilter the source's items are files, so its scope includes the file-type filter
 * @param recursiveScope "include subfolders" is a scope setting the connector honors
 * @param shareable      other users may be given what the source indexed. False where the content
 *                       was read through an access check those users never passed - a mailbox, a
 *                       network share read as the owner, a delegated Microsoft sign-in
 * @param edition        which edition offers it ({@link Edition#ENTERPRISE}: Enterprise Server with
 *                       an Enterprise license)
 * @param customDialog   the page keeps a bespoke dialog for it (a folder picker, an upload, a crawl,
 *                       a share path, a mailbox picker) instead of rendering the descriptor's fields
 * @param liveRead       {@link ConnectorSession#fetch} by id is cheap enough for the agent's
 *                       readSourceItem tool to call it while answering
 * @param push           the source's items are PUSHED to it (the ingestion API, the agent's
 *                       saveToSource) rather than pulled from a service: its session lists what was
 *                       pushed. Two sources of such a type with one name are two different sources,
 *                       so nobody else's shared copy is offered as a duplicate of one
 */
public record Capabilities(boolean incremental, boolean cursor, boolean fileTypeFilter,
                           boolean recursiveScope, boolean shareable, Edition edition,
                           boolean customDialog, boolean liveRead, boolean push) {

    public Capabilities {
        if (edition == null) edition = Edition.COMMUNITY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean incremental;
        private boolean cursor;
        private boolean fileTypeFilter;
        private boolean recursiveScope;
        private boolean shareable;
        private Edition edition = Edition.COMMUNITY;
        private boolean customDialog;
        private boolean liveRead;
        private boolean push;

        public Builder incremental() { this.incremental = true; return this; }
        public Builder cursor() { this.cursor = true; return this; }
        public Builder fileTypeFilter() { this.fileTypeFilter = true; return this; }
        public Builder recursiveScope() { this.recursiveScope = true; return this; }
        public Builder shareable() { this.shareable = true; return this; }
        public Builder edition(Edition value) { this.edition = value; return this; }
        public Builder customDialog() { this.customDialog = true; return this; }
        public Builder liveRead() { this.liveRead = true; return this; }
        public Builder push() { this.push = true; return this; }

        public Capabilities build() {
            return new Capabilities(incremental, cursor, fileTypeFilter, recursiveScope, shareable, edition,
                    customDialog, liveRead, push);
        }
    }
}
