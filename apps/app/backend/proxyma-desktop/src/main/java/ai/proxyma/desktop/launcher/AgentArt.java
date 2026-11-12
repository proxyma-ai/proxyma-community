package ai.proxyma.desktop.launcher;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * The agent picture on the splash's team slide.
 *
 * <p>THE REAL ONE WHERE THE BUILD CARRIES IT (the maintainer, 2026-10-31: "include real picture into
 * the desktop editions"): {@code /splash/agent-robot.png} and its mask {@code agent-robot-mask.png},
 * which the packaging copies into the launcher jar from the application jar it packages (the pom's
 * launcher-splash-art step). The application carries them only where it has the picture, so a build
 * of this repository alone has none, and the splash draws a plain robot of its own instead.
 *
 * <p>Drawn as AgentPicture draws it on a card: cut to 64x88 from the middle (object-cover), the agent's
 * color MULTIPLIED over the ID card the mask marks, so the card keeps its shading.
 */
final class AgentArt {

    private AgentArt() {}

    private static final BufferedImage ROBOT = read("/splash/agent-robot.png");
    private static final BufferedImage MASK = read("/splash/agent-robot-mask.png");
    private static final Map<Integer, BufferedImage> TINTED = new ConcurrentHashMap<>();

    /** Whether this build carries the real picture. */
    static boolean real() {
        return ROBOT != null && MASK != null
                && MASK.getWidth() == ROBOT.getWidth() && MASK.getHeight() == ROBOT.getHeight();
    }

    static void paint(Graphics2D g, int x, int y, int w, int h, Color color) {
        if (!real()) {
            drawn(g, x, y, w, h, color);
            return;
        }
        BufferedImage img = TINTED.computeIfAbsent(color.getRGB(), c -> tint(ROBOT, MASK, color));
        // object-cover: scaled to fill the box, the overflow cut evenly from both sides.
        double scale = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight());
        double sw = w / scale, sh = h / scale;
        int sx = (int) Math.round((img.getWidth() - sw) / 2), sy = (int) Math.round((img.getHeight() - sh) / 2);
        Graphics2D r = (Graphics2D) g.create();
        r.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        r.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        r.drawImage(img, x, y, x + w, y + h, sx, sy, sx + (int) Math.round(sw), sy + (int) Math.round(sh), null);
        r.dispose();
    }

    /** The picture with {@code color} multiplied over what the mask marks, by the mask's alpha. */
    static BufferedImage tint(BufferedImage robot, BufferedImage mask, Color color) {
        int w = robot.getWidth(), h = robot.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        double cr = color.getRed() / 255.0, cg = color.getGreen() / 255.0, cb = color.getBlue() / 255.0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int p = robot.getRGB(px, py);
                double m = (mask.getRGB(px, py) >>> 24) / 255.0;
                int a = p >>> 24, r = (p >> 16) & 0xFF, gr = (p >> 8) & 0xFF, b = p & 0xFF;
                r = (int) Math.round(r * (1 - m + m * cr));
                gr = (int) Math.round(gr * (1 - m + m * cg));
                b = (int) Math.round(b * (1 - m + m * cb));
                out.setRGB(px, py, (a << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        return out;
    }

    private static BufferedImage read(String path) {
        try (InputStream in = AgentArt.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            // Unreadable art is no reason to fail a start: the drawn robot stands in.
            return null;
        }
    }

    /** A plain robot bust whose ID card wears the agent's color, for a build without the picture. */
    private static void drawn(Graphics2D g, int x, int y, int w, int h, Color color) {
        Color shell = new Color(0x4A, 0x4D, 0x52), body = new Color(0x3A, 0x3D, 0x42);
        Graphics2D r = (Graphics2D) g.create();
        r.setColor(shell);
        r.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        r.drawLine(x + w / 2, y + 2, x + w / 2, y + 10);
        r.fill(new Ellipse2D.Double(x + w / 2.0 - 3, y, 6, 6));
        r.fill(new RoundRectangle2D.Double(x + w * 0.16, y + 10, w * 0.68, h * 0.34, 14, 14));
        r.setColor(new Color(0x1F, 0x1F, 0x1F));
        r.fill(new RoundRectangle2D.Double(x + w * 0.24, y + 16, w * 0.52, h * 0.16, 8, 8));
        r.setColor(SplashWindow.BRAND_LEGIBLE);
        r.fill(new Ellipse2D.Double(x + w * 0.34, y + 20, 6, 6));
        r.fill(new Ellipse2D.Double(x + w * 0.56, y + 20, 6, 6));
        r.setColor(body);
        r.fill(new RoundRectangle2D.Double(x + w * 0.04, y + h * 0.5, w * 0.92, h * 0.5, 16, 16));
        r.setColor(color);
        r.fill(new RoundRectangle2D.Double(x + w * 0.3, y + h * 0.62, w * 0.4, h * 0.22, 4, 4));
        r.dispose();
    }
}
