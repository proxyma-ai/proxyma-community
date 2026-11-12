package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Impressions and their click-through rate, which YouTube publishes ONLY through the YouTube Reporting
 * API's bulk reports (since 2026-01-15: {@code channel_reach_basic_a1} - date, channel_id, video_id,
 * video_thumbnail_impressions, video_thumbnail_impressions_ctr; developers.google.com/youtube/reporting/
 * v1/reports/channel_reports). Nothing is reported until a JOB asks for it:
 *
 * <ol>
 *   <li>the job is found ({@code GET /v1/jobs}) or made ({@code POST /v1/jobs}, read-only scope suffices,
 *       nothing public) - once per connection, then remembered for the process;</li>
 *   <li>the job's reports for the period are listed ({@code GET /v1/jobs/{id}/reports}, by the start of
 *       the data they hold), one per day, a re-issued day replacing the older copy;</li>
 *   <li>each is downloaded from its {@code downloadUrl} - on the Reporting API's own host, or ApiClient
 *       refuses it - and its CSV kept in memory by report id, since a report never changes once issued.</li>
 * </ol>
 *
 * <p>YouTube makes the first day about two days after the job is made, and keeps a report 60 days. No
 * table holds them here: an agent that needs a longer history keeps its own (a workspace snapshot).
 */
final class YouTubeReach {

    static final String REPORT_TYPE = "channel_reach_basic_a1";
    static final String JOB_NAME = "Proxyma reach";
    static final int MAX_REPORTS = 90;
    static final int MAX_VIDEOS = 50;
    private static final int MAX_JOB_PAGES = 5;
    private static final int MAX_REPORT_PAGES = 10;
    private static final int CACHE_REPORTS = 500;

    /** One row of a reach CSV. */
    record Row(String date, String videoId, long impressions, double ctr) {}

    record Job(String id, String createTime, boolean madeNow) {}

    // Parsed CSVs by report id: a report is immutable, and a backfilled day is a NEW report id.
    private final Map<String, List<Row>> reports = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Row>> eldest) {
            return size() > CACHE_REPORTS;
        }
    });
    // The reach job of each connection, once found.
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    int cachedReports() {
        return reports.size();
    }

    /** The connection's reach job: remembered, found, or made now. */
    Job job(ApiClient reporting, UUID connectionId) {
        Job known = connectionId == null ? null : jobs.get(connectionId);
        if (known != null) return new Job(known.id(), known.createTime(), false);
        Job found = find(reporting);
        if (found == null) {
            ApiResponse made = reporting.send(HttpMethod.POST, "v1/jobs", Map.of(),
                    Map.of("reportTypeId", REPORT_TYPE, "name", JOB_NAME), Map.of());
            if (made.status() == 409) {
                found = find(reporting); // made meanwhile, by another call
            } else {
                JsonNode job = YouTubeCalls.json(reporting, YouTubeCalls.Api.REPORTING, made);
                found = new Job(job.path("id").asText(""), job.path("createTime").asText(""), true);
            }
            if (found == null || found.id().isBlank()) {
                throw new YouTubeApiException("YouTube Reporting did not make the reach job. Try again later.");
            }
        }
        if (connectionId != null) jobs.put(connectionId, new Job(found.id(), found.createTime(), false));
        return found;
    }

    void forget(UUID connectionId) {
        if (connectionId != null) jobs.remove(connectionId);
    }

    private static Job find(ApiClient reporting) {
        String next = null;
        for (int page = 0; page < MAX_JOB_PAGES; page++) {
            Map<String, Object> q = new LinkedHashMap<>();
            if (next != null) q.put("pageToken", next);
            JsonNode answer = YouTubeCalls.json(reporting, YouTubeCalls.Api.REPORTING, reporting.get("v1/jobs", q));
            for (JsonNode j : answer.path("jobs")) {
                if (REPORT_TYPE.equals(j.path("reportTypeId").asText(""))) {
                    return new Job(j.path("id").asText(""), j.path("createTime").asText(""), false);
                }
            }
            next = answer.path("nextPageToken").asText(null);
            if (next == null || next.isBlank()) return null;
        }
        return null;
    }

    /**
     * Every row of the job's reports whose data starts within [start, end], newest copy of a day only.
     * Null when YouTube no longer has the job (404) - the caller makes it again.
     */
    List<Row> rows(ApiClient reporting, String jobId, LocalDate start, LocalDate end) {
        Map<String, JsonNode> byPeriod = new TreeMap<>();
        String next = null;
        for (int page = 0; page < MAX_REPORT_PAGES; page++) {
            Map<String, Object> q = new LinkedHashMap<>();
            // A day's report starts at midnight Pacific time - 07:00 or 08:00 UTC on that date.
            q.put("startTimeAtOrAfter", start + "T00:00:00Z");
            q.put("startTimeBefore", end.plusDays(1) + "T00:00:00Z");
            if (next != null) q.put("pageToken", next);
            ApiResponse listed = reporting.get("v1/jobs/" + ApiClient.segment(jobId) + "/reports", q);
            if (listed.status() == 404) return null;
            JsonNode answer = YouTubeCalls.json(reporting, YouTubeCalls.Api.REPORTING, listed);
            for (JsonNode r : answer.path("reports")) {
                String period = r.path("startTime").asText("") + "/" + r.path("endTime").asText("");
                JsonNode held = byPeriod.get(period);
                // Two reports of one period: the later createTime is the corrected one.
                if (held == null || r.path("createTime").asText("").compareTo(held.path("createTime").asText("")) > 0) {
                    byPeriod.put(period, r);
                }
            }
            next = answer.path("nextPageToken").asText(null);
            if (next == null || next.isBlank()) break;
        }
        List<Row> out = new ArrayList<>();
        int read = 0;
        for (JsonNode r : byPeriod.values()) {
            if (read++ >= MAX_REPORTS) break;
            String id = r.path("id").asText("");
            List<Row> parsed = id.isEmpty() ? null : reports.get(id);
            if (parsed == null) {
                String url = r.path("downloadUrl").asText("");
                if (url.isBlank()) continue;
                ApiResponse file = reporting.get(url);
                if (!file.ok()) throw YouTubeCalls.refusal(reporting, YouTubeCalls.Api.REPORTING, file);
                parsed = List.copyOf(parse(new String(file.bytes(), StandardCharsets.UTF_8)));
                if (!id.isEmpty()) reports.put(id, parsed);
            }
            out.addAll(parsed);
        }
        return out;
    }

    // -- the CSV --------------------------------------------------------------------------------

    /**
     * A reach CSV's rows, by its header's column names - never by position, so a column YouTube adds
     * later does not shift the others. Dates come as YYYYMMDD; ISO dates are read as well.
     */
    static List<Row> parse(String csv) {
        List<Row> out = new ArrayList<>();
        if (csv == null) return out;
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int at = 0;
        while (at < lines.length && lines[at].isBlank()) at++;
        if (at >= lines.length) return out;
        List<String> header = fields(lines[at].replace("\uFEFF", ""));
        int date = header.indexOf("date");
        int video = header.indexOf("video_id");
        int impressions = header.indexOf("video_thumbnail_impressions");
        int ctr = header.indexOf("video_thumbnail_impressions_ctr");
        if (date < 0 || impressions < 0) return out;
        for (int i = at + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> f = fields(lines[i]);
            if (f.size() <= Math.max(date, impressions)) continue;
            out.add(new Row(isoDate(f.get(date)), video >= 0 && video < f.size() ? f.get(video) : "",
                    longOf(f.get(impressions)), ctr >= 0 && ctr < f.size() ? doubleOf(f.get(ctr)) : 0));
        }
        return out;
    }

    // One CSV line's fields, quotes honored ("a,b" is one field, "" inside quotes is a quote).
    static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    static String isoDate(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.matches("\\d{8}")) return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        return s;
    }

    private static long longOf(String s) {
        try {
            return Math.round(Double.parseDouble(s.strip().replace(",", "")));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double doubleOf(String s) {
        try {
            double v = Double.parseDouble(s.strip());
            return Double.isFinite(v) ? v : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // -- the summary ----------------------------------------------------------------------------

    /** Per day, in date order: impressions summed, ctr weighted by impressions. */
    static List<Map<String, Object>> byDay(List<Row> rows, LocalDate start, LocalDate end) {
        Map<String, double[]> days = new TreeMap<>();
        for (Row r : rows) {
            if (!inRange(r.date(), start, end)) continue;
            double[] d = days.computeIfAbsent(r.date(), k -> new double[2]);
            d[0] += r.impressions();
            d[1] += r.impressions() * r.ctr();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        days.forEach((date, d) -> out.add(entry("date", date, d)));
        return out;
    }

    /** Per video, the most shown first. */
    static List<Map<String, Object>> byVideo(List<Row> rows, LocalDate start, LocalDate end) {
        Map<String, double[]> videos = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!inRange(r.date(), start, end) || r.videoId().isBlank()) continue;
            double[] d = videos.computeIfAbsent(r.videoId(), k -> new double[2]);
            d[0] += r.impressions();
            d[1] += r.impressions() * r.ctr();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        videos.forEach((id, d) -> out.add(entry("videoId", id, d)));
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("impressions")).reversed());
        return out;
    }

    static Map<String, Object> totals(List<Row> rows, LocalDate start, LocalDate end) {
        double[] d = new double[2];
        for (Row r : rows) {
            if (!inRange(r.date(), start, end)) continue;
            d[0] += r.impressions();
            d[1] += r.impressions() * r.ctr();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("impressions", Math.round(d[0]));
        out.put("ctr", d[0] > 0 ? YouTubeAnalytics.round(d[1] / d[0], 4) : 0.0);
        return out;
    }

    private static Map<String, Object> entry(String key, String value, double[] d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        m.put("impressions", Math.round(d[0]));
        m.put("ctr", d[0] > 0 ? YouTubeAnalytics.round(d[1] / d[0], 4) : 0.0);
        return m;
    }

    private static boolean inRange(String date, LocalDate start, LocalDate end) {
        try {
            LocalDate d = LocalDate.parse(date);
            return !d.isBefore(start) && !d.isAfter(end);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The day a job was made, from its createTime, or null. */
    static LocalDate madeOn(String createTime) {
        try {
            return Instant.parse(createTime).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
