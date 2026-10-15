package ai.proxyma.core.connector;

public enum ConnectorType {

    /**
     * Desktop only. A folder that already exists on the user's own disk, walked and indexed strictly
     * BY REFERENCE. Proxyma never copies, moves, rewrites or deletes anything under it - deleting
     * the connector means "stop indexing this", and there is deliberately no code path in the
     * desktop edition capable of meaning anything else (see LocalFolderConnector, and the absence
     * of the whole upload package from that edition's container).
     */
    LOCAL_FOLDER("files"),

    /**
     * Server only. Bytes the user uploaded, which Proxyma stored in that user's own sandbox under
     * {storageRoot}/users/{userId}/uploads/ and therefore OWNS: they count against the user's
     * storage quota, and removing the connector removes them.
     */
    UPLOADED_FILES("files"),

    /**
     * Legacy. The single type that used to cover both of the above, distinguished only by which
     * edition happened to be running. Never written any more - nothing constructs it, and
     * ConnectorController cannot parse a request into it.
     *
     * It survives ONLY so that rows written before the split still deserialize. connectorType is
     * mapped @Enumerated(EnumType.STRING) on both RegisteredConnector and TrackedDocument, so the
     * literal text "FILES" is sitting in the database of every existing install - and this project
     * has no Flyway/Liquibase, only ddl-auto=update, so there is no migration file that could have
     * rewritten those rows ahead of the first read. Removing this constant would make Hibernate
     * throw on loading any of them, taking out every pre-existing connector on a live server and on
     * every desktop install already in the wild.
     *
     * LegacyFilesTypeMigrator rewrites them to the correct edition-specific value at startup.
     * Delete this constant only once every deployment that matters has been through a version
     * carrying that migrator.
     */
    @Deprecated
    FILES("files"),

    JIRA("jira"),
    CONFLUENCE("confluence"),
    BITBUCKET("bitbucket"),
    SVN("svn"),
    SHAREPOINT("sharepoint"),
    TEAMS("teams"),

    /**
     * A folder on a Windows file server, read over SMB2/3 as the SOURCE OWNER'S OWN domain
     * account - so the share's ACLs decide what is indexed, exactly as they decide what the owner
     * can open in Explorer.
     *
     * <p>Deliberately NOT shareable. Sharing would hand other users documents those ACLs keep from
     * them, which is the one thing reading as the owner is for.
     *
     * <p>It reads through a Network share CONNECTION since 2026-10-01: the owner's domain account,
     * and the one file server it may be sent to. A source may only reach its connection's server.
     * That is a credential boundary, not a convenience: connecting to an SMB server sends it an
     * NTLM exchange for the owner's account, which can be cracked offline or relayed - so a source
     * whose path names another host is refused, and no tool can create a connection, so a
     * prompt-injected document cannot point the account at a server its owner did not type. On
     * Enterprise Server an administrator's list of allowed file servers is a ceiling on which
     * servers a connection may name.
     *
     * <p>See docs/architecture.html#spec-05.
     */
    NETWORK_SHARE("networkshare"),

    /**
     * One user's own Microsoft Teams chats - 1:1 and group - read as that user. Personal
     * correspondence, so off by default ({@code m365.chats-enabled}) and never shareable.
     */
    TEAMS_CHAT("teamschat"),

    /**
     * One Outlook mail folder or one calendar, read as the user who added it. Off by default
     * ({@code m365.outlook-enabled}) and never shareable. See docs/architecture.html#spec-08.
     */
    OUTLOOK("outlook"),

    WEB_PAGE("webpage"),

    // -- Built on the connector framework v2 (ai.proxyma.connectors.*): one line each. --------

    /** A GitHub repository's issues, pull requests and documentation files (github.com or GHES). */
    GITHUB("github"),

    /** Slack channels' messages, one item per thread or per channel-day, read through the customer's own Slack app. */
    SLACK("slack"),

    /** A GitLab project's, or a whole group's, issues, merge requests and documentation files. */
    GITLAB("gitlab"),

    /** Linear issues, of chosen teams or of every team the connection can see. */
    LINEAR("linear"),
    /** Google Drive files - Docs, Sheets and Slides exported, other files as they are. */
    GOOGLE_DRIVE("googledrive"),

    /** One Gmail mailbox's threads under chosen labels, read as the connection's account. */
    GMAIL("gmail"),

    /** Events of chosen Google calendars, in a window around today. */
    GOOGLE_CALENDAR("googlecalendar"),

    /** A user's own OneDrive, or one folder in it, read as the Microsoft 365 connection's account. */
    ONEDRIVE("onedrive"),
    /** A Bitbucket Cloud repository's pull requests and documentation files (api.bitbucket.org). */
    BITBUCKET_CLOUD("bitbucketcloud"),
    /** Notion pages and database rows shared with an integration, each page rendered as markdown. */
    NOTION("notion"),

    /** The records of an Airtable base's tables, one item per record. */
    AIRTABLE("airtable"),

    /** A Zendesk account's Help Center articles and/or tickets with their comment threads. */
    ZENDESK("zendesk"),

    /** A Freshdesk account's tickets with their conversations and/or its solution articles. */
    FRESHDESK("freshdesk"),

    /** HubSpot CRM records - companies, contacts, deals, tickets - each with its activity and associated records. */
    HUBSPOT("hubspot"),

    /** Salesforce records of chosen objects - accounts, opportunities, cases - with their related records. */
    SALESFORCE("salesforce"),
    /** Asana tasks of a workspace's chosen projects, or of every project the connection can see. */
    ASANA("asana"),
    /** monday.com items of chosen boards, or of every board the connection can see. */
    MONDAY("monday"),
    /** Trello cards of chosen boards, or of every open board of the connection's member. */
    TRELLO("trello"),
    /** ClickUp tasks of a workspace, or of its chosen spaces, folders and lists. */
    CLICKUP("clickup"),
    /** Files in Dropbox - the whole Dropbox or one folder - with Paper docs exported as markdown. */
    DROPBOX("dropbox"),

    /** Files in Box - All Files or one folder - with Box Notes read as text. */
    BOX("box"),

    /** Zoom meetings' cloud-recording transcripts and AI Companion summaries, one item per meeting. */
    ZOOM("zoom"),

    /** Messages of chosen folders of a mailbox that takes a password over IMAP, and their attachments. */
    IMAP("imap"),

    /** Documents pushed to Proxyma through the ingestion API (a script, Zapier, n8n) - nothing is pulled. */
    CUSTOM("custom");

    // The stable name this type is known by OUTSIDE the JVM - see wireName().
    private final String wireName;

    ConnectorType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * True for the two file-backed types and the legacy value they replaced.
     *
     * Most of the connector pipeline genuinely does not care which edition's flavour it is holding -
     * "does this connector carry an absolute filesystem path that must never reach a client", "does
     * it get scope knobs (recursive, fileTypes)". Those questions had one answer before the split and
     * still do, and asking them through this method keeps them from silently becoming true for only
     * one of the two.
     *
     * Anything that WRITES must not use this. Byte ownership is the whole reason the types are
     * separate, so a caller that deletes, moves or reclaims storage names {@link #UPLOADED_FILES}
     * explicitly and gets no help from here.
     *
     * <p>THE ONE PREDICATE LEFT ON THIS ENUM, since the connector framework v2 (2026-10-14): it is
     * about who owns the bytes and whether a path may reach a client, not about what a plugin can
     * do. Everything a plugin declares - sharing, file-type filter, subfolders, its edition, its
     * service, its sync cadence - is on its descriptor and read through ConnectorCatalog, so a new
     * type is one constant here and nothing else in this file.
     */
    public boolean isFileBacked() {
        return this == LOCAL_FOLDER || this == UPLOADED_FILES || this == FILES;
    }

    /**
     * The stable name this type is known by OUTSIDE the JVM - REST responses, MCP tool output, and
     * the {@code connectorType} metadata field stored on every indexed chunk in the vector store
     * and the BM25 index.
     *
     * All three file-backed values answer to "files", which is what keeps the split invisible where
     * it must be invisible. Two consequences, both load-bearing:
     *
     * - NO RE-INDEX IS NEEDED. Every chunk indexed before the split carries this field already.
     * - No deployment ever serves both types (LOCAL_FOLDER is desktop-only, UPLOADED_FILES
     *   server-only), so no client ever needs to tell them apart.
     *
     * Deliberately NOT {@code name().toLowerCase()}: that is what several call sites used to do
     * independently, which is exactly how this would have leaked. A constructor argument since the
     * connector framework v2, so a new type names its wire name on the one line that adds it.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The types answering to one wire name, in declaration order - one for every type but "files",
     * which three answer to. Empty for a name nothing answers to.
     */
    public static java.util.List<ConnectorType> forWireName(String wire) {
        if (wire == null || wire.isBlank()) return java.util.List.of();
        String lower = wire.trim().toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(values()).filter(t -> t.wireName.equals(lower)).toList();
    }

    /**
     * Normalizes a {@code connectorType} value read back from somewhere this class does not control
     * - specifically the metadata on an already-indexed chunk, which may have been written by any
     * earlier version.
     *
     * Chunks indexed before {@link #wireName()} existed carry the raw enum name ("FILES"), and any
     * indexed by a build that let the split leak would carry "LOCAL_FOLDER"/"UPLOADED_FILES". All
     * of them mean "files" and are reported as such, so a search result set spanning old and new
     * chunks does not label identical sources three different ways. An enum name of any other type
     * reads as that type's wire name, so a new type needs no line here.
     *
     * Returns unrecognised input lowercased rather than null: this only ever feeds a display label,
     * and showing an unexpected value is better than dropping it.
     */
    public static String normalizeWireName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        for (ConnectorType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed) || type.wireName.equalsIgnoreCase(trimmed)) {
                return type.wireName;
            }
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
