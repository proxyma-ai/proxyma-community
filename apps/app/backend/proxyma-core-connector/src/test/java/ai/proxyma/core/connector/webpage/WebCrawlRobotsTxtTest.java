package ai.proxyma.core.connector.webpage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * robots.txt as RFC 9309 reads it, which proxyma.ai/bot promises. Every User-agent line reset the
 * match, so a group naming ProxymaCrawler beside another agent was ignored; and Disallow was a
 * literal prefix, so "/*.pdf" refused nothing.
 */
class WebCrawlRobotsTxtTest {

    private static final String SITE = "https://example.com";

    private static boolean allowed(String robots, String path) {
        return WebCrawlConnector.RobotsTxt.parse(robots).allowed(SITE + path);
    }

    @Test
    @DisplayName("a group naming ProxymaCrawler beside another agent applies to it")
    void groupOfSeveralAgents() {
        String robots = """
                User-agent: Googlebot
                User-agent: ProxymaCrawler
                Disallow: /
                """;
        assertFalse(allowed(robots, "/page"));
        assertFalse(allowed("User-agent: ProxymaCrawler\nUser-agent: Googlebot\nDisallow: /\n", "/page"));
    }

    @Test
    @DisplayName("a group for another agent alone does not apply")
    void anotherAgentsGroup() {
        String robots = """
                User-agent: Googlebot
                Disallow: /

                User-agent: *
                Disallow: /private
                """;
        assertTrue(allowed(robots, "/page"));
        assertFalse(allowed(robots, "/private/a"));
    }

    @Test
    @DisplayName("the bot page's own example keeps it out")
    void botPageExample() {
        assertFalse(allowed("User-agent: ProxymaCrawler\nDisallow: /\n", "/"));
        assertFalse(allowed("User-agent: ProxymaCrawler\nDisallow: /\n", "/any/page?q=1"));
    }

    @Test
    @DisplayName("a group naming us is followed instead of the * group, even with Allow: / for everyone")
    void ourGroupWinsOverStar() {
        String robots = """
                User-agent: *
                Allow: /

                User-agent: ProxymaCrawler
                Disallow: /
                """;
        assertFalse(allowed(robots, "/page"));
        String lenient = """
                User-agent: *
                Disallow: /

                User-agent: ProxymaCrawler
                Allow: /
                """;
        assertTrue(allowed(lenient, "/page"));
    }

    @Test
    @DisplayName("groups for the same agent are merged, and other records do not split a group")
    void mergedGroups() {
        String robots = """
                User-agent: *
                Disallow: /a

                Sitemap: https://example.com/sitemap.xml
                User-agent: *
                Crawl-delay: 5
                Disallow: /b
                """;
        assertFalse(allowed(robots, "/a/1"));
        assertFalse(allowed(robots, "/b/1"));
        assertTrue(allowed(robots, "/c"));
    }

    @Test
    @DisplayName("* matches any run of characters and a final $ the end")
    void wildcards() {
        String robots = """
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /search*q=
                Disallow: /exact$
                """;
        assertFalse(allowed(robots, "/docs/manual.pdf"));
        assertTrue(allowed(robots, "/docs/manual.pdf.html"));
        assertFalse(allowed(robots, "/search?lang=en&q=x"));
        assertTrue(allowed(robots, "/search?lang=en"));
        assertFalse(allowed(robots, "/exact"));
        assertTrue(allowed(robots, "/exact/more"));
    }

    @Test
    @DisplayName("the longest matching rule decides, and Allow wins a tie")
    void longestMatch() {
        String robots = """
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                Disallow: /docs/public/drafts
                Allow: /same
                Disallow: /same
                """;
        assertFalse(allowed(robots, "/docs/private"));
        assertTrue(allowed(robots, "/docs/public/page"));
        assertFalse(allowed(robots, "/docs/public/drafts/1"));
        assertTrue(allowed(robots, "/same"));
    }

    @Test
    @DisplayName("an empty Disallow, no file and a rule before any User-agent refuse nothing")
    void nothingRefused() {
        assertTrue(allowed("User-agent: *\nDisallow:\n", "/page"));
        assertTrue(allowed(null, "/page"));
        assertTrue(allowed("Disallow: /\n", "/page"));
        assertTrue(allowed("User-agent: ProxymaCrawler\nAllow: /x\n\nUser-agent: *\nDisallow: /\n", "/page"));
        // A blank line does not end a run of User-agent lines: both agents share the rule.
        assertFalse(allowed("User-agent: ProxymaCrawler\n\nUser-agent: *\nDisallow: /\n", "/page"));
    }

    @Test
    @DisplayName("a rule written unencoded matches the encoded address, and comments are dropped")
    void encodingAndComments() {
        String robots = "User-agent: * # everyone\r\nDisallow: /Getting Started # old pages\r\n";
        assertFalse(allowed(robots, "/Getting%20Started/intro.htm"));
        assertTrue(allowed(robots, "/Getting-Started"));
    }
}
