package ai.proxyma.desktop.common;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The Proxyma mark for every AWT/Swing surface: the splash window, the system tray icon, and the
 * taskbar/Alt-Tab icon.
 *
 * IT READS THE SHIPPED ASSET. ONE FILE, {@code /static/proxyma.png}, AND NOTHING ELSE.
 *
 * This class used to carry the geometry itself - three outer arcs, three spokes, three gap arcs
 * and a disc, transcribed by hand from design/proxyma.svg, with a comment at the top asking
 * whoever changed the mark to remember to come back and update it. That instruction failed the
 * first time it was tested. The mark was given rounded corners and depth and had its spokes run
 * under the disc; twelve files were regenerated from tools/design/build-logo.py; and this one kept
 * the sharp flat original, because no script wrote it and nobody remembered. The splash screen -
 * the first thing a desktop user sees - showed the previous logo for a whole release while the
 * website, the favicons and the installer showed the new one.
 *
 * The fix is not a better reminder, and it is not generating this file either: a generated copy of
 * the geometry is still a copy, and the next surface that wants the mark would need its own. The
 * mark is an ASSET. Everything that draws it reads one of the three files the design pipeline
 * produces - the SVG on the web, the PNG here and on Linux, the ICO in the installer - so
 * regenerating those files is the whole of updating the logo, everywhere, with nothing to
 * remember.
 *
 * The tray and taskbar icons already worked this way, and that is exactly why they did NOT drift
 * while the splash beside them did.
 *
 * WHAT WAS GIVEN UP: resolution above 512px, and drawing anything when the asset is missing.
 * Neither costs something real. The splash draws the mark at 72 points and the largest icon
 * Windows asks for is 256, so a 512 source is downsampled in every case and never enlarged; and
 * the PNG is baked into the jar beside this class, so "missing" describes a jar that has already
 * failed in louder ways. When it is missing the splash simply shows its wordmark, which reads on
 * its own - the previous fallback drew a rounded square with a letter P in a colour from no
 * Proxyma palette, which is worse than nothing because it looks deliberate.
 */
public final class ProxymaMark {

    /** The one asset. Written by tools/design/rasterize-logo.py from design/proxyma.svg. */
    private static final String ASSET = "/static/proxyma.png";

    /** Sizes Windows picks between for the taskbar, the tray and Alt-Tab. */
    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    /**
     * Decoded once. The splash repaints on every progress tick and the tray asks again on a DPI
     * change; re-decoding a 512x512 PNG for each of those is work nobody asked for.
     *
     * {@code loaded} is what separates "not tried yet" from "tried, absent", so a missing asset is
     * not re-read on every frame. Neither field is volatile: a race decodes the same bytes twice
     * and both callers get an equivalent image, which is cheaper than coordinating.
     */
    private static BufferedImage source;
    private static boolean loaded;

    private ProxymaMark() {}

    // ---------------------------------------------------------------- painting

    /**
     * Paints the mark centred on {@code (cx, cy)} at {@code size} pixels across.
     * Leaves the supplied Graphics2D's rendering hints as it found them.
     */
    public static void paint(Graphics2D g2, double cx, double cy, double size) {
        BufferedImage src = source();
        if (src == null) return;

        Object savedInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Object savedRender = g2.getRenderingHint(RenderingHints.KEY_RENDERING);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int s = (int) Math.round(size);
        // Pre-reduced rather than left to drawImage's single filtering step - see scale().
        BufferedImage ready = (s > 0 && s * 2 <= src.getWidth()) ? scale(src, s) : src;
        g2.drawImage(ready, (int) Math.round(cx - size / 2), (int) Math.round(cy - size / 2), s, s, null);

        if (savedInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, savedInterp);
        if (savedRender != null) g2.setRenderingHint(RenderingHints.KEY_RENDERING, savedRender);
    }

    /** Renders the mark to a transparent square image of {@code size} pixels. */
    public static BufferedImage image(int size) {
        BufferedImage src = source();
        if (src == null) return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        return scale(src, size);
    }

    // ------------------------------------------------------------------- icons

    /** The window/taskbar/tray icon set, largest first. */
    public static List<Image> appIcons() {
        List<Image> icons = new ArrayList<>(ICON_SIZES.length);
        for (int i = ICON_SIZES.length - 1; i >= 0; i--) icons.add(image(ICON_SIZES[i]));
        return icons;
    }

    /** A single icon at the given size, for APIs that take one Image (e.g. TrayIcon). */
    public static Image icon(int size) {
        return image(size);
    }

    // ------------------------------------------------------------------ asset

    private static BufferedImage source() {
        if (!loaded) {
            loaded = true;
            try (var stream = ProxymaMark.class.getResourceAsStream(ASSET)) {
                // ImageIO.read, not Toolkit.createImage: the latter decodes asynchronously, so the
                // first consumer can hand AWT a still-empty image and get a blank tray slot.
                source = stream == null ? null : ImageIO.read(stream);
            } catch (Exception e) {
                source = null;
            }
        }
        return source;
    }

    /**
     * Downsamples in halves rather than in one jump.
     *
     * Java2D's bilinear filter samples four neighbouring pixels, so reducing 512 to 16 in a single
     * step reads 4 of the 1024 pixels each output pixel should represent and discards the rest -
     * a tray icon made of whichever thin arc happened to fall under a sample point, which differs
     * between sizes and reads as a different logo at each one. Halving repeatedly keeps every
     * pixel in the average, and costs a handful of small blits.
     */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage current = src;
        int w = src.getWidth();
        while (w / 2 > size) {
            w /= 2;
            current = blit(current, w);
        }
        return blit(current, size);
    }

    private static BufferedImage blit(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, 0, 0, size, size, null);
        g2.dispose();
        return out;
    }
}
