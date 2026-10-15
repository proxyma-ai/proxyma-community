package ai.proxyma.core.connector.svn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A stand-in for the svn command line, started as its own process by SvnRunnerTest.
 *
 * <p>Like the real one, it reads the password from stdin BEFORE it writes anything when it is
 * given {@code --password-from-stdin} - the ordering that deadlocked the old runner. What it then
 * does is chosen by the target URL (the last argument before the options svn appends):
 * {@code slow} waits, {@code big} writes megabytes, {@code args} prints its own arguments,
 * {@code fail} fails the way svn does.
 */
public final class FakeSvn {

    private FakeSvn() {}

    public static void main(String[] args) throws Exception {
        List<String> all = Arrays.asList(args);
        if (all.contains("--password-from-stdin")) {
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String password = stdin.readLine();
            if (password == null || password.isEmpty()) {
                System.err.println("svn: E215004: No more credentials or we tried too many times.");
                System.exit(1);
            }
        }
        String subcommand = args.length > 0 ? args[0] : "";
        String target = all.stream().filter(a -> a.contains("://")).findFirst().orElse("");
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (target.contains("fail")) {
            System.err.println("svn: E170013: Unable to connect to a repository at URL '" + target + "'");
            System.exit(1);
        }
        if (target.contains("slow")) Thread.sleep(6_000);
        switch (subcommand) {
            case "cat" -> {
                if (target.contains("big")) {
                    OutputStream raw = System.out;
                    byte[] block = new byte[64 * 1024];
                    Arrays.fill(block, (byte) 'x');
                    for (int i = 0; i < 160; i++) raw.write(block); // 10 MB
                    raw.flush();
                } else if (target.contains("args")) {
                    out.print(String.join("\n", args));
                } else {
                    out.print("file content");
                }
            }
            case "list" -> out.print("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <lists><list path="%s">
                    <entry kind="file"><name>a.md</name><size>3</size>
                    <commit revision="7"><author>ana</author><date>2026-09-01T10:00:00.000000Z</date></commit></entry>
                    <entry kind="dir"><name>docs</name></entry>
                    <entry kind="file"><name>b.md</name></entry>
                    <entry kind="file"><name>c.md</name></entry>
                    </list></lists>
                    """.formatted(target));
            default -> { }
        }
        out.flush();
    }
}
