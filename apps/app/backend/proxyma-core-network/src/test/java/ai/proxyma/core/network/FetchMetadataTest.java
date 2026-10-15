package ai.proxyma.core.network;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Following a link from another site opens the app; everything else another site can make the
 * browser send stays refused. The guard refused the page visit too, so the public demo answered a
 * blank 403 to anyone arriving from a search result or a chat message.
 */
class FetchMetadataTest {

    private static MockServerHttpRequest.BaseBuilder<?> req(HttpMethod method, String site, String mode, String dest) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.method(method, "/");
        if (site != null) b.header("Sec-Fetch-Site", site);
        if (mode != null) b.header("Sec-Fetch-Mode", mode);
        if (dest != null) b.header("Sec-Fetch-Dest", dest);
        return b;
    }

    @Test
    void aLinkFromAnotherSiteOpensThePage() {
        assertFalse(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "cross-site", "navigate", "document").build()));
    }

    @Test
    void anotherSitesFetchFormOrSubresourceIsRefused() {
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "cross-site", "cors", "empty").build()));
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.POST, "cross-site", "navigate", "document").build()),
                "a cross-site form POST is the CSRF case");
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "cross-site", "no-cors", "image").build()));
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "cross-site", "navigate", "iframe").build()),
                "another site framing the app");
    }

    /**
     * swx-03. A port is not part of a site: a page on localhost:8000 - a docs preview, another
     * app's XSS - is same-site with this one, and its fetch carried the browser's credentials.
     */
    @Test
    void anotherLocalPortsFetchIsRefusedToo() {
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.POST, "same-site", "cors", "empty").build()));
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "same-site", "no-cors", "image").build()));
        assertTrue(WebConfig.isRefusedCrossSite(req(HttpMethod.POST, "same-site", "navigate", "document").build()),
                "a form another local page posts here");
    }

    @Test
    void sameSiteTypedAndOldBrowsersPass() {
        assertFalse(WebConfig.isRefusedCrossSite(req(HttpMethod.POST, "same-origin", "cors", "empty").build()));
        assertFalse(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "same-site", "navigate", "document").build()));
        assertFalse(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, "none", "navigate", "document").build()));
        assertFalse(WebConfig.isRefusedCrossSite(req(HttpMethod.GET, null, null, null).build()));
    }
}
