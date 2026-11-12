package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The splash's slides (the maintainer, 2026-10-31). A slide that throws while painting ends the
 * splash at the start of every launch - a negative dash phase did, before this test - so every
 * moment of every slide is painted here, off screen.
 */
class SplashSlidesTest {

    private static final int W = SplashWindow.W;

    @Test
    void everyMomentOfEverySlidePaints() {
        BufferedImage img = new BufferedImage(W, SplashWindow.H, BufferedImage.TYPE_INT_ARGB);
        long total = SplashSlides.SLIDE_MS * SplashSlides.SLIDES.size();
        for (int first = 0; first < SplashSlides.SLIDES.size(); first++) {
            int f = first;
            for (long t = 0; t < total; t += 50) {
                long at = t;
                Graphics2D g = img.createGraphics();
                assertThatCode(() -> SplashSlides.paint(g, W, at, f))
                        .as("slide %d at %d ms", SplashSlides.index(at, f), SplashSlides.local(at))
                        .doesNotThrowAnyException();
                g.dispose();
            }
        }
    }

    @Test
    void everyTitleAndLineFitsTheWindow() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics title = g.getFontMetrics(SplashSlides.ui(SplashSlides.TITLE_SIZE, true));
        FontMetrics line = g.getFontMetrics(SplashSlides.ui(SplashSlides.LINE_SIZE, false));
        for (SplashSlides.Slide s : SplashSlides.SLIDES) {
            assertThat(title.stringWidth(s.title())).as(s.title()).isLessThan(W - 80);
            assertThat(line.stringWidth(s.line())).as(s.line()).isLessThan(W - 80);
        }
    }

    @Test
    void theShowStepsThroughEverySlideFromTheFirstAndWrapsAround() {
        long step = SplashSlides.SLIDE_MS;
        assertThat(SplashSlides.index(0, 0)).isZero();
        assertThat(SplashSlides.index(step - 1, 0)).isZero();
        assertThat(SplashSlides.index(step, 0)).isEqualTo(1);
        assertThat(SplashSlides.index(step * SplashSlides.SLIDES.size(), 0)).isZero();
        assertThat(SplashSlides.index(0, 3)).isEqualTo(3);
        assertThat(SplashSlides.index(step * 2, SplashSlides.SLIDES.size() - 1)).isEqualTo(1);
    }

    @Test
    void aSlideFadesInAndOutAndIsWhollyThereBetween() {
        assertThat(SplashSlides.opacity(0)).isZero();
        assertThat(SplashSlides.opacity(SplashSlides.FADE_MS)).isEqualTo(1.0);
        assertThat(SplashSlides.opacity(SplashSlides.SLIDE_MS / 2)).isEqualTo(1.0);
        assertThat(SplashSlides.opacity(SplashSlides.SLIDE_MS - SplashSlides.FADE_MS / 2)).isEqualTo(0.5);
    }

    @Test
    void theDotsAreApartAndCentered() {
        int n = SplashSlides.SLIDES.size();
        Rectangle first = SplashSlides.dot(0, W), last = SplashSlides.dot(n - 1, W);
        assertThat(first.getCenterX() + last.getCenterX()).isEqualTo(W, org.assertj.core.data.Offset.offset(1.0));
        for (int i = 1; i < n; i++) {
            assertThat(SplashSlides.dot(i, W).intersects(SplashSlides.dot(i - 1, W))).isFalse();
        }
    }

    @Test
    void anAgentsColorIsMultipliedOverTheCardTheMaskMarksAndNowhereElse() {
        BufferedImage robot = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        robot.setRGB(0, 0, 0xFFC8C8C8);
        robot.setRGB(1, 0, 0xFFC8C8C8);
        BufferedImage mask = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        mask.setRGB(0, 0, 0x00000000);
        mask.setRGB(1, 0, 0xFF000000);
        BufferedImage out = AgentArt.tint(robot, mask, new Color(0x80, 0xFF, 0x00));
        assertThat(out.getRGB(0, 0)).isEqualTo(0xFFC8C8C8);
        assertThat(out.getRGB(1, 0)).isEqualTo(0xFF64C800);
    }

    /**
     * The app draws Inter at 400, 500 and 600 and the wordmark in Orbitron 700 (2026-11-10: the splash drew
     * 500 as 400, 600 as a synthesized bold, and the wordmark as Orbitron's variable font thickened).
     */
    @Test
    void theFacesAreTheCutsTheAppDrawsWith() {
        assertThat(SplashSlides.facesLoaded()).isTrue();
        assertThat(SplashSlides.font(13, SplashSlides.Weight.REGULAR).getFontName()).isEqualTo("Inter Regular");
        assertThat(SplashSlides.font(13, SplashSlides.Weight.MEDIUM).getFontName()).isEqualTo("Inter Medium");
        assertThat(SplashSlides.font(13, SplashSlides.Weight.SEMIBOLD).getFontName()).isEqualTo("Inter SemiBold");
        assertThat(SplashSlides.font(13, SplashSlides.Weight.SEMIBOLD).isBold()).as("never synthesized").isFalse();
        assertThat(SplashWindow.brandFaceLoaded()).isTrue();
        assertThat(SplashWindow.brandFont(13).getFontName()).isEqualTo("Orbitron Bold");
        assertThat(SplashWindow.brandFont(13).isBold()).as("never synthesized").isFalse();
        // Every kind of character a slide writes is in the cuts' latin subset.
        for (SplashSlides.Weight w : SplashSlides.Weight.values()) {
            assertThat(SplashSlides.font(13, w).canDisplayUpTo("Proxyma 0123456789 $%/.,:-@" + (char) 0xB7 + (char) 0x2009))
                    .as(w.name()).isEqualTo(-1);
        }
    }

    /** Every icon and mark is the app's own path data, and each lies in its 24-unit box. */
    @Test
    void everyIconParsesInsideItsBox() {
        assertThat(Icons.strokeNames()).contains("square-pen", "message-square", "messages-square", "orbit", "network");
        assertThat(Icons.markNames()).contains("files", "confluence", "jira", "youtube", "gemini");
        for (String name : Icons.strokeNames()) {
            Rectangle2D b = Icons.strokeShape(name).getBounds2D();
            assertThat(b.getWidth() + b.getHeight()).as(name).isPositive();
            assertThat(inBox(b)).as(name + " " + b).isTrue();
        }
        for (String id : Icons.markNames()) {
            Rectangle2D b = Icons.markShape(id).getBounds2D();
            assertThat(b.getWidth() * b.getHeight()).as(id).isPositive();
            assertThat(inBox(b)).as(id + " " + b).isTrue();
        }
        assertThatThrownBy(() -> Icons.strokeShape("not-an-icon")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Within the 24-unit box, give or take half a unit - a line has no height, so not Rectangle2D.contains. */
    private static boolean inBox(Rectangle2D b) {
        return b.getMinX() >= -0.5 && b.getMinY() >= -0.5 && b.getMaxX() <= 24.5 && b.getMaxY() <= 24.5;
    }

    /** ChatWindow pins the question once its bottom passes the scroller's top plus PIN_BAR_PX (40). */
    @Test
    void theQuestionIsPinnedOnceItsBottomPassesThePinBar() {
        double qTop = 64, bottomUnscrolled = qTop + 36, edge = 44 + 40;
        assertThat(SplashSlides.pinShown(qTop, 0)).isZero();
        assertThat(SplashSlides.pinShown(qTop, bottomUnscrolled - edge)).isEqualTo(1.0);
        assertThat(SplashSlides.pinShown(qTop, bottomUnscrolled - edge - 6)).isZero();
    }

    @Test
    void aCaptionWrapsAtItsSpaces() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        Font f = SplashSlides.font(12, SplashSlides.Weight.REGULAR);
        String[] lines = SplashSlides.wrap(g, "The agent that answers in your conversations.", f, 180);
        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(String.join(" ", lines)).isEqualTo("The agent that answers in your conversations.");
        for (String line : lines) assertThat(g.getFontMetrics(f).stringWidth(line)).isLessThanOrEqualTo(180);
    }

    @Test
    void aPointAlongALineIsMeasuredByLength() {
        double[] path = {0, 0, 0, 10, 30, 10};
        assertThat(SplashSlides.along(path, 0)).isEqualTo(new Point2D.Double(0, 0));
        assertThat(SplashSlides.along(path, 0.25)).isEqualTo(new Point2D.Double(0, 10));
        assertThat(SplashSlides.along(path, 1)).isEqualTo(new Point2D.Double(30, 10));
    }
}
