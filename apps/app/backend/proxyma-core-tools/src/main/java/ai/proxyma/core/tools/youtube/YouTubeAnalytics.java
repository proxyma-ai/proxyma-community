package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The channel's numbers from the YouTube Analytics API ({@code GET /v2/reports}, ids=channel==MINE), as
 * a FIXED set of named reports - never metrics or dimensions a model made up, which the API would
 * answer with a 400 for every combination it does not support. Each report is one of the channel
 * reports Google documents (developers.google.com/youtube/analytics/channel_reports):
 *
 * <ul>
 *   <li>{@code overview} - by day: engaged views, views, watch time, average view duration and
 *       percentage, subscribers gained and lost, likes, comments, shares. A period past 60 days is
 *       grouped by week here (sums, and averages weighted by views), so a year stays ~50 rows.</li>
 *   <li>{@code topVideos} - the same metrics by video. The API sorts a top-videos report by views
 *       (engagedViews is not a sort it accepts), so the 50 most viewed are asked for and ranked by
 *       engaged views here, each with its title from the Data API.</li>
 *   <li>{@code trafficSources}, {@code searchTerms} (YouTube search, top 25 - the API's own ceiling for
 *       a traffic-source detail report), {@code subscribedStatus}, {@code contentType} (Shorts, videos,
 *       live), {@code geography} (top 25 countries) and {@code retention} (one video's audience curve,
 *       sampled to at most 50 points).</li>
 * </ul>
 */
final class YouTubeAnalytics {

    static final String PATH = "v2/reports";
    static final int MAX_DAYS = 365;
    // Past this many days the overview is grouped by week.
    static final int DAILY_UP_TO = 60;
    static final int MAX_ROWS = 50;

    private static final List<String> ACTIVITY = List.of("engagedViews", "views", "estimatedMinutesWatched",
            "averageViewDuration", "averageViewPercentage", "subscribersGained", "subscribersLost", "likes", "comments",
            "shares");
    private static final List<String> WATCHING = List.of("engagedViews", "views", "estimatedMinutesWatched",
            "averageViewDuration", "averageViewPercentage");
    private static final List<String> SOURCES = List.of("engagedViews", "views", "estimatedMinutesWatched");

    enum Report {
        OVERVIEW("overview", "day", ACTIVITY, "day", null, false, true),
        TOP_VIDEOS("topVideos", "video", ACTIVITY, "-views", 50, false, false),
        TRAFFIC_SOURCES("trafficSources", "insightTrafficSourceType", SOURCES, null, null, false, true),
        SEARCH_TERMS("searchTerms", "insightTrafficSourceDetail", SOURCES, "-views", 25, false, true),
        SUBSCRIBED_STATUS("subscribedStatus", "subscribedStatus", WATCHING, null, null, false, true),
        CONTENT_TYPE("contentType", "creatorContentType", WATCHING, null, null, false, true),
        GEOGRAPHY("geography", "country", WATCHING, null, null, false, true),
        RETENTION("retention", "elapsedVideoTimeRatio", List.of("audienceWatchRatio", "relativeRetentionPerformance"),
                null, null, true, true);

        final String id;
        final String dimension;
        final List<String> metrics;
        final String sort;
        final Integer maxResults;
        final boolean needsVideo;
        final boolean takesVideo;

        Report(String id, String dimension, List<String> metrics, String sort, Integer maxResults, boolean needsVideo,
               boolean takesVideo) {
            this.id = id;
            this.dimension = dimension;
            this.metrics = metrics;
            this.sort = sort;
            this.maxResults = maxResults;
            this.needsVideo = needsVideo;
            this.takesVideo = takesVideo;
        }

        static Report of(String name) {
            if (name == null) return null;
            String n = name.strip();
            for (Report r : values()) if (r.id.equalsIgnoreCase(n)) return r;
            return null;
        }
    }

    private YouTubeAnalytics() {}

    /** The query a report is, for a period and, where it takes one, a video. */
    static Map<String, Object> query(Report report, LocalDate start, LocalDate end, String videoId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("ids", "channel==MINE");
        q.put("startDate", start.toString());
        q.put("endDate", end.toString());
        q.put("metrics", String.join(",", report.metrics));
        q.put("dimensions", report.dimension);
        List<String> filters = new ArrayList<>();
        if (report == Report.SEARCH_TERMS) filters.add("insightTrafficSourceType==YT_SEARCH");
        if (videoId != null) filters.add("video==" + videoId);
        if (!filters.isEmpty()) q.put("filters", String.join(";", filters));
        if (report.sort != null) q.put("sort", report.sort);
        if (report.maxResults != null) q.put("maxResults", report.maxResults);
        return q;
    }

    /**
     * Runs a report and reads it compactly: {@code columns} and {@code rows} (numbers as numbers),
     * plus {@code totals} for the overview and a {@code note} for the model.
     */
    static Map<String, Object> run(ApiClient analytics, ApiClient data, Report report, LocalDate start, LocalDate end,
                                   String videoId) {
        JsonNode table = YouTubeCalls.json(analytics, YouTubeCalls.Api.ANALYTICS,
                analytics.get(PATH, query(report, start, end, videoId)));
        List<String> columns = new ArrayList<>();
        for (JsonNode h : table.path("columnHeaders")) columns.add(h.path("name").asText(""));
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode r : table.path("rows")) {
            List<Object> row = new ArrayList<>();
            for (JsonNode cell : r) row.add(value(cell));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("report", report.id);
        out.put("startDate", start.toString());
        out.put("endDate", end.toString());
        if (videoId != null) out.put("videoId", videoId);
        boolean more = false;
        String note;
        switch (report) {
            case OVERVIEW -> {
                out.put("totals", totals(columns, rows));
                long days = end.toEpochDay() - start.toEpochDay() + 1;
                if (days > DAILY_UP_TO) {
                    rows = byWeek(columns, rows);
                    columns.set(0, "week");
                    out.put("grouping", "week");
                } else {
                    out.put("grouping", "day");
                }
                note = "By " + (days > DAILY_UP_TO ? "week (each row starts on its Monday; averages weighted by views)"
                        : "day") + ". " + LAG + " " + VIEWS;
            }
            case TOP_VIDEOS -> {
                int engaged = columns.indexOf("engagedViews");
                if (engaged >= 0) rows.sort(Comparator.comparingDouble((List<Object> r) -> number(r.get(engaged))).reversed());
                addTitles(data, columns, rows);
                note = "The channel's 50 most viewed videos in the period (YouTube sorts this report by views only), ranked "
                        + "here by engagedViews. averageViewDuration is in seconds, averageViewPercentage in percent. " + LAG;
            }
            case TRAFFIC_SOURCES -> {
                sortByViews(columns, rows);
                note = "Where views came from (YT_SEARCH is YouTube search, SUGGESTED the side panel and end of other videos, "
                        + "BROWSE the home page and feeds, SHORTS the Shorts feed). " + LAG;
            }
            case SEARCH_TERMS -> note = "What viewers typed in YouTube search before watching - the top 25, the most YouTube "
                    + "reports. " + LAG;
            case SUBSCRIBED_STATUS -> {
                sortByViews(columns, rows);
                note = "Subscribed against not subscribed viewers - the nearest the API comes to new against returning "
                        + "viewers. " + LAG;
            }
            case CONTENT_TYPE -> {
                sortByViews(columns, rows);
                note = "Split by format: SHORTS, VIDEO_ON_DEMAND (long videos), LIVE_STREAM, STORY. " + LAG;
            }
            case GEOGRAPHY -> {
                sortByViews(columns, rows);
                more = rows.size() > 25;
                if (more) rows = new ArrayList<>(rows.subList(0, 25));
                note = "Top countries by views, as ISO 3166 codes. " + LAG;
            }
            case RETENTION -> {
                if (rows.size() > MAX_ROWS) {
                    int step = (rows.size() + MAX_ROWS - 1) / MAX_ROWS;
                    List<List<Object>> sampled = new ArrayList<>();
                    for (int i = step - 1; i < rows.size(); i += step) sampled.add(rows.get(i));
                    if (sampled.get(sampled.size() - 1) != rows.get(rows.size() - 1)) sampled.add(rows.get(rows.size() - 1));
                    rows = sampled;
                }
                note = "elapsedVideoTimeRatio is how far into the video (0.5 = halfway); audienceWatchRatio the share of "
                        + "viewers still watching there (above 1 where people rewatch); relativeRetentionPerformance compares "
                        + "it with videos of similar length (0.5 is average). Includes paid views. " + LAG;
            }
            default -> note = LAG;
        }
        if (rows.size() > MAX_ROWS) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
            more = true;
        }
        if (rows.isEmpty()) note = "YouTube has no data for this report in that period. " + note;
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("more", more);
        out.put("note", note);
        return out;
    }

    static final String LAG = "YouTube Analytics lags two to three days, so the last days of a period may be missing or "
            + "still growing.";
    static final String VIEWS = "Follow engagedViews for trends: since 2026-08-24 views also counts plays that begin on "
            + "autoplay or hover.";

    // -- reading ------------------------------------------------------------------------------

    static Object value(JsonNode cell) {
        if (cell.isIntegralNumber()) return cell.asLong();
        if (cell.isNumber()) return round(cell.asDouble(), 3);
        return cell.asText("");
    }

    static double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    static double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static void sortByViews(List<String> columns, List<List<Object>> rows) {
        int views = columns.indexOf("views");
        if (views >= 0) rows.sort(Comparator.comparingDouble((List<Object> r) -> number(r.get(views))).reversed());
    }

    // The whole period's figures from its daily rows: counts summed, durations and percentages
    // weighted by views - YouTube's own definition of an average view duration is watch time over views.
    static Map<String, Object> totals(List<String> columns, List<List<Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> sum = aggregate(columns, rows);
        for (int i = 1; i < columns.size(); i++) out.put(columns.get(i), sum.get(i));
        return out;
    }

    static List<List<Object>> byWeek(List<String> columns, List<List<Object>> rows) {
        Map<String, List<List<Object>>> weeks = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            String week;
            try {
                week = LocalDate.parse(String.valueOf(row.get(0))).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toString();
            } catch (RuntimeException e) {
                week = String.valueOf(row.get(0));
            }
            weeks.computeIfAbsent(week, k -> new ArrayList<>()).add(row);
        }
        List<List<Object>> out = new ArrayList<>();
        weeks.forEach((week, members) -> {
            List<Object> row = aggregate(columns, members);
            row.set(0, week);
            out.add(row);
        });
        return out;
    }

    private static List<Object> aggregate(List<String> columns, List<List<Object>> rows) {
        int views = columns.indexOf("views");
        int minutes = columns.indexOf("estimatedMinutesWatched");
        double totalViews = 0;
        for (List<Object> r : rows) totalViews += views >= 0 ? number(r.get(views)) : 0;
        List<Object> out = new ArrayList<>();
        out.add(null);
        for (int i = 1; i < columns.size(); i++) {
            String c = columns.get(i);
            if (c.toLowerCase(Locale.ROOT).startsWith("average")) {
                double value;
                if (c.equals("averageViewDuration") && minutes >= 0 && totalViews > 0) {
                    double m = 0;
                    for (List<Object> r : rows) m += number(r.get(minutes));
                    value = m * 60 / totalViews;
                } else {
                    double weighted = 0;
                    for (List<Object> r : rows) weighted += number(r.get(i)) * (views >= 0 ? number(r.get(views)) : 1);
                    value = totalViews > 0 ? weighted / totalViews : 0;
                }
                out.add(round(value, c.equals("averageViewDuration") ? 0 : 2));
            } else {
                double s = 0;
                boolean integral = true;
                for (List<Object> r : rows) {
                    Object v = r.get(i);
                    if (!(v instanceof Long)) integral = false;
                    s += number(v);
                }
                out.add(integral ? (Object) Math.round(s) : (Object) round(s, 2));
            }
        }
        return out;
    }

    // Each top video's title, one Data API call for all of them; a title that cannot be read is left out.
    private static void addTitles(ApiClient data, List<String> columns, List<List<Object>> rows) {
        int video = columns.indexOf("video");
        if (video < 0 || rows.isEmpty()) return;
        Map<String, String> titles = new LinkedHashMap<>();
        try {
            List<String> ids = new ArrayList<>();
            for (List<Object> r : rows) ids.add(String.valueOf(r.get(video)));
            JsonNode page = data.getJson(YouTubeTools.V3 + "videos", Map.of("part", "snippet", "id", String.join(",", ids),
                    "maxResults", 50));
            for (JsonNode v : page.path("items")) titles.put(v.path("id").asText(""), v.path("snippet").path("title").asText(""));
        } catch (RuntimeException unreadable) {
            // the numbers stand without their titles
        }
        columns.add(video + 1, "title");
        for (List<Object> r : rows) r.add(video + 1, titles.getOrDefault(String.valueOf(r.get(video)), ""));
    }
}
