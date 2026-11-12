package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * SVG path data as the splash reads it (2026-11-10): the app's icons are drawn from lucide's and the
 * kit's own path strings, so every way those strings are written has to read as a browser reads it.
 */
class SvgPathTest {

    @Test
    void numbersAfterAMoveAreLinesAndLowercaseIsRelative() {
        // lucide's X: "M18 6 6 18" is a move then a line; "m6 6 12 12" a move then a RELATIVE line.
        Rectangle2D b = SvgPath.parse("m6 6 12 12").getBounds2D();
        assertThat(b).isEqualTo(new Rectangle2D.Double(6, 6, 12, 12));
        Rectangle2D a = SvgPath.parse("M18 6 6 18").getBounds2D();
        assertThat(a).isEqualTo(new Rectangle2D.Double(6, 6, 12, 12));
    }

    @Test
    void numbersRunTogetherAsMinifiedPathsWriteThem() {
        Rectangle2D b = SvgPath.parse("M.5.5l2-.5h-1e-1v1.5z").getBounds2D();
        assertThat(b.getMinX()).isEqualTo(0.5, offset(1e-9));
        assertThat(b.getMinY()).isEqualTo(0.0, offset(1e-9));
        assertThat(b.getMaxX()).isEqualTo(2.5, offset(1e-9));
        assertThat(b.getMaxY()).isEqualTo(1.5, offset(1e-9));
    }

    @Test
    void anArcIsDrawnOnItsCircleAndItsFlagsMayRunTogether() {
        // A circle of radius 10 about (12, 12), as the icon table writes lucide's <circle>.
        Rectangle2D circle = SvgPath.parse("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z").getBounds2D();
        assertThat(circle.getMinX()).isEqualTo(2, offset(0.01));
        assertThat(circle.getMinY()).isEqualTo(2, offset(0.01));
        assertThat(circle.getMaxX()).isEqualTo(22, offset(0.01));
        assertThat(circle.getMaxY()).isEqualTo(22, offset(0.01));
        // Simple Icons' compact flags: "011" is large-arc 0, sweep 1, then the x coordinate 1.
        Path2D compact = SvgPath.parse("M0 0a1 1 0 011 1");
        Path2D spaced = SvgPath.parse("M0 0a1 1 0 0 1 1 1");
        assertThat(compact.getBounds2D()).isEqualTo(spaced.getBounds2D());
        assertThat(compact.getCurrentPoint()).isEqualTo(spaced.getCurrentPoint());
    }

    @Test
    void smoothCurvesReflectTheLastControlPoint() {
        Path2D p = SvgPath.parse("M0 0C0 10 10 10 10 0S20 -10 20 0");
        assertThat(p.getCurrentPoint().getX()).isEqualTo(20);
        assertThat(p.getBounds2D().getMinY()).isLessThan(0);
        assertThat(p.getBounds2D().getMaxY()).isGreaterThan(0);
    }

    @Test
    void dataThatIsNotAPathIsRefused() {
        assertThatThrownBy(() -> SvgPath.parse("12 12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgPath.parse("M1 1a1 1 0 2 0 3 3")).isInstanceOf(IllegalArgumentException.class);
    }
}
