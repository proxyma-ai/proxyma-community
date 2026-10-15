package ai.proxyma.core.connector.jira;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which custom fields are left out of an issue's document - the part of the Jira renderer
 * (JiraIssueMarkdown) reachable without a Jira server. What a sync asks Jira for, and when, is the
 * native session's, tested in proxyma-connectors (JiraConnectorTest there).
 */
class JiraConnectorTest {

    @Nested
    @DisplayName("the custom-field deny list")
    class DenyList {

        @Test
        @DisplayName("keeps the two generic names when nothing is configured")
        void builtInDefaults() {
            Set<String> denied = JiraIssueMarkdown.parseSkipCustomFields(null);
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("Rank", denied));
            // Shown since 2026-09-29: which Epic a Story is under is a question readers ask, and
            // hiding it left the assistant unable to check a link it had just set.
            assertFalse(JiraIssueMarkdown.isDeniedCustomField("Epic Link", denied));
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("Development", denied));
            assertFalse(JiraIssueMarkdown.isDeniedCustomField("Sprint", denied));
        }

        @Test
        @DisplayName("matches the display name whatever its case or surrounding space")
        void caseAndSpaceInsensitive() {
            Set<String> denied = JiraIssueMarkdown.parseSkipCustomFields(" Story Points , TEAM PRIORITY ");
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("story points", denied));
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("  Team Priority  ", denied));
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("RANK", denied));
        }

        @Test
        @DisplayName("a configured list adds to the built-ins rather than replacing them")
        void configuredNamesAddToBuiltIns() {
            Set<String> denied = JiraIssueMarkdown.parseSkipCustomFields("Story Points");
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("Story Points", denied));
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("Rank", denied));
        }

        @Test
        @DisplayName("blank and empty entries are not deny-list members")
        void blankEntriesAreDropped() {
            Set<String> denied = JiraIssueMarkdown.parseSkipCustomFields(",, Story Points ,,");
            assertEquals(3, denied.size()); // Rank, Development, Story Points
            assertFalse(JiraIssueMarkdown.isDeniedCustomField("", denied));
            assertFalse(JiraIssueMarkdown.isDeniedCustomField(null, denied));
        }

        @Test
        @DisplayName("a customfield id is NOT matched - an id means a different field per server")
        void idsAreNotMatched() {
            // The list this replaced held customfield_10000 "// Rank". On another instance that id
            // is an ordinary field, and denying it hid something nobody asked to hide.
            Set<String> denied = JiraIssueMarkdown.parseSkipCustomFields("customfield_10000");
            assertFalse(JiraIssueMarkdown.isDeniedCustomField("Rank (obsolete)", denied));
            assertTrue(JiraIssueMarkdown.isDeniedCustomField("customfield_10000", denied),
                    "a name that happens to look like an id is still matched as a name");
        }
    }
}
