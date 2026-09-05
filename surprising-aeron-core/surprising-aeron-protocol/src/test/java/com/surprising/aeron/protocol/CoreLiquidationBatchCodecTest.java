package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreLiquidationBatchCodecTest {

    @Test
    void roundTripsCanonicalBatchAndResult() {
        ExecuteLiquidationBatchCommand command = command(512);

        byte[] first = TradingCommandCodec.encodeExecuteLiquidationBatch(command);
        byte[] second = TradingCommandCodec.encodeExecuteLiquidationBatch(command);
        ExecuteLiquidationBatchCommand decoded = TradingCommandCodec.decodeExecuteLiquidationBatch(first);

        assertThat(second).containsExactly(first);
        assertThat(decoded).isEqualTo(command);
        assertThat(TradingCommandCodec.encodeExecuteLiquidationBatch(decoded)).containsExactly(first);

        CoreLiquidationBatchResultView result = new CoreLiquidationBatchResultView(2, 1, 1, 0, 37, 64);
        byte[] resultBytes = CoreLiquidationBatchResultCodec.encode(result);
        assertThat(CoreLiquidationBatchResultCodec.decode(resultBytes)).isEqualTo(result);
        assertThat(CoreLiquidationBatchResultCodec.encode(CoreLiquidationBatchResultCodec.decode(resultBytes)))
                .containsExactly(resultBytes);
    }

    @Test
    void rejectsZeroAndOverLimitCancellationBudgets() {
        assertThatThrownBy(() -> decodeWithBudget(0)).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> decodeWithBudget(1_025)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsUnsortedAndDuplicateActions() {
        ExecuteLiquidationBatchAction first = action(7, 11, "BTC-USDT");
        ExecuteLiquidationBatchAction second = action(8, 12, "ETH-USDT");

        assertThatThrownBy(() -> new ExecuteLiquidationBatchCommand(
                List.of(second, first), 512, 3_000, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecuteLiquidationBatchCommand(
                List.of(first, first), 512, 3_000, null, 0))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] unsorted = TradingCommandCodec.encodeExecuteLiquidationBatch(command(512));
        ByteBuffer.wrap(unsorted).order(ByteOrder.LITTLE_ENDIAN).putLong(Integer.BYTES * 2, 9);
        assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(unsorted))
                .isInstanceOf(ProtocolException.class);

        byte[] duplicate = TradingCommandCodec.encodeExecuteLiquidationBatch(command(512));
        ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putLong(secondActionOffset(duplicate), 7);
        assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(duplicate))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsNegativeCursorsAndInvalidRiskBounds() {
        assertThatThrownBy(() -> new ExecuteLiquidationBatchAction(
                7, 11, "BTC-USDT", 3, 19, 60_000, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreRiskScanContinuation("BTC-USDT", 19, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecuteLiquidationBatchCommand(
                List.of(action(7, 11, "BTC-USDT")), 512, 3_000,
                new CoreRiskScanContinuation("BTC-USDT", 19, 0), 4_097))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] negativeCursor = TradingCommandCodec.encodeExecuteLiquidationBatch(command(512));
        ByteBuffer.wrap(negativeCursor).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(firstCursorOffset(negativeCursor), -1);
        assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(negativeCursor))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void defensivelyCopiesActions() {
        ArrayList<ExecuteLiquidationBatchAction> mutable = new ArrayList<>();
        mutable.add(action(7, 11, "BTC-USDT"));
        ExecuteLiquidationBatchCommand command = new ExecuteLiquidationBatchCommand(
                mutable, 512, 3_000, null, 0);

        mutable.clear();

        assertThat(command.actions()).hasSize(1);
        assertThatThrownBy(() -> command.actions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsExcessiveActionCountsBeforeAllocation() {
        byte[] payload = ByteBuffer.allocate(Integer.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ExecuteLiquidationBatchCommand.WIRE_VERSION)
                .putInt(ExecuteLiquidationBatchCommand.MAX_ACTIONS + 1)
                .array();

        assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(payload))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsEveryTruncationAndTrailingBytes() {
        byte[] encoded = TradingCommandCodec.encodeExecuteLiquidationBatch(command(512));
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(truncated))
                    .isInstanceOf(ProtocolException.class);
        }
        assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidationBatch(
                Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(ProtocolException.class);

        byte[] result = CoreLiquidationBatchResultCodec.encode(
                new CoreLiquidationBatchResultView(1, 1, 0, 0, 1, 0));
        assertThatThrownBy(() -> CoreLiquidationBatchResultCodec.decode(Arrays.copyOf(result, result.length - 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> CoreLiquidationBatchResultCodec.decode(Arrays.copyOf(result, result.length + 1)))
                .isInstanceOf(ProtocolException.class);
    }

    private static ExecuteLiquidationBatchCommand command(int maxCancelOrders) {
        return new ExecuteLiquidationBatchCommand(List.of(
                action(7, 11, "BTC-USDT"),
                action(8, 12, "ETH-USDT")), maxCancelOrders, 3_000,
                new CoreRiskScanContinuation("BTC-USDT", 19, 41), 64);
    }

    private static ExecuteLiquidationBatchAction action(long liquidationId, long userId, String symbol) {
        return new ExecuteLiquidationBatchAction(liquidationId, userId, symbol, 3, 19, 60_000, 91);
    }

    private static void decodeWithBudget(int budget) {
        byte[] encoded = TradingCommandCodec.encodeExecuteLiquidationBatch(command(512));
        ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(budgetOffset(encoded), budget);
        TradingCommandCodec.decodeExecuteLiquidationBatch(encoded);
    }

    private static int secondActionOffset(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(Integer.BYTES * 2 + Long.BYTES * 2);
        int symbolLength = Short.toUnsignedInt(buffer.getShort());
        return buffer.position() + symbolLength + Long.BYTES * 4;
    }

    private static int firstCursorOffset(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(Integer.BYTES * 2 + Long.BYTES * 2);
        int symbolLength = Short.toUnsignedInt(buffer.getShort());
        return buffer.position() + symbolLength + Long.BYTES * 3;
    }

    private static int budgetOffset(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt();
        int count = buffer.getInt();
        for (int index = 0; index < count; index++) {
            buffer.position(buffer.position() + Long.BYTES * 2);
            int symbolLength = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + symbolLength + Long.BYTES * 4);
        }
        return buffer.position();
    }
}
