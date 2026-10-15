package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.bitbucket.BitbucketConnector;
import ai.proxyma.core.connector.facts.SourceFacts;
import ai.proxyma.core.connector.facts.SourceFactsReader;
import ai.proxyma.core.connector.jira.JiraConnector;
import ai.proxyma.core.ingest.service.ChunkingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item's metadata read back from what the index holds: rendered by the connector's OWN renderer,
 * cut by the ingest pipeline's OWN chunker, then read by SourceFactsReader - the path a source's
 * metadata edges take into the knowledge graph. A change to a renderer that the reader no longer
 * understands fails here rather than emptying the graph's metadata edges without a word.
 */
class SourceFactsFromIndexTest {

    private final ChunkingService chunking = new ChunkingService();

    private List<String> chunks(String markdown) {
        return chunking.chunk("item", markdown, Map.of(), ChunkingService.ContentType.MARKDOWN).stream()
                .map(Document::getText).toList();
    }

    private static List<String> links(SourceFacts facts) {
        return facts.links().stream().map(l -> l.subject() + " | " + l.predicate() + " | " + l.object()).toList();
    }

    @Test
    @DisplayName("a Jira issue: people, components, versions, labels, its parent, subtasks and linked issues")
    void jiraIssue() {
        JiraConnector.JiraIssueDto issue = new JiraConnector.JiraIssueDto();
        issue.setKey("PAY-12");
        issue.setSummary("Retry failed payouts");
        issue.setAssignee("Ana Lima (ana@example.com)");
        issue.setReporter("Bo Tran");
        issue.setComponents(List.of("Ledger", "Payouts"));
        issue.setFixVersions(List.of("2.4"));
        issue.setLabels(List.of("backend"));
        issue.setParentKey("PAY-1");
        issue.setParentSummary("Payout reliability");
        issue.setSubtasks(List.of("[PAY-13] Add a backoff (To Do)"));
        issue.setIssueLinks(List.of("**blocks** [OPS-7] Rotate the payout key (In Progress) [link 1001]"));
        issue.setDescription("Payouts that fail are not retried. " + "Long text. ".repeat(400));

        String markdown = new JiraConnector(RestClient.builder()).issueToMarkdown(issue);
        SourceFacts facts = SourceFactsReader.read(ConnectorType.JIRA, "PAY-12", "Retry failed payouts", "PAY",
                chunks(markdown));

        assertThat(links(facts)).containsExactlyInAnyOrder(
                "PAY-12 | assigned to | Ana Lima",
                "PAY-12 | reported by | Bo Tran",
                "PAY-12 | in component | Ledger",
                "PAY-12 | in component | Payouts",
                "PAY-12 | fixed in | 2.4",
                "PAY-12 | labelled | backend",
                "PAY-12 | child of | PAY-1",
                "PAY-13 | child of | PAY-12",
                "PAY-12 | blocks | OPS-7");
        assertThat(facts.things().get(0).description()).isEqualTo("Retry failed payouts");
        assertThat(facts.things()).anySatisfy(t -> {
            assertThat(t.name()).isEqualTo("OPS-7");
            assertThat(t.description()).isEqualTo("Rotate the payout key");
        });
    }

    @Test
    @DisplayName("a Bitbucket pull request and commit: author, reviewers, target branch, Jira keys, the PR a commit is in")
    void bitbucket() {
        BitbucketConnector bitbucket = new BitbucketConnector(RestClient.builder());
        BitbucketConnector.BitbucketPrDto pr = new BitbucketConnector.BitbucketPrDto();
        pr.id = 12;
        pr.title = "PAY-12 retry failed payouts";
        pr.state = "OPEN";
        pr.author = "Ana Lima";
        pr.reviewers = List.of("Bo Tran (approved)", "Chi Vu");
        pr.fromRef = "feature/PAY-12-retry";
        pr.toRef = "main";
        SourceFacts prFacts = SourceFactsReader.read(ConnectorType.BITBUCKET, "PR-12", pr.title, "PAY/ledger",
                chunks(bitbucket.prToMarkdown(pr)));
        assertThat(links(prFacts)).containsExactlyInAnyOrder(
                "PAY/ledger PR-12 | authored by | Ana Lima",
                "PAY/ledger PR-12 | reviewed by | Bo Tran",
                "PAY/ledger PR-12 | reviewed by | Chi Vu",
                "PAY/ledger PR-12 | merges into | PAY/ledger main",
                "PAY/ledger PR-12 | references | PAY-12");

        BitbucketConnector.BitbucketCommitDto commit = new BitbucketConnector.BitbucketCommitDto();
        commit.id = "0123456789abcdef0123";
        commit.displayId = "0123456789a";
        commit.message = "PAY-12 back off between retries";
        commit.authorName = "Ana Lima";
        commit.authorEmail = "ana@example.com";
        commit.pullRequests = List.of("PR-12 PAY-12 retry failed payouts (OPEN)");
        SourceFacts commitFacts = SourceFactsReader.read(ConnectorType.BITBUCKET, "COMMIT-" + commit.id,
                commit.message, "PAY/ledger", chunks(bitbucket.commitToMarkdown(commit)));
        assertThat(links(commitFacts)).containsExactlyInAnyOrder(
                "PAY/ledger 0123456789a | authored by | Ana Lima",
                "PAY/ledger 0123456789a | part of | PAY/ledger PR-12",
                "PAY/ledger 0123456789a | references | PAY-12");
    }

    @Test
    @DisplayName("a Confluence page: space, authors, labels and its place in the page tree")
    void confluencePage() {
        // pageToMarkdown is private to the connector; this is its output, line for line.
        String markdown = """
                # Payout runbook

                ## Page Details
                | Field | Value |
                |-------|-------|
                | Space | OPS - Operations |
                | Created | 2026-10-01 09:00 by Ana Lima |
                | Last Updated | 2026-10-02 10:00 by Bo Tran |
                | Labels | runbook, payouts |
                | URL | https://wiki.example.com/x |

                ## Location
                Operations Home > Runbooks > Payout runbook

                ## Content

                Restart the payout worker.
                """;
        SourceFacts facts = SourceFactsReader.read(ConnectorType.CONFLUENCE, "123", "Payout runbook", "OPS",
                chunks(markdown));
        assertThat(links(facts)).containsExactlyInAnyOrder(
                "Payout runbook | in space | Operations",
                "Payout runbook | created by | Ana Lima",
                "Payout runbook | last updated by | Bo Tran",
                "Payout runbook | labelled | runbook",
                "Payout runbook | labelled | payouts",
                "Runbooks | child of | Operations Home",
                "Payout runbook | child of | Runbooks");
    }

    @Test
    @DisplayName("an author whose name holds \"by \" is read whole (Bobby Smith, not Smith)")
    void authorNamesHoldingBy() {
        String markdown = """
                # Payout runbook

                ## Page Details
                | Field | Value |
                |-------|-------|
                | Created | 2026-10-01 09:00 by Bobby Smith |
                | Last Updated | 2026-10-02 10:00 by Ruby Lee |
                """;
        SourceFacts facts = SourceFactsReader.read(ConnectorType.CONFLUENCE, "123", "Payout runbook", "OPS",
                chunks(markdown));
        assertThat(links(facts)).contains(
                "Payout runbook | created by | Bobby Smith",
                "Payout runbook | last updated by | Ruby Lee");
    }

    @Test
    @DisplayName("the graph worker hands a document's facts to the graph as entities and relations")
    void workerMapsFactsToTheGraph() {
        JiraConnector.JiraIssueDto issue = new JiraConnector.JiraIssueDto();
        issue.setKey("PAY-12");
        issue.setSummary("Retry failed payouts");
        issue.setAssignee("Ana Lima");
        ai.proxyma.core.registry.model.TrackedDocument doc = new ai.proxyma.core.registry.model.TrackedDocument();
        doc.setSourceId("PAY-12");
        doc.setTitle("Retry failed payouts");
        doc.setConnectorType(ConnectorType.JIRA);
        ai.proxyma.core.registry.model.RegisteredConnector connector = new ai.proxyma.core.registry.model.RegisteredConnector();
        connector.setName("PAY");

        ai.proxyma.core.graph.service.ExtractedGraph facts = GraphExtractionOrchestrator.sourceFacts(doc, connector,
                chunks(new JiraConnector(RestClient.builder()).issueToMarkdown(issue)));

        assertThat(facts.relations()).singleElement().satisfies(r -> {
            assertThat(r.subject()).isEqualTo("PAY-12");
            assertThat(r.predicate()).isEqualTo("assigned to");
            assertThat(r.object()).isEqualTo("Ana Lima");
        });
        assertThat(facts.entities()).extracting(e -> e.name() + ":" + e.storedType())
                .containsExactly("PAY-12:issue", "Ana Lima:person");
        // A source type with no metadata header is nothing to add, not an error.
        doc.setConnectorType(ConnectorType.SVN);
        assertThat(GraphExtractionOrchestrator.sourceFacts(doc, connector, List.of("text"))).isNull();
    }

    @Test
    @DisplayName("Jira's change history dates each assignee's spell and each status change")
    void jiraHistory() {
        JiraConnector.JiraIssueDto issue = new JiraConnector.JiraIssueDto();
        issue.setKey("PAY-12");
        issue.setSummary("Retry failed payouts");
        issue.setAssignee("Chi Vu");
        issue.setCreated("2026-03-01T09:00:00.000+0000");
        JiraConnector.JiraIssueDto.ChangelogEntry toBo = new JiraConnector.JiraIssueDto.ChangelogEntry();
        toBo.setCreated("2026-04-01T10:00:00.000+0000");
        toBo.setAuthor("Ana Lima");
        toBo.setField("assignee");
        toBo.setFromString("Ana Lima");
        toBo.setToString("Bo Tran");
        JiraConnector.JiraIssueDto.ChangelogEntry toChi = new JiraConnector.JiraIssueDto.ChangelogEntry();
        toChi.setCreated("2026-05-01T10:00:00.000+0000");
        toChi.setAuthor("Bo Tran");
        toChi.setField("assignee");
        toChi.setFromString("Bo Tran");
        toChi.setToString("Chi Vu");
        JiraConnector.JiraIssueDto.ChangelogEntry done = new JiraConnector.JiraIssueDto.ChangelogEntry();
        done.setCreated("2026-05-02T11:00:00.000+0000");
        done.setAuthor("Chi Vu");
        done.setField("status");
        done.setFromString("In Progress");
        done.setToString("Done");
        issue.setChangelog(List.of(toBo, toChi, done));

        SourceFacts facts = SourceFactsReader.read(ConnectorType.JIRA, "PAY-12", "Retry failed payouts", "PAY",
                chunks(new JiraConnector(RestClient.builder()).issueToMarkdown(issue)));

        List<String> dated = facts.links().stream()
                .map(l -> l.predicate() + " " + l.object() + " " + day(l.validFrom()) + ".." + day(l.validTo())
                        + (l.occurredAt() == null ? "" : " @" + day(l.occurredAt())))
                .toList();
        assertThat(dated).contains(
                "assigned to Ana Lima 2026-03-01..2026-04-01",
                "assigned to Bo Tran 2026-04-01..2026-05-01",
                "assigned to Chi Vu 2026-05-01..-",
                "moved to Done -..- @2026-05-02");
    }

    private static String day(java.time.Instant at) {
        return at == null ? "-" : at.toString().substring(0, 10);
    }

    @Test
    @DisplayName("an SVN file's last committer, from the listing the row keeps, and since when")
    void svnLastCommitter() {
        ai.proxyma.core.registry.model.TrackedDocument doc = new ai.proxyma.core.registry.model.TrackedDocument();
        doc.setSourceId("trunk/payouts/Retry.java");
        doc.setTitle("trunk/payouts/Retry.java");
        doc.setConnectorType(ConnectorType.SVN);
        doc.setSourceAuthor("ana");
        doc.setSourceLastModified(java.time.Instant.parse("2026-05-03T08:00:00Z"));

        SourceFacts facts = GraphExtractionOrchestrator.svnFacts(doc);

        assertThat(links(facts)).containsExactly("trunk/payouts/Retry.java | last changed by | ana");
        assertThat(facts.links().get(0).validFrom()).isEqualTo(java.time.Instant.parse("2026-05-03T08:00:00Z"));
        doc.setSourceAuthor(null);
        assertThat(GraphExtractionOrchestrator.svnFacts(doc).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("sources that render no metadata give no facts")
    void othersGiveNothing() {
        assertThat(SourceFactsReader.read(ConnectorType.SVN, "trunk/a.txt", "a.txt", "repo", List.of("text")).isEmpty())
                .isTrue();
    }
}
