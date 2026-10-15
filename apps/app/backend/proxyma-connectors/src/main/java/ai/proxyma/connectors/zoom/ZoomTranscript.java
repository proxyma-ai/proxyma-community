package ai.proxyma.connectors.zoom;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Zoom transcript (WebVTT) as lines a person reads: {@code [00:01:02] Ana Lee: what was said}.
 *
 * <p>Zoom writes one cue per few seconds of speech, numbered, timed, with the speaker before a colon:
 * <pre>
 * WEBVTT
 *
 * 1
 * 00:00:01.120 --> 00:00:04.560
 * Ana Lee: Good morning, everyone.
 * </pre>
 * Cues in a row from one speaker become one line with the first cue's time, so a minute of one
 * person talking is one paragraph and not twenty. A cue with no speaker (closed captions, or audio
 * Zoom could not attribute) is a line of its own without a name. The header, cue numbers, cue
 * settings, NOTE and STYLE blocks and tags such as {@code <v Ana>} are dropped.
 */
final class ZoomTranscript {

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*((?:\\d+:)?\\d{1,2}:\\d{2}(?:[.,]\\d+)?)\\s+-->\\s+\\S+.*$");
    private static final Pattern VOICE = Pattern.compile("^<v(?:\\.[^ >]*)?\\s+([^>]+)>(.*)$");
    private static final Pattern TAGS = Pattern.compile("</?[a-zA-Z][^>]*>");
    // "Name: text" - a name of at most 80 characters with no sentence punctuation in it.
    private static final Pattern SPEAKER = Pattern.compile("^([^:.?!]{1,80}?):\\s+(.*)$");

    private ZoomTranscript() {}

    record Line(String time, String speaker, String text) {}

    /** The transcript as Markdown lines, one per speaker turn; empty for one with no cues. */
    static String render(String vtt) {
        StringBuilder out = new StringBuilder();
        for (Line line : turns(vtt)) {
            out.append('[').append(line.time()).append("] ");
            if (line.speaker() != null) out.append(line.speaker()).append(": ");
            out.append(line.text()).append("\n\n");
        }
        return out.toString().strip();
    }

    /** Every cue's text with its start and speaker, consecutive cues of one speaker joined. */
    static List<Line> turns(String vtt) {
        List<Line> out = new ArrayList<>();
        if (vtt == null || vtt.isBlank()) return out;
        String[] lines = vtt.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "").split("\n");
        String time = null;
        StringBuilder cue = null;
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "";
            if (time == null) {
                Matcher m = TIMING.matcher(line);
                if (m.matches()) {
                    time = clock(m.group(1));
                    cue = new StringBuilder();
                }
                continue;
            }
            if (!line.isBlank()) {
                if (!cue.isEmpty()) cue.append(' ');
                cue.append(line.strip());
                continue;
            }
            add(out, time, cue.toString());
            time = null;
            cue = null;
        }
        return out;
    }

    private static void add(List<Line> out, String time, String raw) {
        String speaker = null;
        String text = raw;
        Matcher voice = VOICE.matcher(text);
        if (voice.matches()) {
            speaker = voice.group(1).strip();
            text = voice.group(2);
        }
        text = TAGS.matcher(text).replaceAll("").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").strip();
        if (speaker == null) {
            Matcher m = SPEAKER.matcher(text);
            if (m.matches()) {
                speaker = m.group(1).strip();
                text = m.group(2).strip();
            }
        }
        if (text.isEmpty()) return;
        if (!out.isEmpty()) {
            Line last = out.get(out.size() - 1);
            if (speaker != null && speaker.equals(last.speaker())) {
                out.set(out.size() - 1, new Line(last.time(), last.speaker(), last.text() + " " + text));
                return;
            }
        }
        out.add(new Line(time, speaker, text));
    }

    // "00:01:02.345" or "01:02.345" as "00:01:02" - the second is enough to find the moment.
    private static String clock(String stamp) {
        String whole = stamp.split("[.,]")[0];
        String[] parts = whole.split(":");
        int h = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
        int m = Integer.parseInt(parts[parts.length - 2]);
        int s = Integer.parseInt(parts[parts.length - 1]);
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
