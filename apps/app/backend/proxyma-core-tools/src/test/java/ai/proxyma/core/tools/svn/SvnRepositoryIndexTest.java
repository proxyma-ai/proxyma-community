package ai.proxyma.core.tools.svn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listing the root of an SVN server that HOLDS repositories (Apache SVNParentPath) - `svn list`
 * fails there with E130003, and the tool reads the server's index of repositories instead. Found
 * by the maintainer's self test on 2026-09-30.
 */
class SvnRepositoryIndexTest {

    @Test
    void theClientsInvalidXmlErrorIsARepositoryIndexAndOthersAreNot() {
        assertTrue(SvnToolService.isRepositoryIndex(new IOException(
                "svn exited with code 1. Stderr: svn: E130003: The XML response contains invalid XML")));
        assertFalse(SvnToolService.isRepositoryIndex(new IOException(
                "svn exited with code 1. Stderr: svn: E170013: Unable to connect to a repository")));
        assertFalse(SvnToolService.isRepositoryIndex(new IOException((String) null)));
    }

    @Test
    void anApacheParentPathIndexNamesEachRepository() {
        String html = """
                <html><head><title>Collection of Repositories</title></head>
                <body><h2>Collection of Repositories</h2><ul>
                <li><a href="../">..</a></li>
                <li><a href="PJ_RTA-CAR/">PJ_RTA-CAR/</a></li>
                <li><a href="Tools%20Box/">Tools Box/</a></li>
                <li><a href="https://elsewhere.example.com/x/">elsewhere</a></li>
                <li><a href="/svn/absolute/">absolute</a></li>
                </ul><a href="http://subversion.apache.org/">Powered by Subversion</a></body></html>
                """;

        assertEquals(List.of("PJ_RTA-CAR", "Tools Box"), SvnToolService.repositoryNames(html));
    }

    @Test
    void theXsltFlavorOfTheIndexNamesThemToo() {
        String xml = "<svn version=\"1.14\"><index path=\"/\">"
                + "<dir name=\"alpha\" href=\"alpha/\"/><dir name=\"beta\" href=\"beta/\"/></index></svn>";

        assertEquals(List.of("alpha", "beta"), SvnToolService.repositoryNames(xml));
    }

    @Test
    void aPageWithNoRepositoryLinksNamesNone() {
        assertEquals(List.of(), SvnToolService.repositoryNames("<html><body>Forbidden</body></html>"));
        assertEquals(List.of(), SvnToolService.repositoryNames(null));
    }
}
