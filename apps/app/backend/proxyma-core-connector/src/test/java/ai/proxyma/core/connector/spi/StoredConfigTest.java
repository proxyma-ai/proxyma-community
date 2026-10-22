package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one reader of a source's config column: its fields as JSON for every type but the file-backed
 * ones, whose column is the folder's path - and a row an older build wrote, read in its old shape.
 */
class StoredConfigTest {

    @Test
    @DisplayName("a folder source's column is its path, read as the field path")
    void aFolderIsItsPath() {
        assertThat(StoredConfig.fields(ConnectorType.LOCAL_FOLDER, "C:/Users/ana/Documents"))
                .isEqualTo(Map.of("path", "C:/Users/ana/Documents"));
        assertThat(StoredConfig.fields(ConnectorType.UPLOADED_FILES, "/data/uploads/Docs"))
                .isEqualTo(Map.of("path", "/data/uploads/Docs"));
        assertThat(StoredConfig.fields(ConnectorType.LOCAL_FOLDER, " ")).isEmpty();
    }

    @Test
    @DisplayName("every other type's column is its fields as JSON")
    void otherTypesAreJson() {
        assertThat(StoredConfig.fields(ConnectorType.NETWORK_SHARE, "{\"uncPath\":\"//fs/share\"}"))
                .isEqualTo(Map.of("uncPath", "//fs/share"));
        assertThat(StoredConfig.fields(ConnectorType.GITHUB, "{\"owner\":\"o\",\"repo\":\"r\"}"))
                .isEqualTo(Map.of("owner", "o", "repo", "r"));
    }

    @Test
    @DisplayName("a Jira project or Confluence space an older build stored as its server's URL takes its key from its name")
    void anOldKeyedRowReadsItsKeyFromItsName() {
        assertThat(StoredConfig.fields(ConnectorType.JIRA, "https://jira.corp.example", "RHEU"))
                .isEqualTo(Map.of("projectKey", "RHEU"));
        assertThat(StoredConfig.fields(ConnectorType.CONFLUENCE, "https://wiki.corp.example", "OPS"))
                .isEqualTo(Map.of("spaceKey", "OPS"));
        // A key that is stored wins over the name, and other types never borrow it.
        assertThat(StoredConfig.fields(ConnectorType.JIRA, "{\"projectKey\":\"PROJ\"}", "Renamed"))
                .isEqualTo(Map.of("projectKey", "PROJ"));
        assertThat(StoredConfig.fields(ConnectorType.GITHUB, "{\"owner\":\"o\",\"repo\":\"r\"}", "o/r"))
                .isEqualTo(Map.of("owner", "o", "repo", "r"));
    }

    @Test
    @DisplayName("a Bitbucket repository or a Teams channel an older build named PROJ/repo or team/channel reads both fields from its name")
    void anOldPairReadsFromItsName() {
        assertThat(StoredConfig.fields(ConnectorType.BITBUCKET, "https://bitbucket.corp.example", "PROJ/my-repo"))
                .isEqualTo(Map.of("projectKey", "PROJ", "repoSlug", "my-repo"));
        assertThat(StoredConfig.fields(ConnectorType.TEAMS, null, "Engineering/general"))
                .isEqualTo(Map.of("teamName", "Engineering", "channelName", "general"));
        // Stored fields win, and a name with no slash fills nothing.
        assertThat(StoredConfig.fields(ConnectorType.BITBUCKET, "{\"projectKey\":\"P\",\"repoSlug\":\"r\"}", "Renamed/x"))
                .isEqualTo(Map.of("projectKey", "P", "repoSlug", "r"));
        assertThat(StoredConfig.fields(ConnectorType.TEAMS, null, "Engineering")).isEmpty();
    }

    @Test
    @DisplayName("an older SharePoint site, Outlook folder or Teams chats row reads in the shape it was written")
    void oldMicrosoftRowsRead() {
        assertThat(StoredConfig.fields(ConnectorType.SHAREPOINT, "https://acme.sharepoint.com/sites/Docs", "Docs"))
                .isEqualTo(Map.of("siteUrl", "https://acme.sharepoint.com/sites/Docs", "siteName", "Docs"));
        assertThat(StoredConfig.fields(ConnectorType.OUTLOOK,
                "kind=mail\nmailbox=me\nfolderId=F1\nfolderName=Inbox\nreceivedDays=30\n"))
                .containsEntry("kind", "mail").containsEntry("mailbox", "me").containsEntry("folderId", "F1")
                .containsEntry("name", "Inbox").containsEntry("receivedDays", "30");
        assertThat(StoredConfig.fields(ConnectorType.OUTLOOK, "kind=calendar\nmailbox=me\ncalendarId=C1\ncalendarName=Team\n"))
                .containsEntry("calendarId", "C1").containsEntry("name", "Team");
        assertThat(StoredConfig.fields(ConnectorType.TEAMS_CHAT, "days=14")).isEqualTo(Map.of("days", "14"));
    }

    @Test
    @DisplayName("a network share an older build stored as its bare path still reads")
    void anOldShareRowReads() {
        assertThat(StoredConfig.fields(ConnectorType.NETWORK_SHARE, "//fs1/share/docs"))
                .isEqualTo(Map.of("uncPath", "//fs1/share/docs"));
    }
}
