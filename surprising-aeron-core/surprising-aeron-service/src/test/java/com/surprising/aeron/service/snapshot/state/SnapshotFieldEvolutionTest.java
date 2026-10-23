package com.surprising.aeron.service.snapshot.state;

import com.surprising.instrument.api.model.ContractType;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证字段编号快照的演进规则：字段重排和未知可选字段不影响状态，必填缺失、错误内容、错误布局版本及损坏记录必须拒绝；当前布局缺少成交价字段时明确拒绝。覆盖这些代表性结构，不启动运行时或 Cluster。
 */
class SnapshotFieldEvolutionTest {
    /**
     * 对空永续状态快照反转根字段顺序并添加未知字段九百，要求恢复值及业务哈希与原状态相同。
     */
    @Test
    void reorderingAndAddingFieldsDoesNotChangeRestoredState() {
        // 当前字段演进用例要编码并在变换后恢复的原业务状态。
        var state = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 当前编码器生成的带版本头快照字节。
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        // 去掉版本头后拆出的完整字段片段列表，后续重排、移除或添加。
        var fields = split(Arrays.copyOfRange(encoded, 4, encoded.length));
        Collections.reverse(fields);
        fields.add(new SnapshotFields.Writer().text(900, "future optional business metadata").encode());
        // 局部字段结构改变后的记录或带头快照字节。
        byte[] changed = joinWithVersion(encoded, fields);
        assertThat(TradingStateSnapshotCodec.decode(changed, state.productLine())).isEqualTo(state);
        assertThat(TradingStateSnapshotCodec.decode(changed, state.productLine()).businessStateHash())
                .isEqualTo(state.businessStateHash());
    }

    /**
     * 分别移除必填字段二、把字段二改成长度错误的文本内容，两种情况都要求按快照字段错误拒绝。
     */
    @Test
    void criticalFieldCannotDisappearOrChangeType() {
        // 当前字段演进用例要编码并在变换后恢复的原业务状态。
        var state = TradingCoreState.empty(ProductLine.SPOT);
        // 当前编码器生成的带版本头快照字节。
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        for (boolean /* 当前循环是否把必填字段替换成错误文本；false 时只删除字段。 */ wrongType : new boolean[]{false, true}) {
            // 去掉版本头后拆出的完整字段片段列表，后续重排、移除或添加。
            var fields = split(Arrays.copyOfRange(encoded, 4, encoded.length));
            fields.removeIf(/* 当前检查或复制的一条完整字段字节，包含编号与长度头。 */ field -> id(field) == 2);
            if (wrongType) fields.add(new SnapshotFields.Writer().text(2, "not a revision").encode());
            assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(encoded, fields), state.productLine()))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("snapshot field");
        }
    }

    /** 不兼容的旧布局和未来布局都必须在读取业务字段前被拒绝。 */
    @Test
    void unsupportedLayoutVersionsAreRejected() {
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));
        int version = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt();
        for (int other : new int[] {version - 1, version + 1}) {
            byte[] changed = encoded.clone();
            ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(other);
            assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(changed, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported trading snapshot version");
        }
    }

    /**
     * 构造最近成交价为零的标记价状态，逐层移除其字段七，再要求明确拒绝不完整记录。
     */
    @Test
    void missingLastPriceFieldIsRejected() {
        // 用于提供空用户、订单、币对和财库的基础现货状态。
        var empty = TradingCoreState.empty(ProductLine.SPOT);
        // 本用例固定最近成交价为零的币对标记价记录。
        var mark = new com.surprising.aeron.model.CoreMarkPriceState("1", 100, 0, 0, 1, 1000, 0);
        // 仅含这条标记价、没有其他风险记录的状态。
        var risk = new com.surprising.aeron.model.CoreRiskState(Map.of("1", mark), Map.of(), Map.of(), Map.of(), 1);
        // 当前字段演进用例要编码并在变换后恢复的原业务状态。
        var state = new TradingCoreState(empty.productLine(), 0, empty.users(), empty.orders(),
                empty.instruments(), risk, empty.treasuryState());
        // 当前编码器生成的带版本头快照字节。
        byte[] encoded = TradingStateSnapshotCodec.encode(state);
        // 依次进入根风险、标记价表、正文条目，最终移除标记价最近成交价字段七。
        // 局部字段结构改变后的记录或带头快照字节。
        byte[] changed = replace(Arrays.copyOfRange(encoded, 4, encoded.length), 6,
                /* 根风险字段的内容记录，下一层要进入标记价表。 */ r -> replace(r, 1, /* 标记价 Map 记录，下一层要进入条目列表字段。 */ marks -> replace(marks, 1, /* 当前仅含一个标记价映射条目的列表字节，前面带条目长度。 */ entries -> {
                    // 按小端读取唯一标记价列表条目的字节视图。
                    var input = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN);
                    // 当前测试条目声明的字节长度，或截断循环保留的字节数。
                    int length = input.getInt();
                    assertThat(input.remaining()).isEqualTo(length);
                    // 复制出的单个标记价正文记录。
                    byte[] entry = new byte[length]; input.get(entry);
                    // 移除标记价最近成交价字段后重建的 Map 条目字节。
                    byte[] updated = replace(entry, 7, /* 移除正文中的最近成交价字段。 */ ignored -> null);
                    return ByteBuffer.allocate(4 + updated.length).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(updated.length).put(updated).array();
                })));
        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(encoded, split(changed)), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("snapshot field");
    }

    @Test
    void instrumentAndMarketFieldsFollowProductLine() {
        for (ProductLine line : ProductLine.values()) {
            boolean option = line.isOptionProduct();
            boolean inverse = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY;
            var risk = line.isDerivative() ? new CoreInstrumentRiskLimits(
                    100_000, option ? -99 : 10_000_000, 1_000_000, 0, 1_000_000,
                    List.of(new CoreRiskLimitBracket(1_000_000, option ? -88 : 10_000_000,
                            100_000, 50_000, option ? 1_200_000 : -77))) : null;
            var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(line,
                    new com.surprising.aeron.protocol.RegisterInstrumentCommand("1", "BTC", "USDT", "USDT",
                            1, inverse ? 3 : -99, inverse ? 100 : -99, risk, 0, 0,
                            line.isDeliveryProduct() ? 2_000 : -99, option ? 0 : -99, option ? 100 : -99,
                            1, true, true, true, 3, 15, line.isDerivative() ? -99 : 7,
                            new com.surprising.aeron.protocol.CoreOrderProtection(20_000, 8_000, true, 30_000, 4_000)));
            var empty = TradingCoreState.empty(line);
            var runtimeMark = new com.surprising.aeron.service.market.MarkPriceRuntime(
                    instrument.instrumentIdValue(), instrument, 100, 99, option ? 98 : -99, 1, 1_000, 97);
            assertThat(runtimeMark.forwardPriceTicks()).isEqualTo(option ? 98 : 0);
            var mark = new com.surprising.aeron.model.CoreMarkPriceState("1", 100, 99,
                    runtimeMark.forwardPriceTicks(), 1, 1_000, 97);
            var riskState = new com.surprising.aeron.model.CoreRiskState(Map.of("1", mark), Map.of(),
                    Map.of(), Map.of(), 1, empty.riskState().scanControl());
            var state = new TradingCoreState(line, 0, empty.users(), empty.orders(),
                    Map.of("1", instrument), riskState, empty.treasuryState());
            byte[] encoded = TradingStateSnapshotCodec.encode(state);
            var root = new SnapshotFields.Reader(Arrays.copyOfRange(encoded, 4, encoded.length));
            var entries = new SnapshotFields.Reader(root.bytes(5)).list(1, bytes -> bytes);
            assertThat(entries).hasSize(1);
            var fields = split(entries.getFirst()).stream().map(SnapshotFieldEvolutionTest::id).toList();
            assertThat(fields).doesNotContain(2, 10);
            if (inverse) assertThat(fields).contains(7, 8);
            else assertThat(fields).doesNotContain(7, 8);
            if (line.isDeliveryProduct()) assertThat(fields).contains(13);
            else assertThat(fields).doesNotContain(13);
            if (option) assertThat(fields).contains(14, 15);
            else assertThat(fields).doesNotContain(14, 15);
            if (!line.isDerivative()) assertThat(fields).contains(28);
            else assertThat(fields).doesNotContain(28);
            if (line.isDerivative()) {
                assertThat(fields).contains(9, 17, 18, 19, 20, 29, 30, 31, 32, 33);
                assertThat(instrument.orderProtection()).isNotNull();
                if (option) assertThat(fields).doesNotContain(16);
                else assertThat(fields).contains(16);
                var brackets = new SnapshotFields.Reader(entries.getFirst()).list(20, bytes -> bytes);
                for (byte[] bracket : brackets) {
                    assertThat(split(bracket).stream().map(SnapshotFieldEvolutionTest::id).toList())
                            .containsExactlyElementsOf(option ? List.of(3, 5, 6, 7) : List.of(3, 4, 5, 6));
                }
            } else {
                assertThat(fields).doesNotContain(9, 16, 17, 18, 19, 20, 29, 30, 31, 32, 33);
                assertThat(instrument.orderProtection()).isNull();
            }
            var riskFields = new SnapshotFields.Reader(root.bytes(6));
            var marks = new SnapshotFields.Reader(riskFields.bytes(1)).list(1, bytes -> bytes);
            var markFields = split(marks.getFirst()).stream().map(SnapshotFieldEvolutionTest::id).toList();
            if (option) assertThat(markFields).containsExactly(1, 2, 3, 5, 6, 7, 4);
            else assertThat(markFields).containsExactly(1, 2, 3, 5, 6, 7);
            var restored = TradingStateSnapshotCodec.decode(encoded, line);
            assertThat(restored).isEqualTo(state);
            assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
            assertThat(restored.instruments().get("1").riskLimits()).isEqualTo(instrument.riskLimits());
        }
    }

    @Test
    void orderSnapshotStoresOnlyTotalAndExecutedQuantityForEveryProduct() {
        for (ProductLine line : ProductLine.values()) {
            boolean inverse = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY;
            String quote = inverse ? "USD" : "USDT";
            String settle = inverse ? "BTC" : "USDT";
            var transitions = new com.surprising.aeron.service.order.state.RuntimeTestStateTransitions();
            var empty = transitions.adjustBalance(transitions.registerInstrument(TradingCoreState.empty(line),
                    com.surprising.aeron.service.core.CoreStateTestFixtures.instrument(line, "1", "BTC", quote, settle)),
                    7, new com.surprising.aeron.protocol.BalanceAdjustmentCommand(settle, 50_000));
            var order = new com.surprising.aeron.model.CoreOrderState(11, line, 7, "1",
                    com.surprising.aeron.protocol.CoreOrderSide.BUY, 100, 10, 3, false,
                    com.surprising.aeron.model.CoreOrderStatus.CANCELED, 1);
            var state = new TradingCoreState(line, 1, empty.users(), Map.of(11L, order),
                    empty.instruments(), empty.riskState(), empty.treasuryState());
            byte[] encoded = TradingStateSnapshotCodec.encode(state);
            byte[] rootBytes = Arrays.copyOfRange(encoded, 4, encoded.length);
            var root = new SnapshotFields.Reader(rootBytes);
            var orders = new SnapshotFields.Reader(root.bytes(4)).list(1, bytes -> bytes);
            assertThat(orders).hasSize(1);
            assertThat(split(orders.getFirst()).stream().map(SnapshotFieldEvolutionTest::id).toList())
                    .contains(8, 9).doesNotContain(10);
            var restored = TradingStateSnapshotCodec.decode(encoded, line);
            assertThat(restored).isEqualTo(state);
            assertThat(restored.orders().get(11L).remainingQuantitySteps()).isEqualTo(7);
            assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());

            byte[] invalidOrder = replace(orders.getFirst(), 9,
                    ignored -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(11).array());
            byte[] invalidOrders = new SnapshotFields.Writer().list(1, List.of(invalidOrder), bytes -> bytes).encode();
            byte[] invalidRoot = replace(rootBytes, 4, ignored -> invalidOrders);
            assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(encoded, split(invalidRoot)), line))
                    .isInstanceOf(ProtocolException.class);
            byte[] previousVersion = encoded.clone();
            ByteBuffer.wrap(previousVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 47);
            assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(previousVersion, line))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported trading snapshot version");
        }
    }

    @Test
    void riskSnapshotStoresRatioAndDerivesStatusAfterRestore() {
        long[] ratios = {0, 799_999, 800_000, 999_999, 1_000_000, Long.MAX_VALUE};
        for (ProductLine line : ProductLine.values()) {
            if (!line.isDerivative()) continue;
            String quote = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY
                    ? "USD" : "USDT";
            var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(line,
                    com.surprising.aeron.service.core.CoreStateTestFixtures.instrument(line, "1", "BTC", quote,
                            com.surprising.aeron.service.core.CoreStateTestFixtures.settleAsset(line)));
            var user = new com.surprising.aeron.service.account.CoreUserState(
                    line, 7, 0, Map.of(), Map.of(), Map.of());
            var empty = TradingCoreState.empty(line);
            for (long ratio : ratios) {
                var risk = new com.surprising.aeron.model.CoreRiskSnapshot(7, "1",
                        com.surprising.aeron.protocol.CorePositionSide.NET, 9, 1_000, 20, 100, ratio);
                var riskState = new com.surprising.aeron.model.CoreRiskState(Map.of(), Map.of(risk.key(), risk),
                        Map.of(), Map.of(), 1, empty.riskState().scanControl());
                var state = new TradingCoreState(line, 0, Map.of(7L, user), Map.of(), Map.of("1", instrument),
                        riskState, empty.treasuryState());
                byte[] encoded = TradingStateSnapshotCodec.encode(state);
                byte[] rootBytes = Arrays.copyOfRange(encoded, 4, encoded.length);
                var root = new SnapshotFields.Reader(rootBytes);
                var riskRoot = new SnapshotFields.Reader(root.bytes(6));
                var risks = new SnapshotFields.Reader(riskRoot.bytes(2)).list(1, bytes -> bytes);
                assertThat(risks).hasSize(1);
                assertThat(split(risks.getFirst()).stream().map(SnapshotFieldEvolutionTest::id).toList())
                        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
                var restored = TradingStateSnapshotCodec.decode(encoded, line);
                assertThat(restored).isEqualTo(state);
                assertThat(restored.riskState().snapshots().get(risk.key()).status()).isEqualTo(risk.status());
                assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());

                for (boolean missing : List.of(false, true)) {
                    byte[] invalid = replace(risks.getFirst(), 8, ignored -> missing ? null
                            : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1).array());
                    byte[] invalidRows = new SnapshotFields.Writer().list(1, List.of(invalid), bytes -> bytes).encode();
                    byte[] invalidRisk = replace(root.bytes(6), 2, ignored -> invalidRows);
                    byte[] invalidRoot = replace(rootBytes, 6, ignored -> invalidRisk);
                    assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(joinWithVersion(encoded, split(invalidRoot)), line))
                            .isInstanceOf(ProtocolException.class);
                }
                byte[] previousVersion = encoded.clone();
                ByteBuffer.wrap(previousVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 47);
                assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(previousVersion, line))
                        .isInstanceOf(ProtocolException.class).hasMessageContaining("unsupported trading snapshot version");
            }
        }
    }

    /**
     * 在一条字段记录里查找指定编号，对内容执行测试变换；返回空则移除该字段，否则重新写头和内容，最后断言目标确实存在。
     * @param record 待修改的一条字段记录，不含外层版本头
     * @param fieldId 要修改或删除的目标字段编号
     * @param update 对目标字段内容的测试变换函数，返回 null 表示删除
     */
    private static byte[] replace(byte[] record, int fieldId, java.util.function.UnaryOperator<byte[]> update) {
        // 本次测试重建字段记录的输出缓冲。
        var output = new java.io.ByteArrayOutputStream();
        // 是否在遍历中找到目标编号，结束时必须为真以避免测试改错位置。
        boolean found = false;
        for (byte[] /* 当前检查或复制的一条完整字段字节，包含编号与长度头。 */ field : split(record)) {
            if (id(field) != fieldId) { output.writeBytes(field); continue; }
            found = true;
            // 目标字段变换后的内容，空值表示删除整个字段。
            byte[] value = update.apply(Arrays.copyOfRange(field, 8, field.length));
            if (value != null) output.writeBytes(new SnapshotFields.Writer().bytes(fieldId, value).encode());
        }
        assertThat(found).isTrue();
        return output.toByteArray();
    }

    /**
     * 分别验证重复字段、各种非空截断长度、非法 UTF-8 字节被拒绝，以及 long 转 int 溢出仍抛 ArithmeticException。
     */
    @Test
    void duplicateFieldsTruncationMalformedTextAndOverflowAreRejected() {
        // 测试生成的编号一、数值四十二的完整字段，后续用来重复或截断。
        byte[] field = new SnapshotFields.Writer().number(1, 42).encode();
        // 把相同编号一字段连续写两次的非法记录。
        byte[] duplicate = ByteBuffer.allocate(field.length * 2).put(field).put(field).array();
        assertThatThrownBy(() -> new SnapshotFields.Reader(duplicate)).isInstanceOf(ProtocolException.class);
        for (/* 当前测试条目声明的字节长度，或截断循环保留的字节数。 */ int length = 1; length < field.length; length++) {
            // 只保留当前循环指定长度的不完整字段字节。
            byte[] truncated = Arrays.copyOf(field, length);
            assertThatThrownBy(() -> new SnapshotFields.Reader(truncated)).isInstanceOf(ProtocolException.class);
        }
        // 字段内容为非法 UTF-8 字节的读取器，构造可成功但文本读取必须失败。
        var invalid = new SnapshotFields.Reader(new SnapshotFields.Writer().bytes(1, new byte[]{(byte) 0xff}).encode());
        assertThatThrownBy(() -> invalid.text(1)).isInstanceOf(ProtocolException.class);
        // 字段值为 Long.MAX_VALUE 的读取器，用来核对转 int 的精确溢出检查。
        var overflow = new SnapshotFields.Reader(new SnapshotFields.Writer().number(1, Long.MAX_VALUE).encode());
        assertThatThrownBy(() -> overflow.integer(1)).isInstanceOf(ArithmeticException.class);
    }

    /**
     * 读取完整测试字段头的第一个小端整数，作为字段编号；不执行生产边界校验。
     * @param field 包含编号、长度和内容的完整单字段字节
     */
    private static int id(byte[] field) { return ByteBuffer.wrap(field).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    /**
     * 按每项编号及长度头切出完整字段字节，用于重排和局部替换；依赖测试输入本身格式完整，不是生产记录校验器。
     * @param encoded 要按字段头拆分的完整记录
     */
    private static List<byte[]> split(byte[] encoded) {
        // 拆分记录或拼接新版本头时使用的小端字节缓冲。
        var buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        // 去掉版本头后拆出的完整字段片段列表，后续重排、移除或添加。
        List<byte[]> fields = new ArrayList<>();
        while (buffer.hasRemaining()) {
            // 当前字段头开始的位置，用于复制该字段完整字节。
            int start = buffer.position(); buffer.getInt(); /* 当前测试条目声明的字节长度，或截断循环保留的字节数。 */ int length = buffer.getInt();
            buffer.position(buffer.position() + length);
            fields.add(Arrays.copyOfRange(encoded, start, buffer.position()));
        }
        return fields;
    }
    /**
     * 按列表当前顺序拼接完整字段，沿用被修改快照的原版本头。
     * @param original 被修改的原快照，保留其四字节版本头
     * @param fields 需要按当前顺序拼回快照的完整单字段字节列表
     */
    private static byte[] joinWithVersion(byte[] original, List<byte[]> fields) {
        // 拆分记录或拼接新版本头时使用的小端字节缓冲。
        var buffer = ByteBuffer.allocate(4 + fields.stream().mapToInt(/* 参与计算拼接总长度的一段完整字段字节。 */ a -> a.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(original, 0, Integer.BYTES); fields.forEach(buffer::put); return buffer.array();
    }
}
