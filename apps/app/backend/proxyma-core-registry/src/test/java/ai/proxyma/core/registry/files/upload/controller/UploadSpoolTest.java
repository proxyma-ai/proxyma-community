package ai.proxyma.core.registry.files.upload.controller;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An upload is written to disk as it arrives and its buffers are given back. It was collected in
 * memory, up to 200 MB a file, and the pooled buffers it was read from were never released.
 */
class UploadSpoolTest {

    private final NettyDataBufferFactory factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
    private final List<ByteBuf> handedOut = new ArrayList<>();

    // Pooled Netty buffers, as a request body arrives in - the kind that leaks when not released.
    private DataBuffer chunk(String text) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        buf.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        handedOut.add(buf);
        return factory.wrap(buf);
    }

    @Test
    @DisplayName("the bytes reach the file in order, and the count is the file's size")
    void writesTheUpload(@TempDir Path dir) throws Exception {
        Path target = Files.createFile(dir.resolve("upload.part"));

        Long size = UploadSpool.toFile(Flux.defer(() -> Flux.just(chunk("first, "), chunk("second, "), chunk("third"))),
                target, 1024).block();

        assertThat(Files.readString(target)).isEqualTo("first, second, third");
        assertThat(size).isEqualTo(Files.size(target)).isEqualTo(20L);
    }

    @Test
    @DisplayName("every buffer of an upload that was stored is released")
    void releasesWhatItWrote(@TempDir Path dir) throws Exception {
        Path target = Files.createFile(dir.resolve("upload.part"));

        UploadSpool.toFile(Flux.defer(() -> Flux.just(chunk("a"), chunk("b"), chunk("c"))), target, 1024).block();

        assertThat(handedOut).hasSize(3).allSatisfy(buf -> assertThat(buf.refCnt()).isZero());
    }

    @Test
    @DisplayName("an upload past the limit is refused as it passes it, and its buffers are released too")
    void refusesPastTheLimit(@TempDir Path dir) throws Exception {
        Path target = Files.createFile(dir.resolve("upload.part"));
        // 4 MB limit, six chunks of 1 MB: the fifth is the one that passes it.
        String megabyte = "x".repeat(1024 * 1024);

        assertThatThrownBy(() -> UploadSpool.toFile(
                Flux.defer(() -> Flux.range(0, 6).map(i -> chunk(megabyte))), target, 4L * 1024 * 1024).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed size of 4MB");

        assertThat(handedOut).isNotEmpty().allSatisfy(buf -> assertThat(buf.refCnt()).isZero());
        assertThat(Files.size(target)).isLessThanOrEqualTo(4L * 1024 * 1024);
    }
}
