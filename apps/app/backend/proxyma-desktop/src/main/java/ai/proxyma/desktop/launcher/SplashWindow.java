package ai.proxyma.desktop.launcher;

import ai.proxyma.desktop.common.ProxymaMark;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

// Splash screen shown by ProxymaLauncher during startup. Pure Swing, no Spring dependency,
// created on the AWT event thread.
//
// Redesigned 2026-08-01 to match the web UI's design system rather than diverge from it. Every
// colour below is a token from frontend/src/index.css (dark mode), the corner radius
// comes from the same tightened radius scale the app uses, and the progress bar is the Swing
// equivalent of the app's own indeterminate bar. What it replaced was built on the pre-rebuild
// palette (cyan #22D3EE / #06B6D4 / #A5F3FC on a near-black #040D14) and carried two ornaments
// the app no longer has anywhere: an orbiting glow arc around the logo, and logo segments that
// blinked amber while prerequisites installed. A logo that flickers reads as a fault, and the
// progress it was encoding is what the status line and the bar say plainly.
//
// THE MARK TURNS, since 2026-09-27 (the maintainer's request): slowly and steadily, at the header
// logo's own idle speed, so the window reads as alive through a Python install that can take
// minutes. Motion, not a signal - it carries no progress and does not speed up. It stops on an
// error, where a turning mark would read as work still going on.
//
// THE BAR IS THE APP'S WAVE BAR, also since 2026-09-27: the Updates page's download bar
// (`.progress-wave` in index.css), not a segment sweeping an empty track. Brand-accent fill with a
// light band travelling along it when the size is known; a dim full-width track with the band
// travelling along it when it is not - the Python install, and everything around it. The band's
// pace is the one motion here that carries information: it tracks how far along startup is.
//
// State transitions (called from the launcher thread, all fields volatile):
//   create()               -> IDLE    indeterminate wave, slow
//   setCheckingPrereqs()   -> PREREQ  same, with its own status line
//   setPrereqsReady()      -> READY   same
//   setDownloading()       -> DOWNLOAD bar fills to the fraction downloaded, the wave rides the
//                                     fill - the one phase whose length is known
//   setChromaStarting()    -> CHROMA  wave accelerates to full pace over 1.2s
//   setChromaRunning()     -> APP     full pace
//   setAppRunning()        -> DONE    bar fills solid, 1.2s, then auto-close
//   setError()             -> ERROR   bar goes solid danger, 5s, then auto-close
// A minute after the window opened, any phase but DOWNLOAD, DONE and ERROR adds SLOW_HINT under
// the bar.
class SplashWindow extends JFrame implements Splash {

    // -- tokens, mirroring the Dark appearance of proxyma-tokens.css (neutral since 2026-10-30) ---
    // The fill is the app window's own dark gray (--field-base, desktop-shell's backgroundColor), so
    // the splash and the window it hands over to are one color - no teal tint in either.
    private static final Color FILL         = new Color(0x10, 0x10, 0x11); // --field-base   #101011
    private static final Color LINE         = new Color(0x2A, 0x2A, 0x2D); // --line         #2a2a2d
    private static final Color INK_PRIMARY  = new Color(0xF5, 0xF6, 0xF7); // --ink-primary  #f5f6f7
    private static final Color INK_CAPTION  = new Color(0xA0, 0xA1, 0xA4); // --ink-caption  #a0a1a4
    private static final Color INK_DISABLED = new Color(0x70, 0x71, 0x74); // --ink-disabled #707174
    private static final Color BRAND_LEGIBLE= new Color(0xA6, 0xD6, 0xE7); // --brand-legible p300
    private static final Color BRAND_ACCENT = new Color(0x78, 0xBE, 0xD9); // --brand-accent   p400
    private static final Color DANGER       = new Color(0xF8, 0x71, 0x71); // --danger

    // -- geometry -------------------------------------------------------------
    private static final int W = 440;
    private static final int H = 260;
    private static final int RADIUS   = 14;  // the app's largest surface radius (rounded-3xl)
    private static final int LOGO     = 72;
    private static final int LOGO_CY  = 82;
    private static final float WORDMARK_SIZE = 19f;
    private static final int BAR_W    = 200;
    // 4, not 3: the wave is a lighter band INSIDE the bar, and at 3px it was a flicker nobody saw.
    private static final int BAR_H    = 4;
    private static final int BAR_Y    = 214;
    // The line under the bar that says a start is taking longer than usual - see SLOW_AFTER_MS.
    private static final int HINT_BASELINE = 242;
    // The only region that changes frame to frame: the status line's ascent/descent around its
    // baseline at y=190, down past the bar and the hint under it. Anything drawn outside it is
    // painted once.
    private static final int ANIM_BAND_Y = 168;
    private static final int ANIM_BAND_H = H - 4 - ANIM_BAND_Y;

    // A START THAT IS TAKING LONGER THAN USUAL SAYS SO, after a minute (maintainer's request,
    // 2026-10-10). A start after an update rebuilds the Python runtime first, and a slow machine
    // with a busy antivirus can take minutes over a healthy start - a minute of the same status
    // line read as stuck. The app's own restart screen (RestartingModal) shows the same sentence
    // at the same moment. Not while downloading, where the bar and its megabytes say how far along
    // it is; not on an error. The text is English, like every other line this window draws: the
    // launcher starts before the application and its saved language.
    static final long SLOW_AFTER_MS = 60_000;
    static final String SLOW_HINT = "This is taking a little longer than usual. Please wait.";
    // The hint fades in over the app's --motion-slow.
    private static final double HINT_FADE_MS = 300.0;
    private final long createdAt = System.currentTimeMillis();
    private volatile double hintAlpha = 0.0;

    // -- animation ------------------------------------------------------------
    // One pass of the wave, as `.progress-wave` runs it: 1.8s, ease-in-out, entering one bar-width
    // before the bar and leaving two after it. The indeterminate phases scale that pace by
    // sweepRate; the download runs it at full pace so a stalled download still visibly lives.
    private static final double WAVE_MS = 1_800.0;
    // The header logo's idle speed (Topbar.tsx, IDLE_DEG_PER_S).
    private static final double MARK_DEG_PER_MS = 30.0 / 1_000.0;
    // The box the turning mark is repainted in. The mark is round, so turning never leaves it.
    private static final Rectangle MARK_BOX =
            new Rectangle(W / 2 - LOGO / 2 - 2, LOGO_CY - LOGO / 2 - 2, LOGO + 4, LOGO + 4);

    private volatile double  sweepPos  = 0.0;  // 0..1, one pass of the wave
    private volatile double  sweepRate = 0.45; // 0..1, scales the wave's pace
    private volatile double  markDeg   = 0.0;
    private volatile double  solidFill = 0.0;  // 0..1, used by DONE/ERROR instead of the sweep
    private volatile String  statusText = "Starting";
    private volatile boolean isError = false;
    private volatile double  downloadFraction = 0.0;

    private enum Phase { IDLE, PREREQ, DOWNLOAD, READY, CHROMA, APP, DONE, ERROR }
    private volatile Phase phase = Phase.IDLE;
    private volatile long  phaseEnteredAt = System.currentTimeMillis();

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private Timer animTimer;
    private long  lastTick = System.currentTimeMillis();

    // -- brand face ----------------------------------------------------------

    // Orbitron, the face the web header draws the wordmark in (--font-brand, frontend
    // proxyma-tokens.css). It is not installed system-wide on any of the three platforms, so the
    // splash used to draw the wordmark in the platform sans - Segoe UI on Windows - and the first
    // thing a user saw of the product was its name in a different typeface from the one it uses
    // everywhere afterwards. The TTF now ships in the jar (src/main/resources/fonts, SIL Open Font
    // License, OFL.txt beside it) and is loaded from the classpath, which needs no installation and
    // no GraphicsEnvironment registration - deriveFont is enough to draw with.
    //
    // BOLD IS SYNTHESISED. The workspace carries only Orbitron Regular (400); the header uses 700.
    // Java2D thickens the real Orbitron letterforms rather than substituting another face, which is
    // the part that matters here - but it is not the 700 cut, and shipping Orbitron-Bold.ttf beside
    // this one would make it exact.
    //
    // Loaded ONCE, statically. createFont parses the file on every call, and this is on the path
    // that has to put a window on screen before anything else happens.
    private static final Font BRAND_FONT = loadBrandFont();

    private static Font loadBrandFont() {
        try (InputStream in = SplashWindow.class.getResourceAsStream("/fonts/Orbitron.ttf")) {
            if (in == null) {
                LoggerFactory.getLogger(SplashWindow.class)
                        .warn("Brand font missing from the jar - the splash wordmark will use the platform sans");
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            // A font that will not load is not a reason to fail startup before anything has been
            // said to the user - the same rule create() applies to the whole window.
            LoggerFactory.getLogger(SplashWindow.class)
                    .warn("Could not load the brand font ({}) - falling back to the platform sans", e.getMessage());
            return null;
        }
    }

    private static Font brandFont(float size) {
        return BRAND_FONT != null
                ? BRAND_FONT.deriveFont(Font.BOLD, size)
                : new Font(Font.SANS_SERIF, Font.BOLD, (int) size);
    }

    // -- factory --------------------------------------------------------------
    // Never throws. A desktop build wants a real window, but a display that is missing or refuses a
    // window is not a reason to abort startup before anything has been said - it degrades to
    // Splash.NONE, which logs the same sequence. The HeadlessException this used to propagate
    // surfaced as an unexplained stack trace with no window and no message.
    static Splash create() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            LoggerFactory.getLogger(SplashWindow.class)
                    .info("No display available - starting without a splash window");
            return Splash.NONE;
        }
        SplashWindow[] ref = new SplashWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                SplashWindow w = new SplashWindow();
                w.setVisible(true);
                ref[0] = w;
            });
        } catch (Exception e) {
            LoggerFactory.getLogger(SplashWindow.class)
                    .warn("Could not show the splash window ({}) - continuing without it", e.getMessage());
            return Splash.NONE;
        }
        return ref[0];
    }

    private SplashWindow() {
        super("Proxyma");
        setUndecorated(true);
        setSize(W, H);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        // The splash is the only window this process ever shows, so its icon IS the taskbar icon
        // during startup. Without this it was Java's default coffee cup. setIconImages (plural)
        // hands Windows the whole size set so it picks a crisp one per surface rather than
        // downscaling a single large bitmap. Taskbar.setIconImage is the macOS/Linux dock
        // equivalent and is a no-op where unsupported - guarded, since it throws if it is not.
        setIconImages(ProxymaMark.appIcons());
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(ProxymaMark.icon(256));
                }
            }
        } catch (Exception ignored) {
            // Headless, or a desktop that does not expose a dock icon - nothing to fall back to.
        }
        centerOnMouseScreen();
        // Rounded corners via the SHAPE only, and only where the device advertises it.
        //
        // This used to also call setBackground(new Color(0,0,0,0)), which puts the window on the
        // PERPIXEL_TRANSLUCENT path - an ARGB visual that a compositing manager blends. X11 sessions
        // without a compositor (a stock VirtualBox Ubuntu guest on llvmpipe is exactly one) have
        // nothing to blend it with, so every frame cleared the window to transparent and repainted,
        // which is what the splash was doing 60 times a second: it FLICKERED. The alpha bought
        // nothing anyway - paintComponent fills the whole surface with FILL, and the corners are
        // the shape's job. PERPIXEL_TRANSPARENT is the X11 shape extension, which needs no
        // compositor and is why this one is kept. Unsupported means square corners, not a fault.
        if (shapedWindowsSupported()) {
            setShape(new RoundRectangle2D.Double(0, 0, W, H, RADIUS * 2, RADIUS * 2));
        }

        setContentPane(new SplashPanel(this::minimize));

        animTimer = new Timer(16, e -> tick());
        animTimer.start();
    }

    // -- state transitions ----------------------------------------------------

    @Override public void setCheckingPrereqs()              { enter(Phase.PREREQ, "Checking prerequisites"); }
    @Override public void setInstallingPrereqs(String what) { enter(Phase.PREREQ, "Installing " + what); }
    @Override public void setPrereqsReady()                 { enter(Phase.READY,  "Prerequisites ready"); }
    @Override public void setChromaStarting()               { enter(Phase.CHROMA, "Starting"); }
    @Override public void setChromaRunning()                { enter(Phase.APP,    "Starting"); }

    @Override
    public void setAppRunning() {
        enter(Phase.DONE, "Ready");
        closeAfter(1_200);
    }

    @Override public void setStatus(String s) { statusText = s; }

    @Override
    public void setDownloading(String status, double fraction) {
        downloadFraction = Math.max(0.0, Math.min(1.0, fraction));
        if (phase != Phase.DOWNLOAD) enter(Phase.DOWNLOAD, status);
        else statusText = status;
    }

    @Override
    public void setError(String message) {
        isError = true;
        enter(Phase.ERROR, message);
        // Long enough to read a sentence about a failed download; the launcher decides when the
        // process actually ends.
        closeAfter(12_000);
    }

    private void enter(Phase next, String status) {
        phase = next;
        statusText = status;
        phaseEnteredAt = System.currentTimeMillis();
    }

    private void closeAfter(long ms) {
        new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
            dispose();
        }, "splash-close-timer").start();
    }

    // -- animation ------------------------------------------------------------
    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTick;
        lastTick = now;
        long inPhase = now - phaseEnteredAt;

        switch (phase) {
            // Prerequisite work is genuinely open-ended (a Python download can take minutes), so
            // the bar stays deliberately unhurried here rather than implying imminent completion.
            case IDLE, PREREQ, READY -> sweepRate = 0.45;
            // From here on the remaining work is bounded and short, so the sweep speeds up - the
            // one piece of motion in this window that actually carries information.
            // Eased toward the real fraction so 64 KB steps read as motion, not as a stutter.
            case DOWNLOAD -> solidFill = Math.max(0.001, solidFill + (downloadFraction - solidFill) * 0.2);
            case CHROMA -> sweepRate = 0.45 + 0.55 * Math.min(1.0, inPhase / 1_200.0);
            case APP    -> sweepRate = 1.0;
            case DONE   -> solidFill = Math.min(1.0, inPhase / 300.0);
            case ERROR  -> solidFill = 1.0;
        }

        if (phase == Phase.DOWNLOAD) {
            // The wave across the filled part: a fixed pace, so a stalled download still visibly
            // lives while a moving one is read off the fill itself.
            sweepPos = (sweepPos + elapsed / WAVE_MS) % 1.0;
        } else if (phase != Phase.DONE && phase != Phase.ERROR) {
            sweepPos = (sweepPos + elapsed / WAVE_MS * sweepRate) % 1.0;
        }
        if (phase != Phase.ERROR) markDeg = (markDeg + elapsed * MARK_DEG_PER_MS) % 360.0;
        long slowFor = now - createdAt - SLOW_AFTER_MS;
        boolean hintAllowed = phase != Phase.DOWNLOAD && phase != Phase.DONE && phase != Phase.ERROR;
        hintAlpha = hintAllowed && slowFor > 0 ? Math.min(1.0, slowFor / HINT_FADE_MS) : 0.0;

        // Only two regions change between frames: the turning mark, and the band holding the status
        // line (baseline 190) and the bar (BAR_Y). Repainting all 440x260 sixty times a second
        // redrew everything else to an identical result, which on software rendering is slow
        // enough to be visible as flicker. Full width for the band, so a shorter status string
        // cannot leave the tail of a longer one behind it.
        repaint(MARK_BOX.x, MARK_BOX.y, MARK_BOX.width, MARK_BOX.height);
        repaint(0, ANIM_BAND_Y, W, ANIM_BAND_H);
    }

    // Whether this device can clip a window to a shape. Defensive rather than trusting the call:
    // this runs before anything has been said to the user, so a splash that cannot round its
    // corners has to degrade to square ones, never to an exception out of the constructor.
    private boolean shapedWindowsSupported() {
        try {
            GraphicsConfiguration gc = getGraphicsConfiguration();
            GraphicsDevice device = gc != null
                    ? gc.getDevice()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return device.isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT);
        } catch (Exception e) {
            return false;
        }
    }

    private void minimize() { setState(JFrame.ICONIFIED); }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                if (animTimer != null) animTimer.stop();
                super.dispose();
            });
        }
    }

    private void centerOnMouseScreen() {
        Point mouse = MouseInfo.getPointerInfo().getLocation();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = gd.getDefaultConfiguration().getBounds();
            if (bounds.contains(mouse)) {
                setLocation(bounds.x + (bounds.width - W) / 2, bounds.y + (bounds.height - H) / 2);
                return;
            }
        }
        setLocationRelativeTo(null);
    }

    // -- painting -------------------------------------------------------------
    private class SplashPanel extends JPanel {

        private final Rectangle minBtn = new Rectangle(W - 34, 12, 22, 22);
        private final Runnable minimizeAction;
        private boolean minHover;

        SplashPanel(Runnable minimizeAction) {
            this.minimizeAction = minimizeAction;
            // Opaque, so Swing's repaint is a plain blit into this panel's own double buffer rather
            // than a clear-and-recomposite of a transparent hierarchy against the window behind it.
            // paintComponent covers every pixel with FILL regardless; matching the panel
            // background to it means the pre-fill and the paint are the same colour, so a partial
            // repaint can never show a seam.
            setOpaque(true);
            setBackground(FILL);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (minBtn.contains(e.getPoint())) minimizeAction.run();
                }
                @Override public void mouseExited(MouseEvent e) { minHover = false; repaint(); }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    boolean over = minBtn.contains(e.getPoint());
                    if (over != minHover) { minHover = over; repaint(); }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);

            int arc = RADIUS * 2;
            g2.setColor(FILL);
            g2.fillRoundRect(0, 0, W, H, arc, arc);

            // No brand glow behind the logo any more - there is no glow anywhere in the product
            // (.claude/rules/frontend.md), and this was the last one.

            // 1px hairline, the app's --line on --canvas. The window is shaped, so this doubles
            // as the only thing separating it from whatever is behind it.
            g2.setColor(LINE);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, W - 1, H - 1, arc, arc);

            paintMark(g2);

            // Wordmark, in the app's own brand face - see brandFont(). Tracking is the header's
            // `tracking-wider` (0.05em) rather than the 3px this carried while it was drawn in the
            // platform sans: that figure existed to loosen a face which is not Orbitron, and
            // Orbitron is already a wide one. Applying both would have made the splash wordmark
            // visibly looser than the header it is meant to match.
            drawTracked(g2, "Proxyma", brandFont(WORDMARK_SIZE), WORDMARK_SIZE * 0.05f,
                        BRAND_LEGIBLE, 160);

            // The status line is a PHASE, not a sentence: "Starting", "Installing Python
            // packages" - small, and the same line the web restart screen (RestartingModal) shows.
            // SENTENCE CASE since 2026-10-01 (maintainer's feedback); it was upper case and
            // tracked, which shouted on a screen whose only job is to say "wait". An ERROR is a
            // full sentence, because it is something the user has to read and act on.
            if (isError) {
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g2.setColor(DANGER);
                FontMetrics fm = g2.getFontMetrics();
                // Two lines at most: an error the user has to act on is a sentence, and cutting it
                // at 440px left the part that said what to do. Anything longer still ends in "...".
                int maxW = W - 48;
                String[] lines = wrap(statusText, fm, maxW);
                if (lines.length == 1) {
                    g2.drawString(lines[0], (W - fm.stringWidth(lines[0])) / 2, 190);
                } else {
                    g2.drawString(lines[0], (W - fm.stringWidth(lines[0])) / 2, 182);
                    g2.drawString(lines[1], (W - fm.stringWidth(lines[1])) / 2, 198);
                }
            } else {
                drawTracked(g2, statusText, new Font(Font.SANS_SERIF, Font.PLAIN, 12), 0f, INK_CAPTION, 190);
            }

            drawProgressBar(g2);
            double hint = hintAlpha;
            if (hint > 0 && !isError) {
                drawTracked(g2, SLOW_HINT, new Font(Font.SANS_SERIF, Font.PLAIN, 12), 0f,
                        withAlpha(INK_CAPTION, (int) Math.round(255 * hint)), HINT_BASELINE);
            }
            drawMinimizeButton(g2);

            g2.dispose();
        }

        // The mark, turned by markDeg. Rendered ONCE at the screen's real pixel size and rotated from
        // that cache every frame: ProxymaMark.paint downsamples the 512px asset on every call,
        // which was fine for a mark painted once and is not for one painted sixty times a second.
        private BufferedImage mark;
        private double markScale;

        private void paintMark(Graphics2D g2) {
            double scale = Math.max(1.0, g2.getTransform().getScaleX());
            if (mark == null || scale != markScale) {
                mark = ProxymaMark.image((int) Math.round(LOGO * scale));
                markScale = scale;
            }
            Graphics2D r = (Graphics2D) g2.create();
            r.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            r.rotate(Math.toRadians(markDeg), W / 2.0, LOGO_CY);
            r.drawImage(mark, W / 2 - LOGO / 2, LOGO_CY - LOGO / 2, LOGO, LOGO, null);
            r.dispose();
        }

        private String[] wrap(String text, FontMetrics fm, int maxW) {
            if (fm.stringWidth(text) <= maxW) return new String[] {text};
            int cut = text.length();
            while (cut > 0) {
                cut = text.lastIndexOf(' ', cut - 1);
                if (cut <= 0) break;
                if (fm.stringWidth(text.substring(0, cut)) <= maxW) {
                    return new String[] {text.substring(0, cut), ellipsize(text.substring(cut + 1), fm, maxW)};
                }
            }
            return new String[] {ellipsize(text, fm, maxW)};
        }

        private String ellipsize(String text, FontMetrics fm, int maxW) {
            if (fm.stringWidth(text) <= maxW) return text;
            while (text.length() > 1 && fm.stringWidth(text + "...") > maxW) text = text.substring(0, text.length() - 1);
            return text + "...";
        }

        private void drawProgressBar(Graphics2D g2) {
            int x = (W - BAR_W) / 2;

            g2.setColor(LINE);
            g2.fillRoundRect(x, BAR_Y, BAR_W, BAR_H, BAR_H, BAR_H);

            Shape saved = g2.getClip();
            g2.setClip(new RoundRectangle2D.Double(x, BAR_Y, BAR_W, BAR_H, BAR_H, BAR_H));

            if (phase == Phase.DONE || phase == Phase.ERROR) {
                g2.setColor(isError ? DANGER : BRAND_ACCENT);
                g2.fillRect(x, BAR_Y, (int) (BAR_W * solidFill), BAR_H);
            } else if (phase == Phase.DOWNLOAD) {
                // The Updates page's known-size bar: an accent fill with the wave riding it.
                int fillW = Math.max(1, (int) (BAR_W * solidFill));
                g2.setColor(BRAND_ACCENT);
                g2.fillRect(x, BAR_Y, fillW, BAR_H);
                drawWave(g2, x, fillW, new Color(255, 255, 255, 150));
            } else {
                // Its unknown-size bar: the whole track dimly filled, the wave travelling along it.
                g2.setColor(withAlpha(BRAND_ACCENT, 77));
                g2.fillRect(x, BAR_Y, BAR_W, BAR_H);
                drawWave(g2, x, BAR_W, BRAND_LEGIBLE);
            }

            g2.setClip(saved);
        }

        // `.progress-wave::after`: a band as wide as what it rides, clear at both edges and `peak`
        // in the middle, moving from one width before it to two after it, eased in and out.
        private void drawWave(Graphics2D g2, int x, int width, Color peak) {
            if (width < 4) return;
            double t = sweepPos;
            double eased = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            int waveX = x - width + (int) Math.round(3 * width * eased);
            Shape before = g2.getClip();
            g2.clipRect(x, BAR_Y, width, BAR_H);
            g2.setPaint(new GradientPaint(waveX, 0, withAlpha(peak, 0), waveX + width / 2f, 0, peak, true));
            g2.fillRect(waveX, BAR_Y, width, BAR_H);
            g2.setPaint(null);
            g2.setClip(before);
        }

        private static Color withAlpha(Color c, int alpha) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
        }

        private void drawMinimizeButton(Graphics2D g2) {
            if (minHover) {
                // The app's own icon-button hover: ink-caption at 10%, on a ROUNDED SQUARE (6px, the
                // app's rounded-md), not a circle - every other icon button in the product is a
                // rounded square (maintainer's feedback, 2026-10-01).
                g2.setColor(new Color(INK_CAPTION.getRed(), INK_CAPTION.getGreen(), INK_CAPTION.getBlue(), 26));
                g2.fillRoundRect(minBtn.x, minBtn.y, minBtn.width, minBtn.height, 12, 12);
            }
            g2.setColor(minHover ? INK_PRIMARY : INK_DISABLED);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int my = minBtn.y + minBtn.height / 2;
            g2.drawLine(minBtn.x + 6, my, minBtn.x + minBtn.width - 6, my);
            g2.setStroke(new BasicStroke(1f));
        }

        // Java2D has no letter-spacing on a plain drawString, and the wordmark needs the same
        // widened tracking the web logo uses - so glyphs are placed one at a time.
        private void drawTracked(Graphics2D g2, String text, Font font, float tracking, Color color, int baselineY) {
            g2.setFont(font);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            float total = 0;
            for (int i = 0; i < text.length(); i++) {
                total += fm.charWidth(text.charAt(i));
                if (i < text.length() - 1) total += tracking;
            }
            float cx = (W - total) / 2f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                g2.drawString(String.valueOf(c), cx, baselineY);
                cx += fm.charWidth(c) + tracking;
            }
        }
    }
}
