package ai.proxyma.core.connector.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/**
 * Lazy iteration over a paged API, one page fetched when the one before it is used up - for the
 * four shapes services page in: an RFC 8288 {@code Link} header, a cursor in the body, an offset, a
 * page number. Hand the iterator to {@link Listings#mapped} and a failed page becomes an errored
 * listing rather than an exception.
 *
 * <p>Two rules every shape keeps. An EMPTY page does not end a listing that says there is more (some
 * services return one while a filter catches up). And a page that points back at a page already
 * read ends it - a service that hands back the same cursor would otherwise be asked for ever.
 */
public final class Paginator {

    // A listing longer than this is a loop, not a source.
    static final int MAX_PAGES = 100_000;

    private Paginator() {}

    /** One page: its items and what names the next one, null when there is none. */
    public record Page<T>(List<T> items, String next) {}

    /** Fetches the page {@code next} names - null for the first. */
    @FunctionalInterface
    public interface PageFetcher<T> {
        Page<T> fetch(String next);
    }

    /** The items of every page, fetched as they are needed. */
    public static <T> Iterator<T> iterate(PageFetcher<T> fetcher) {
        return new Iterator<>() {
            private Iterator<T> page = null;
            private String next = null;
            private boolean done;
            private int pages;
            private final Set<String> seen = new java.util.HashSet<>();

            @Override
            public boolean hasNext() {
                while (page == null || !page.hasNext()) {
                    if (done) return false;
                    if (++pages > MAX_PAGES) return false;
                    Page<T> p = fetcher.fetch(next);
                    page = p.items() == null ? java.util.Collections.emptyIterator() : p.items().iterator();
                    next = p.next();
                    if (next == null || next.isBlank() || !seen.add(next)) done = true;
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return page.next();
            }
        };
    }

    /**
     * A JSON-array API paged by its {@code Link: <...>; rel="next"} header - GitHub, GitLab, many
     * others. {@code items} picks the array out of each page (the page itself for a bare array).
     */
    public static Iterator<JsonNode> linkHeader(ApiClient client, String path, Map<String, ?> query,
                                                Function<JsonNode, JsonNode> items) {
        return iterate(next -> {
            ApiResponse response = client.requireOk(next == null ? client.get(path, query) : client.get(next));
            return new Page<>(elements(items.apply(response.json())), response.nextLink());
        });
    }

    /**
     * A cursor in the body: {@code cursorParam} carries the cursor on the next request, and
     * {@code nextCursor} reads it out of a page (null or blank when there is no next page).
     */
    public static Iterator<JsonNode> cursor(ApiClient client, String path, Map<String, ?> query, String cursorParam,
                                            Function<JsonNode, JsonNode> items,
                                            Function<JsonNode, String> nextCursor) {
        return iterate(next -> {
            Map<String, Object> q = new LinkedHashMap<>(query == null ? Map.of() : query);
            if (next != null) q.put(cursorParam, next);
            JsonNode page = client.getJson(path, q);
            return new Page<>(elements(items.apply(page)), nextCursor.apply(page));
        });
    }

    /**
     * An offset and a limit: pages of {@code limit} items until one comes back short, or until
     * {@code total} (when the page states it) is reached.
     */
    public static Iterator<JsonNode> offset(ApiClient client, String path, Map<String, ?> query, String offsetParam,
                                            String limitParam, int limit, Function<JsonNode, JsonNode> items,
                                            Function<JsonNode, Integer> total) {
        return iterate(next -> {
            int offset = next == null ? 0 : Integer.parseInt(next);
            Map<String, Object> q = new LinkedHashMap<>(query == null ? Map.of() : query);
            q.put(offsetParam, offset);
            q.put(limitParam, limit);
            JsonNode page = client.getJson(path, q);
            List<JsonNode> got = elements(items.apply(page));
            Integer stated = total == null ? null : total.apply(page);
            int reached = offset + got.size();
            boolean more = stated != null ? reached < stated : got.size() >= limit;
            return new Page<>(got, more && !got.isEmpty() ? String.valueOf(reached) : null);
        });
    }

    /** Page numbers from {@code first}, until a page comes back with fewer than {@code perPage} items. */
    public static Iterator<JsonNode> pageNumber(ApiClient client, String path, Map<String, ?> query, String pageParam,
                                                int first, int perPage, Function<JsonNode, JsonNode> items) {
        return iterate(next -> {
            int number = next == null ? first : Integer.parseInt(next);
            Map<String, Object> q = new LinkedHashMap<>(query == null ? Map.of() : query);
            q.put(pageParam, number);
            JsonNode page = client.getJson(path, q);
            List<JsonNode> got = elements(items.apply(page));
            return new Page<>(got, got.size() >= perPage ? String.valueOf(number + 1) : null);
        });
    }

    /** The elements of a JSON array; nothing for anything else. */
    public static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(out::add);
        return out;
    }

    /** {@code fetch(next)} for a body cursor that is itself a whole URL ({@code @odata.nextLink}, {@code next}). */
    public static Iterator<JsonNode> nextUrl(ApiClient client, String path, Map<String, ?> query,
                                             Function<JsonNode, JsonNode> items,
                                             Function<JsonNode, String> nextUrl) {
        return iterate(next -> {
            JsonNode page = next == null ? client.getJson(path, query) : client.getJson(next);
            return new Page<>(elements(items.apply(page)), nextUrl.apply(page));
        });
    }
}
