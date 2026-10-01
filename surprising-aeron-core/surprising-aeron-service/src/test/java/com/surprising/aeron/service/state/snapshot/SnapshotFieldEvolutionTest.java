package com.surprising.aeron.service.state.snapshot;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotFieldEvolutionTest {
    @Test
    void reorderingAndAddingFieldsDoesNotChangeRestoredState() {
        var state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        var fields = split(Arrays.copyOfRange(encoded, 4, encoded.length));
        Collections.reverse(fields);
        fields.add(new SnapshotFields.Writer().text(900, "future optional business metadata").encode());
        byte[] changed = joinWithVersion(fields);
        assertThat(TradingStateSnapshotCodec.decode(changed, state.productLine())).isEqualTo(state);
        assertThat(TradingStateSnapshotCodec.decode(changed, state.productLine()).businessStateHash())
                .isEqualTo(state.businessStateHash());
    }

    @Test
    void criticalFieldCannotDisappearOrChangeType() {
        var state = TradingCoreState.empty(ProductLine.SPOT);
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        for (boolean wrongType : new boolean[]{false, true}) {
            var fields = split(Arrays.copyOfRange(encoded, 4, encoded.length));
            fields.removeIf(field -> id(field) == 2);
            if (wrongType) fields.add(new SnapshotFields.Writer().text(2, "not a revision").encode());
            assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(fields), state.productLine()))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("snapshot field");
        }
    }

    @Test
    void incompatibleBusinessRequirementsAreNotSilentlyIgnored() {
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));
        var fields = split(Arrays.copyOfRange(encoded, 4, encoded.length));
        fields.removeIf(field -> id(field) == 12);
        fields.add(new SnapshotFields.Writer().number(12, 2).encode());
        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(fields), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("requires reader version");
    }

    @Test
    void missingNewOptionalFieldUsesExplicitHistoricalDefault() {
        var empty = TradingCoreState.empty(ProductLine.SPOT);
        var mark = new com.surprising.aeron.service.state.model.CoreMarkPriceState("1", 100, 0, 0, 1, 1000, 0);
        var risk = new com.surprising.aeron.service.state.model.CoreRiskState(Map.of("1", mark), Map.of(), Map.of(), Map.of(), 1);
        var state = new TradingCoreState(empty.productLine(), 0, empty.users(), empty.orders(),
                empty.instruments(), risk, empty.treasuryState());
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        // Root.risk -> risk.marks -> map.entries -> entry.value -> mark.lastPriceTicks (field 7).
        byte[] changed = replace(Arrays.copyOfRange(encoded, 4, encoded.length), 6,
                r -> replace(r, 1, marks -> replace(marks, 1, entries -> {
                    var input = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN);
                    int length = input.getInt();
                    assertThat(input.remaining()).isEqualTo(length);
                    byte[] entry = new byte[length]; input.get(entry);
                    byte[] updated = replace(entry, 2, m -> replace(m, 7, ignored -> null));
                    return ByteBuffer.allocate(4 + updated.length).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(updated.length).put(updated).array();
                })));
        var restored = TradingStateSnapshotCodec.decode(joinWithVersion(split(changed)), ProductLine.SPOT);
        assertThat(restored).isEqualTo(state);
        assertThat(restored.riskState().markPrices().get("1").lastPriceTicks()).isZero();
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
    }

    private static byte[] replace(byte[] record, int fieldId, java.util.function.UnaryOperator<byte[]> update) {
        var output = new java.io.ByteArrayOutputStream();
        boolean found = false;
        for (byte[] field : split(record)) {
            if (id(field) != fieldId) { output.writeBytes(field); continue; }
            found = true;
            byte[] value = update.apply(Arrays.copyOfRange(field, 8, field.length));
            if (value != null) output.writeBytes(new SnapshotFields.Writer().bytes(fieldId, value).encode());
        }
        assertThat(found).isTrue();
        return output.toByteArray();
    }

    @Test
    void duplicateFieldsTruncationMalformedTextAndOverflowAreRejected() {
        byte[] field = new SnapshotFields.Writer().number(1, 42).encode();
        byte[] duplicate = ByteBuffer.allocate(field.length * 2).put(field).put(field).array();
        assertThatThrownBy(() -> new SnapshotFields.Reader(duplicate)).isInstanceOf(ProtocolException.class);
        for (int length = 1; length < field.length; length++) {
            byte[] truncated = Arrays.copyOf(field, length);
            assertThatThrownBy(() -> new SnapshotFields.Reader(truncated)).isInstanceOf(ProtocolException.class);
        }
        var invalid = new SnapshotFields.Reader(new SnapshotFields.Writer().bytes(1, new byte[]{(byte) 0xff}).encode());
        assertThatThrownBy(() -> invalid.text(1)).isInstanceOf(ProtocolException.class);
        var overflow = new SnapshotFields.Reader(new SnapshotFields.Writer().number(1, Long.MAX_VALUE).encode());
        assertThatThrownBy(() -> overflow.integer(1)).isInstanceOf(ArithmeticException.class);
    }

    private static int id(byte[] field) { return ByteBuffer.wrap(field).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static List<byte[]> split(byte[] encoded) {
        var buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        List<byte[]> fields = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int start = buffer.position(); buffer.getInt(); int length = buffer.getInt();
            buffer.position(buffer.position() + length);
            fields.add(Arrays.copyOfRange(encoded, start, buffer.position()));
        }
        return fields;
    }
    private static byte[] joinWithVersion(List<byte[]> fields) {
        var buffer = ByteBuffer.allocate(4 + fields.stream().mapToInt(a -> a.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(37); fields.forEach(buffer::put); return buffer.array();
    }
}
