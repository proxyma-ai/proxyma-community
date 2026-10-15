package ai.proxyma.core.registry.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Sources page and the source dialog page at twenty (2026-10-14). What they may ask for is one
 * row to the endpoint's ceiling - a hundred sources, two hundred items - and never zero, which was a
 * 500 from PageRequest on the source list.
 */
class SourceListPagingTest {

    @Test
    @DisplayName("a page holds one row to the ceiling")
    void sizeIsClamped() {
        assertThat(ConnectorController.clampSize(20, 100)).isEqualTo(20);
        assertThat(ConnectorController.clampSize(0, 100)).isEqualTo(1);
        assertThat(ConnectorController.clampSize(-7, 200)).isEqualTo(1);
        assertThat(ConnectorController.clampSize(5000, 100)).isEqualTo(100);
        assertThat(ConnectorController.clampSize(5000, 200)).isEqualTo(200);
    }
}
