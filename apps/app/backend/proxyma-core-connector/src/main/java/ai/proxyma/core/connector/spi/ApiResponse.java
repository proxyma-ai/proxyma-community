package ai.proxyma.core.connector.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One answer from a service, read whole: its status, its headers and its body. For a body too large
 * to hold, {@link ApiClient#stream} hands back the stream instead.
 */
public final class ApiResponse {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI uri;
    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;
    // The service's name as ApiClient's sentences say it, for json()'s own; null is "The service".
    private final String service;
    private JsonNode json;

    public ApiResponse(URI uri, int status, HttpHeaders headers, byte[] body) {
        this(uri, status, headers, body, null);
    }

    public ApiResponse(URI uri, int status, HttpHeaders headers, byte[] body, String service) {
        this.uri = uri;
        this.status = status;
        this.headers = headers == null ? new HttpHeaders() : headers;
        this.body = body == null ? new byte[0] : body;
        this.service = service == null || service.isBlank() ? null : service;
    }

    public URI uri() { return uri; }

    public int status() { return status; }

    public boolean ok() { return status >= 200 && status < 300; }

    public HttpHeaders headers() { return headers; }

    /** One header's first value, or null. */
    public String header(String name) { return headers.getFirst(name); }

    public byte[] bytes() { return body; }

    public String text() { return new String(body, StandardCharsets.UTF_8); }

    /**
     * The body as JSON; an empty body is an empty object. Throws when it is not JSON - and says so
     * when it is a web page, which is what an address copied from the browser (a page's path joined
     * to every API call) or a redirect to a sign-in page answers with.
     */
    public JsonNode json() {
        if (json == null) {
            try {
                json = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            } catch (IOException e) {
                String path = uri == null ? null : uri.getRawPath();
                if (isWebPage()) {
                    String name = service == null ? "The service" : service;
                    throw new ConnectorHttpException(name + " answered with a web page instead of its API. "
                            + "Check the connection's address.", status, path, e);
                }
                throw new ConnectorHttpException("The service answered with something that is not JSON.", status, path, e);
            }
        }
        return json;
    }

    /** Whether the body is HTML: by its Content-Type, or by {@code <} as its first character. */
    public boolean isWebPage() {
        String type = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (type != null) {
            String t = type.strip().toLowerCase(Locale.ROOT);
            if (t.startsWith("text/html") || t.startsWith("application/xhtml")) return true;
        }
        String text = text();
        int i = 0;
        // Leading whitespace and a byte order mark are not the page's first character.
        while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == (char) 0xFEFF)) i++;
        return i < text.length() && text.charAt(i) == '<';
    }

    /** The URL of the page after this one, from an RFC 8288 {@code Link: <...>; rel="next"} header. */
    public String nextLink() {
        return linkRel("next");
    }

    /** The URL a {@code Link} header names with {@code rel}, or null. */
    public String linkRel(String rel) {
        String link = headers.getFirst(HttpHeaders.LINK);
        if (link == null) return null;
        for (String part : link.split(",")) {
            int open = part.indexOf('<');
            int close = part.indexOf('>', open + 1);
            if (open < 0 || close < 0) continue;
            String params = part.substring(close + 1);
            for (String param : params.split(";")) {
                String p = param.trim();
                if (p.startsWith("rel=")) {
                    String value = p.substring(4).replace("\"", "").trim();
                    for (String r : value.split("\\s+")) {
                        if (r.equalsIgnoreCase(rel)) return part.substring(open + 1, close).trim();
                    }
                }
            }
        }
        return null;
    }
}
