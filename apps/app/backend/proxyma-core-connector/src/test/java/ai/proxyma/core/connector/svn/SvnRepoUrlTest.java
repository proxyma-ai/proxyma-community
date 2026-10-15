package ai.proxyma.core.connector.svn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pasted SVN web-UI addresses become repository URLs; real repository paths are left alone.
 *
 * <p>The first two cases are the addresses from the 2026-09-14 report, against a VisualSVN Server:
 * a source registered from either indexed nothing, because svn was pointed at the web UI.
 */
class SvnRepoUrlTest {

    private static final String CHECKOUT =
            "https://svn.example.com/svn/Projects.Demo/01_Projects/2024000001_PJ_Starter_Kit";

    @Test
    @DisplayName("the browser address becomes the checkout URL")
    void webUiUrl() {
        assertEquals(CHECKOUT, SvnConnector.normalizeRepoUrl(
                "https://svn.example.com/!/#Projects.Demo/view/head/01_Projects/2024000001_PJ_Starter_Kit"));
    }

    @Test
    @DisplayName("the part after # pasted as a path loses its view/head segment")
    void fragmentPastedAsPath() {
        assertEquals("Projects.Demo/01_Projects/2024000001_PJ_Starter_Kit",
                SvnConnector.normalizeRepoPath("Projects.Demo/view/head/01_Projects/2024000001_PJ_Starter_Kit"));
    }

    @Test
    @DisplayName("a numbered revision in the web address is dropped too")
    void numberedRevision() {
        assertEquals("https://host/svn/Repo/trunk", SvnConnector.normalizeRepoUrl("https://host/!/#Repo/view/4812/trunk"));
        assertEquals("Repo", SvnConnector.normalizeRepoPath("Repo/view/head"));
    }

    @Test
    @DisplayName("a checkout URL is already right and is not touched")
    void checkoutUrlUnchanged() {
        assertEquals(CHECKOUT, SvnConnector.normalizeRepoUrl(CHECKOUT));
        assertEquals(CHECKOUT, SvnConnector.normalizeRepoUrl(CHECKOUT + "/"));
    }

    @Test
    @DisplayName("a real folder named view deeper in a path is not mistaken for the web UI")
    void realViewFolderKept() {
        assertEquals("projects/docs/view/2024/report", SvnConnector.normalizeRepoPath("projects/docs/view/2024/report"));
        assertEquals("https://host/svn/Repo/docs/view/2024",
                SvnConnector.normalizeRepoUrl("https://host/svn/Repo/docs/view/2024"));
    }
}
