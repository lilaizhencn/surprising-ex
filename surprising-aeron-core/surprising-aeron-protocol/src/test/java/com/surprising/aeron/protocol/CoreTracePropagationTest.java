package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoreTracePropagationTest {
    private CoreMessage message(ProductLine product) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(), product,
                CommandSource.GATEWAY, 1, 1, 2, 3, 4), CoreProtocol.probePayload(7));
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void traceCrossesTheWireAndResponseWithoutChangingIdempotencyOrPayload(ProductLine product) {
        var original = message(product);
        var traced = new CoreMessage(original.header().withTraceId("http-order-1"), original.payloadUnsafe());
        var restored = CoreMessageCodec.decode(CoreMessageCodec.encode(traced));
        assertThat(restored.header()).isEqualTo(traced.header());
        assertThat(restored.payloadUnsafe()).containsExactly(original.payloadUnsafe());
        assertThat(CommandFingerprint.of(traced)).isEqualTo(CommandFingerprint.of(original));
        assertThat(restored.header().response(CoreMessageType.COMMAND_RESULT).traceId()).isEqualTo("http-order-1");
        // Stored fixed-header commands remain directly readable; no rewritten archive or snapshot is needed.
        assertThat(CoreMessageCodec.decode(CoreMessageCodec.encode(original)).header().traceId()).isEmpty();
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void realtimeFramesPreserveTheSameTraceInBothArrayAndBorrowedBufferDecoders(ProductLine product) {
        var frame = new RealtimeFrame(product, RealtimeFrame.Kind.TRADE, 0, 30, 2, 40, 0, "604", "trade-1",
                new byte[]{1,2,3}, "http-order-1");
        var bytes = RealtimeFrameCodec.encode(frame);
        assertThat(RealtimeFrameCodec.decode(bytes).traceId()).isEqualTo("http-order-1");
        assertThat(RealtimeFrameCodec.decode(java.nio.ByteBuffer.wrap(bytes).asReadOnlyBuffer()).traceId()).isEqualTo("http-order-1");
        assertThat(RealtimeFrameCodec.decode(bytes).payload()).containsExactly(new byte[]{1,2,3});
        var untraced = new RealtimeFrame(product, RealtimeFrame.Kind.TRADE, 0, 30, 2, 40, 0, "604", "trade-1", new byte[]{1});
        assertThat(RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(untraced)).traceId()).isEmpty();
    }
    @Test void boundsAndValidatesUntrustedTraceMetadata() {
        var header = message(ProductLine.SPOT).header();
        assertThatThrownBy(() -> header.withTraceId("bad\ntrace")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> header.withTraceId("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CoreMessageCodec.decode(CoreMessageCodec.encode(new CoreMessage(header.withTraceId("x".repeat(128)), new byte[0]))).header().traceId()).hasSize(128);
        byte[] bad = CoreMessageCodec.encode(new CoreMessage(header.withTraceId("trace-1"), new byte[0]));
        bad[CoreProtocol.HEADER_LENGTH] = 0;
        assertThatThrownBy(() -> CoreMessageCodec.decode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
