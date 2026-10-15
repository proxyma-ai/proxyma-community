import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders a seed script, replacing placeholders with values chosen at run time.
 *
 * <pre>java RenderSeed.java &lt;source.sql&gt; &lt;target.sql&gt; [@@KEY@@=value ...]</pre>
 *
 * <p>Run by tools\dev\run.bat through the single-file source launcher, the same way
 * GenerateJwtKeys.java is. It exists because batch cannot do a whole-file search and replace
 * without mangling the input: {@code for /f} drops blank lines, eats leading whitespace, and
 * under delayed expansion treats {@code !} as a variable marker - and a SQL script is exactly
 * the kind of file where a silently dropped line produces a fixture that half-loads.
 *
 * <p>The seed scripts are checked in with a placeholder rather than a hardcoded path, because
 * -DataRoot is configurable and a folder connector pointing at the wrong directory is exactly the
 * fixture that dissolves: SyncOrchestratorService re-walks a due connector every 60s and
 * reconciles it against the real filesystem. The checked-in file is never rewritten; this writes
 * a rendered copy into the instance directory.
 *
 * <p>UTF-8 with NO byte-order mark, deliberately. Windows PowerShell's {@code Set-Content
 * -Encoding UTF8} writes one, and H2's parser then treats the BOM as the first character of the
 * first statement: "Syntax error in SQL statement [*]\feff-- Pseudo data for ...", which names
 * the whole file and not the cause. {@link Files#writeString} never writes one.
 */
public final class RenderSeed {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: RenderSeed <source.sql> <target.sql> [@@KEY@@=value ...]");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        if (!Files.isRegularFile(source)) {
            System.err.println("Seed script not found: " + source.toAbsolutePath());
            System.exit(1);
        }

        String sql = Files.readString(source, StandardCharsets.UTF_8);
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                System.err.println("Not a KEY=value replacement: " + args[i]);
                System.exit(2);
            }
            String key = args[i].substring(0, eq);
            String value = args[i].substring(eq + 1);
            if (!sql.contains(key)) {
                // Loud rather than silent: a placeholder that stopped matching leaves the seed
                // pointing at whatever the script had hardcoded, and the fixture then looks
                // correct until the sync orchestrator reconciles it away seconds later.
                System.err.println("Placeholder " + key + " does not appear in " + source.getFileName());
                System.exit(1);
            }
            sql = sql.replace(key, value);
        }

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, sql, StandardCharsets.UTF_8);
    }

    private RenderSeed() {
    }
}
