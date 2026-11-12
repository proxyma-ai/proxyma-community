package ai.proxyma.desktop.launcher;

import ai.proxyma.desktop.common.ProxymaMark;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE SPLASH TELLS PROXYMA'S STORY while it waits (the maintainer, 2026-10-31): five short animated
 * scenes, each with a title and one line. A first start spends minutes downloading and installing,
 * which is the longest anyone looks at this window.
 *
 * <p>EACH SCENE IS THE REAL APP, redrawn (the maintainer: "match the UI with the real app"; and on
 * 2026-11-10: "Ensure the splash screen reflect correctly the app, including font, design, layout, icon,
 * images"). The authority is the app's source, never a comment here:
 * <ul>
 *   <li>FONT: Inter at the weights the app loads (400, 500, 600 - proxyma-fonts.css), each its own cut,
 *   never a weight Java2D synthesizes; the type scale of the kit's tailwind-preset (text-xs 12/16,
 *   text-sm 13/18, text-base 13/20, text-xl 16/22).</li>
 *   <li>COLOR: the Dark appearance's tokens (proxyma-tokens.css) with the default accent.</li>
 *   <li>ICONS: {@link Icons} - lucide-react's own paths and the kit's BrandMark, at the place and size the
 *   app draws each one.</li>
 *   <li>LAYOUT: the app's components at their own sizes - GlassFrame's frame and sheet with the Sidebar
 *   (Sidebar.tsx, navItems.ts), the kit's Chat (thread, ToolCallRow, the sources fold, PromptCard) and
 *   ChatComposer, SourcesPage and the kit's Sources list, ProjectRail and the crew canvas's AgentCard,
 *   and Settings' providers and model fields. A scene is a viewport 800x372: shorter than any real
 *   window, so the kit's {@code short:} variants (max-height 500px) apply, and what would sit below
 *   the fold is cut off as the app's own scrolling column cuts it.</li>
 *   <li>IMAGES: the agent picture is {@link AgentArt}'s, the app's own robot; the logo is
 *   {@link ProxymaMark}'s asset.</li>
 * </ul>
 * Change one of those components and the scene here no longer matches it.
 *
 * <p>Pure Java2D and no state of its own: every frame is a function of the time since the show began,
 * so {@link SplashWindow} only keeps a clock, and a test can paint any moment of any scene into an
 * image. The text is English, like every other line the splash draws: the launcher starts before the
 * application and its saved language.
 */
final class SplashSlides {

    private SplashSlides() {}

    /** How long each slide stays up. */
    static final long SLIDE_MS = 7_500;
    /** Each slide fades in over its first FADE_MS and out over its last. */
    static final long FADE_MS = 400;

    // -- layout, in window coordinates ------------------------------------------------------------
    static final int SCENE_W = 800;
    static final int SCENE_H = 372;
    static final int SCENE_Y = 24;
    static final int TITLE_BASELINE = 432;
    static final int LINE_BASELINE = 456;
    static final int DOTS_Y = 480;
    static final float TITLE_SIZE = 19f;
    static final float LINE_SIZE = 13f;

    // -- the app's Dark tokens (proxyma-tokens.css, proxyma-primitives.css) ------------------------
    /** --field-base: the frame (.app-glass composites to it) and the window. */
    private static final Color FRAME         = SplashWindow.FILL;
    /** --canvas: the page sheet (.app-sheet composites to it). */
    private static final Color PAGE          = new Color(0x1F, 0x1F, 0x1F);
    /** The sheet's edge, rgba(255,255,255,.07). */
    private static final Color SHEET_EDGE    = new Color(255, 255, 255, 18);
    /** .surface-card and .surface-float in the app shell. */
    private static final Color CARD          = new Color(0x25, 0x25, 0x25);
    /** A card's edge in the app shell, rgba(255,255,255,.08). */
    private static final Color CARD_EDGE     = new Color(255, 255, 255, 20);
    /** bg-lift (white at 3%) on the frame, on the page and on a card. */
    private static final Color LIFT_ON_FRAME = new Color(0x1F, 0x1F, 0x1F);
    private static final Color LIFT_ON_PAGE  = new Color(0x26, 0x26, 0x26);
    /** --surface-alt: a sunken well (FilterChips' track) and a field (bg-field). */
    private static final Color WELL          = new Color(0x18, 0x18, 0x18);
    private static final Color LINE_40       = new Color(0x2E, 0x2E, 0x2E); // border-line/40 on a card
    private static final Color LINE_50       = new Color(0x2E, 0x2E, 0x2E); // bg-line/50
    private static final Color INK_SECONDARY = new Color(0xCF, 0xD0, 0xD2); // --ink-secondary
    private static final Color INK_DISABLED  = new Color(0x7A, 0x7B, 0x7E); // --ink-disabled
    private static final Color BRAND_SOLID   = new Color(0x20, 0x6F, 0x8D); // --brand-solid (p700)
    private static final Color SUCCESS       = new Color(0x34, 0xD3, 0x99); // --success
    private static final Color WARNING       = new Color(0xFB, 0xBF, 0x24); // --warning
    private static final Color DANGER        = new Color(0xF8, 0x71, 0x71); // --danger
    // agentColor.ts, in the YouTube template's order of its team.
    private static final Color AGENT_RED    = new Color(0xEF, 0x44, 0x44);
    private static final Color AGENT_TEAL   = new Color(0x14, 0xB8, 0xA6);
    private static final Color AGENT_VIOLET = new Color(0x8B, 0x5C, 0xF6);

    /** The middle dot the app separates a line's facts with (MetaList: a non-breaking space either side). */
    private static final String SEP = " " + (char) 0xB7 + " ";
    /** The agent card's figures separator: a thin space either side. */
    private static final String THIN_SEP = (char) 0x2009 + "" + (char) 0xB7 + (char) 0x2009;

    interface Scene { void paint(Stage s, long t); }

    record Slide(String title, String line, Scene scene) {}

    static final List<Slide> SLIDES = List.of(
            new Slide("All your work in one place",
                    "Folders, Drive, Jira, Confluence, email, websites and more.", SplashSlides::sources),
            new Slide("Answers with sources",
                    "Ask in your own words. Every answer links to where it came from.", SplashSlides::answers),
            new Slide("An assistant that gets things done",
                    "It searches, reads and writes. It asks before it changes anything.", SplashSlides::agent),
            new Slide("Hand off your YouTube comments",
                    "A team drafts replies in your voice. Nothing is posted until you say so.", SplashSlides::team),
            new Slide("Yours, on your computer",
                    "Your index stays here. Use a free Gemini key, a local model or your own.", SplashSlides::local));

    // -- the clock ----------------------------------------------------------------------------------

    /** The slide up {@code elapsed} ms into a show that opened on slide {@code first}. */
    static int index(long elapsed, int first) {
        return (int) Math.floorMod(first + Math.floorDiv(elapsed, SLIDE_MS), (long) SLIDES.size());
    }

    /** How far into its slide the show is. */
    static long local(long elapsed) {
        return Math.floorMod(elapsed, SLIDE_MS);
    }

    /** 0..1: fading in over the first FADE_MS of a slide and out over its last. */
    static double opacity(long local) {
        double in = local / (double) FADE_MS;
        double out = (SLIDE_MS - local) / (double) FADE_MS;
        return clamp(Math.min(in, out));
    }

    /** Where a click on the dot of slide {@code i} lands, in a window {@code width} wide. */
    static Rectangle dot(int i, int width) {
        int n = SLIDES.size(), gap = 16;
        int x0 = width / 2 - (n - 1) * gap / 2;
        return new Rectangle(x0 + i * gap - 7, DOTS_Y - 7, 14, 14);
    }

    // -- painting -------------------------------------------------------------------------------------

    /** Paints the slide up at {@code elapsed}: its scene, its title and line, and the dots. */
    static void paint(Graphics2D g, int width, long elapsed, int first) {
        int i = index(elapsed, first);
        long t = local(elapsed);
        double a = opacity(t);
        Slide slide = SLIDES.get(i);

        // GRAYSCALE TEXT WITH FRACTIONAL ADVANCES, as the browser sets the app's Inter. Under the window's
        // LCD hint Java measures a string with hinted advances but draws it, whenever the composite is not
        // opaque - every fade, and every alpha a scene sets - in grayscale with others, up to 8px apart
        // over a line of Inter Medium; what was placed after a measured string ran into it.
        g = (Graphics2D) g.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        // The app's window the scene sits in: the frame's color with the sheet's radius and edge. Always
        // there, so a slide change fades the scene and not the window.
        Graphics2D pg = (Graphics2D) g.create();
        pg.translate((width - SCENE_W) / 2, SCENE_Y);
        pg.setColor(FRAME);
        pg.fill(new RoundRectangle2D.Double(0, 0, SCENE_W, SCENE_H, 32, 32));
        pg.clip(new RoundRectangle2D.Double(0, 0, SCENE_W, SCENE_H, 32, 32));
        slide.scene().paint(new Stage(pg, a), t);
        pg.setComposite(AlphaComposite.SrcOver);
        pg.setClip(null);
        pg.setColor(SHEET_EDGE);
        pg.setStroke(new BasicStroke(1f));
        pg.draw(new RoundRectangle2D.Double(0.5, 0.5, SCENE_W - 1, SCENE_H - 1, 32, 32));
        pg.dispose();

        Graphics2D tg = (Graphics2D) g.create();
        new Stage(tg, a).alpha(1);
        int rise = (int) Math.round(6 * (1 - easeOut(clamp(t / (double) FADE_MS))));
        centeredAt(tg, slide.title(), ui(TITLE_SIZE, true), SplashWindow.INK_PRIMARY, width / 2, TITLE_BASELINE + rise);
        centeredAt(tg, slide.line(), ui(LINE_SIZE, false), SplashWindow.INK_CAPTION, width / 2, LINE_BASELINE + rise);
        tg.dispose();

        for (int d = 0; d < SLIDES.size(); d++) {
            Rectangle r = dot(d, width);
            int size = d == i ? 7 : 5;
            g.setColor(d == i ? SplashWindow.BRAND_ACCENT : SplashWindow.LINE);
            g.fillOval((int) r.getCenterX() - size / 2, (int) r.getCenterY() - size / 2, size, size);
        }
        g.dispose();
    }

    /** The scene's Graphics and the slide's opacity, which every alpha a scene sets is scaled by. */
    static final class Stage {
        final Graphics2D g;
        private final double base;

        Stage(Graphics2D g, double base) {
            this.g = g;
            this.base = base;
        }

        void alpha(double a) {
            g.setComposite(AlphaComposite.SrcOver.derive((float) clamp(base * a)));
        }
    }

    // -- scene 1: the Sources page, syncing ----------------------------------------------------------

    private record Source(String name, String mark, int items) {}

    /** The Files group the list shows first, then the next group's heading under the fold. */
    private static final Source[] FILES = {
            new Source("Product docs", "files", 1_284),
            new Source("Contracts", "files", 312),
            new Source("Price lists", "files", 86)};

    /** FilterChips over the list: every type present, with the count of its sources. */
    private static final String[][] CHIPS = {
            {"All", "8"}, {"Files", "3"}, {"Confluence", "1"}, {"Jira", "1"}, {"Google Drive", "1"},
            {"Web pages", "1"}, {"Gmail", "1"}};
    /** The whole page's figures: all eight sources, the ones under the fold included. */
    private static final int OTHER_DOCS = 3_410 + 5_902 + 862 + 214 + 808;

    /**
     * SourcesPage, short: the Page (px-6, short:py-3 space-y-3) with its PageTitle - "Sources" at
     * 16/22 semibold, the figures under it, the md Connect button at the right - then FilterBar's
     * FilterChips, then SOURCE_LIST_BOX: SourceListHeader, a ListGroupHeading per type, and the rows on
     * the grid 1fr / 4rem / 6.5rem / 15rem with a 16px gap - the BrandMark and name, the items, the
     * last sync, the status. (FilterBar's search field, on a row of its own under the chips, is left
     * out: in this short a viewport it would leave room for one row.)
     */
    private static void sources(Stage s, long t) {
        Graphics2D g = s.g;
        s.alpha(1);
        sheet(g, 0, 0, SCENE_W, SCENE_H);
        int x = 24, w = SCENE_W - 48;

        long docs = Math.round(OTHER_DOCS * easeOut(clamp(t / 4_000.0)));
        for (int i = 0; i < FILES.length; i++) docs += shownItems(t, i);
        int top = 12;
        Font title = font(16, Weight.SEMIBOLD);
        put(g, "Sources", title, SplashWindow.INK_PRIMARY, x, top, 22);
        metaLine(g, x, top + 26, new String[][] {
                {"8", " sources"},
                {String.format(Locale.US, "%.1fK", docs / 1_000.0), " documents"},
                {String.format(Locale.US, "%.1f GB", 0.2 + 1.2 * easeOut(clamp(t / 4_000.0))), " on disk"}});
        primaryButton(g, "Connect", "plus", 16, x + w, top, 36, 16);

        // FilterChips: the well, and a chip per type - the active one lifted.
        int cy = top + 42 + 12;
        Font chip = font(13, Weight.MEDIUM), count = font(12, Weight.REGULAR);
        int cw = 8;
        for (String[] c : CHIPS) cw += width(g, c[0], chip) + 8 + width(g, c[1], count) + 24 + 2;
        g.setColor(WELL);
        g.fill(new RoundRectangle2D.Double(x, cy, cw - 2, 40, 24, 24));
        int chx = x + 4;
        for (int i = 0; i < CHIPS.length; i++) {
            int chw = width(g, CHIPS[i][0], chip) + 8 + width(g, CHIPS[i][1], count) + 24;
            if (i == 0) lift(g, chx, cy + 4, chw, 32, 16, LIFT_ON_FRAME);
            put(g, CHIPS[i][0], chip, i == 0 ? SplashWindow.INK_PRIMARY : INK_SECONDARY, chx + 12, cy + 4, 32);
            put(g, CHIPS[i][1], count, SplashWindow.INK_CAPTION, chx + 12 + width(g, CHIPS[i][0], chip) + 8, cy + 4, 32);
            chx += chw + 2;
        }

        // SOURCE_LIST_BOX.
        int ly = cy + 40 + 12;
        card(g, x, ly, w, SCENE_H + 40 - ly);
        int in = x + 24, iw = w - 48;
        int nameW = iw - 64 - 104 - 240 - 48;
        int itemsRight = in + nameW + 16 + 64, syncX = itemsRight + 16, statusX = syncX + 104 + 16;
        Font head = font(12, Weight.MEDIUM);
        put(g, "Name", head, SplashWindow.INK_CAPTION, in, ly + 8, 16);
        rightTop(g, "Items", head, SplashWindow.INK_CAPTION, itemsRight, ly + 8, 16);
        put(g, "Last sync", head, SplashWindow.INK_CAPTION, syncX, ly + 8, 16);
        put(g, "Status", head, SplashWindow.INK_CAPTION, statusX, ly + 8, 16);
        g.setColor(LINE_40);
        g.fillRect(in, ly + 32, iw, 1);

        int gy = ly + 33;
        groupHeading(g, "Files", "3", in, gy);
        int ry = gy + 40;
        for (int i = 0; i < FILES.length; i++, ry += 42) {
            long start = sourceStart(i);
            Source src = FILES[i];
            Icons.mark(g, src.mark(), in, ry + 13, 16, SplashWindow.BRAND_LEGIBLE);
            put(g, src.name(), font(13, Weight.MEDIUM), SplashWindow.INK_PRIMARY, in + 28, ry + 12, 18);
            rightTop(g, String.format(Locale.US, "%,d", shownItems(t, i)), font(13, Weight.REGULAR), INK_SECONDARY,
                    itemsRight, ry + 12, 18);
            Font small = font(12, Weight.REGULAR);
            if (t - start < sourceSyncMs(i)) {
                put(g, "Not synced yet", small, SplashWindow.INK_CAPTION, syncX, ry + 13, 16);
                // SourceHealthMark busy, then the working phrase in --brand-accent-legible.
                spinner(g, statusX, ry + 14, 14, t);
                put(g, "Syncing...", small, SplashWindow.BRAND_LEGIBLE, statusX + 22, ry + 13, 16);
            } else {
                put(g, "just now", small, SplashWindow.INK_CAPTION, syncX, ry + 13, 16);
                put(g, "Up to date", small, SplashWindow.INK_CAPTION, statusX, ry + 13, 16);
            }
        }
        // The section's pb-2, the Divider, and the next type's heading under the fold.
        g.setColor(LINE_40);
        g.fillRect(in, ry + 8, iw, 1);
        groupHeading(g, "Confluence spaces", "1", in, ry + 9);
        Icons.mark(g, "confluence", in, ry + 9 + 40 + 13, 16, SplashWindow.BRAND_LEGIBLE);
        put(g, "Team wiki", font(13, Weight.MEDIUM), SplashWindow.INK_PRIMARY, in + 28, ry + 9 + 40 + 12, 18);
    }

    /** ListGroupHeading: pt-4 pb-2, 12px medium caption, the count after it. */
    private static void groupHeading(Graphics2D g, String label, String count, int x, int top) {
        Font f = font(12, Weight.MEDIUM);
        put(g, label, f, SplashWindow.INK_CAPTION, x, top + 16, 16);
        put(g, count, f, SplashWindow.INK_CAPTION, x + width(g, label, f) + 4, top + 16, 16);
    }

    private static long sourceStart(int i) {
        return 300 + i * 420L;
    }

    private static long sourceSyncMs(int i) {
        return 1_800 + i * 300L;
    }

    /** How many of source {@code i}'s items have been counted at {@code t}. */
    private static long shownItems(long t, int i) {
        if (t < sourceStart(i)) return 0;
        return Math.round(FILES[i].items() * easeOut(clamp((t - sourceStart(i)) / (double) sourceSyncMs(i))));
    }

    // -- the app window around scenes 2 and 3: GlassFrame, the Sidebar, the window bar ---------------

    /** GlassFrame (p-2, gap-2): the Sidebar at w-60, then the window bar (h-8) and the sheet beside it. */
    private static final int SIDE_X = 8;
    private static final int SIDE_W = 240;
    private static final int SHEET_X = SIDE_X + SIDE_W + 8;
    private static final int SHEET_Y = 8 + 32 + 4;
    private static final int SHEET_W = SCENE_W - 8 - SHEET_X;
    private static final int SHEET_H = SCENE_H - 8 - SHEET_Y;
    /** The thread column: the sheet less the chat's px-6 gutter (THREAD_COLUMN's 720 is not reached). */
    private static final int COL_X = SHEET_X + 24;
    private static final int COL_R = SHEET_X + SHEET_W - 24;
    /** The composer: its 76px under the thread, above the composer area's short:py-1. */
    private static final int COMPOSER_H = 76;
    private static final int COMPOSER_Y = SHEET_Y + SHEET_H - 4 - COMPOSER_H;
    /** Where the thread's scrolling column ends and its content starts (ChatWindow, short:py-3). */
    private static final int THREAD_BOTTOM = COMPOSER_Y - 4;
    private static final int THREAD_TOP = SHEET_Y + 12;

    /** The open conversation's neighbors in the sidebar. */
    private static final String[] RECENT = {"Q3 price list", "Refund policy", "Onboarding steps", "Support summary"};

    /**
     * The window as Desktop draws it: the frame, the Sidebar on it, the window's own buttons (WindowControls)
     * in the bar over the sheet, and the sheet.
     *
     * <p>Sidebar.tsx: its head (h-11) - the kit's Logo, the 28px mark turning and the wordmark in Orbitron
     * 700 at 13px in --brand-legible - and the fold button; then New chat and the pages navItems.ts lists
     * on Desktop with both add-ons (h-9 rows, px-2.5, a 16px icon, 13px medium, 12px rounded), the open page
     * lifted with its icon in --brand-legible; then "Recent chats" (12px semibold caption, the History
     * button at its right) over the conversations (h-8), the open one lifted with a pulsing success dot
     * while its turn runs. The open conversation reads "New conversation" until its title is written.
     */
    private static void appFrame(Stage s, long t, String titled, long titledAt, long runningUntil, String[] others) {
        Graphics2D g = s.g;
        s.alpha(1);
        g.setColor(FRAME);
        g.fillRect(0, 0, SCENE_W, SCENE_H);

        // WindowControls: minimize, maximize, close, 40x32 each, gap-0.5, in --ink-secondary.
        int bx = SCENE_W - 8 - 3 * 40 - 4;
        Icons.stroke(g, "minus", bx + 12, 16, 16, INK_SECONDARY);
        Icons.stroke(g, "square", bx + 42 + 13.5, 17.5, 13, INK_SECONDARY);
        Icons.stroke(g, "x", bx + 84 + 12, 16, 16, INK_SECONDARY);

        sheet(g, SHEET_X, SHEET_Y, SHEET_W, SHEET_H);

        // The head.
        int headTop = 12;
        logo(g, SIDE_X + 8, headTop + 8, t);
        Icons.stroke(g, "panel-left-close", SIDE_X + SIDE_W - 4 - 32 + 8, headTop + 14, 16, SplashWindow.INK_CAPTION);

        Font row = font(13, Weight.MEDIUM);
        String[] nav = {"New chat", "Chat", "Sources", "YouTube assistant", "Constellations"};
        String[] icons = {"square-pen", "message-square", "database", "circle-play", "orbit"};
        for (int i = 0; i < nav.length; i++) {
            int top = headTop + 44 + 4 + i * 40;
            boolean open = i == 1;
            if (open) lift(g, SIDE_X, top, SIDE_W, 36, 24, LIFT_ON_FRAME);
            Icons.stroke(g, icons[i], SIDE_X + 10, top + 10, 16, open ? SplashWindow.BRAND_LEGIBLE : INK_SECONDARY);
            put(g, nav[i], row, open ? SplashWindow.INK_PRIMARY : INK_SECONDARY, SIDE_X + 36, top + 9, 18);
        }

        // SidebarSection "Recent chats": px-2.5 pt-4 pb-1, the History button (24px, a 14px icon) at its right.
        int sec = headTop + 44 + 4 + 5 * 40;
        put(g, "Recent chats", font(12, Weight.SEMIBOLD), SplashWindow.INK_CAPTION, SIDE_X + 10, sec + 16, 16);
        Icons.stroke(g, "history", SIDE_X + SIDE_W - 10 - 24 + 5, sec + 13, 14, SplashWindow.INK_CAPTION);

        int top = sec + 16 + 16 + 4 + 4, maxW = SIDE_W - 20, busyW = maxW - 14;
        lift(g, SIDE_X, top, SIDE_W, 32, 24, LIFT_ON_FRAME);
        double named = in(t, titledAt, 400);
        if (named < 1) {
            s.alpha(1 - named);
            put(g, fit(g, "New conversation", row, t < runningUntil ? busyW : maxW), row, SplashWindow.INK_PRIMARY, SIDE_X + 10, top + 7, 18);
        }
        if (named > 0) {
            s.alpha(named);
            put(g, fit(g, titled, row, t < runningUntil ? busyW : maxW), row, SplashWindow.INK_PRIMARY, SIDE_X + 10, top + 7, 18);
        }
        if (t < runningUntil) {
            s.alpha(pulse(t));
            g.setColor(SUCCESS);
            g.fill(new Ellipse2D.Double(SIDE_X + SIDE_W - 10 - 6, top + 13, 6, 6));
        }
        s.alpha(1);
        for (int i = 0; i < others.length; i++) {
            put(g, fit(g, others[i], row, maxW), row, INK_SECONDARY, SIDE_X + 10, top + 34 * (i + 1) + 7, 18);
        }
    }

    /** The kit's Logo at 28px: the mark turning (one turn in 12s, --mark-turn), 8px, the wordmark. */
    private static void logo(Graphics2D g, int x, int y, long t) {
        BufferedImage mark = markImage(g);
        Graphics2D r = (Graphics2D) g.create();
        r.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        r.rotate(Math.toRadians(t * 0.03), x + 14, y + 14);
        r.drawImage(mark, x, y, 28, 28, null);
        r.dispose();
        SplashWindow.drawWordmark(g, x + 28 + 8, y + 14, 13f);
    }

    private static BufferedImage mark;
    private static double markScale;

    private static BufferedImage markImage(Graphics2D g) {
        double scale = Math.max(1.0, g.getTransform().getScaleX());
        if (mark == null || scale != markScale) {
            mark = ProxymaMark.image((int) Math.round(28 * scale));
            markScale = scale;
        }
        return mark;
    }

    /**
     * ChatComposer, from ChatPage: a rounded-lg surface-float box with --shadow-float; the editor (px-4
     * pt-3, 13/20) with the placeholder in disabled ink or what is being typed; the toolbar (px-2 pb-2):
     * the attach menu's Plus, the permission mode (Hand, "Ask", a chevron), the provider and model in 12px
     * caption with the middle dot in disabled ink; and at the right Send (ArrowUp) while there is text, or
     * Stop (a filled Square) while the turn runs - icon buttons with no fill, nothing at all otherwise.
     */
    private static void composer(Stage s, String typed, boolean sendable, boolean streaming) {
        Graphics2D g = s.g;
        s.alpha(1);
        int x = COL_X, y = COMPOSER_Y, w = COL_R - COL_X, h = COMPOSER_H;
        floatShadow(g, x, y, w, h);
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        insetHighlight(g, x, y, w, 24, new Color(255, 255, 255, 20));
        Font body = font(13, Weight.REGULAR);
        if (typed.isEmpty()) put(g, "Type @ to mention a source or / to use a skill", body, INK_DISABLED, x + 16, y + 12, 20);
        else put(g, typed, body, SplashWindow.INK_PRIMARY, x + 16, y + 12, 20);

        int bar = y + h - 8 - 32;
        Icons.stroke(g, "plus", x + 8 + 8, bar + 8, 16, SplashWindow.INK_CAPTION);
        Font f = font(12, Weight.REGULAR);
        int mx = x + 8 + 32 + 4 + 8;
        Icons.stroke(g, "hand", mx, bar + 9.5, 13, SplashWindow.INK_CAPTION);
        put(g, "Ask", f, SplashWindow.INK_CAPTION, mx + 13 + 6, bar + 8, 16);
        int cx = mx + 13 + 6 + width(g, "Ask", f) + 4;
        Icons.stroke(g, "chevron-down", cx, bar + 10, 12, SplashWindow.INK_CAPTION);
        int lx = cx + 12 + 8 + 8;
        put(g, "Gemini", f, SplashWindow.INK_CAPTION, lx, bar + 8, 16);
        lx += width(g, "Gemini", f);
        put(g, SEP, f, INK_DISABLED, lx, bar + 8, 16);
        put(g, "gemini-2.5-flash", f, SplashWindow.INK_CAPTION, lx + width(g, SEP, f), bar + 8, 16);

        if (streaming) Icons.solid(g, "square", x + w - 8 - 32 + 8, bar + 8, 16, SplashWindow.INK_CAPTION);
        else if (sendable) Icons.stroke(g, "arrow-up", x + w - 8 - 32 + 8, bar + 8, 16, SplashWindow.INK_CAPTION);
    }

    /** ChatMessage, user: max 78%, rounded-lg bg-lift px-3 py-2, 13/20 ink-primary, at the column's right. */
    private static void question(Graphics2D g, String q, int top) {
        Font body = font(13, Weight.REGULAR);
        int qw = Math.min(width(g, q, body) + 24, (int) ((COL_R - COL_X) * 0.78));
        g.setColor(LIFT_ON_PAGE);
        g.fill(new RoundRectangle2D.Double(COL_R - qw, top, qw, 36, 24, 24));
        put(g, fit(g, q, body, qw - 24), body, SplashWindow.INK_PRIMARY, COL_R - qw + 12, top + 8, 20);
    }

    /**
     * 0..1: whether the question at {@code qTop} is pinned with the thread scrolled by {@code scroll} -
     * ChatWindow's rule: once its bottom is above the scroller's top plus PIN_BAR_PX (40), faded in over a
     * few pixels as Presence fades it.
     */
    static double pinShown(double qTop, double scroll) {
        double bottom = qTop + 36 - scroll;
        return clamp((SHEET_Y + 40 - bottom) / 6.0 + 1);
    }

    /**
     * The question pinned over the thread once it has scrolled out of view (ChatWindow): its words at the
     * column's right, 13px medium, cut to 78%, over the pin-fade from the page's color.
     */
    private static void pinned(Stage s, String q, double shown) {
        if (shown <= 0) return;
        Graphics2D g = s.g;
        s.alpha(shown);
        // .pin-fade: the page's color to 2.5rem, gone by 4rem.
        Graphics2D fade = (Graphics2D) g.create();
        fade.clip(new RoundRectangle2D.Double(SHEET_X + 1, SHEET_Y + 1, SHEET_W - 2, SHEET_H - 2, 30, 30));
        fade.setColor(PAGE);
        fade.fillRect(SHEET_X, SHEET_Y, SHEET_W, 40);
        fade.setPaint(new GradientPaint(0, SHEET_Y + 40, PAGE, 0, SHEET_Y + 64, new Color(0x1F, 0x1F, 0x1F, 0)));
        fade.fillRect(SHEET_X, SHEET_Y + 40, SHEET_W, 24);
        fade.dispose();
        Font f = font(13, Weight.MEDIUM);
        String text = fit(g, q, f, (int) ((COL_R - COL_X) * 0.78));
        rightTop(g, text, f, SplashWindow.INK_PRIMARY, COL_R, SHEET_Y + 11, 18);
        s.alpha(1);
    }

    // -- scene 2: a chat turn - typed, sent, a tool row, the answer with its link, the sources fold ---

    private static void answers(Stage s, long t) {
        Graphics2D g = s.g;
        long sendAt = 1_300, toolAt = 1_500, toolDone = 2_600, answerAt = 2_700, foldAt = 4_000, openAt = 4_500, endAt = 4_100;
        appFrame(s, t, "Acme delivery dates", 4_600, endAt, RECENT);
        String q = "What did we promise Acme about delivery?";

        int left = COL_X;
        Font body = font(13, Weight.REGULAR);
        String l1 = "We promised delivery by March 14, with a weekly status update.";
        String l2 = "Each late week costs 2% of the order, as set in ";
        String link = "Acme contract.pdf";

        // The column's content, top to bottom, each part growing in as it arrives - and the column follows
        // its newest line (threadFollow.ts), so what no longer fits scrolls up under the pinned question.
        double y = THREAD_TOP + 8;
        double qTop = y;
        y += 36 * in(t, sendAt, 250) + 24 * in(t, toolAt, 200);
        double toolTop = y;
        y += 20 * in(t, toolAt, 200);
        double answerTop = y + 8;
        double stream = clamp((t - answerAt) / 1_100.0);
        y += (8 + 40 + 8) * in(t, answerAt, 300);
        double foldTop = y;
        y += (12 + 12 + 16) * in(t, foldAt, 300);
        double listTop = y + 8;
        y += (8 + 16 + 8 + 16 + 8) * in(t, openAt, 300);
        double scroll = Math.max(0, y - (THREAD_BOTTOM - 8));

        Graphics2D th = (Graphics2D) g.create();
        th.clipRect(SHEET_X, SHEET_Y, SHEET_W, THREAD_BOTTOM - SHEET_Y);
        th.translate(0, -scroll);
        Stage ts = new Stage(th, s.base);
        if (t >= sendAt) {
            ts.alpha(in(t, sendAt, 250));
            question(th, q, (int) qTop);
        }
        if (t > toolAt) {
            ts.alpha(in(t, toolAt, 200));
            toolRow(th, "search", "Searching your sources - Acme delivery", "Searched 2 sources", "1s",
                    left, (int) toolTop, t, toolDone);
        }
        if (stream > 0) {
            ts.alpha(1);
            int total = l1.length() + l2.length() + link.length();
            int shown = (int) Math.round(total * stream);
            int b1 = (int) answerTop;
            put(th, l1.substring(0, Math.min(l1.length(), shown)), body, INK_SECONDARY, left, b1, 20);
            int rest = shown - l1.length();
            if (rest > 0) {
                put(th, l2.substring(0, Math.min(l2.length(), rest)), body, INK_SECONDARY, left, b1 + 20, 20);
                int lx = left + width(th, l2, body);
                int linkShown = rest - l2.length();
                if (linkShown > 0) {
                    String part = link.substring(0, Math.min(link.length(), linkShown));
                    put(th, part, body, SplashWindow.BRAND_LEGIBLE, lx, b1 + 20, 20);
                    // The kit's ExternalLink: SquareArrowOutUpRight after the words.
                    if (linkShown >= link.length()) {
                        Icons.stroke(th, "square-arrow-out-up-right", lx + width(th, link, body) + 4, b1 + 24, 12,
                                SplashWindow.BRAND_LEGIBLE);
                    }
                }
            }
        }

        // SourceCitations: mt-3 pt-3 over a hairline, "2 sources" and its chevron (turning a quarter as it
        // opens), then the list behind a 2px left rule: each source's mark, its name and its score.
        double fold = in(t, foldAt, 300);
        if (fold > 0) {
            ts.alpha(fold);
            int ft = (int) foldTop + 12;
            th.setColor(LINE_40);
            th.fillRect(left, ft, COL_R - left, 1);
            Font f = font(12, Weight.REGULAR);
            put(th, "2 sources", f, SplashWindow.INK_CAPTION, left, ft + 12, 16);
            chevron(th, left + width(th, "2 sources", f) + 8, ft + 13, in(t, openAt, 250), SplashWindow.INK_CAPTION);
            double open = in(t, openAt, 250);
            if (open > 0) {
                ts.alpha(fold * open);
                int lt = (int) listTop;
                th.setColor(LINE_40);
                th.fillRect(left, lt, 2, 40);
                String[][] rows = {{"Acme contract.pdf", "91%"}, {"ACME-112 Delivery dates", "84%"}};
                String[] marks = {"files", "jira"};
                for (int r = 0; r < rows.length; r++) {
                    int rt = lt + r * 24;
                    Icons.mark(th, marks[r], left + 14, rt + 1, 14, INK_DISABLED);
                    put(th, rows[r][0], f, SplashWindow.INK_CAPTION, left + 36, rt, 16);
                    put(th, rows[r][1], f, INK_DISABLED, left + 36 + width(th, rows[r][0], f) + 8, rt, 16);
                }
            }
        }
        th.dispose();
        pinned(s, q, pinShown(qTop, scroll));

        int typed = t < sendAt ? (int) Math.round(q.length() * clamp((t - 250) / 900.0)) : 0;
        composer(s, q.substring(0, typed), typed > 0, t >= sendAt && t < endAt);
    }

    // -- scene 3: the agent works, then asks before it writes ------------------------------------------

    private static void agent(Stage s, long t) {
        Graphics2D g = s.g;
        long ask = 2_700, press = 4_500, decided = 4_750, created = 5_200, endAt = 5_500;
        appFrame(s, t, "March blockers", 5_900, endAt,
                new String[] {"Acme delivery dates", "Q3 price list", "Refund policy", "Onboarding steps"});
        String q = "Which bugs block the March release? Put a summary in Confluence.";
        int left = COL_X, w = COL_R - COL_X;
        Font body = font(13, Weight.REGULAR);

        // The calls of the first run fold away as they finish (toolFold.ts): while one runs it is drawn,
        // and once none does the last one is left with the chevron that opens the rest.
        String[][] calls = {
                {"search", "Searching Jira issues - fixVersion = March", "Searched Jira issues - fixVersion = March", "1s"},
                {"file-text", "Opening Jira issue - PROJ-341", "Opened Jira issue - PROJ-341", "1s"},
                {"file-text", "Opening Jira issue - PROJ-347", "Opened Jira issue - PROJ-347", "1s"},
                {"file-text", "Opening Jira issue - PROJ-352", "Opened Jira issue - PROJ-352", "1s"}};
        long[] starts = {300, 1_100, 1_500, 1_900}, ends = {1_100, 1_500, 1_900, 2_300};

        double y = THREAD_TOP + 8;
        double qTop = y;
        y += 36 + 24;
        double toolTop = y;
        y += 20;
        double textTop = y + 8;
        y += 36 * in(t, 2_300, 300);
        double cardTop = y + 4;
        int cardH = approvalHeight(g, w);
        double cardIn = in(t, ask, 300) * (1 - in(t, decided, 300));
        y += (cardH + 8) * cardIn + 20 * in(t, decided, 300);
        double rowTop = y - 20 * in(t, decided, 300);
        double doneTop = y + 8;
        y += 36 * in(t, endAt - 100, 300);
        double scroll = Math.max(0, y - (THREAD_BOTTOM - 8));

        Graphics2D th = (Graphics2D) g.create();
        th.clipRect(SHEET_X, SHEET_Y, SHEET_W, THREAD_BOTTOM - SHEET_Y);
        th.translate(0, -scroll);
        Stage ts = new Stage(th, s.base);
        ts.alpha(in(t, 0, 300));
        question(th, q, (int) qTop);

        int current = -1;
        for (int i = 0; i < calls.length; i++) if (t >= starts[i]) current = i;
        if (current >= 0) {
            ts.alpha(in(t, starts[0], 200));
            String[] c = calls[current];
            toolRow(th, c[0], c[1], c[2], c[3], left, (int) toolTop, t, ends[current]);
            if (t >= ends[calls.length - 1]) {
                Font f = font(12, Weight.REGULAR);
                int after = left + 24 + width(th, c[2], f) + 8 + width(th, c[3], f) + 8;
                Icons.stroke(th, "chevron-down", after, (int) toolTop + 1, 14, SplashWindow.INK_CAPTION);
            }
        }
        if (t > 2_300) {
            ts.alpha(in(t, 2_300, 300));
            put(th, "Twelve bugs are open, and three of them block the March release.", body, INK_SECONDARY,
                    left, (int) textTop, 20);
        }
        if (cardIn > 0) {
            ts.alpha(cardIn);
            approvalCard(th, ts, left, (int) cardTop, w, t, ask, press, decided);
        }
        if (t >= decided) {
            ts.alpha(in(t, decided, 200));
            toolRow(th, "file-plus", "Creating a Confluence page - Open bugs, week 44",
                    "Created a Confluence page - Open bugs, week 44", "1s", left, (int) rowTop, t, created);
        }
        if (t > endAt - 100) {
            ts.alpha(in(t, endAt - 100, 300));
            put(th, "Done. The summary is in the Team space.", body, INK_SECONDARY, left, (int) doneTop, 20);
        }
        th.dispose();
        pinned(s, q, pinShown(qTop, scroll));
        composer(s, "", false, t < endAt);

        // The pointer, on its way to Allow once.
        if (t > 3_500 && t < decided + 300) {
            s.alpha(cardIn);
            Rectangle once = allowOnce(g, left, (int) (cardTop - scroll), w);
            double move = easeInOut(clamp((t - 3_600) / 800.0));
            int cx = (int) Math.round(SCENE_W - 40 + (once.getCenterX() - (SCENE_W - 40)) * move);
            int cy = (int) Math.round(SCENE_H - 10 + (once.getCenterY() - (SCENE_H - 10)) * move);
            cursor(g, cx, cy);
        }
    }

    /** The arguments the approval card lists: the ones its title does not already name (argumentRows.ts). */
    private static final String[][] ARGS = {{"Space key", "TEAM"},
            {"Content", "Three bugs block the March release: PROJ-341, PROJ-347 and PROJ-352."}};
    private static final String COUNTDOWN = "Denied automatically in 4m 59s";

    /**
     * ToolApprovalPrompt in the kit's PromptCard: a card (px-4 py-3 space-y-3); PenLine in --warning, the
     * call's running phrase in 13px medium; under it, set in pl-7, the arguments as KeyValue rows (12px,
     * the label in caption ink, gap-x 16, gap-y 8); then the action row (justify-end, gap-x 12, wrapping):
     * the countdown at the left, "Allow for this conversation" (secondary sm), "Allow once" (primary sm)
     * and "Deny" in danger ink, set apart.
     */
    private static void approvalCard(Graphics2D g, Stage s, int x, int y, int w, long t, long ask, long press, long decided) {
        int h = approvalHeight(g, w);
        card(g, x, y, w, h);
        Icons.stroke(g, "pen-line", x + 16, y + 12 + 2, 16, WARNING);
        put(g, "Creating a Confluence page - Open bugs, week 44", font(13, Weight.MEDIUM), SplashWindow.INK_PRIMARY,
                x + 16 + 28, y + 12, 18);
        Font small = font(12, Weight.REGULAR);
        int ax = x + 16 + 28, labelW = 0;
        for (String[] a : ARGS) labelW = Math.max(labelW, width(g, a[0], small));
        int ay = y + 12 + 18 + 12;
        for (String[] a : ARGS) {
            put(g, a[0], small, SplashWindow.INK_CAPTION, ax, ay, 16);
            put(g, fit(g, a[1], small, x + w - 16 - (ax + labelW + 16)), small, SplashWindow.INK_PRIMARY, ax + labelW + 16, ay, 16);
            ay += 24;
        }

        List<int[]> lines = actionLines(g, w - 32);
        int ly = ay - 8 + 12;
        boolean sending = t >= press;
        for (int[] line : lines) {
            int right = x + w - 16;
            for (int k = line.length - 1; k >= 0; k--) {
                int item = line[k];
                if (item == 0) continue;
                if (item == 3) {
                    int dw = width(g, "Deny", font(12, Weight.SEMIBOLD));
                    put(g, "Deny", font(12, Weight.SEMIBOLD), DANGER, right - dw, ly + 8, 16);
                    right -= dw + 8 + 12;
                } else if (item == 2) {
                    int bw = buttonWidth(g, "Allow once");
                    boolean down = t >= press && t < press + 160;
                    g.setColor(down ? BRAND_SOLID.darker() : BRAND_SOLID);
                    g.fill(new RoundRectangle2D.Double(right - bw, ly, bw, 32, 16, 16));
                    centeredTop(g, "Allow once", font(13, Weight.SEMIBOLD), Color.WHITE, right - bw / 2, ly, 32);
                    right -= bw + 12;
                } else {
                    int bw = buttonWidth(g, "Allow for this conversation");
                    g.setColor(SplashWindow.LINE);
                    g.draw(new RoundRectangle2D.Double(right - bw + 0.5, ly + 0.5, bw - 1, 31, 16, 16));
                    centeredTop(g, "Allow for this conversation", font(13, Weight.SEMIBOLD), SplashWindow.INK_PRIMARY,
                            right - bw / 2, ly, 32);
                    right -= bw + 12;
                }
            }
            if (line[0] == 0) {
                if (sending) {
                    spinner(g, x + 16, ly + 9, 14, t);
                    put(g, "Sending approval", small, SplashWindow.INK_CAPTION, x + 16 + 22, ly + 8, 16);
                } else {
                    long left5 = Math.max(0, 299_000 - Math.max(0, t - ask));
                    put(g, String.format(Locale.US, "Denied automatically in %dm %02ds", left5 / 60_000, left5 / 1_000 % 60),
                            small, SplashWindow.INK_CAPTION, x + 16, ly + 8, 16);
                }
            }
            ly += 32 + 8;
        }
    }

    /** The action row's items (0 the countdown, 1 secondary, 2 primary, 3 Deny), wrapped as flex-wrap does. */
    private static List<int[]> actionLines(Graphics2D g, int width) {
        int[] widths = {width(g, COUNTDOWN, font(12, Weight.REGULAR)), buttonWidth(g, "Allow for this conversation"),
                buttonWidth(g, "Allow once"), width(g, "Deny", font(12, Weight.SEMIBOLD)) + 8};
        List<int[]> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            int need = (line.isEmpty() ? 0 : 12) + widths[i];
            if (!line.isEmpty() && used + need > width) {
                lines.add(line.stream().mapToInt(Integer::intValue).toArray());
                line = new ArrayList<>();
                used = 0;
                need = widths[i];
            }
            line.add(i);
            used += need;
        }
        lines.add(line.stream().mapToInt(Integer::intValue).toArray());
        return lines;
    }

    private static int approvalHeight(Graphics2D g, int w) {
        return 12 + 18 + 12 + ARGS.length * 24 - 8 + 12 + actionLines(g, w - 32).size() * 40 - 8 + 12;
    }

    /** Where Allow once sits on a card at (x, y), for the pointer. */
    private static Rectangle allowOnce(Graphics2D g, int x, int y, int w) {
        List<int[]> lines = actionLines(g, w - 32);
        int ly = y + 12 + 18 + 12 + ARGS.length * 24 - 8 + 12;
        for (int[] line : lines) {
            int right = x + w - 16;
            for (int k = line.length - 1; k >= 0; k--) {
                if (line[k] == 3) right -= width(g, "Deny", font(12, Weight.SEMIBOLD)) + 8 + 12;
                else if (line[k] == 2) return new Rectangle(right - buttonWidth(g, "Allow once"), ly, buttonWidth(g, "Allow once"), 32);
                else if (line[k] == 1) right -= buttonWidth(g, "Allow for this conversation") + 12;
            }
            ly += 40;
        }
        return new Rectangle(x + w - 120, y + 60, 100, 32);
    }

    /**
     * One ToolCallRow (text-xs, gap-2): the tool's icon (toolIcon.ts) at 14px in a 16px slot, caption ink
     * and the phrase with the text wave while it runs; once done the icon in --brand-legible, the finished
     * phrase in caption ink and the time in disabled ink.
     */
    private static void toolRow(Graphics2D g, String icon, String running, String finished, String took,
                                int x, int top, long t, long doneAt) {
        Font f = font(12, Weight.REGULAR);
        if (t < doneAt) {
            Icons.stroke(g, icon, x + 1, top + 1, 14, SplashWindow.INK_CAPTION);
            waveText(g, running, f, SplashWindow.INK_CAPTION, x + 24, baseline(g, f, top, 16), t);
        } else {
            Icons.stroke(g, icon, x + 1, top + 1, 14, SplashWindow.BRAND_LEGIBLE);
            put(g, finished, f, SplashWindow.INK_CAPTION, x + 24, top, 16);
            put(g, took, f, INK_DISABLED, x + 24 + width(g, finished, f) + 8, top, 16);
        }
    }

    // -- scene 4: a YouTube channel's team - the channel's rail and its canvas ------------------------

    /** CARD_W and CARD_H (canvasGrid.ts), and the grid's column pitch. */
    private static final int CARD_W = 288;
    private static final int CARD_H = 120;
    private static final int COLUMN_PITCH = 336;
    /** ProjectRail's width (w-72). */
    private static final int RAIL_W = 288;

    private record Agent(String name, String role, boolean lead, Color color, int tokens, double cost, int seconds,
                         long start, String[] marks) {}

    /** The YouTube assistant template's own names, roles and colors (youtube-assistant/template.json). */
    private static final Agent[] TEAM = {
            new Agent("Channel Lead", "Runs the team", true, AGENT_RED, 18_400, 0.06, 734, 0,
                    new String[] {"brain", "bot-message-square"}),
            new Agent("Reply Writer", "Writes replies", false, AGENT_TEAL, 41_200, 0.12, 1_290, 500,
                    new String[] {"file-pen", "inbox"}),
            new Agent("Learner", "Learns your voice", false, AGENT_VIOLET, 22_900, 0.04, 512, 700,
                    new String[] {"brain", "file-pen"})};

    /**
     * The channel's Team section: ProjectRail at its own width beside the crew canvas, panned the way a
     * person would leave it - the lead over the two agents it leads at the template's own column pitch
     * (Reply Writer and Learner, the template's columns 1 and 2 under the lead's 1.5), the last running
     * on past the edge; the rows brought closer than the template's, as a dragged card may be.
     */
    private static void team(Stage s, long t) {
        Graphics2D g = s.g;
        s.alpha(1);
        sheet(g, 0, 0, SCENE_W, SCENE_H);

        // CrewCanvas: 1px dots in --line on a 24px grid.
        g.setColor(SplashWindow.LINE);
        for (int y = 12; y < SCENE_H; y += 24) {
            for (int x = RAIL_W + 12; x < SCENE_W; x += 24) g.fillRect(x, y, 1, 1);
        }

        int col1 = RAIL_W + 16, rowY = 216, leadY = 48;
        int leadX = col1 + COLUMN_PITCH / 2;
        int[][] at = {{leadX, leadY}, {col1, rowY}, {col1 + COLUMN_PITCH, rowY}};
        int fromX = leadX + CARD_W / 2, midY = leadY + CARD_H + (rowY - leadY - CARD_H) / 2;

        // AgentLinkLayer: lead lines, right angles with 12px rounded bends, 2px ink-caption, a 5x5 arrow
        // marker at the led agent; while two agents talk a short brand-accent dash runs along the line.
        double lines = in(t, 300, 400);
        if (lines > 0) {
            s.alpha(lines);
            g.setColor(SplashWindow.INK_CAPTION);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 1; k < at.length; k++) {
                int tx = at[k][0] + CARD_W / 2;
                double[] path = {fromX, leadY + CARD_H, fromX, midY, tx, midY, tx, rowY - 10};
                g.draw(orth(path, 12));
                arrowDown(g, tx, rowY);
            }
            g.setStroke(new BasicStroke(1f));
            long talk = (t - 1_200) % 2_400;
            if (t > 1_200 && talk < 1_200) {
                int tx = at[1][0] + CARD_W / 2;
                double[] path = {fromX, leadY + CARD_H, fromX, midY, tx, midY, tx, rowY - 10};
                Point2D p = along(path, talk / 1_200.0), q = along(path, Math.max(0, talk / 1_200.0 - 0.12));
                g.setColor(SplashWindow.BRAND_ACCENT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Double(q, p));
                g.setStroke(new BasicStroke(1f));
            }
        }
        for (int i = 0; i < TEAM.length; i++) agentCard(s, t, at[i][0], at[i][1], TEAM[i]);

        projectRail(s, t);
    }

    /**
     * ProjectRail in a channel's place (border-r): the "YouTube assistant" back link (ArrowLeft, 12px caption);
     * ChannelHeading - the channel's picture (56px round; without one the YouTube BrandMark on --surface-alt),
     * its name (13 semibold), handle and "subscribers - videos" (12px caption); the channel's sections (h-9,
     * px-3, a 16px icon, 13px) with Team open - lifted, medium, its icon in --brand-legible - and the counts
     * waiting as plain caption text; the team below them is under the fold.
     */
    private static void projectRail(Stage s, long t) {
        Graphics2D g = s.g;
        s.alpha(1);
        g.setColor(PAGE);
        g.fillRect(0, 0, RAIL_W, SCENE_H);
        g.setColor(SplashWindow.LINE);
        g.fillRect(RAIL_W, 0, 1, SCENE_H);

        Icons.stroke(g, "arrow-left", 16, 12 + 10, 16, SplashWindow.INK_CAPTION);
        put(g, "YouTube assistant", font(12, Weight.REGULAR), SplashWindow.INK_CAPTION, 16 + 16 + 8, 12 + 10, 16);

        int hy = 12 + 36 + 12;
        // The channel's picture, as the app's panel shows it (2026-11-11: a play mark there is what a channel whose
        // picture is missing looked like) - an invented channel, so its initial on the brand's color.
        g.setColor(SplashWindow.BRAND_ACCENT);
        g.fill(new Ellipse2D.Double(16, hy, 56, 56));
        put(g, "F", font(22, Weight.SEMIBOLD), PAGE, 16 + 28 - 7, hy + 28 - 14, 28);
        int tx = 16 + 56 + 12, ty = hy + 1;
        put(g, "Flute lessons", font(13, Weight.SEMIBOLD), SplashWindow.INK_PRIMARY, tx, ty, 18);
        put(g, "@flutelessons", font(12, Weight.REGULAR), SplashWindow.INK_CAPTION, tx, ty + 20, 16);
        metaLine(g, tx, ty + 38, new String[][] {{"6.8K", " subscribers"}, {"142", " videos"}});

        String[] sections = {"Overview", "Team", "Approvals", "Questions", "Settings"};
        String[] icons = {"layout-dashboard", "network", "inbox", "message-circle-question-mark", "settings"};
        String[] counts = {null, null, "12", "3", null};
        int sy = hy + 56 + 16;
        for (int i = 0; i < sections.length; i++) {
            int top = sy + i * 38;
            boolean open = i == 1;
            if (open) lift(g, 12, top, RAIL_W - 24, 36, 16, LIFT_ON_PAGE);
            Icons.stroke(g, icons[i], 24, top + 10, 16, open ? SplashWindow.BRAND_LEGIBLE : INK_SECONDARY);
            put(g, sections[i], font(13, open ? Weight.MEDIUM : Weight.REGULAR),
                    open ? SplashWindow.INK_PRIMARY : INK_SECONDARY, 24 + 16 + 12, top + 9, 18);
            if (counts[i] != null) {
                rightTop(g, counts[i], font(12, Weight.REGULAR), SplashWindow.INK_CAPTION, RAIL_W - 24, top + 10, 16);
            }
        }

        // The team under its border ("Pause all" and the agents) is below this view's fold; a button cut in half by
        // the sheet's edge read as a fault, so the rail ends at its sections.
    }

    /**
     * AgentCard (w-72 h-[120px], p-4, gap-3): the picture (64x88) at the left; at the right, spread top to
     * bottom, the name (13px medium) with the leader's Crown and the working dot (8px, pulsing), the role
     * (12px caption), then the model and the figures - context used, cost, time worked (12/16
     * ink-secondary); the 3px context bar along the bottom. What the agent is doing is an icon over the
     * card's top-right corner (AgentStatus's marks, h-5, 24px above): the activity, then the quiet marks.
     */
    private static void agentCard(Stage s, long t, int x, int y, Agent a) {
        long start = a.start();
        double k = in(t, start, 350);
        if (k <= 0) return;
        Graphics2D g = s.g;
        int dy = (int) Math.round(8 * (1 - k));
        y += dy;
        s.alpha(k);
        card(g, x, y, CARD_W, CARD_H);
        AgentArt.paint(g, x + 16, y + 16, 64, 88, a.color());

        int cx = x + 16 + 64 + 12;
        Font name = font(13, Weight.MEDIUM);
        put(g, a.name(), name, SplashWindow.INK_PRIMARY, cx, y + 16, 20);
        int after = cx + width(g, a.name(), name) + 6;
        if (a.lead()) {
            Icons.stroke(g, "crown", after, y + 16 + 3, 14, INK_SECONDARY);
            after += 14 + 6;
        }
        s.alpha(k * pulse(t));
        g.setColor(SUCCESS);
        g.fill(new Ellipse2D.Double(after, y + 16 + 6, 8, 8));
        s.alpha(k);
        put(g, a.role(), font(12, Weight.REGULAR), SplashWindow.INK_CAPTION, cx, y + 36, 20);

        long since = Math.max(0, t - start);
        double run = since / 1_000.0;
        Font f12 = font(12, Weight.REGULAR);
        put(g, "Gemini / gemini-2.5-flash", f12, INK_SECONDARY, cx, y + CARD_H - 16 - 36, 16);
        String figures = String.format(Locale.US, "%.1fk/1M", (a.tokens() + run * 900) / 1_000.0)
                + THIN_SEP + String.format(Locale.US, "$%.2f", a.cost() + run * 0.004)
                + THIN_SEP + clock(a.seconds() + (long) run);
        put(g, figures, f12, INK_SECONDARY, cx, y + CARD_H - 16 - 16, 16);

        Graphics2D bar = (Graphics2D) g.create();
        bar.clip(new RoundRectangle2D.Double(x + 1, y + 1, CARD_W - 2, CARD_H - 2, 22, 22));
        bar.setColor(LINE_50);
        bar.fillRect(x + 1, y + CARD_H - 4, CARD_W - 2, 3);
        bar.setColor(BRAND_SOLID);
        bar.fillRect(x + 1, y + CARD_H - 4, (int) Math.round((CARD_W - 2) * Math.min(0.6, 0.12 + run * 0.02)), 3);
        bar.dispose();

        // The marks: the activity changes every two seconds - a wait for the person in --warning, pulsing -
        // and the lead's schedule, a quiet Clock.
        String[] acts = a.marks();
        int which = (int) ((since / 2_000) % acts.length);
        double phase = (since % 2_000) / 2_000.0;
        double fade = clamp(Math.min(phase * 8, (1 - phase) * 8));
        boolean waiting = "inbox".equals(acts[which]);
        int mx = x + CARD_W - 4 - 14;
        if (a.lead()) {
            Icons.stroke(g, "clock", mx, y - 24 + 3, 14, INK_SECONDARY);
            mx -= 14 + 8 + 8;
        }
        s.alpha(k * fade * (waiting ? pulse(t) : 1));
        Icons.stroke(g, acts[which], mx, y - 24 + 3, 14, waiting ? WARNING : SplashWindow.INK_PRIMARY);
        s.alpha(k);
    }

    private static String clock(long seconds) {
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60);
    }

    private static void arrowDown(Graphics2D g, double x, double tipY) {
        Path2D p = new Path2D.Double();
        p.moveTo(x - 5, tipY - 10);
        p.lineTo(x + 5, tipY - 10);
        p.lineTo(x, tipY);
        p.closePath();
        g.fill(p);
    }

    // -- scene 5: the AI providers, and the models chosen from them -----------------------------------

    private record Provider(String name, String protocol, String mark, String caption) {}

    private static final Provider[] PROVIDERS = {
            new Provider("Gemini", "Google Gemini", "gemini", "Default address" + SEP + "key AIza...k9Qw"),
            new Provider("Ollama", "Ollama", "api", "http://localhost:11434" + SEP + "no key"),
            new Provider("OpenRouter", "OpenRouter", "api", "Default address" + SEP + "key sk-or...4f2a"),
            new Provider("Claude", "Anthropic", "claude", "Default address" + SEP + "key sk-ant...7Hc1")};

    /**
     * Settings' AI Providers tab (ProvidersTab: its title and intro, "Add provider", and a ListRow per
     * provider - the BrandMark, the name with its protocol after it, the address and key under it, and Test,
     * Edit and Delete as icon buttons, Test answering "Reachable - N models." in --success) beside the two
     * model fields chosen from them: the Agents tab's "Agent model" and the Search tab's "Embedding model".
     * (Each field's provider Select, beside it in the app, is left out for the width.)
     */
    private static void local(Stage s, long t) {
        Graphics2D g = s.g;
        s.alpha(1);
        sheet(g, 0, 0, SCENE_W, SCENE_H);
        int x = 24, w = 440;
        put(g, "AI Providers", font(14, Weight.SEMIBOLD), SplashWindow.INK_PRIMARY, x, 12, 20);
        put(g, "Add any service that speaks a supported protocol.", font(12, Weight.REGULAR), SplashWindow.INK_CAPTION,
                x, 36, 16);
        primaryButton(g, "Add provider", "plus", 14, x + w, 12, 36, 16);

        long tested = 1_800, testAt = 700;
        int ly = 64, rowsH = 0;
        for (int i = 0; i < PROVIDERS.length; i++) rowsH += 60 + (i == 0 ? 20 : 0);
        card(g, x, ly, w, rowsH + 16);
        int ry = ly + 8;
        for (int i = 0; i < PROVIDERS.length; i++) {
            Provider p = PROVIDERS[i];
            double k = in(t, 200 + i * 160L, 300);
            s.alpha(k);
            int rx = x + 24;
            Icons.mark(g, p.mark(), rx, ry + 12 + 1, 16, SplashWindow.BRAND_LEGIBLE);
            Font label = font(13, Weight.MEDIUM), cap = font(12, Weight.REGULAR);
            put(g, p.name(), label, SplashWindow.INK_PRIMARY, rx + 28, ry + 12, 18);
            put(g, p.protocol(), cap, SplashWindow.INK_CAPTION, rx + 28 + width(g, p.name(), label) + 8, ry + 13, 16);
            String[] parts = p.caption().split(SEP);
            int cxp = rx + 28;
            put(g, parts[0], cap, SplashWindow.INK_CAPTION, cxp, ry + 32, 16);
            cxp += width(g, parts[0], cap);
            put(g, SEP, cap, INK_DISABLED, cxp, ry + 32, 16);
            put(g, parts[1], cap, SplashWindow.INK_CAPTION, cxp + width(g, SEP, cap), ry + 32, 16);

            // RowActions: Test, Edit, then Delete in danger ink - 28px icon buttons with 16px icons.
            int ax = x + w - 24 - 28 * 3 - 8;
            boolean testing = i == 0 && t >= testAt && t < tested;
            if (testing) spinner(g, ax + 6, ry + 12 + 1, 16, t);
            else Icons.stroke(g, "plug-zap", ax + 6, ry + 12 + 1, 16, SplashWindow.INK_CAPTION);
            Icons.stroke(g, "pencil", ax + 28 + 4 + 6, ry + 12 + 1, 16, SplashWindow.INK_CAPTION);
            Icons.stroke(g, "trash-2", ax + 56 + 8 + 6, ry + 12 + 1, 16, DANGER);
            if (i == 0) {
                double ok = in(t, tested, 250);
                if (ok > 0) {
                    s.alpha(k * ok);
                    put(g, "Reachable - 38 models.", cap, SUCCESS, rx + 28, ry + 52, 16);
                }
            }
            s.alpha(1);
            int rh = 60 + (i == 0 ? 20 : 0);
            if (i < PROVIDERS.length - 1) {
                g.setColor(LINE_40);
                g.fillRect(x + 24, ry + rh, w - 48, 1);
            }
            ry += rh;
        }

        int rx = x + w + 24, rw = SCENE_W - 24 - rx;
        modelChoice(s, t, rx, 12, rw, "Main agent", "The agent that answers in your conversations.",
                "Agent model", "gemini-2.5-flash", 2_300);
        modelChoice(s, t, rx, 12 + MODEL_CARD_H + 16, rw, "Embedding", "The model that indexes documents for search.",
                "Embedding model", "gemini-embedding-001", 2_800);
    }

    private static final int MODEL_CARD_H = 162;

    /**
     * A SettingsSection in a card: its title (13 semibold) and caption (wrapping, as the app's does), then the model's Field - the
     * label (13 medium) over the Combobox (h-9, bg-field, a 1px --line edge, rounded-md, a chevron), the
     * model picked into it, and the focus border in --brand-solid while it is picked.
     */
    private static void modelChoice(Stage s, long t, int x, int y, int w, String heading, String caption, String label,
                                    String model, long pickedAt) {
        Graphics2D g = s.g;
        double k = in(t, 400, 300);
        if (k <= 0) return;
        s.alpha(k);
        card(g, x, y, w, MODEL_CARD_H);
        put(g, heading, font(13, Weight.SEMIBOLD), SplashWindow.INK_PRIMARY, x + 20, y + 18, 18);
        Font cap = font(12, Weight.REGULAR);
        String[] lines = wrap(g, caption, cap, w - 40);
        for (int i = 0; i < Math.min(2, lines.length); i++) {
            put(g, lines[i], cap, SplashWindow.INK_CAPTION, x + 20, y + 38 + i * 16, 16);
        }
        put(g, label, font(13, Weight.MEDIUM), SplashWindow.INK_PRIMARY, x + 20, y + 82, 18);
        int fy = y + 106, fw = w - 40;
        double picked = in(t, pickedAt, 300);
        g.setColor(WELL);
        g.fill(new RoundRectangle2D.Double(x + 20, fy, fw, 36, 16, 16));
        double ring = picked * (1 - in(t, pickedAt + 700, 400));
        g.setColor(SplashWindow.LINE);
        g.draw(new RoundRectangle2D.Double(x + 20.5, fy + 0.5, fw - 1, 35, 16, 16));
        if (ring > 0) {
            s.alpha(k * ring);
            g.setColor(BRAND_SOLID);
            g.draw(new RoundRectangle2D.Double(x + 20.5, fy + 0.5, fw - 1, 35, 16, 16));
            s.alpha(k);
        }
        Icons.stroke(g, "chevron-down", x + 20 + fw - 12 - 16, fy + 10, 16, SplashWindow.INK_CAPTION);
        if (picked > 0) {
            s.alpha(k * picked);
            put(g, model, font(13, Weight.REGULAR), SplashWindow.INK_PRIMARY, x + 20 + 12, fy + 9, 18);
        }
        s.alpha(k);
    }

    // -- drawing helpers -------------------------------------------------------------------------------

    /** .app-sheet: the page's color, rounded-xl (16px), its rgba(255,255,255,.07) edge. */
    private static void sheet(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(PAGE);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 32, 32));
        g.setColor(SHEET_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 32, 32));
    }

    /** .surface-card with shadow-card: rounded-lg (12px), #252525, its rgba(255,255,255,.08) edge. */
    private static void card(Graphics2D g, int x, int y, int w, int h) {
        cardShadow(g, x, y, w, h, 24);
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setColor(CARD_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 24, 24));
        insetHighlight(g, x, y, w, 24, new Color(255, 255, 255, 15));
    }

    /** A selected row or chip: bg-lift with shadow-card. */
    private static void lift(Graphics2D g, int x, int y, int w, int h, int arc, Color fill) {
        cardShadow(g, x, y, w, h, arc);
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
        insetHighlight(g, x, y, w, arc, new Color(255, 255, 255, 15));
    }

    /** shadow-card's outer part: 0 1px 2px rgba(0,0,0,.3) and 0 4px 12px -4px rgba(0,0,0,.35), softly. */
    private static void cardShadow(Graphics2D g, int x, int y, int w, int h, int arc) {
        for (int i = 3; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 18 * (4 - i)));
            g.fill(new RoundRectangle2D.Double(x - i + 2, y + 1 + i, w + 2 * i - 4, h, arc + i, arc + i));
        }
    }

    /** --shadow-float's outer part, under the composer. */
    private static void floatShadow(Graphics2D g, int x, int y, int w, int h) {
        for (int i = 8; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 10));
            g.fill(new RoundRectangle2D.Double(x - i + 4, y + 2 + i, w + 2 * i - 8, h, 24 + i, 24 + i));
        }
    }

    /** The inset 0 1px 0 highlight along a surface's top edge. */
    private static void insetHighlight(Graphics2D g, int x, int y, int w, int arc, Color c) {
        g.setColor(c);
        g.fillRect(x + arc / 2, y + 1, w - arc, 1);
    }

    /** A primary button (bg-brand-solid, white, 13px semibold, rounded-md) with a leading icon, its right edge at {@code right}. */
    private static void primaryButton(Graphics2D g, String label, String icon, int iconSize, int right, int top, int h, int px) {
        Font f = font(13, Weight.SEMIBOLD);
        int w = Math.max(100, width(g, label, f) + iconSize + 8 + 2 * px);
        g.setColor(BRAND_SOLID);
        g.fill(new RoundRectangle2D.Double(right - w, top, w, h, 16, 16));
        int cx = right - w + (w - (iconSize + 8 + width(g, label, f))) / 2;
        Icons.stroke(g, icon, cx, top + (h - iconSize) / 2.0, iconSize, Color.WHITE);
        put(g, label, f, Color.WHITE, cx + iconSize + 8, top, h);
    }

    /** An sm button's width: px-3 either side of 13px semibold, at least 100px. */
    private static int buttonWidth(Graphics2D g, String label) {
        return Math.max(100, width(g, label, font(13, Weight.SEMIBOLD)) + 24);
    }

    /** A MetaList of figures: each number then its words, in caption ink, the middle dot in disabled ink. */
    private static void metaLine(Graphics2D g, int x, int top, String[][] items) {
        Font f = font(12, Weight.REGULAR);
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                put(g, SEP, f, INK_DISABLED, x, top, 16);
                x += width(g, SEP, f);
            }
            put(g, items[i][0] + items[i][1], f, SplashWindow.INK_CAPTION, x, top, 16);
            x += width(g, items[i][0] + items[i][1], f);
        }
    }

    /** {@code s} in a line box of {@code lineHeight} whose top is {@code top}, as CSS centers the face in it. */
    private static void put(Graphics2D g, String s, Font f, Color c, int x, double top, double lineHeight) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x, baseline(g, f, top, lineHeight));
    }

    private static void rightTop(Graphics2D g, String s, Font f, Color c, int right, double top, double lineHeight) {
        put(g, s, f, c, right - width(g, s, f), top, lineHeight);
    }

    private static void centeredTop(Graphics2D g, String s, Font f, Color c, int cx, double top, double lineHeight) {
        put(g, s, f, c, cx - width(g, s, f) / 2, top, lineHeight);
    }

    static int baseline(Graphics2D g, Font f, double top, double lineHeight) {
        FontMetrics fm = g.getFontMetrics(f);
        return (int) Math.round(top + (lineHeight - (fm.getAscent() + fm.getDescent())) / 2.0 + fm.getAscent());
    }

    private static int width(Graphics2D g, String s, Font f) {
        return g.getFontMetrics(f).stringWidth(s);
    }

    private static void centeredAt(Graphics2D g, String s, Font f, Color c, int cx, int baseline) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, baseline);
    }

    /** {@code s} broken into lines of at most {@code maxW} at its spaces, as a paragraph wraps. */
    static String[] wrap(Graphics2D g, String s, Font f, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width(g, next, f) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    /** {@code s}, cut with an ellipsis to {@code maxW}, as the app's truncate does. */
    private static String fit(Graphics2D g, String s, Font f, int maxW) {
        FontMetrics fm = g.getFontMetrics(f);
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s.trim() + "...";
    }

    /** animate-pulse, Tailwind's: opacity 1 to 0.5 and back every 2s. */
    private static double pulse(long t) {
        return 0.75 + 0.25 * Math.cos(t / 2_000.0 * 2 * Math.PI);
    }

    /**
     * `.text-wave` (proxyma-primitives.css): the words at 35% with a soft full-strength band sweeping
     * left to right every 1.8s, linear.
     */
    private static void waveText(Graphics2D g, String s, Font f, Color c, int x, int baseline, long t) {
        g.setFont(f);
        int w = Math.max(1, g.getFontMetrics().stringWidth(s));
        double p = (t % 1_800) / 1_800.0;
        float center = (float) (x - w + 3 * w * p);
        float half = 0.42f * w;
        Color dim = new Color(c.getRed(), c.getGreen(), c.getBlue(), 89);
        g.setPaint(new LinearGradientPaint(center - half, 0, center + half, 0,
                new float[] {0f, 0.5f, 1f}, new Color[] {dim, c, dim}, MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.drawString(s, x, baseline);
        g.setPaint(null);
    }

    /** The kit's Spinner: a three-quarter arc turning, in --brand-legible, {@code size} across. */
    private static void spinner(Graphics2D g, int x, int y, int size, long t) {
        g.setColor(SplashWindow.BRAND_LEGIBLE);
        g.setStroke(new BasicStroke(size / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double inset = size / 8.0;
        g.draw(new java.awt.geom.Arc2D.Double(x + inset, y + inset, size - 2 * inset, size - 2 * inset,
                -t * 0.45 % 360, 270, java.awt.geom.Arc2D.OPEN));
        g.setStroke(new BasicStroke(1f));
    }

    /** lucide ChevronRight at 14px, turned a quarter as {@code open} goes from 0 to 1. */
    private static void chevron(Graphics2D g, int x, int y, double open, Color c) {
        Graphics2D r = (Graphics2D) g.create();
        r.rotate(Math.PI / 2 * open, x + 7, y + 7);
        Icons.stroke(r, "chevron-right", x, y, 14, c);
        r.dispose();
    }

    private static void cursor(Graphics2D g, int x, int y) {
        Polygon p = new Polygon(new int[] {0, 0, 4, 7, 9, 6, 11}, new int[] {0, 14, 10, 16, 15, 9, 9}, 7);
        p.translate(x, y);
        g.setColor(SplashWindow.INK_PRIMARY);
        g.fillPolygon(p);
        g.setColor(FRAME);
        g.drawPolygon(p);
    }

    // -- geometry -------------------------------------------------------------------------------------

    /** A polyline whose bends are rounded to {@code radius}, as the canvas draws its lines. */
    static Shape orth(double[] xy, double radius) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int k = 2; k < xy.length - 2; k += 2) {
            double px = xy[k - 2], py = xy[k - 1], cx = xy[k], cy = xy[k + 1], nx = xy[k + 2], ny = xy[k + 3];
            double in = Math.min(radius, Math.hypot(cx - px, cy - py) / 2);
            double out = Math.min(radius, Math.hypot(nx - cx, ny - cy) / 2);
            double r = Math.min(in, out);
            Point2D a = toward(cx, cy, px, py, r), b = toward(cx, cy, nx, ny, r);
            p.lineTo(a.getX(), a.getY());
            p.quadTo(cx, cy, b.getX(), b.getY());
        }
        p.lineTo(xy[xy.length - 2], xy[xy.length - 1]);
        return p;
    }

    private static Point2D toward(double x, double y, double tx, double ty, double d) {
        double len = Math.hypot(tx - x, ty - y);
        if (len == 0) return new Point2D.Double(x, y);
        return new Point2D.Double(x + (tx - x) / len * d, y + (ty - y) / len * d);
    }

    /** The point {@code f} (0..1) of the way along a polyline, by length. */
    static Point2D along(double[] xy, double f) {
        double total = 0;
        for (int k = 2; k < xy.length; k += 2) total += Math.hypot(xy[k] - xy[k - 2], xy[k + 1] - xy[k - 1]);
        double want = clamp(f) * total;
        for (int k = 2; k < xy.length; k += 2) {
            double seg = Math.hypot(xy[k] - xy[k - 2], xy[k + 1] - xy[k - 1]);
            if (want <= seg || k == xy.length - 2) {
                double u = seg == 0 ? 0 : Math.min(1, want / seg);
                return new Point2D.Double(xy[k - 2] + (xy[k] - xy[k - 2]) * u, xy[k - 1] + (xy[k + 1] - xy[k - 1]) * u);
            }
            want -= seg;
        }
        return new Point2D.Double(xy[0], xy[1]);
    }

    // -- easing ---------------------------------------------------------------------------------------

    static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** 0..1 over [start, start + dur], eased out. */
    private static double in(long t, long start, long dur) {
        return easeOut(clamp((t - start) / (double) dur));
    }

    private static double easeOut(double x) {
        return 1 - Math.pow(1 - x, 3);
    }

    private static double easeInOut(double x) {
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }

    // -- the faces, loaded once -------------------------------------------------------------------------

    /**
     * The weights the app loads (proxyma-fonts.css: Inter 400, 500, 600 and 700), each from its own cut -
     * Medium and SemiBold were drawn as Regular and as Java2D's synthesized bold, which is heavier than
     * any weight the app uses. The two extra cuts are @fontsource/inter's latin files, the data the app
     * bundle ships, unwrapped from WOFF (SIL OFL, OFL-Inter.txt beside them).
     */
    enum Weight { REGULAR, MEDIUM, SEMIBOLD }

    private static final Font INTER = load("/fonts/Inter.ttf");
    private static final Font INTER_MEDIUM = load("/fonts/Inter-Medium.ttf");
    private static final Font INTER_SEMIBOLD = load("/fonts/Inter-SemiBold.ttf");

    private static Font load(String path) {
        try (InputStream in = SplashSlides.class.getResourceAsStream(path)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) {
            // The platform sans reads fine; a missing font is no reason to fail a start.
        }
        return null;
    }

    /** Whether every cut the slides draw with was found - false only for a jar that lost its fonts. */
    static boolean facesLoaded() {
        return INTER != null && INTER_MEDIUM != null && INTER_SEMIBOLD != null;
    }

    /** Inter at {@code size}: semibold for the slide's title, regular otherwise. */
    static Font ui(float size, boolean semibold) {
        return font(size, semibold ? Weight.SEMIBOLD : Weight.REGULAR);
    }

    static Font font(float size, Weight weight) {
        Font cut = switch (weight) {
            case REGULAR -> INTER;
            case MEDIUM -> INTER_MEDIUM;
            case SEMIBOLD -> INTER_SEMIBOLD;
        };
        if (cut != null) return cut.deriveFont(Font.PLAIN, size);
        Font base = INTER != null ? INTER : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(weight == Weight.SEMIBOLD ? Font.BOLD : Font.PLAIN, size);
    }
}
