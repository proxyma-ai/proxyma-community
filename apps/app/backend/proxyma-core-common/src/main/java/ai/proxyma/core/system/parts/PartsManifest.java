package ai.proxyma.core.system.parts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What one desktop release is made of: the parts a platform needs and where to download each.
 *
 * <p>Published beside every release as {@code parts-<platform>.txt} and listed in its signed
 * SHA256SUMS.txt, so a manifest is as trustworthy as the release. The part files themselves live
 * elsewhere - on one long-lived release named {@code parts}, under names derived from their
 * content - so a part that did not change between two releases is the same file, uploaded once and
 * downloaded once. See tools/release/parts.py, which writes this.
 *
 * <p>PLAIN TEXT, NOT JSON, on purpose: the launcher reads it before the application exists and
 * carries no JSON library, and a format this small is safer parsed by eye than by a hand-rolled
 * JSON scanner (UpdateService has one, and its history is in its comments).
 *
 * <pre>
 * proxyma-parts 1
 * version 0.17.0
 * platform windows-x64
 * base https://github.com/proxyma-ai/proxyma-releases/releases/download/parts/
 * part app 1a2b3c4d5e6f7a8b proxyma-windows-x64-app-1a2b3c4d5e6f7a8b.zip &lt;sha256&gt; &lt;bytes&gt;
 * </pre>
 *
 * <p>The same format is what the launcher keeps on disk to remember what is installed
 * ({@link PartsStore}).
 */
public record PartsManifest(String version, String platform, String base, List<Part> parts) {

    public static final String HEADER = "proxyma-parts 1";

    /**
     * One part. {@code id} names its directory once installed and is derived from the content, so
     * two releases carrying the same bytes share one install.
     */
    public record Part(String name, String id, String file, String sha256, long size) {}

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8,64}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,200}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public PartsManifest {
        parts = List.copyOf(parts);
    }

    public Optional<Part> part(String name) {
        return parts.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public long totalSize() {
        return parts.stream().mapToLong(Part::size).sum();
    }

    /**
     * Parses a manifest, refusing anything malformed rather than skipping it.
     *
     * <p>Every field is checked against a strict pattern because the values become directory
     * names and URL path segments: a part name of {@code ..} would otherwise walk out of the parts
     * directory. The signature already makes a hostile manifest unlikely; this makes it harmless.
     */
    public static PartsManifest parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty parts manifest");
        String[] lines = text.split("\\R");
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            throw new IllegalArgumentException("not a parts manifest (expected '" + HEADER + "')");
        }
        String version = null, platform = null, base = null;
        List<Part> parts = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            switch (f[0]) {
                case "version" -> version = one(f, line);
                case "platform" -> platform = one(f, line);
                case "base" -> base = one(f, line);
                case "part" -> {
                    if (f.length != 6) throw new IllegalArgumentException("bad part line: " + line);
                    String name = check(NAME, f[1], "part name");
                    if (parts.stream().anyMatch(p -> p.name().equals(name))) {
                        throw new IllegalArgumentException("part listed twice: " + name);
                    }
                    long size;
                    try { size = Long.parseLong(f[5]); } catch (NumberFormatException e) { size = -1; }
                    if (size < 0) throw new IllegalArgumentException("bad size: " + line);
                    parts.add(new Part(name, check(ID, f[2], "part id"), check(FILE, f[3], "file name"),
                            check(SHA, f[4].toLowerCase(), "sha256"), size));
                }
                default -> { /* an unknown key is a newer writer; ignored, never guessed at */ }
            }
        }
        if (version == null || platform == null) throw new IllegalArgumentException("manifest has no version or platform");
        // https, or this machine - the one place a test release can be served from without a
        // certificate. The signature is what makes a manifest trustworthy; this only keeps its
        // downloads off plain HTTP across a network.
        if (base != null && !base.startsWith("https://") && !base.startsWith("http://127.0.0.1:")
                && !base.startsWith("http://localhost:")) {
            throw new IllegalArgumentException("base must be https: " + base);
        }
        return new PartsManifest(version, platform, base, parts);
    }

    public String format() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        sb.append("version ").append(version).append('\n');
        sb.append("platform ").append(platform).append('\n');
        if (base != null) sb.append("base ").append(base).append('\n');
        for (Part p : parts) {
            sb.append("part ").append(p.name()).append(' ').append(p.id()).append(' ').append(p.file())
                    .append(' ').append(p.sha256()).append(' ').append(p.size()).append('\n');
        }
        return sb.toString();
    }

    /** The asset name a release publishes this platform's manifest under. */
    public static String assetName(String platform) {
        return "parts-" + platform + ".txt";
    }

    private static String one(String[] f, String line) {
        if (f.length != 2) throw new IllegalArgumentException("bad line: " + line);
        return f[1];
    }

    private static String check(Pattern p, String value, String what) {
        if (!p.matcher(value).matches()) throw new IllegalArgumentException("bad " + what + ": " + value);
        return value;
    }
}
