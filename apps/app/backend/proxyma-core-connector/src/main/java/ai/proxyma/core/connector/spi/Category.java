package ai.proxyma.core.connector.spi;

/**
 * What kind of thing a source holds - the group it is listed under on the Sources page and the
 * style of its @-mention in the chat. The labels are English; the page passes them through t()
 * and the Vietnamese catalog carries them.
 */
public enum Category {
    FILES("Files"),
    WEB("Websites"),
    DOCS("Docs and wikis"),
    CODE("Code"),
    TICKETS("Tickets and tasks"),
    CHAT("Chat"),
    EMAIL("Email"),
    CALENDAR("Calendar"),
    STORAGE("Cloud storage"),
    CRM("CRM"),
    SUPPORT("Support"),
    MEETINGS("Meetings"),
    OTHER("Other");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
