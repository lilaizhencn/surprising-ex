package com.surprising.aeron.tools.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineReplayMainTest {

    @Test
    void replaysLengthPrefixedProtocolMessages() throws Exception {
        byte[] encoded = CoreMessageCodec.encode(command());
        Path log = Files.createTempFile("aeron-replay-", ".bin");
        Files.write(log, ByteBuffer.allocate(Integer.BYTES + encoded.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(encoded.length)
                .put(encoded)
                .array());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OfflineReplayMain.class);
        var output = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        output.start();
        logger.addAppender(output);
        try {
            OfflineReplayMain.main(new String[]{"SPOT", log.toString()});
            assertThat(output.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("messages=1", "appliedCommandCount=1"));
        } finally {
            logger.detachAppender(output);
            output.stop();
            Files.deleteIfExists(log);
        }

    }

    @Test
    void rejectsTruncatedMessageInsteadOfSilentlyStopping() throws Exception {
        Path log = Files.createTempFile("aeron-replay-truncated-", ".bin");
        Files.write(log, ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putShort((short) 1).array());

        assertThatThrownBy(() -> OfflineReplayMain.main(new String[]{"SPOT", log.toString()}))
                .isInstanceOf(java.io.EOFException.class)
                .hasMessageContaining("truncated replay message");
    }

    private static CoreMessage command() {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.RECOVERY_TOOL, 1, 1, 0, 1_000, 1),
                CoreProtocol.probePayload(1));
    }
}
