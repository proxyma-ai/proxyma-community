package ai.proxyma.core.connector.spi;

/**
 * Which edition offers a connector or a service.
 *
 * <p>{@link #COMMUNITY} is every edition - Desktop, Home Server and Enterprise Server alike, with no
 * license check. {@link #ENTERPRISE} is Enterprise Server only AND an Enterprise license: Microsoft
 * 365 is the case it was made for, since it needs an Entra tenant, and a tenant is something an
 * organization has. On any other edition such a type is absent from the catalog, refused at
 * registration, and an existing source of it is listed but never synced.
 */
public enum Edition {
    COMMUNITY,
    ENTERPRISE;

    /** Whether a deployment that is (or is not) Enterprise Server offers what this edition names. */
    public boolean offeredOn(boolean enterpriseServer) {
        return this == COMMUNITY || enterpriseServer;
    }
}
