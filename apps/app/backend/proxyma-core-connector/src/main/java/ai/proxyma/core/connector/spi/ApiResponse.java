package ai.proxyma.core.connector.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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
    private JsonNode json;

    public ApiResponse(URI uri, int status, HttpHeaders headers, byte[] body) {
        this.uri = uri;
        this.status = status;
        this.headers = headers == null ? new HttpHeaders() : headers;
        this.body = body == null ? new byte[0] : body;
    }

    public URI uri() { return uri; }

    public int status() { return status; }

    public boolean ok() { return status >= 200 && status < 300; }

    public HttpHeaders headers() { return headers; }

    /** One header's first value, or null. */
    public String header(String name) { return headers.getFirst(name); }

    public byte[] bytes() { return body; }

    public String text() { return new String(body, StandardCharsets.UTF_8); }

    /** The body as JSON; an empty body is an empty object. Throws when it is not JSON. */
    public JsonNode json() {
        if (json == null) {
            try {
                json = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            } catch (IOException e) {
                throw new ConnectorHttpException("The service answered with something that is not JSON.", status,
                        uri == null ? null : uri.getRawPath(), e);
            }
        }
        return json;
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
