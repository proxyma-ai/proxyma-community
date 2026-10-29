package ai.proxyma.desktop.system;

import ai.proxyma.core.system.parts.Platform;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The tray icon's right-click menu, drawn to look like the app's own small menus (the kit's
 * {@code MENU_PANEL} and {@code menuRow}) rather than handed to AWT's {@code PopupMenu}.
 *
 * <p>AWT's menu is the NATIVE one - on Windows a classic Win32 menu in the system font, with no
 * icons, no hover color of ours and no way to set either. Beside the app it read as a different
 * product (maintainer's request, 2026-09-29). This is a small undecorated window instead, with the
 * same metrics as the web menu: a 224px panel, 8px corners, 36px rows padded 16px, a 15px icon
 * 10px from 14px text, hairline rules between groups, the danger tone on Quit.
 *
 * <p>It follows the OPERATING SYSTEM's light or dark setting, not the app's Appearance, which lives
 * in the window's own storage where this process cannot read it. A tray menu is an OS surface, and
 * matching the taskbar it opens from is the smaller surprise. Dark is the splash's own dark gray.
 */
final class TrayMenu {

    private static final Logger log = LoggerFactory.getLogger(TrayMenu.class);

    enum Glyph { WINDOW, GLOBE, POWER }

    record Item(String label, Glyph glyph, boolean danger, Runnable action) {}

    // The web menu's metrics, in CSS pixels - Swing's logical pixels scale with the display the
    // same way the browser's do.
    private static final int PANEL_W = 224;     // w-56 - not WIDTH, which inside a JPanel is ImageObserver.WIDTH (1)
    private static final int ROW_H = 36;        // py-2 around a 20px line
    private static final int PAD_X = 16;        // px-4
    private static final int GAP = 10;          // gap-2.5
    private static final int ICON = 15;
    private static final float TEXT = 14f;      // text-sm
    private static final int RADIUS = 8;        // rounded-lg

    /** The colors this menu uses, per mode - mirroring proxyma-tokens.css's Dark and Light as of 2026-10-28. */
    private record Palette(Color fill, Color line, Color ink, Color inkHover, Color hover, Color danger, Color dangerHover) {
        static Palette current() {
            if (lightTheme()) {
                Color fill = new Color(255, 255, 255);
                return new Palette(fill, blend(new Color(228, 228, 231), fill, 0.45f),
                        new Color(63, 63, 70), new Color(24, 24, 27),
                        blend(new Color(90, 90, 90), fill, 0.10f),
                        new Color(185, 28, 28), blend(new Color(185, 28, 28), fill, 0.10f));
            }
            Color fill = new Color(28, 28, 30);   // Dark's surface plane as of 2026-10-28
            return new Palette(fill, blend(new Color(46, 46, 50), fill, 0.45f),
                    new Color(212, 212, 216), new Color(244, 244, 245),
                    blend(new Color(161, 161, 170), fill, 0.10f),
                    new Color(248, 113, 113), blend(new Color(248, 113, 113), fill, 0.10f));
        }
    }

    private static final Font UI_FONT = loadUiFont();

    private static JDialog open;

    private TrayMenu() {}

    /** Shows the menu at a screen point - the pointer, when the tray icon was right-clicked. */
    static void show(Point at, List<List<Item>> groups) {
        SwingUtilities.invokeLater(() -> {
            try {
                close();
                open = build(at, groups);
            } catch (Throwable e) {
                // A menu that cannot be drawn must not take the tray icon with it; double-click
                // still opens the window.
                log.warn("Could not show the tray menu: {}", e.getMessage());
            }
        });
    }

    private static void close() {
        if (open != null) {
            open.dispose();
            open = null;
        }
    }

    private static JDialog build(Point at, List<List<Item>> groups) {
        Palette palette = Palette.current();
        JDialog dialog = new JDialog();
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);
        dialog.setFocusableWindowState(true);

        MenuPanel panel = new MenuPanel(groups, palette);
        dialog.setContentPane(panel);
        Dimension size = panel.getPreferredSize();
        dialog.setSize(size);
        dialog.setLocation(place(at, size));

        // Dismissed the way a menu is: a click anywhere else takes focus away, and Escape.
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) {
                if (open == dialog) close();
            }
        });
        if (!roundWithDwm(dialog, palette)) {
            if (dialog.getGraphicsConfiguration().getDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
                dialog.setShape(new RoundRectangle2D.Double(0, 0, size.width, size.height, RADIUS * 2, RADIUS * 2));
            }
        }
        dialog.setVisible(true);
        dialog.toFront();
        panel.requestFocusInWindow();
        return dialog;
    }

    /**
     * Above and to the right of the pointer, the way the taskbar's own menus open, flipped to
     * whichever side has room and kept inside the screen's work area (never under the taskbar).
     */
    private static Point place(Point at, Dimension size) {
        Rectangle area = workArea(at);
        int x = at.x;
        int y = at.y - size.height;
        if (x + size.width > area.x + area.width) x = at.x - size.width;
        if (y < area.y) y = at.y;
        x = Math.max(area.x, Math.min(x, area.x + area.width - size.width));
        y = Math.max(area.y, Math.min(y, area.y + area.height - size.height));
        return new Point(x, y);
    }

    private static Rectangle workArea(Point at) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            if (bounds.contains(at)) {
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                        bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    private static final class MenuPanel extends JPanel {
        private final List<List<Item>> groups;
        private final List<Item> flat = new ArrayList<>();
        private final Palette palette;
        private int hover = -1;

        MenuPanel(List<List<Item>> groups, Palette palette) {
            this.groups = groups;
            this.palette = palette;
            groups.forEach(flat::addAll);
            setOpaque(true);
            setFocusable(true);
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) { setHover(rowAt(e.getY())); }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) { setHover(-1); }
                @Override public void mouseReleased(MouseEvent e) { activate(rowAt(e.getY())); }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> close();
                        case KeyEvent.VK_DOWN -> setHover(hover < 0 ? 0 : (hover + 1) % flat.size());
                        case KeyEvent.VK_UP -> setHover(hover <= 0 ? flat.size() - 1 : hover - 1);
                        case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> activate(hover);
                        default -> { }
                    }
                }
            });
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(PANEL_W, flat.size() * ROW_H + Math.max(0, groups.size() - 1) + 2);
        }

        private void setHover(int row) {
            if (row != hover) {
                hover = row;
                repaint();
            }
        }

        private void activate(int row) {
            if (row < 0 || row >= flat.size()) return;
            Runnable action = flat.get(row).action();
            close();
            // Off the event thread: opening a window starts a process, and quitting exits.
            Thread.ofVirtual().name("tray-action").start(action);
        }

        /** The row under a y coordinate, allowing for the 1px rules between groups. */
        private int rowAt(int y) {
            int top = 1;
            int index = 0;
            for (int g = 0; g < groups.size(); g++) {
                if (g > 0) top += 1;
                for (int i = 0; i < groups.get(g).size(); i++) {
                    if (y >= top && y < top + ROW_H) return index;
                    top += ROW_H;
                    index++;
                }
            }
            return -1;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(palette.fill());
            g2.fillRect(0, 0, w, h);

            Font font = UI_FONT.deriveFont(Font.PLAIN, TEXT);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int top = 1;
            int index = 0;
            for (int gi = 0; gi < groups.size(); gi++) {
                if (gi > 0) {
                    g2.setColor(palette.line());
                    g2.fillRect(0, top, w, 1);
                    top += 1;
                }
                for (Item item : groups.get(gi)) {
                    boolean hot = index == hover;
                    if (hot) {
                        g2.setColor(item.danger() ? palette.dangerHover() : palette.hover());
                        g2.fillRect(1, top, w - 2, ROW_H);
                    }
                    Color ink = item.danger() ? palette.danger() : hot ? palette.inkHover() : palette.ink();
                    int iconY = top + (ROW_H - ICON) / 2;
                    drawGlyph(g2, item.glyph(), PAD_X, iconY, ink);
                    g2.setColor(ink);
                    int baseline = top + (ROW_H - fm.getHeight()) / 2 + fm.getAscent();
                    drawRegular(g2, item.label(), PAD_X + ICON + GAP, baseline);
                    top += ROW_H;
                    index++;
                }
            }
            // The panel's own hairline, drawn last so a hovered row never covers it. Where DWM
            // rounds the corners it also draws its border in this color, so the two meet.
            g2.setColor(palette.line());
            g2.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, RADIUS * 2, RADIUS * 2));
            g2.dispose();
        }
    }

    /**
     * Lucide's app-window, globe and power, the icons the web menus use, drawn from their 24-unit
     * geometry at 15px with Lucide's stroke (2 units, round caps and joins).
     */
    private static void drawGlyph(Graphics2D g, Glyph glyph, int x, int y, Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(x, y);
        g2.scale(ICON / 24.0, ICON / 24.0);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (glyph) {
            case WINDOW -> {
                g2.draw(new RoundRectangle2D.Double(2, 4, 20, 16, 4, 4));
                g2.draw(new Line2D.Double(10, 4, 10, 8));
                g2.draw(new Line2D.Double(2, 8, 22, 8));
                g2.draw(new Line2D.Double(6, 4, 6, 8));
            }
            case GLOBE -> {
                g2.draw(new Ellipse2D.Double(2, 2, 20, 20));
                g2.draw(new Arc2D.Double(22.5 - 14.5, 12 - 14.5, 29, 29, 136.4, 87.2, Arc2D.OPEN));
                g2.draw(new Arc2D.Double(1.5 - 14.5, 12 - 14.5, 29, 29, -43.6, 87.2, Arc2D.OPEN));
                g2.draw(new Line2D.Double(2, 12, 22, 12));
            }
            case POWER -> {
                g2.draw(new Line2D.Double(12, 2, 12, 12));
                g2.draw(new Arc2D.Double(3, 4, 18, 18, 45, -270, Arc2D.OPEN));
            }
        }
        g2.dispose();
    }

    /**
     * Text at the app's weight, 400. The shipped Space Grotesk is the VARIABLE font, whose default
     * instance is 300, and Java cannot select a point on its weight axis - so the outline is filled
     * and then stroked by a fraction of a pixel, which is the difference between the two. Falling
     * back to a sans-serif, the stroke is skipped.
     */
    private static void drawRegular(Graphics2D g2, String text, int x, int y) {
        if (UI_FONT.getFamily().equals(Font.SANS_SERIF)) {
            g2.drawString(text, x, y);
            return;
        }
        Shape outline = g2.getFont().createGlyphVector(g2.getFontRenderContext(), text).getOutline(x, y);
        g2.fill(outline);
        Stroke previous = g2.getStroke();
        g2.setStroke(new BasicStroke(0.45f));
        g2.draw(outline);
        g2.setStroke(previous);
    }

    private static Color blend(Color top, Color under, float alpha) {
        return new Color(
                Math.round(top.getRed() * alpha + under.getRed() * (1 - alpha)),
                Math.round(top.getGreen() * alpha + under.getGreen() * (1 - alpha)),
                Math.round(top.getBlue() * alpha + under.getBlue() * (1 - alpha)));
    }

    private static boolean isWindows() {
        return Platform.windows();
    }

    /** Windows' "app mode" - the same switch that turns the taskbar's own menus dark. */
    private static boolean lightTheme() {
        if (!isWindows()) return false;
        try {
            return Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "AppsUseLightTheme") != 0;
        } catch (Throwable e) {
            return true;   // Windows' own default when the value was never written
        }
    }

    /**
     * Windows 11 rounds the window itself and gives it the system's menu shadow and border, drawn
     * by the compositor and so anti-aliased - a Swing shape clips on whole pixels and has no shadow.
     * False anywhere that does not apply (Windows 10 accepts the call and ignores it, which is
     * square corners and a hairline: acceptable), and the caller then falls back to a shape.
     */
    private static boolean roundWithDwm(JDialog dialog, Palette palette) {
        if (!isWindows()) return false;
        try {
            dialog.addNotify();
            Pointer hwnd = Native.getWindowPointer(dialog);
            if (hwnd == null) return false;
            int round = Dwm.DWMWCP_ROUNDSMALL;
            int corner = Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, Dwm.DWMWA_WINDOW_CORNER_PREFERENCE,
                    new IntByReference(round), 4);
            Color line = palette.line();
            // COLORREF is 0x00BBGGRR.
            int colorRef = (line.getBlue() << 16) | (line.getGreen() << 8) | line.getRed();
            Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, Dwm.DWMWA_BORDER_COLOR, new IntByReference(colorRef), 4);
            return corner == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);
        int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
        int DWMWA_BORDER_COLOR = 34;
        int DWMWCP_ROUNDSMALL = 3;
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, IntByReference value, int size);
    }

    /** Space Grotesk, the app's UI face, shipped in the jar beside the splash's Orbitron. */
    private static Font loadUiFont() {
        try (InputStream in = TrayMenu.class.getResourceAsStream("/fonts/SpaceGrotesk.ttf")) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            log.debug("Could not load the UI font: {}", e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    }
}
