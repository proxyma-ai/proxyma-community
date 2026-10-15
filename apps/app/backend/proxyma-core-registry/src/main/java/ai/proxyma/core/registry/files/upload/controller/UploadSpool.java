package ai.proxyma.core.registry.files.upload.controller;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An upload written to a file AS IT ARRIVES, counted on the way.
 *
 * <p>FileController used to reduce the part into a ByteArrayOutputStream - up to 200 MB a file,
 * more while the array doubled, and once again for the copy handed to storage - so a few people
 * uploading at once was most of a heap. Nothing here holds more than the buffer in flight.
 *
 * <p>IT ALSO RELEASES WHAT IT READS, which the old code did not: {@code buf.asInputStream()}
 * leaves the buffer to its caller, and nobody released it. On Netty those buffers are pooled
 * memory outside the heap, so each upload leaked its own size until the collector happened to
 * notice. Here each buffer is released in a finally as it is written, and the discard hook covers
 * the ones still queued when an upload is refused or its connection drops.
 *
 * <p>Public since the ingestion API takes files too (IngestController), with the same bound.
 *
 * <p>Written by hand rather than with DataBufferUtils.write: that hands written buffers on to a
 * later step to release, and an upload refused part-way left some of them unreleased (seen in
 * UploadSpoolTest before this was rewritten).
 */
public final class UploadSpool {

    private UploadSpool() {
    }

    /**
     * Writes {@code content} to {@code target} and answers with the number of bytes written.
     * Fails with IllegalArgumentException, naming the limit, as soon as the upload passes
     * {@code maxBytes} - the rest is not read. The caller owns {@code target} either way.
     */
    public static Mono<Long> toFile(Flux<DataBuffer> content, Path target, long maxBytes) {
        return Mono.using(
                () -> FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                channel -> content
                        // A file write blocks, and the buffers arrive on the thread reading the request.
                        .publishOn(Schedulers.boundedElastic())
                        .reduce(0L, (size, buffer) -> write(channel, buffer, size, maxBytes))
                        .doOnDiscard(DataBuffer.class, DataBufferUtils::release),
                UploadSpool::close);
    }

    private static long write(FileChannel channel, DataBuffer buffer, long sizeSoFar, long maxBytes) {
        try {
            long size = sizeSoFar + buffer.readableByteCount();
            if (size > maxBytes) {
                throw new IllegalArgumentException(
                        "File exceeds maximum allowed size of " + (maxBytes / (1024 * 1024)) + "MB");
            }
            try (DataBuffer.ByteBufferIterator parts = buffer.readableByteBuffers()) {
                while (parts.hasNext()) {
                    ByteBuffer part = parts.next();
                    while (part.hasRemaining()) channel.write(part);
                }
            }
            return size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static void close(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // The bytes are written or the upload has already failed; there is nothing to add.
        }
    }
}
