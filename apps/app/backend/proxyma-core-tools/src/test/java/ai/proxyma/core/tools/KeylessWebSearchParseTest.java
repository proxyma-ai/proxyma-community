package ai.proxyma.core.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two result pages keyless web search reads. The lite page is the fallback when the HTML one
 * serves its bot check (2026-09-30); its fixture is the shape the live endpoint returned that day.
 */
class KeylessWebSearchParseTest {

    private static final String LITE = """
            <html><body><table>
            <tr><td valign="top">1.&nbsp;</td><td>
              <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.spring.io%2Frest%2Dclients.html&amp;rut=abc" class='result-link'>REST Clients :: Spring Framework</a>
            </td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>RestTemplate is deprecated in favor of <b>RestClient</b>.</td></tr>
            <tr><td>&nbsp;</td><td><span class='link-text'>docs.spring.io/rest-clients.html</span></td></tr>
            <tr class="result-sponsored"><td>2.</td><td>
              <a href="https://duckduckgo.com/y.js?ad=1" class='result-link'>An ad</a></td></tr>
            <tr><td valign="top">3.&nbsp;</td><td>
              <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.baeldung.com%2Frestclient&amp;rut=def" class='result-link'>A Guide to RestClient</a>
            </td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>Baeldung's guide.</td></tr>
            </table></body></html>
            """;

    @Test
    void theLitePageGivesTitlesUnwrappedLinksAndSnippetsWithoutAds() {
        List<Map<String, Object>> results =
                KeylessWebSearch.parseLiteResults(Jsoup.parse(LITE, "https://lite.duckduckgo.com/lite/"), 10);

        assertEquals(2, results.size(), results.toString());
        assertEquals("REST Clients :: Spring Framework", results.get(0).get("title"));
        assertEquals("https://docs.spring.io/rest-clients.html", results.get(0).get("link"));
        assertEquals("RestTemplate is deprecated in favor of RestClient.", results.get(0).get("snippet"));
        assertEquals("https://www.baeldung.com/restclient", results.get(1).get("link"));
    }

    @Test
    void theLimitIsKept() {
        assertEquals(1, KeylessWebSearch.parseLiteResults(Jsoup.parse(LITE), 1).size());
    }

    @Test
    void theHtmlPagesResultsAreReadAndItsAdsDropped() {
        String html = """
                <div id="links">
                  <div class="result results_links result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad">Ad</a></div>
                  <div class="result results_links"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&amp;rut=1">Example A</a>
                    <a class="result__snippet">About A.</a></div>
                </div>
                """;
        List<Map<String, Object>> results = KeylessWebSearch.parseHtmlResults(Jsoup.parse(html), 10);

        assertEquals(1, results.size());
        assertEquals("https://example.com/a", results.get(0).get("link"));
        assertEquals("About A.", results.get(0).get("snippet"));
    }

    @Test
    void theBotCheckIsRecognized() {
        Document challenge = Jsoup.parse("<div class=\"anomaly-modal__mask\"></div>");
        assertTrue(KeylessWebSearch.isBotCheck(200, challenge));
        assertTrue(KeylessWebSearch.isBotCheck(202, Jsoup.parse("<p>please wait</p>")));
        assertFalse(KeylessWebSearch.isBotCheck(200, Jsoup.parse("<div class=\"result\"></div>")));
    }
}
