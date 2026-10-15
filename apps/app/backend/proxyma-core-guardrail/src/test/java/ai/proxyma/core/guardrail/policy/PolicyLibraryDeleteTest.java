package ai.proxyma.core.guardrail.policy;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.DocumentConversionService;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.system.util.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an EXAMPLES document removes EVERY example it put in the index.
 *
 * <p>delete() took the row out of the manifest first and then read the manifest for how many
 * examples there were - so it found none, and deleted only #0 to #63. A file of 200 prompts left
 * 136 vectors that nothing listed and nothing could remove, and they kept blocking messages.
 */
class PolicyLibraryDeleteTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a 100-example file is removed whole, its last example included")
    void deleteRemovesEveryExample() throws Exception {
        DocumentConversionService conversion = mock(DocumentConversionService.class);
        when(conversion.canConvert(any(), any())).thenReturn(true);
        String examples = IntStream.range(0, 100).mapToObj(i -> "attack number " + i)
                .collect(Collectors.joining("\n\n"));
        when(conversion.convert(any(), any())).thenReturn(new DocumentConversionService.Converted(examples, "text"));
        VectorStoreService vectors = mock(VectorStoreService.class);
        BM25IndexService bm25 = mock(BM25IndexService.class);
        PolicyLibrary library = new PolicyLibrary(dir.toString(), mock(AppPaths.class),
                new ObjectMapper().findAndRegisterModules(), conversion, mock(EmbeddingService.class), vectors, bm25);

        PolicyDocument doc = library.addFile(PolicyDocument.Kind.EXAMPLES, "attacks.txt",
                examples.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 200 && library.list().get(0).status() != PolicyDocument.Status.INDEXED; i++) {
            Thread.sleep(25);
        }
        assertThat(library.list().get(0).chunkCount()).isEqualTo(100);

        assertThat(library.delete(doc.id())).isTrue();
        verify(vectors).deleteBySourceMetadata(anyString(), eq(doc.id() + "#99"));
        verify(bm25).deleteDocuments(anyString(), eq(doc.id() + "#99"));
        // And the manifest was replaced whole, never left half written beside a temp file.
        assertThat(Files.exists(dir.resolve("manifest.json.tmp"))).isFalse();
    }
}
