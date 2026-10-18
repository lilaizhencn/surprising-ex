package com.surprising.aeron.tools.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.service.orchestration.TradingExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineReplayMainTest {

    @ParameterizedTest @EnumSource(TradingExecutionMode.class)
    void replaysLengthPrefixedProtocolMessages(TradingExecutionMode mode) throws Exception {
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
        String previous = System.setProperty("surprising.aeron.execution-mode", mode.name());
        try {
            OfflineReplayMain.main(new String[]{"SPOT", log.toString()});
            assertThat(output.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("messages=1", "appliedCommandCount=1"));
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
            else System.setProperty("surprising.aeron.execution-mode", previous);
            logger.detachAppender(output);
            output.stop();
            Files.deleteIfExists(log);
        }

    }

    @Test
    void rejectsTruncatedMessageInsteadOfSilentlyStopping() throws Exception {
        Path log = Files.createTempFile("aeron-replay-truncated-", ".bin");
        Files.write(log, ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putShort((short) 1).array());

        try {
            assertThatThrownBy(() -> OfflineReplayMain.main(new String[]{"SPOT", log.toString()}))
                    .isInstanceOf(java.io.EOFException.class)
                    .hasMessageContaining("truncated replay message");
        } finally { Files.deleteIfExists(log); }
    }

    @ParameterizedTest @EnumSource(TradingExecutionMode.class)
    void replaysOrdersRejectionAndRetryBeforeFollowingQuery(TradingExecutionMode mode) throws Exception {
        var commands = new java.util.ArrayList<CoreMessage>();
        commands.add(message(CoreMessageType.REGISTER_INSTRUMENT, 1,
                TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0))));
        var balance = message(CoreMessageType.ADJUST_BALANCE, 2,
                TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand("USDT", 1_000)));
        commands.add(balance);
        commands.add(message(CoreMessageType.PLACE_ORDER, 3, place(101, 1)));
        commands.add(message(CoreMessageType.CANCEL_ORDER, 4,
                TradingCommandCodec.encodeCancelOrder(
                        new CancelOrderCommand(101))));
        // An insufficient-funds rejection still has to retire before the next replayed command.
        commands.add(message(CoreMessageType.PLACE_ORDER, 5, place(102, 1_000)));
        commands.add(message(CoreMessageType.PLACE_ORDER, 6, place(103, 1)));
        commands.add(message(CoreMessageType.CANCEL_ORDER, 7,
                TradingCommandCodec.encodeCancelOrder(
                        new CancelOrderCommand(103))));
        commands.add(balance);
        commands.add(new CoreMessage(CoreMessageHeader.query(CoreMessageType.STATE_HASH_QUERY, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.RECOVERY_TOOL, 1, 0, 7, 1_000, 9), CoreMessage.EMPTY_PAYLOAD));
        Path log = Files.createTempFile("aeron-order-replay-", ".bin");
        var bytes = new java.io.ByteArrayOutputStream();
        for (var command : commands) {
            byte[] encoded = CoreMessageCodec.encode(command);
            bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(encoded.length).array());
            bytes.write(encoded);
        }
        Files.write(log, bytes.toByteArray());
        String previous = System.setProperty("surprising.aeron.execution-mode", mode.name());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OfflineReplayMain.class);
        var output = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        output.start(); logger.addAppender(output);
        try {
            OfflineReplayMain.main(new String[]{"SPOT", log.toString()});
            assertThat(output.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("messages=9", "appliedCommandCount=7"));
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
            else System.setProperty("surprising.aeron.execution-mode", previous);
            logger.detachAppender(output); output.stop(); Files.deleteIfExists(log);
        }
    }

    private static CoreMessage message(CoreMessageType type, long sequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(66, sequence), ProductLine.SPOT,
                CommandSource.RECOVERY_TOOL, 1, sequence, 7, 1_000, sequence), payload);
    }

    private static byte[] place(long orderId, long quantity) {
        return TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(orderId, "1",
                        CoreOrderSide.BUY, 100, quantity, false,
                        CoreMarginMode.CROSS,
                        CorePositionSide.NET,
                        CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "replay-" + orderId));
    }

    private static CoreMessage command() {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.RECOVERY_TOOL, 1, 1, 7, 1_000, 1),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
    }
}
