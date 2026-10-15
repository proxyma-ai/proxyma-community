package ai.proxyma.core.system.util;

public final class UrlUtils {

    private UrlUtils() {}

    public static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return url;
        url = url.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
