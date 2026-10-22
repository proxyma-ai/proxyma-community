package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.SubAgentReport;
import ai.proxyma.core.chat.domain.SubAgentReport.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sub-agent's last message, read as a report: its verdict from the STATUS line, its text without
 * it.
 *
 * <p>The verdict is the part worth pinning. A report that does not say is never a success by
 * default where the run was cut short, and a COMPLETED the run cannot stand behind is taken down
 * to PARTIAL - the failure this type exists to prevent is an unfinished task handed on as done.
 */
class SubAgentReportTest {

    @Test
    @DisplayName("the STATUS line gives the verdict and is taken out of the text")
    void statusLineIsReadAndRemoved() {
        SubAgentReport r = SubAgentReport.fromFinalMessage(
                "Claude Sonnet is $3 per million input tokens (pricing page).\n\nSTATUS: COMPLETED", Status.PARTIAL);

        assertThat(r.status()).isEqualTo(Status.COMPLETED);
        assertThat(r.report()).isEqualTo("Claude Sonnet is $3 per million input tokens (pricing page).");
    }

    @Test
    @DisplayName("a status written the way models write it is still read")
    void tolerantOfDecoration() {
        assertThat(SubAgentReport.fromFinalMessage("Found two.\n**STATUS: PARTIAL** - sheet 3 unread.",
                Status.COMPLETED).status()).isEqualTo(Status.PARTIAL);
        assertThat(SubAgentReport.fromFinalMessage("Nothing matched.\nstatus: failed", Status.COMPLETED).status())
                .isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("the LAST status line counts, and an earlier one stays in the text")
    void lastStatusLineWins() {
        SubAgentReport r = SubAgentReport.fromFinalMessage(
                "The ticket's own field reads\nSTATUS: FAILED\nand that is the build status.\n\nSTATUS: COMPLETED",
                Status.PARTIAL);

        assertThat(r.status()).isEqualTo(Status.COMPLETED);
        assertThat(r.report()).contains("STATUS: FAILED");
    }

    @Test
    @DisplayName("a report with no status line is whatever the caller says an unstated one is")
    void unstatedIsTheCallersReading() {
        assertThat(SubAgentReport.fromFinalMessage("Here is what I found.", Status.COMPLETED).status())
                .isEqualTo(Status.COMPLETED);
        assertThat(SubAgentReport.fromFinalMessage("Here is what I found.", Status.PARTIAL).status())
                .isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("a run that was cut short cannot report COMPLETED")
    void aCutShortRunIsAtMostPartial() {
        SubAgentReport claimed = SubAgentReport.fromFinalMessage("All done.\nSTATUS: COMPLETED", Status.PARTIAL);

        assertThat(claimed.atMost(Status.PARTIAL).status()).isEqualTo(Status.PARTIAL);
        // Its own FAILED is not softened into PARTIAL.
        assertThat(SubAgentReport.failed("no access").atMost(Status.PARTIAL).status()).isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("the message that carries reports to the main agent can be read back")
    void reportMessageRoundTrips() {
        String message = SubAgentService.reportMessage(java.util.List.of(
                new SubAgentService.Settled("11111111-2222-3333-4444-555555555555", "Extract \"Gemini\" pricing",
                        "COMPLETED", "Gemini Pro is $1.25.", 133),
                new SubAgentService.Settled("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "Sweep the space",
                        "PARTIAL", "Half of it.", 60)), java.util.List.of());

        assertThat(SubAgentService.isReportMessage(message)).isTrue();
        assertThat(SubAgentService.isReportMessage("What did the sub-agent report?")).isFalse();
        var reports = SubAgentService.reportsIn(message);
        assertThat(reports).hasSize(2);
        assertThat(reports.get(0)).containsExactly("11111111-2222-3333-4444-555555555555",
                "Extract 'Gemini' pricing", "COMPLETED", "report");
        assertThat(reports.get(1)[2]).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("a report names the sub-agents still running, so the main agent waits for the rest")
    void aReportNamesTheOnesStillRunning() {
        var report = java.util.List.of(new SubAgentService.Settled("11111111-2222-3333-4444-555555555555",
                "Read the pricing page", "COMPLETED", "Sonnet is $3.", 40));

        String alone = SubAgentService.reportMessage(report, java.util.List.of());
        String withOthers = SubAgentService.reportMessage(report,
                java.util.List.of("Sweep the \"Ops\" space", "Check Jira"));

        assertThat(alone).doesNotContain("Still running");
        assertThat(withOthers).contains("(Still running: \"Sweep the 'Ops' space\", \"Check Jira\".");
        // Still read back as the one report it carries.
        assertThat(SubAgentService.reportsIn(withOthers)).hasSize(1);
    }
}
