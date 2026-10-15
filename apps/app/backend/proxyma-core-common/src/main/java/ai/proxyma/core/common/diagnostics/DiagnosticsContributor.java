package ai.proxyma.core.common.diagnostics;

import java.util.function.BiConsumer;

/**
 * A module's contribution to the once-a-day feature snapshot in the diagnostics report - how many
 * sources of each type, whether some capability is switched on.
 *
 * <p>Implemented where the fact lives (the registry knows its sources) so that the diagnostics
 * module never reads another module's repository. Values must be a Boolean, a whole number, an
 * enum constant, or one of {@link DiagLabels}' fixed words and ranges ({@code countBucket},
 * {@code sizeBucket}...). The report builder drops anything else - including every other string,
 * because a token-shaped string can still be a key - and cleans every key through
 * {@link DiagLabels#clean}.
 */
public interface DiagnosticsContributor {

    void contribute(BiConsumer<String, Object> put);
}
