package ai.proxyma.desktop.launcher;

import java.awt.geom.Path2D;

/**
 * SVG path data ({@code d}) as a Java2D path, so the splash draws the app's icons from the very paths the
 * app draws them from (lucide's, and the kit's BrandMark) rather than from shapes transcribed by hand.
 *
 * <p>Every command of the SVG grammar, absolute and relative: M L H V C S Q T A Z. An arc is turned into
 * cubic curves of at most a quarter turn each. Numbers may run together as minified paths write them
 * ({@code .5-.3}, {@code 1e-3}), and an arc's two flags may be written without a separator
 * ({@code a1 1 0 011 1}), which Simple Icons' paths do.
 */
final class SvgPath {

    private SvgPath() {}

    static Path2D.Double parse(String d) {
        return new Parser(d).run();
    }

    private static final class Parser {
        private final String s;
        private int i;
        private final Path2D.Double p = new Path2D.Double();
        private double x, y, startX, startY;
        // The last control point, for S and T, which reflect it.
        private double ctrlX, ctrlY;
        private char last;

        Parser(String s) {
            this.s = s;
        }

        Path2D.Double run() {
            char cmd = 0;
            while (true) {
                skip();
                if (i >= s.length()) break;
                char c = s.charAt(i);
                if (Character.isLetter(c) && c != 'e' && c != 'E') {
                    cmd = c;
                    i++;
                } else if (cmd == 0) {
                    throw new IllegalArgumentException("Path data must start with a command: " + s);
                } else if (cmd == 'M') {
                    cmd = 'L';
                } else if (cmd == 'm') {
                    cmd = 'l';
                }
                command(cmd);
                if (cmd == 'Z' || cmd == 'z') cmd = 0;
            }
            return p;
        }

        private void command(char cmd) {
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num(); y = oy + num();
                    p.moveTo(x, y);
                    startX = x; startY = y;
                    set(x, y, 'M');
                }
                case 'L' -> { x = ox + num(); y = oy + num(); p.lineTo(x, y); set(x, y, 'L'); }
                case 'H' -> { x = ox + num(); p.lineTo(x, y); set(x, y, 'L'); }
                case 'V' -> { y = oy + num(); p.lineTo(x, y); set(x, y, 'L'); }
                case 'C' -> {
                    double x1 = ox + num(), y1 = oy + num(), x2 = ox + num(), y2 = oy + num();
                    x = ox + num(); y = oy + num();
                    p.curveTo(x1, y1, x2, y2, x, y);
                    set(x2, y2, 'C');
                }
                case 'S' -> {
                    double x1 = last == 'C' ? 2 * x - ctrlX : x, y1 = last == 'C' ? 2 * y - ctrlY : y;
                    double x2 = ox + num(), y2 = oy + num();
                    x = ox + num(); y = oy + num();
                    p.curveTo(x1, y1, x2, y2, x, y);
                    set(x2, y2, 'C');
                }
                case 'Q' -> {
                    double x1 = ox + num(), y1 = oy + num();
                    x = ox + num(); y = oy + num();
                    p.quadTo(x1, y1, x, y);
                    set(x1, y1, 'Q');
                }
                case 'T' -> {
                    double x1 = last == 'Q' ? 2 * x - ctrlX : x, y1 = last == 'Q' ? 2 * y - ctrlY : y;
                    x = ox + num(); y = oy + num();
                    p.quadTo(x1, y1, x, y);
                    set(x1, y1, 'Q');
                }
                case 'A' -> {
                    double rx = num(), ry = num(), rot = num();
                    boolean large = flag(), sweep = flag();
                    double x2 = ox + num(), y2 = oy + num();
                    arc(p, x, y, rx, ry, rot, large, sweep, x2, y2);
                    x = x2; y = y2;
                    set(x, y, 'A');
                }
                case 'Z' -> {
                    p.closePath();
                    x = startX; y = startY;
                    set(x, y, 'Z');
                }
                default -> throw new IllegalArgumentException("Unknown path command '" + cmd + "' in " + s);
            }
        }

        private void set(double cx, double cy, char kind) {
            ctrlX = cx; ctrlY = cy; last = kind;
        }

        private void skip() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        }

        private boolean flag() {
            skip();
            char c = s.charAt(i++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("Bad arc flag '" + c + "' in " + s);
            return c == '1';
        }

        private double num() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) i++;
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (start == i) throw new IllegalArgumentException("Expected a number at " + i + " in " + s);
            return Double.parseDouble(s.substring(start, i));
        }
    }

    /** An endpoint arc (SVG 1.1, appendix F.6.5) as cubic curves of at most a quarter turn. */
    static void arc(Path2D p, double x1, double y1, double rx, double ry, double rotDeg,
                    boolean large, boolean sweep, double x2, double y2) {
        if (x1 == x2 && y1 == y2) return;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            p.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            double k = Math.sqrt(lambda);
            rx *= k;
            ry *= k;
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rx * y1p / ry, cyp = -coef * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2, cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
        double ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry, vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;
        double theta = Math.atan2(uy, ux);
        double delta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9);
        double step = delta / segs, t = 4.0 / 3.0 * Math.tan(step / 4);
        for (int k = 0; k < segs; k++) {
            double a1 = theta + k * step, a2 = a1 + step;
            double c1x = Math.cos(a1) - t * Math.sin(a1), c1y = Math.sin(a1) + t * Math.cos(a1);
            double c2x = Math.cos(a2) + t * Math.sin(a2), c2y = Math.sin(a2) - t * Math.cos(a2);
            double ex = Math.cos(a2), ey = Math.sin(a2);
            p.curveTo(cx + rx * c1x * cos - ry * c1y * sin, cy + rx * c1x * sin + ry * c1y * cos,
                    cx + rx * c2x * cos - ry * c2y * sin, cy + rx * c2x * sin + ry * c2y * cos,
                    k == segs - 1 ? x2 : cx + rx * ex * cos - ry * ey * sin,
                    k == segs - 1 ? y2 : cy + rx * ex * sin + ry * ey * cos);
        }
    }
}
