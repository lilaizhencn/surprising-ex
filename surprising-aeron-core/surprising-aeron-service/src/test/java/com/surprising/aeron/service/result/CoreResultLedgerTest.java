package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntimeRestoreTestSupport;
import com.surprising.product.api.ProductLine;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证近期命令结果账本的指纹稳定、按保留顺序淘汰、条数与字节上限及恢复配对。部分用例直接构造结果记录，不能把它们扩大为真实撮合、资金或长期资金幂等验证。
 */
class CoreResultLedgerTest {

    /**
     * 反复借输入和指纹后存入两千零四十八条结果，核对仍保留项的稳定指纹、重复响应及同 UUID 内容冲突；最早对外取出的不可变指纹在记录复用后仍正确。
     */
    @Test
    void borrowedFingerprintsOutliveDecoderReuseAndExposedValuesOutliveLedgerRecordReuse() {
        // 本场景真实的近期结果账本
        var ledger = new CommandResultLedger(new LinkedHashMap<>());
        // 可生成借用指纹槽和独立指纹值的计算器
        var calculator = new com.surprising.aeron.protocol.CommandFingerprint.Calculator();
        // 本场景反复借还输入消息的解码器
        var decoder = new com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder();
        // 按场景保存参照指纹或应用计数与保留序号的有序 Map
        var expected = new LinkedHashMap<UUID, com.surprising.aeron.protocol.CommandFingerprint>();
        // 最早从账本取出的不可变指纹值，须超出池记录复用期仍保持稳定
        com.surprising.aeron.protocol.CommandFingerprint firstExposed = null;
        // 最早探针对应的独立预期指纹
        com.surprising.aeron.protocol.CommandFingerprint firstExpected = null;
        for (/* 当前写入或应用的探针序号 */ int sequence = 1; sequence <= 2048; sequence++) {
            // 本次新增或替换结果的目标 UUID
            UUID id = new UUID(18, sequence);
            // 本轮独立探针消息，作为指纹参照
            var owned = checkpoint(id, sequence, sequence);
            // 把独立探针编码后的完整协议帧
            byte[] frame = com.surprising.aeron.protocol.CoreMessageCodec.encode(owned);
            // 由解码器借出的本轮消息，存入后归还解码租约
            var input = decoder.decodeBorrowed(new org.agrona.concurrent.UnsafeBuffer(frame), 0, frame.length);
            // 本轮要存入结果的借用或独立命令指纹
            var fingerprint = calculator.ofBorrowed(input);
            // 当前独立消息计算出的稳定指纹参照
            var stable = calculator.of(owned);
            ledger.storeOwnedResult(id, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
            if (sequence == 1) {
                firstExposed = ledger.get(id).fingerprint();
                firstExpected = stable;
            }
            input.releaseDecoderSlot();
            expected.put(id, stable);
            if (expected.size() > TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS)
                expected.remove(expected.keySet().iterator().next());
            for (var /* 仍应保留的参照 Map 当前键值项 */ entry : expected.entrySet())
                assertThat(ledger.get(entry.getKey()).fingerprint()).isEqualTo(entry.getValue());
            assertThat(ledger.duplicateResponse(ledger.get(id), owned, stable, sequence).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            // 保留相同头部 UUID 但改动载荷的冲突请求，只用于检查重复判定
            var changed = new CoreMessage(owned.header(), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", sequence + 1)));
            assertThat(ledger.duplicateResponse(ledger.get(id), changed, calculator.of(changed), sequence).resultCode())
                    .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
        }
        assertThat(firstExposed).isEqualTo(firstExpected);
    }

    /**
     * 用固定随机种子在一千零二十四个 UUID 中执行两万次新增或替换，按参照 Map 核对顺序、应用计数和保留序号，并周期性从导出结果重建账本。
     */
    @Test
    void sustainedTurnoverPreservesLookupRetentionAndRestoreOrder() {
        // 本场景真实的近期结果账本
        var ledger = new CommandResultLedger(new LinkedHashMap<>());
        // 按场景保存参照指纹或应用计数与保留序号的有序 Map
        var expected = new LinkedHashMap<UUID, long[]>();
        // 固定种子九百一十五的随机源，保证两万次周转可复现
        var random = new java.util.Random(915);
        // 固定的一千零二十四个随机 UUID 候选
        var ids = new UUID[1024];
        for (/* 初始化候选 UUID 的数组下标 */ int i = 0; i < ids.length; i++) ids[i] = new UUID(random.nextLong(), random.nextLong());
        // 参照模型下一条新增身份应分配的保留序号
        long nextRetention = 1;
        for (/* 当前写入或应用的探针序号 */ int sequence = 1; sequence <= 20_000; sequence++) {
            // 本次新增或替换结果的目标 UUID
            UUID id = ids[random.nextInt(ids.length)];
            // 参照模型本次沿用或新增的保留序号
            long retention = expected.containsKey(id) ? expected.get(id)[1] : nextRetention++;
            expected.put(id, new long[]{sequence, retention});
            if (expected.size() > TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS)
                expected.remove(expected.keySet().iterator().next());
            ledger.storeResult(id, stored(ResponseStatus.APPLIED, CoreResultCode.NONE,
                    sequence, 0, new byte[]{(byte) sequence}, 0));
            assertThat(ledger.get(id).appliedCommandCount()).isEqualTo(sequence);
            if (sequence % 97 != 0) continue;
            assertThat(ledger.entries().keySet()).containsExactlyElementsOf(expected.keySet());
            for (var /* 仍应保留的参照 Map 当前键值项 */ entry : expected.entrySet()) {
                // 本次查到的记录或只读查询响应，按场景断言元数据
                var result = ledger.get(entry.getKey());
                assertThat(result.appliedCommandCount()).isEqualTo(entry.getValue()[0]);
                assertThat(result.retentionSequence()).isEqualTo(entry.getValue()[1]);
            }
            assertThat(ledger.get(new UUID(0, -sequence))).isNull();
            if (sequence % 970 == 0)
                ledger = new CommandResultLedger(new LinkedHashMap<>(ledger.entries()));
        }
    }

    /**
     * 先存两条结果再替换第一条，核对直接接管响应数组、保留原顺序及原序号；只在本次替换后检查旧记录，不能推断旧池记录永久不变。
     */
    @Test
    void ownedInsertionPreservesSequenceAndPriorResultWhenReplacing() {
        // 本场景真实的近期结果账本
        var ledger = new CommandResultLedger(new LinkedHashMap<>());
        // 按场景最先存入的结果 UUID
        // 按场景第二个结果 UUID
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        // 本轮要存入结果的借用或独立命令指纹
        var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.of(checkpoint(first, 1, 1));
        // 第一次存入时直接交给账本持有的数组
        byte[] originalBytes = {1, 2};
        ledger.storeOwnedResult(first, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, originalBytes);
        // 替换前借出的旧池记录，仅在本次替换之后立即核对
        var original = ledger.get(first);
        ledger.storeOwnedResult(second, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                2, new byte[]{3});
        // 替换同一命令时交入的新数组
        byte[] replacementBytes = {4};
        ledger.storeOwnedResult(first, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                3, replacementBytes);
        assertThat(original.responseDataUnsafe()).isSameAs(originalBytes);
        assertThat(original.responseData()).containsExactly(1, 2);
        assertThat(ledger.get(first).responseDataUnsafe()).isSameAs(replacementBytes);
        assertThat(ledger.get(first).retentionSequence()).isEqualTo(original.retentionSequence());
        assertThat(ledger.entries().keySet()).containsExactly(first, second);
        CommandResultLedger.validateResultLedger(ledger.entries());
    }

    /**
     * 默认构造先复制源数组，修改保留序号的新记录与原记录共用内部字节；对外 responseData 返回独立副本，改外部副本不能改内部数据。
     */
    @Test
    void retentionMetadataReusesOwnedResponseBytesWithoutExposingThem() {
        // 独立记录构造时默认复制的原字节数组
        byte[] source = new byte[]{1, 2, 3};
        // 默认复制响应字节的原独立记录
        CommandResultLedger.StoredResult created = stored(
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 7, source, 0);
        source[0] = 9;

        // 只换保留序号、继续共用内部字节的新独立记录
        CommandResultLedger.StoredResult retained = created.withRetentionSequence(11);
        // 从记录导出的有效响应副本，测试故意改写它
        byte[] exposed = retained.responseData();
        exposed[1] = 9;

        assertThat(created.responseDataUnsafe()).isSameAs(retained.responseDataUnsafe());
        assertThat(retained.responseData()).containsExactly(1, 2, 3);
        assertThat(retained.retentionSequence()).isEqualTo(11);
    }

    /**
     * 超过近期结果条数上限后查询最早命令，要求明确返回 RESULT_UNKNOWN_OUTSIDE_RETENTION，不把未保留结果伪装成已找到或未执行。
     */
    @Test
    void evictedCommandResultIsExplicitlyOutsideRetention() {
        try (/* 本场景自动关闭的现货 Core 运行时 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 条数淘汰场景中最早应用并随后查询的命令 UUID
            UUID firstCommandId = UUID.randomUUID();
            state.apply(checkpoint(firstCommandId, 1, 1));
            for (/* 当前写入或应用的探针序号 */ int sequence = 2; sequence <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
                assertThat(state.apply(checkpoint(UUID.randomUUID(), sequence, 1)).status())
                        .isEqualTo(ResponseStatus.APPLIED);
            }

            // 本次查到的记录或只读查询响应，按场景断言元数据
            CoreResponse result = state.apply(commandResultQuery(firstCommandId));

            assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(result.resultCode()).isEqualTo(CoreResultCode.fromRejectionCode(
                    "RESULT_UNKNOWN_OUTSIDE_RETENTION"));
        }
    }

    /**
     * 构造条数尚未超限但响应总字节已超限的七十条结果，通过真实恢复助手要求拒绝该账本。
     */
    @Test
    void restoredLedgerRejectsResponseBytesOverTheBound() {
        // 为超字节恢复场景准备的有序结果集合
        Map<UUID, CommandResultLedger.StoredResult> results = new LinkedHashMap<>();
        // 本场景构造的大响应字节数组
        byte[] response = new byte[512_000];
        Arrays.fill(response, (byte) 7);
        for (/* 当前创建的超字节恢复结果下标 */ int index = 0; index < 70; index++) {
            results.put(UUID.randomUUID(), stored(ResponseStatus.APPLIED, CoreResultCode.NONE,
                    index + 1L, index + 10L, response, index + 1L));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TradingCoreRuntimeRestoreTestSupport.restore(
                        ProductLine.SPOT, 70, results, Map.of(),
                        com.surprising.aeron.model.TradingCoreState.empty(ProductLine.SPOT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result ledger");
    }

    /**
     * 反射调用实际存储入口写入三条十二 MiB 响应，要求按字节上限淘汰最早项，并将剩余结果恢复后核对业务哈希。
     */
    @Test
    void evictsOldestResultWhenResponseBytesExceedTheBound() throws Exception {
        try (/* 本场景自动关闭的现货 Core 运行时 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 通过反射取得的实际账本存储方法
            Method storeResult = CommandResultLedger.class.getDeclaredMethod(
                    "storeResult", UUID.class, CommandResultLedger.StoredResult.class);
            storeResult.setAccessible(true);
            // 本场景构造的大响应字节数组
            byte[] response = new byte[12 * 1024 * 1024];
            // 按场景最先存入的结果 UUID
            UUID first = UUID.randomUUID();
            // 按场景第二个结果 UUID
            UUID second = UUID.randomUUID();
            // 按字节上限测试中第三个结果 UUID
            UUID third = UUID.randomUUID();

            storeResult.invoke(state.resultLedger(), first,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, response, 0));
            storeResult.invoke(state.resultLedger(), second,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 2, 2, response, 0));
            storeResult.invoke(state.resultLedger(), third,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, response, 0));

            assertThat(state.commandResults()).doesNotContainKey(first);
            assertThat(state.commandResults()).containsKeys(second, third);
            try (/* 从相同结果、来源与业务状态恢复的自动关闭 Core */ TradingCoreRuntime restored = TradingCoreRuntimeRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(), state.commandResults(), state.lastSourceSequences(), state.tradingState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    /**
     * 手动构造待完成样式记录，再替换为更大成功结果；超字节上限时必须淘汰最旧的其他项、保护本次替换项并沿用原保留序号，恢复后哈希相同。这里没有启动真实撮合等待。
     */
    @Test
    void replacementEvictsOldestOtherResultsAndKeepsTheReplacedKeyBounded() throws Exception {
        try (/* 本场景自动关闭的现货 Core 运行时 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 通过反射取得的实际账本存储方法
            Method storeResult = CommandResultLedger.class.getDeclaredMethod(
                    "storeResult", UUID.class, CommandResultLedger.StoredResult.class);
            storeResult.setAccessible(true);
            // 替换扩容场景最早的其他结果 UUID，超限时应淘汰
            UUID oldest = UUID.randomUUID();
            // 手动构造为待完成样式、随后替换的受保护结果 UUID
            UUID pending = UUID.randomUUID();
            // 替换发生前最后插入的其他结果 UUID
            UUID newest = UUID.randomUUID();
            // 用于建立字节预算压力的十四 MiB 响应数组
            byte[] fourteenMiB = new byte[14 * 1024 * 1024];
            // 受保护项初始的一 MiB 响应数组
            byte[] oneMiB = new byte[1024 * 1024];
            // 受保护项替换后的四 MiB 响应数组
            byte[] fourMiB = new byte[4 * 1024 * 1024];

            storeResult.invoke(state.resultLedger(), oldest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, fourteenMiB, 0));
            storeResult.invoke(state.resultLedger(), pending,
                    stored(ResponseStatus.OK, CoreResultCode.MATCHING_PENDING, 2, 2, oneMiB, 0));
            // 替换前保存的受保护项保留序号，替换后必须沿用
            long pendingRetention = state.commandResults().get(pending).retentionSequence();
            storeResult.invoke(state.resultLedger(), newest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, fourteenMiB, 0));

            storeResult.invoke(state.resultLedger(), pending,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 4, 4, fourMiB, 0));

            assertThat(state.commandResults()).doesNotContainKey(oldest);
            assertThat(state.commandResults()).containsKeys(pending, newest);
            assertThat(state.commandResults().get(pending).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.commandResults().get(pending).responseData()).hasSize(fourMiB.length);
            assertThat(state.commandResults().get(pending).retentionSequence()).isEqualTo(pendingRetention);
            try (/* 从相同结果、来源与业务状态恢复的自动关闭 Core */ TradingCoreRuntime restored = TradingCoreRuntimeRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(), state.commandResults(), state.lastSourceSequences(), state.tradingState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    /**
     * 构造 ADJUST_BALANCE 类型的唯一探针；载荷借用余额调整编码产生可变指纹内容，不在此执行余额入账。
     * @param commandId 探针本身或结果查询目标的命令 UUID
     * @param sourceSequence 探针使用的原来源序号
     * @param delta 仅用于构造探针载荷差异的余额调整编码数值
     */
    private static CoreMessage checkpoint(UUID commandId, long sourceSequence, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000 + sourceSequence, sourceSequence), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", delta)));
    }

    /**
     * 构造具有稳定指纹的独立结果记录，响应默认复制；输入保留序号由调用场景指定，后续账本存入仍按自己的顺序分配。
     * @param status 要手动保存的原执行状态
     * @param resultCode 要手动保存的原业务结果码
     * @param appliedCommandCount 记录保存的原应用计数
     * @param resultIdentity 辅助生成指纹探针 UUID 的第二段身份值
     * @param response 独立记录默认复制的输入响应数组
     * @param retentionSequence 独立记录初始保留序号，直接存入账本时由账本重新决定
     */
    private static CommandResultLedger.StoredResult stored(
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long resultIdentity,
            byte[] response,
            long retentionSequence) {
        // 辅助指纹探针至少为一的来源序号
        long sourceSequence = Math.max(1, appliedCommandCount);
        // 仅为独立结果生成稳定指纹的 ADJUST_BALANCE 探针
        CoreMessage command = checkpoint(new UUID(appliedCommandCount, resultIdentity), sourceSequence, 1);
        return new CommandResultLedger.StoredResult(
                com.surprising.aeron.protocol.CommandFingerprint.of(command), status, resultCode,
                appliedCommandCount, response, retentionSequence);
    }

    /**
     * 构造查询指定命令 UUID 的只读 COMMAND_RESULT_QUERY，查询自身另有独立 UUID。
     * @param commandId 探针本身或结果查询目标的命令 UUID
     */
    private static CoreMessage commandResultQuery(UUID commandId) {
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.COMMAND_RESULT_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, 1001, 9_000, 99),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }
}
