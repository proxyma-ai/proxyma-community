package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ChromaStoreService addresses Chroma two different ways and they MUST agree.
 *
 * <p>Vectors are written through {@code ChromaVectorStore}, which is handed no tenant or database
 * and so uses Spring AI's own defaults. Deletes, renames and UUID lookups are made straight against
 * {@code ChromaApi}, which takes the tenant and database as arguments - and those come from
 * ChromaStoreService's own two constants. If the two disagree, the direct calls address an empty
 * namespace and every one of them fails in a way nothing surfaces: a 404 caught at debug level, a
 * null UUID returned as "nothing to delete", a shadow collection reported as "never created".
 *
 * <p>They DID disagree, for the whole life of the file: the constants read "proxyma-tenant" and
 * "proxyma-database" while every vector sat in SpringAiTenant/SpringAiDatabase. The application
 * created that namespace on every startup and never put anything in it.
 *
 * <p>This reads the builder's defaults by reflection rather than restating them, so the assertion
 * is against Spring AI's actual behavior and not against a copy of it that can go stale. A Spring
 * AI upgrade that moves the defaults fails here, which is the point: the alternative is discovering
 * it when a customer's deleted source keeps answering searches.
 *
 * <p><b>The fix for a failure here is to follow Spring AI, never to lead it.</b> Making the builder
 * take these constants instead would move every future collection to a namespace no existing
 * install's vectors are in, and search would return nothing on every deployment in the field -
 * silently, with no error anywhere.
 */
class ChromaNamespaceTest {

    private static ChromaVectorStore.Builder builder() {
        // Neither mock is called - the builder only stores them, and this test never builds.
        return ChromaVectorStore.builder(mock(ChromaApi.class), mock(EmbeddingModel.class));
    }

    private static String builderDefault(String fieldName) throws Exception {
        Field field = ChromaVectorStore.Builder.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(builder());
    }

    private static String serviceConstant(String fieldName) throws Exception {
        Field field = ChromaStoreService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    @DisplayName("the tenant the service deletes from is the tenant the store writes to")
    void tenantMatchesSpringAiDefault() throws Exception {
        assertThat(serviceConstant("TENANT_NAME")).isEqualTo(builderDefault("tenantName"));
    }

    @Test
    @DisplayName("the database the service deletes from is the database the store writes to")
    void databaseMatchesSpringAiDefault() throws Exception {
        assertThat(serviceConstant("DATABASE_NAME")).isEqualTo(builderDefault("databaseName"));
    }

    /**
     * Guards the reflection itself. If a Spring AI upgrade renames the builder's fields, the two
     * tests above would throw NoSuchFieldException - a failure, so nothing is silently skipped -
     * but this states the expected values outright so the failure message says what moved.
     */
    @Test
    @DisplayName("Spring AI's defaults are still the ones this service was aligned to")
    void springAiDefaultsAreUnchanged() throws Exception {
        assertThat(builderDefault("tenantName")).isEqualTo("SpringAiTenant");
        assertThat(builderDefault("databaseName")).isEqualTo("SpringAiDatabase");
    }
}
