import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;

/**
 * Runs PlantUML with Space Grotesk registered in the JVM first.
 *
 * <p>PlantUML measures every label with Java's font metrics and writes the measured width into the
 * SVG; the page then draws the label in Space Grotesk, the product's UI face. Measured with a
 * different face, a label's letters are stretched or squeezed to fit the box. Registering the same
 * face here makes the measured width and the drawn width one number. The TTFs are
 * tools/docs/fonts/ (SIL OFL, see OFL.txt there).
 *
 * <p>Run in source-file mode, so it needs no build step:
 * {@code java -cp plantuml-mit.jar tools/docs/lib/RenderUml.java <font dir> <plantuml args...>}
 */
public class RenderUml {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File fonts = new File(args[0]);
        File[] ttfs = fonts.listFiles((dir, name) -> name.endsWith(".ttf"));
        if (ttfs == null || ttfs.length == 0) throw new IllegalStateException("No .ttf in " + fonts);
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (File f : ttfs) {
            if (!env.registerFont(Font.createFont(Font.TRUETYPE_FONT, f))) {
                // false means a font of that name already exists: harmless, metrics are the same face.
                System.err.println("Font already registered: " + f.getName());
            }
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        net.sourceforge.plantuml.Run.main(rest);
    }
}
