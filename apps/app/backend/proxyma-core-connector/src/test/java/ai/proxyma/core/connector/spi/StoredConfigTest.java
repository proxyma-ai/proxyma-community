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
    @DisplayName("a network share an older build stored as its bare path still reads")
    void anOldShareRowReads() {
        assertThat(StoredConfig.fields(ConnectorType.NETWORK_SHARE, "//fs1/share/docs"))
                .isEqualTo(Map.of("uncPath", "//fs1/share/docs"));
    }
}
