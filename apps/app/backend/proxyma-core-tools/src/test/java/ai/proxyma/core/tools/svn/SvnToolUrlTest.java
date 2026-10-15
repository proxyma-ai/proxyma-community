package ai.proxyma.core.tools.svn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live SVN tools take a full URL as well as a path, and a URL is the dangerous input: the call
 * authenticates with the user's saved SVN credential, so a URL on another server must be refused
 * rather than followed, or the credential goes wherever a model was told to send it.
 */
class SvnToolUrlTest {

    private static final String ROOT = "https://svn.example.com/svn/Projects";

    @Test
    void aRelativePathIsUnchanged() {
        assertEquals("trunk/src", SvnToolService.relativeToRoot(ROOT, "trunk/src"));
        assertEquals("", SvnToolService.relativeToRoot(ROOT, null));
    }

    @Test
    void aUrlUnderTheRootBecomesThePathBelowIt() {
        assertEquals("01_Projects/Alpha",
                SvnToolService.relativeToRoot(ROOT, "https://svn.example.com/svn/Projects/01_Projects/Alpha/"));
    }

    @Test
    void theRootItselfIsTheEmptyPath() {
        assertEquals("", SvnToolService.relativeToRoot(ROOT + "/", ROOT));
    }

    @Test
    void schemeAndHostCaseAndEscapesDoNotMatter() {
        // Decoded here because the connector encodes each segment itself - left encoded, "%20"
        // would reach the server as "%2520".
        assertEquals("My Folder/a.c",
                SvnToolService.relativeToRoot(ROOT, "HTTPS://SVN.Example.com/svn/Projects/My%20Folder/a.c"));
    }

    @Test
    void aUrlOnAnotherServerIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SvnToolService.relativeToRoot(ROOT, "https://attacker.example.net/svn/Projects/x"));
        assertTrue(e.getMessage().contains("not on the SVN server set in Settings"), e.getMessage());
    }

    @Test
    void aSiblingThatSharesThePrefixIsRefused() {
        // "/svn/ProjectsOld" starts with "/svn/Projects" as a string and is not below it.
        assertThrows(IllegalArgumentException.class,
                () -> SvnToolService.relativeToRoot(ROOT, "https://svn.example.com/svn/ProjectsOld/x"));
    }

    @Test
    void aLookalikeHostIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> SvnToolService.relativeToRoot(ROOT, "https://svn.example.com.evil.net/svn/Projects/x"));
    }
}
