package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launcher jar is built from an explicit list of classes (the desktop pom's {@code launcher-jar}
 * shade execution), and a class missing from that list is a NoClassDefFoundError at the first start
 * - on every install, including through an auto-updated launcher part. Unit tests run on the whole
 * classpath, so nothing else notices: a new import compiles and passes (editions-assembly-13).
 *
 * <p>This walks every class the shade keeps from proxyma-desktop, follows each Proxyma class it
 * refers to, and asserts all of them are kept, and that nothing else they need is outside the JDK,
 * SLF4J or Logback. It reads the include list from the pom itself, so the two cannot drift.
 */
class LauncherShadeTest {

    private static final Pattern TYPE_IN_DESCRIPTOR = Pattern.compile("L(ai/proxyma/[A-Za-z0-9_/$]+);");
    private static final List<String> ALSO_IN_THE_JAR = List.of("java/", "javax/", "jdk/", "sun/", "com/sun/",
            "org/slf4j/", "ch/qos/logback/");

    @Test
    void everyClassTheLauncherNeedsIsInTheLauncherJar() throws Exception {
        List<Pattern> desktopIncludes = includes("ai.proxyma:proxyma-desktop");
        List<Pattern> commonIncludes = includes("ai.proxyma:proxyma-core-common");
        assertThat(desktopIncludes).as("the pom's launcher-jar filter").isNotEmpty();

        Path desktopClasses = Path.of(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Deque<String> todo = new ArrayDeque<>();
        try (var files = Files.walk(desktopClasses)) {
            files.filter(f -> f.toString().endsWith(".class"))
                    .map(f -> desktopClasses.relativize(f).toString().replace('\\', '/'))
                    .map(f -> f.substring(0, f.length() - ".class".length()))
                    .filter(name -> matchesAny(name + ".class", desktopIncludes))
                    .forEach(todo::add);
        }
        assertThat(todo).as("the walk's starting points").contains("ai/proxyma/desktop/launcher/Boot");

        Set<String> seen = new HashSet<>(todo);
        Set<String> missing = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String name = todo.pop();
            for (String ref : references(name)) {
                if (ref.equals(name)) continue;
                if (ref.startsWith("ai/proxyma/")) {
                    URL where = LauncherShadeTest.class.getClassLoader().getResource(ref + ".class");
                    if (where == null) continue;     // an array or a name the constant pool only mentions
                    String origin = where.toString();
                    boolean kept = origin.contains("proxyma-core-common")
                            ? matchesAny(ref + ".class", commonIncludes)
                            : origin.contains("proxyma-desktop") && matchesAny(ref + ".class", desktopIncludes);
                    if (!kept) missing.add(ref + "  (needed by " + name + ")");
                    if (seen.add(ref)) todo.add(ref);
                } else if (ALSO_IN_THE_JAR.stream().noneMatch(ref::startsWith)) {
                    missing.add(ref + "  (needed by " + name + ")");
                }
            }
        }
        assertThat(missing).as("classes the launcher uses that its jar does not carry").isEmpty();
    }

    /** The ant-style include patterns of one artifact's filter, as regular expressions. */
    private static List<Pattern> includes(String artifact) throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        NodeList filters = pom.getElementsByTagName("filter");
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < filters.getLength(); i++) {
            Element filter = (Element) filters.item(i);
            if (!artifact.equals(text(filter, "artifact"))) continue;
            NodeList includes = filter.getElementsByTagName("include");
            for (int j = 0; j < includes.getLength(); j++) {
                String glob = includes.item(j).getTextContent().trim();
                patterns.add(Pattern.compile(glob.replace(".", "\\.").replace("**", "\u0000")
                        .replace("*", "[^/]*").replace("\u0000", ".*")));
            }
        }
        return patterns;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static boolean matchesAny(String path, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /**
     * Every class a class file names in its constant pool: CONSTANT_Class entries, and - for
     * Proxyma's own types - those in field and method descriptors, which the verifier may load.
     */
    private static Set<String> references(String className) throws IOException {
        Set<String> refs = new LinkedHashSet<>();
        try (InputStream raw = LauncherShadeTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            if (raw == null) return refs;
            DataInputStream in = new DataInputStream(raw);
            in.readInt();                       // magic
            in.readUnsignedShort();             // minor
            in.readUnsignedShort();             // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classIndex[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    default -> throw new IOException("Unknown constant pool tag " + tag + " in " + className);
                }
            }
            for (int i = 1; i < count; i++) {
                if (classIndex[i] != 0) {
                    String name = utf8[classIndex[i]];
                    if (!name.startsWith("[")) refs.add(name);
                    else {
                        Matcher m = TYPE_IN_DESCRIPTOR.matcher(name);
                        while (m.find()) refs.add(m.group(1));
                    }
                }
                if (utf8[i] != null && utf8[i].contains("Lai/proxyma/")) {
                    Matcher m = TYPE_IN_DESCRIPTOR.matcher(utf8[i]);
                    while (m.find()) refs.add(m.group(1));
                }
            }
        }
        return refs;
    }
}
