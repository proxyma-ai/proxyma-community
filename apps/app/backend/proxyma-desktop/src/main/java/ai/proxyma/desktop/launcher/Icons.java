package ai.proxyma.desktop.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE APP'S OWN ICONS, for the splash (the maintainer, 2026-11-10: the splash must reflect the app,
 * "including font, design, layout, icon"). The slides used to draw shapes transcribed by hand after
 * lucide - and several were the wrong icon or a rough likeness of the right one.
 *
 * <p>Two sets, as the app has them:
 * <ul>
 *   <li>{@link #stroke}: lucide icons, exactly as lucide-react draws them - a 24-unit box, a 2-unit
 *   stroke, round caps and joins, no fill. The paths are lucide-react's own {@code __iconNode} data,
 *   every circle, rect and line written as path data.</li>
 *   <li>{@link #mark}: the kit's BrandMark (libs/ui-kit/react/BrandMark.tsx) - filled silhouettes on
 *   the same box, evenodd, in one ink - which the app draws beside every source and provider.</li>
 * </ul>
 *
 * <p>GENERATED from those two sources by a script that imports each icon's {@code __iconNode} and
 * reads each mark's path out of BrandMark.tsx; run it again after the app changes an icon the splash
 * shows. A name the tables lack throws, which SplashSlidesTest turns into a failed build rather than a
 * blank space on a slide.
 */
final class Icons {

    private Icons() {}

    // lucide-react 1.21.0
    private static final Map<String, String> LUCIDE = Map.ofEntries(
            Map.entry("square-pen", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7 | M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z"),
            Map.entry("message-square", "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z"),
            Map.entry("database", "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0Z | M3 5V19A9 3 0 0 0 21 19V5 | M3 12A9 3 0 0 0 21 12"),
            Map.entry("messages-square", "M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z | M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1"),
            Map.entry("circle-play", "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z | M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z"),
            Map.entry("orbit", "M20.341 6.484A10 10 0 0 1 10.266 21.85 | M3.659 17.516A10 10 0 0 1 13.74 2.152 | M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z | M17 5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z | M3 19a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z"),
            Map.entry("panel-left-close", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z | M9 3v18 | m16 15-3-3 3-3"),
            Map.entry("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8 | M3 3v5h5 | M12 7v5l4 2"),
            Map.entry("search", "m21 21-4.34-4.34 | M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z"),
            Map.entry("file-text", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z | M14 2v5a1 1 0 0 0 1 1h5 | M10 9H8 | M16 13H8 | M16 17H8"),
            Map.entry("file-plus", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z | M14 2v5a1 1 0 0 0 1 1h5 | M9 15h6 | M12 18v-6"),
            Map.entry("pen-line", "M13 21h8 | M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"),
            Map.entry("chevron-right", "m9 18 6-6-6-6"),
            Map.entry("chevron-down", "m6 9 6 6 6-6"),
            Map.entry("plus", "M5 12h14 | M12 5v14"),
            Map.entry("hand", "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2 | M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2 | M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8 | M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"),
            Map.entry("arrow-left", "m12 19-7-7 7-7 | M19 12H5"),
            Map.entry("layout-dashboard", "M4 3h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z | M15 3h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z | M15 12h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z | M4 16h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z"),
            Map.entry("network", "M17 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z | M3 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z | M10 2h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z | M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3 | M12 12V8"),
            Map.entry("inbox", "M22 12L16 12L14 15L10 15L8 12L2 12 | M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"),
            Map.entry("message-circle-question-mark", "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719 | M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3 | M12 17h.01"),
            Map.entry("settings", "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915 | M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z"),
            Map.entry("pause", "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z | M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z"),
            Map.entry("crown", "M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294z | M5 21h14"),
            Map.entry("clock", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z | M12 6v6l4 2"),
            Map.entry("brain", "M12 18V5 | M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4 | M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5 | M17.997 5.125a4 4 0 0 1 2.526 5.77 | M18 18a4 4 0 0 0 2-7.464 | M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517 | M6 18a4 4 0 0 1-2-7.464 | M6.003 5.125a4 4 0 0 0-2.526 5.77"),
            Map.entry("send", "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z | m21.854 2.147-10.94 10.939"),
            Map.entry("file-pen", "M12.659 22H18a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v9.34 | M14 2v5a1 1 0 0 0 1 1h5 | M10.378 12.622a1 1 0 0 1 3 3.003L8.36 20.637a2 2 0 0 1-.854.506l-2.867.837a.5.5 0 0 1-.62-.62l.836-2.869a2 2 0 0 1 .506-.853z"),
            Map.entry("minus", "M5 12h14"),
            Map.entry("square", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z"),
            Map.entry("x", "M18 6 6 18 | m6 6 12 12"),
            Map.entry("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z | M7 11V7a5 5 0 0 1 10 0v4"),
            Map.entry("plug-zap", "M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4Z | m2 22 3-3 | M7.5 13.5 10 11 | M10.5 16.5 13 14 | m18 3-4 4h6l-4 4"),
            Map.entry("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z | m15 5 4 4"),
            Map.entry("trash-2", "M10 11v6 | M14 11v6 | M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6 | M3 6h18 | M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
            Map.entry("corner-down-right", "m15 10 5 5-5 5 | M4 4v7a4 4 0 0 0 4 4h12"),
            Map.entry("square-arrow-out-up-right", "M21 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h6 | m21 3-9 9 | M15 3h6v6"),
            Map.entry("check", "M20 6 9 17l-5-5"),
            Map.entry("list-filter", "M2 5h20 | M6 12h12 | M9 19h6"),
            Map.entry("arrow-up-down", "m21 16-4 4-4-4 | M17 20V4 | m3 8 4-4 4 4 | M7 4v16"),
            Map.entry("arrow-up", "m5 12 7-7 7 7 | M12 19V5"),
            Map.entry("bot-message-square", "M12 6V2H8 | M15 11v2 | M2 12h2 | M20 12h2 | M20 16a2 2 0 0 1-2 2H8.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 4 20.286V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z | M9 11v2"));

    private static final Map<String, String> MARKS = Map.ofEntries(
            Map.entry("files", "M2 5.5A1.5 1.5 0 0 1 3.5 4h5.4a1.5 1.5 0 0 1 1.06.44L11.5 6h9A1.5 1.5 0 0 1 22 7.5v11a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5Z"),
            Map.entry("web", "M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20ZM12 2.3a4.75 9.7 0 1 0 0 19.4 4.75 9.7 0 1 0 0-19.4ZM12 2.3a3.25 9.7 0 1 0 0 19.4 3.25 9.7 0 1 0 0-19.4ZM2.2 11.25h19.6v1.5H2.2Z"),
            Map.entry("jira", "M11.571 11.513H0a5.218 5.218 0 0 0 5.232 5.215h2.13v2.057A5.215 5.215 0 0 0 12.575 24V12.518a1.005 1.005 0 0 0-1.005-1.005zm5.723-5.756H5.736a5.215 5.215 0 0 0 5.215 5.214h2.129v2.058a5.218 5.218 0 0 0 5.215 5.214V6.758a1.001 1.001 0 0 0-1.001-1.001zM23.013 0H11.455a5.215 5.215 0 0 0 5.215 5.215h2.129v2.057A5.215 5.215 0 0 0 24 12.483V1.005A1.001 1.001 0 0 0 23.013 0Z"),
            Map.entry("confluence", "M.87 18.257c-.248.382-.53.875-.763 1.245a.764.764 0 0 0 .255 1.04l4.965 3.054a.764.764 0 0 0 1.058-.26c.199-.332.454-.763.733-1.221 1.967-3.247 3.945-2.853 7.508-1.146l4.957 2.337a.764.764 0 0 0 1.028-.382l2.364-5.346a.764.764 0 0 0-.382-1 599.851 599.851 0 0 1-4.965-2.361C10.911 10.97 5.224 11.185.87 18.257zM23.131 5.743c.249-.405.531-.875.764-1.25a.764.764 0 0 0-.256-1.034L18.675.404a.764.764 0 0 0-1.058.26c-.195.335-.451.763-.734 1.225-1.966 3.246-3.945 2.85-7.508 1.146L4.437.694a.764.764 0 0 0-1.027.382L1.046 6.422a.764.764 0 0 0 .382 1c1.039.49 3.105 1.467 4.965 2.361 6.698 3.246 12.392 3.029 16.738-4.04z"),
            Map.entry("googledrive", "M2 5.5A1.5 1.5 0 0 1 3.5 4h5.4a1.5 1.5 0 0 1 1.06.44L11.5 6h9A1.5 1.5 0 0 1 22 7.5v11a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5ZM9 17.5a2.5 2.5 0 0 1-.4-4.97A3.5 3.5 0 0 1 15.3 11.5a2.5 2.5 0 0 1-.3 6Z"),
            Map.entry("gmail", "M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4Zm1.2 3.2v2.3l7.3 4.8 7.3-4.8V7.2L12 12Z"),
            Map.entry("imap", "M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4Zm1.2 3.2v2.3l7.3 4.8 7.3-4.8V7.2L12 12Z"),
            Map.entry("gemini", "M10 4l2.3 6.7L19 13l-6.7 2.3L10 22l-2.3-6.7L1 13l6.7-2.3ZM19 1l1 3 3 1-3 1-1 3-1-3-3-1 3-1Z"),
            Map.entry("chatgpt", "M4.5 3h15A1.5 1.5 0 0 1 21 4.5v11a1.5 1.5 0 0 1-1.5 1.5H10l-5 4.5V17h-.5A1.5 1.5 0 0 1 3 15.5v-11A1.5 1.5 0 0 1 4.5 3ZM12 5.5l1.2 3.3 3.3 1.2-3.3 1.2-1.2 3.3-1.2-3.3L7.5 10l3.3-1.2Z"),
            Map.entry("claude", "m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z"),
            Map.entry("api", "M3.5 3h17A1.5 1.5 0 0 1 22 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 19.5v-15A1.5 1.5 0 0 1 3.5 3Zm2.3 5.7 3.3 3.3-3.3 3.3 1.4 1.4 4.7-4.7-4.7-4.7Zm6.2 6.8V17h6v-1.5Z"),
            Map.entry("youtube", "M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20ZM10 7.8v8.4l6.6-4.2Z"));

    private static final Map<String, Shape> PARSED = new ConcurrentHashMap<>();

    static boolean hasStroke(String name) { return LUCIDE.containsKey(name); }

    static boolean hasMark(String id) { return MARKS.containsKey(id); }

    static java.util.Set<String> strokeNames() { return LUCIDE.keySet(); }

    static java.util.Set<String> markNames() { return MARKS.keySet(); }

    /** The icon's outline in its 24-unit box, for a test. */
    static Shape strokeShape(String name) { return shape("l:" + name, LUCIDE.get(name), name); }

    static Shape markShape(String id) { return shape("m:" + id, MARKS.get(id), id); }

    /** The lucide icon {@code name}, its 24-unit box drawn at {@code size} with its top-left at (x, y). */
    static void stroke(Graphics2D g0, String name, double x, double y, double size, Color c) {
        Shape s = shape("l:" + name, LUCIDE.get(name), name);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        g.scale(size / 24.0, size / 24.0);
        g.setColor(c);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.dispose();
    }

    /** The lucide icon {@code name} stroked AND filled, as the app draws one with {@code fill="currentColor"} (Stop's square). */
    static void solid(Graphics2D g0, String name, double x, double y, double size, Color c) {
        Shape s = shape("l:" + name, LUCIDE.get(name), name);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.scale(size / 24.0, size / 24.0);
        g.setColor(c);
        g.fill(s);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.dispose();
    }

    /** The BrandMark {@code id}, filled, its 24-unit box drawn at {@code size} with its top-left at (x, y). */
    static void mark(Graphics2D g0, String id, double x, double y, double size, Color c) {
        Shape s = shape("m:" + id, MARKS.get(id), id);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.scale(size / 24.0, size / 24.0);
        g.setColor(c);
        g.fill(s);
        g.dispose();
    }

    private static Shape shape(String key, String d, String name) {
        if (d == null) throw new IllegalArgumentException("No icon named " + name);
        return PARSED.computeIfAbsent(key, k -> {
            // An icon's elements, apart: each one's data begins a path of its own, where an opening
            // relative 'm' is relative to nothing before it.
            Path2D.Double p = new Path2D.Double(key.startsWith("m:") ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            for (String part : d.split("\\|")) p.append(SvgPath.parse(part.trim()), false);
            return p;
        });
    }
}
