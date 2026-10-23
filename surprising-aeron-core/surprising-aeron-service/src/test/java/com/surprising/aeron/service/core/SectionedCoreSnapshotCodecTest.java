package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.CoreMatchingOrder;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.result.CommandResultLedger;
import com.surprising.aeron.service.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 分段快照格式与恢复边界测试：验证段顺序、大小上限、截断、CRC、组件元数据配对、幂等结果保存、字节往返和恢复激活时序。部分名称沿用旧版或更宽说法，下面按实际修改的段与断言说明；线程数量检查只覆盖指定名称，不能据此断言所有资源都已关闭。
 */
class SectionedCoreSnapshotCodecTest {

    /** 本测试按协议布局使用的十二字节封套长度。 */
    private static final int ENVELOPE_LENGTH = 12;
    /** 本测试按协议布局使用的八字节段头长度。 */
    private static final int SECTION_HEADER_LENGTH = 8;
    /** 元数据负载起始位置，即封套加第一段段头之后。 */
    private static final int HEADER_PAYLOAD_OFFSET = ENVELOPE_LENGTH + SECTION_HEADER_LENGTH;

    /**
     * 构造带固定指纹、响应字节和保留序号的幂等结果，保存恢复后比较这四项字段。原状态没有真实余额或订单；本方法原有代码未显式关闭两个运行时，不把它描述为资源清理验证。
     */
    @Test
    void snapshotRestoresFingerprintResponseBytesAndRetentionMetadata() {
        // 人工幂等结果对应的随机命令 UUID。
        UUID commandId = UUID.randomUUID();
        // 人工响应字节，余额辅助场景中则是直接命令的可空响应。
        byte[] response = {1, 3, 5, 7, 11};
        // 人工指定的三十二字节原命令指纹。
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(new byte[] {
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
        });
        // 保存指纹、响应和保留序号的人工结果。
        CommandResultLedger.StoredResult stored = new CommandResultLedger.StoredResult(
                fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, response, 4);
        // 准备配对并待编码的原始运行时。
        TradingCoreRuntime original = TradingCoreRuntimeRestoreTestSupport.restore(ProductLine.SPOT, 1,
                Map.of(commandId, stored), Map.of(),
                com.surprising.aeron.model.TradingCoreState.empty(ProductLine.SPOT));

        // 从快照恢复的候选运行时，具体关闭范围见当前测试。
        TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot());

        assertThat(restored.commandResults().get(commandId).responseData()).containsExactly(response);
        assertThat(restored.commandResults().get(commandId).fingerprint()).isEqualTo(fingerprint);
        assertThat(restored.commandResults().get(commandId).appliedCommandCount()).isEqualTo(1);
        assertThat(restored.commandResults().get(commandId).retentionSequence()).isEqualTo(4);
    }

    /**
     * 读取当前快照封套和每个段头，要求版本正确、段数与 Lane 数一致、负载长度在范围内，段号连续从一递增并恰好读尽字节；不比较不同 Map 插入顺序下的编码。
     */
    @Test
    void snapshotUsesDeterministicBoundedSectionOrder() {
        // 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
            byte[] snapshot = state.snapshot(41);
            // 当前读取或篡改协议字段的小端视图。
            ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);

            assertThat(Short.toUnsignedInt(buffer.getShort(Integer.BYTES)))
                    .isEqualTo(SectionedCoreSnapshotCodec.VERSION);
            // 依据当前实际账户 Lane 数计算的合法总段数。
            int expectedSectionCount = SectionedCoreSnapshotCodec.sectionCount(state.laneTopology().accountLaneCount());
            assertThat(buffer.getInt(8)).isEqualTo(expectedSectionCount);
            buffer.position(ENVELOPE_LENGTH);
            // 按快照段顺序读取到的段号数组。
            int[] sectionIds = new int[expectedSectionCount];
            for (/* 当前遍历的段号数组下标。 */ int index = 0; index < sectionIds.length; index++) {
                sectionIds[index] = buffer.getInt();
                // 当前段头声明的负载字节数。
                int sectionLength = buffer.getInt();
                assertThat(sectionLength).isBetween(1, SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES);
                buffer.position(Math.addExact(buffer.position(), sectionLength));
            }
            assertThat(sectionIds).containsExactly(
                    java.util.stream.IntStream.rangeClosed(1, expectedSectionCount).toArray());
            assertThat(buffer.hasRemaining()).isFalse();
        } finally {
            state.close();
        }
    }

    /**
     * 分别截短末尾、降低显式总预算、改小段数、声明超长或过短头部、翻转负载及追加零字节，要求对应结构、大小、CRC 或尾随垃圾错误。各变体独立由原快照克隆。
     */
    @Test
    void rejectsTruncationOversizeInvalidSectionMetadataChecksumAndTrailingGarbage() {
        // 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
            byte[] snapshot = state.snapshot(42);
            // 封套段数被改为八的克隆，用于触发段数检查。
            byte[] invalidCount = snapshot.clone();
            ByteBuffer.wrap(invalidCount).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 8);
            // 第一段负载长度声明超出单段上限的克隆。
            byte[] invalidLength = snapshot.clone();
            ByteBuffer.wrap(invalidLength).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ENVELOPE_LENGTH + Integer.BYTES, SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES + 1);
            // 第一段元数据负载长度被声明为一的克隆。
            byte[] shortHeader = snapshot.clone();
            ByteBuffer.wrap(shortHeader).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ENVELOPE_LENGTH + Integer.BYTES, 1);
            // 只翻转一个非页尾负载字节且保留旧 CRC 的克隆。
            byte[] checksumMismatch = snapshot.clone();
            checksumMismatch[checksumMismatch.length - SECTION_HEADER_LENGTH - Long.BYTES - 1] ^= 1;

            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                    Arrays.copyOf(snapshot, snapshot.length - 1), ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class);
            // 总预算比原完整快照少一字节的显式恢复缓冲。
            SectionedCoreSnapshotCodec.RecoveryBuffer bounded =
                    new SectionedCoreSnapshotCodec.RecoveryBuffer(snapshot.length - 1);
            assertThatThrownBy(() -> bounded.accept(new UnsafeBuffer(snapshot), 0, snapshot.length))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("maximum size");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(invalidCount, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section count");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(invalidLength, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section length");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(shortHeader, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section length");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(checksumMismatch, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                    Arrays.copyOf(snapshot, snapshot.length + 1), ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("trailing");
        } finally {
            state.close();
        }
    }

    /** 部署默认仍是一 GiB，默认恢复器实际使用该预算；协议硬界限独立约束数组长度。 */
    @Test
    void deploymentSnapshotLimitAndProtocolHardLimitRemainSeparate() throws Exception {
        var defaults = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource())
                .bindOrCreate("surprising.aeron", org.springframework.boot.context.properties.bind.Bindable.of(
                        com.surprising.aeron.config.CoreRuntimeConfiguration.class));
        assertThat(defaults.snapshot().maxBytes())
                .isGreaterThan(64 * 1024 * 1024)
                .isEqualTo(SectionedCoreSnapshotCodec.DEFAULT_MAX_SNAPSHOT_BYTES);
        String previous = System.getProperty("surprising.aeron.snapshot.max-bytes");
        System.clearProperty("surprising.aeron.snapshot.max-bytes");
        try {
            var recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
            var delegateField = recovery.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(recovery);
            var budgetField = delegate.getClass().getDeclaredField("maximumSnapshotBytes");
            budgetField.setAccessible(true);
            assertThat(budgetField.getInt(delegate)).isEqualTo(defaults.snapshot().maxBytes());
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.snapshot.max-bytes");
            else System.setProperty("surprising.aeron.snapshot.max-bytes", previous);
        }
        assertThat(SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES).isEqualTo(Integer.MAX_VALUE - 8);
        assertThat(SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES).isLessThan(
                SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES);
    }

    /** 剩余预算在 Matcher 编码前传入，不能等完整嵌套数组构造后才在总长度检查拒绝。 */
    @Test
    void writerPassesRemainingBudgetToMatcherBeforeEncoding() {
        try (var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            var image = new java.util.concurrent.atomic.AtomicReference<
                    com.surprising.aeron.service.snapshot.CoreSnapshotImage>();
            state.snapshots.snapshotEncoder(snapshotImage -> {
                image.set(snapshotImage);
                return java.util.concurrent.CompletableFuture.completedFuture(
                        com.surprising.aeron.service.snapshot.SectionedCoreSnapshotWriter.encode(
                                snapshotImage, state.configuration().snapshot().maxBytes()));
            });
            byte[] encoded = state.snapshot(19);
            ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
            int sectionCount = buffer.getInt(8);
            int encodedPrefixBytes = 0;
            buffer.position(ENVELOPE_LENGTH);
            for (int section = 0; section < 3; section++) {
                buffer.getInt();
                int length = buffer.getInt();
                encodedPrefixBytes += length;
                buffer.position(buffer.position() + length);
            }
            int budget = ENVELOPE_LENGTH + sectionCount * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                    + SectionedCoreSnapshotCodec.FOOTER_LENGTH + encodedPrefixBytes + 64;
            assertThatThrownBy(() -> com.surprising.aeron.service.snapshot.SectionedCoreSnapshotWriter.encode(
                    image.get(), budget)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("matcher snapshot exceeds maximum size");
        }
    }

    /**
     * 实际遍历前八个基础业务段，分别翻转首字节或截断到段末前一字节，要求恢复失败；没有逐项覆盖各 Lane 段和页尾的每种边界。
     */
    @Test
    void rejectsMutationAndTruncationAtEverySectionBoundary() {
        // 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        try {
            // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
            byte[] snapshot = state.snapshot(48);
            // 遍历前八段边界使用的小端读取视图。
            ByteBuffer layout = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
            layout.position(ENVELOPE_LENGTH);
            for (/* 本测试正在覆盖的一至八号基础业务段。 */ int expectedSectionId = 1; expectedSectionId <= 8; expectedSectionId++) {
                assertThat(layout.getInt()).isEqualTo(expectedSectionId);
                // 当前段头声明的负载字节数。
                int sectionLength = layout.getInt();
                // 当前业务段负载在完整字节中的绝对起始偏移。
                int payloadOffset = layout.position();

                // 为当前独立错误变体克隆并修改的完整快照字节。
                byte[] mutated = snapshot.clone();
                mutated[payloadOffset] ^= 1;
                assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(mutated, ProductLine.SPOT))
                        .as("mutation in section %s", expectedSectionId)
                        .isInstanceOf(ProtocolException.class);

                // 从当前段末尾前一字节截断的输入。
                byte[] truncated = Arrays.copyOf(snapshot, payloadOffset + sectionLength - 1);
                assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(truncated, ProductLine.SPOT))
                        .as("truncation in section %s", expectedSectionId)
                        .isInstanceOf(ProtocolException.class);
                layout.position(payloadOffset + sectionLength);
            }
        } finally {
            state.close();
        }
    }

    /**
     * 把当前快照版本改为二十二，要求完整恢复、清单读取和增量接收三个入口都在版本边界拒绝，未尝试兼容旧版。
     */
    @Test
    void onlyCurrentSectionedDecoderAcceptsRecoveryInput() {
        // 外层版本被改为二十二的快照字节。
        byte[] unsupported;
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            unsupported = state.snapshot(47);
        }
        ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putShort(Integer.BYTES, (short) 22);
        // 把不支持版本的字节包成 DirectBuffer，供增量入口接收。
        UnsafeBuffer encoded = new UnsafeBuffer(unsupported);

        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(unsupported, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 22");
        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.manifest(unsupported, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 22");
        // 验证版本拒绝的增量接收入口。
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        assertThatThrownBy(() -> recovery.accept(encoded, 0, unsupported.length))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 22");
    }

    /**
     * 在默认多 Lane 布局下修改第十段段号或 Lane 号，并重算 CRC；另一个变体只把封套段数减一而未删字节，要求结构或 Lane 校验拒绝，避免仅由原 CRC 拦截。
     */
    @Test
    void rejectsMissingDuplicateAndMisroutedAccountLaneSectionsAfterChecksumRecomputation() {
        // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
        byte[] snapshot;
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            snapshot = state.snapshot(49);
        }
        // 第十段号被覆盖成九的错误克隆。
        byte[] duplicate = mutateSectionId(snapshot, 10, 9);
        // 第十段内 Lane 号被覆盖成零的错误克隆。
        byte[] misrouted = mutateSectionPayloadInt(snapshot, 10, 0, 0);
        // 只减少封套总段数、实际字节仍保留的错误克隆。
        byte[] missing = snapshot.clone();
        // 用于减少封套声明段数的小端字节视图。
        ByteBuffer missingHeader = ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN);
        missingHeader.putInt(8, missingHeader.getInt(8) - 1);
        missing = rewriteOuterChecksum(missing);

        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(duplicate, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("section");
        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(misrouted, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("lane");
        // 供异常断言 lambda 固定捕获的错误段数输入。
        byte[] missingSection = missing;
        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(missingSection, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("section");
    }

    /**
     * 准备合成 Core 挂单与真实原生订单簿配对，编解码后使用同一个 MatcherSnapshot 输入再次编码，要求字节相同、状态哈希和订单相同；再独立捕获恢复运行时的新快照比较引擎哈希及拓扑。
     */
    @Test
    void byteExactRoundTripPreservesStateHashAndOpenOrders() {
        // 含合成 OPEN 买单的 Core 元数据状态。
        TradingCoreState tradingState = stateWithOpenBid();
        // 真实原生订单簿捕获并与该测试 Core 元数据配对的快照。
        MatcherSnapshot matcherSnapshot;
        try (/* 本测试创建的真实同步订单簿适配器，资源退出时关闭。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(com.surprising.aeron.service.core.DirectMatcherTestInputs.placeDirect(adapter, 7, bid()).accepted()).isTrue();
            matcherSnapshot = adapter.snapshotAsync(
                    43, 1, tradingState.businessStateHash(), tradingState,
                    tradingState.orders().values()).join();
        }
        // 只执行验证命令以取得幂等结果与消费水位的辅助运行时。
        TradingCoreRuntime source = new TradingCoreRuntime(ProductLine.SPOT);
        // 推进已应用 Core 数量的一条 CONTINUE_RISK_SCAN 命令。
        CoreMessage increment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.CONTINUE_RISK_SCAN,
                UUID.fromString("00000000-0000-0000-0000-000000000043"), ProductLine.SPOT,
                CommandSource.GATEWAY, 43, 1, 7, 1_000, 43), com.surprising.aeron.protocol.TradingCommandCodec.encodeContinueRiskScan(new com.surprising.aeron.protocol.ContinueRiskScanCommand(1)));
        assertThat(source.apply(increment).status()).isEqualTo(ResponseStatus.APPLIED);
        // 准备配对并待编码的原始运行时。
        TradingCoreRuntime original = TradingCoreRuntimeRestoreTestSupport.restore(ProductLine.SPOT, 1,
                source.commandResults(), source.lastSourceSequences(), tradingState, matcherSnapshot);
        // 从快照恢复的候选运行时，具体关闭范围见当前测试。
        TradingCoreRuntime restored = null;
        try {
            // 对原运行时与指定 MatcherSnapshot 第一次编码的字节。
            byte[] first = SectionedCoreSnapshotCodec.encode(original, matcherSnapshot).toByteArray();
            restored = SectionedCoreSnapshotCodec.decode(first, ProductLine.SPOT);
            // 对恢复运行时使用同一个 MatcherSnapshot 输入再次编码的字节。
            byte[] second = SectionedCoreSnapshotCodec.encode(restored, matcherSnapshot).toByteArray();

            assertThat(second).containsExactly(first);
            assertThat(restored.stateHash()).isEqualTo(original.stateHash());
            assertThat(restored.tradingState().orders()).containsExactlyEntriesOf(original.tradingState().orders());
            // 第一次编码字节解析得到的原清单。
            CoreSnapshotManifest originalManifest = SectionedCoreSnapshotCodec.manifest(first, ProductLine.SPOT);
            // 恢复运行时独立捕获新编号快照后解析出的清单。
            CoreSnapshotManifest restoredMatcherManifest = SectionedCoreSnapshotCodec.manifest(
                    restored.snapshot(44), ProductLine.SPOT);
            assertThat(restoredMatcherManifest.engineStateHash()).isEqualTo(originalManifest.engineStateHash());
            assertThat(restoredMatcherManifest.topology()).isEqualTo(originalManifest.topology());
        } finally {
            if (restored != null) restored.close();
            original.close();
            source.close();
        }
    }

    /**
     * 实际把第五段交易状态负载首个 int 改成最大值并重算外层 CRC，要求协议失败且指定线程名计数不增加；这里并未篡改第四段 Matcher 负载。
     */
    @Test
    void corruptMatcherSectionStartsNoRecoveryConsumer() {
        // 正常余额调整场景生成的控制快照输入。
        byte[] control = populatedSnapshot(88);
        // 恢复操作前符合特定名称过滤条件的活线程数量。
        long threadsBefore = recoveryConsumerThreadCount();
        // 第五段交易状态首个 int 被覆盖后的 CRC 正确输入。
        byte[] corrupted = mutateSectionPayloadInt(control, 5, 0, Integer.MAX_VALUE);

        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(corrupted, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class);
        assertThat(recoveryConsumerThreadCount()).isEqualTo(threadsBefore);
    }

    /**
     * 通过恢复激活前钩子记录受检查组件的被动标记，要求钩子时 allPassive，decode 返回后 allActivated；不以此证明所有系统线程或资源的完整生命周期。
     */
    @Test
    void accountLanesRestoreWhileEveryConsumerIsPassiveThenOneActivationStartsAll() {
        // 正常余额调整场景生成的控制快照输入。
        byte[] control = populatedSnapshot(89);
        // 保存激活前钩子观察到的组件状态，用于和返回后的状态比较。
        AtomicReference<CoreFaults.ActivationState> beforeActivation = new AtomicReference<>();
        CoreFaults.beforeActivation(/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ state ->
                beforeActivation.set(CoreFaults.activationState(state)));
        try (/* 从快照恢复的候选运行时，具体关闭范围见当前测试。 */ TradingCoreRuntime restored = SectionedCoreSnapshotCodec.decode(control, ProductLine.SPOT)) {
            assertThat(beforeActivation.get()).isNotNull();
            assertThat(beforeActivation.get().allPassive()).isTrue();
            assertThat(CoreFaults.activationState(restored).allActivated()).isTrue();
        } finally {
            CoreFaults.beforeActivation(null);
        }
    }

    /**
     * 在激活前钩子主动抛异常，要求恢复包装协议错误、候选受检查标记仍被动且指定线程名计数不增。没有逐一断言每个资源的 close，也不能只凭本测试名宣称无任何资源泄漏。
     */
    @Test
    void passiveCandidateConstructionFailureClosesEveryResourceWithoutStartingConsumers() {
        // 正常余额调整场景生成的控制快照输入。
        byte[] control = populatedSnapshot(90);
        // 保存故障注入钩子接到的尚未激活运行时引用。
        AtomicReference<TradingCoreRuntime> candidate = new AtomicReference<>();
        // 恢复操作前符合特定名称过滤条件的活线程数量。
        long threadsBefore = recoveryConsumerThreadCount();
        CoreFaults.beforeActivation(/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ state -> {
            candidate.set(state);
            throw new IllegalStateException("injected passive candidate failure");
        });
        try {
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(control, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("injected passive candidate failure");
            assertThat(candidate.get()).isNotNull();
            assertThat(CoreFaults.activationState(candidate.get()).allPassive()).isTrue();
            assertThat(recoveryConsumerThreadCount()).isEqualTo(threadsBefore);
        } finally {
            CoreFaults.beforeActivation(null);
        }
    }

    /**
     * 注入固定预建 MatcherSnapshot，推进一条验证命令后轮询指定 Cluster 时间和位置的快照，核对清单版本、编号、水位、来源摘要、业务哈希与拓扑；捕获桩忽略传入参数，本方法没有单独检查 outbox 元数据。
     */
    @Test
    void pairedManifestExposesFencePositionSequencesHashesAndOutboxMetadata() {
        // 真实原生订单簿捕获并与该测试 Core 元数据配对的快照。
        MatcherSnapshot matcherSnapshot;
        try (/* 本测试创建的真实同步订单簿适配器，资源退出时关闭。 */ DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            // 用于预建固定 MatcherSnapshot 的现货空状态。
            TradingCoreState empty = TradingCoreState.empty(ProductLine.SPOT);
            matcherSnapshot = adapter.snapshotAsync(
                    73, 1, empty.businessStateHash(), empty, List.of()).join();
        }
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT,
                (/* 捕获接口传入的快照编号；本固定结果桩未读取它。 */ snapshotId, /* 捕获接口传入的 Core 水位；本固定结果桩未读取它。 */ coreSequence, /* 捕获接口传入的业务哈希；本固定结果桩未读取它。 */ businessStateHash, /* 捕获接口传入的冻结状态；本固定结果桩未读取它。 */ tradingState, /* 捕获接口传入的活动订单；本固定结果桩未读取它。 */ activeOrders) ->
                        java.util.concurrent.CompletableFuture.completedFuture(matcherSnapshot))) {
            // 推进已应用 Core 数量的一条 CONTINUE_RISK_SCAN 命令。
            CoreMessage increment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.CONTINUE_RISK_SCAN,
                    UUID.fromString("00000000-0000-0000-0000-000000000073"), ProductLine.SPOT,
                    CommandSource.GATEWAY, 73, 1, 7, 1_000, 73), com.surprising.aeron.protocol.TradingCommandCodec.encodeContinueRiskScan(new com.surprising.aeron.protocol.ContinueRiskScanCommand(1)));
            assertThat(state.apply(increment).status()).isEqualTo(ResponseStatus.APPLIED);
            state.snapshots.beginSnapshot(73, Long.MAX_VALUE);
            // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
            byte[] snapshot = null;
            // 本次清单快照轮询的五秒 nanoTime 截止点。
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (snapshot == null && System.nanoTime() < deadline) {
                snapshot = state.snapshots.pollSnapshot(1_234, 5_678, System.nanoTime());
                if (snapshot == null) Thread.onSpinWait();
            }
            assertThat(snapshot).isNotNull();

            // 按当前产品线完整解析得到的公开快照清单。
            CoreSnapshotManifest manifest = TradingCoreRuntime.inspectSnapshot(ProductLine.SPOT, snapshot);

            assertThat(manifest.schemaVersion()).isEqualTo(SectionedCoreSnapshotCodec.VERSION);
            assertThat(manifest.snapshotId()).isEqualTo(73);
            assertThat(manifest.coreSequence()).isEqualTo(state.appliedCommandCount());
            assertThat(manifest.clusterTimestamp()).isEqualTo(1_234);
            assertThat(manifest.clusterPosition()).isEqualTo(5_678);
            assertThat(manifest.sourceSequenceDigest()).isNotZero();
            assertThat(manifest.matcherSequence()).isNotNegative();
            assertThat(manifest.businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            assertThat(manifest.topology()).isEqualTo(state.laneTopology());
        }
    }

    /**
     * 构造九类头部配对错误并各自重算外层 CRC，要求失败消息分别指向产品线、拓扑、应用/快照/Core/投影水位、业务/资金哈希及来源摘要；不代表头部所有字段的每种错误组合。
     */
    @Test
    void rejectsEveryPairedManifestMismatchAfterChecksumRecomputation() {
        // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
        byte[] snapshot;
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.CONTINUE_RISK_SCAN,
                    UUID.fromString("00000000-0000-0000-0000-000000000074"), ProductLine.SPOT,
                    CommandSource.GATEWAY, 74, 1, 7, 1_000, 74), com.surprising.aeron.protocol.TradingCommandCodec.encodeContinueRiskScan(new com.surprising.aeron.protocol.ContinueRiskScanCommand(1)))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            snapshot = state.snapshot(74);
        }

        // 八类预期错误名称到各自 CRC 已重算输入的有序表。
        Map<String, byte[]> mismatches = new LinkedHashMap<>();
        mismatches.put("product line", mutateHeaderProductLine(snapshot));
        mismatches.put("applied sequence", mutateHeaderLong(snapshot, 1));
        mismatches.put("snapshot id", mutateHeaderLong(snapshot, 9));
        mismatches.put("core sequence", mutateHeaderLong(snapshot, 17));
        mismatches.put("projection sequence", mutateHeaderLong(snapshot, 25));
        mismatches.put("business state hash", mutateHeaderLong(snapshot, 65));
        mismatches.put("funds hash", mutateHeaderLong(snapshot, 73));
        mismatches.put("source sequence digest", mutateHeaderLong(snapshot, 81));

        mismatches.forEach((/* 本变体预期出现于异常中的字段名称。 */ field, /* 为当前独立错误变体克隆并修改的完整快照字节。 */ mutated) -> {
            // 解码本变体捕获到的异常，要求是协议错误并含指定字段名。
            Throwable failure = catchThrowable(() -> SectionedCoreSnapshotCodec.decode(mutated, ProductLine.SPOT));
            assertThat(failure).as(field).isInstanceOf(ProtocolException.class);
            assertThat(failure).as(field).hasMessageContaining(field);
        });
    }

    /**
     * 使用当前 v29 快照，改产品线为期权并把第五段交易状态清零，再重算 CRC；要求先报产品线不匹配，说明该检查早于交易状态解码，方法名中的 V9 不改变当前格式。
     */
    @Test
    void rejectsWrongProductLineBeforeDecodingOtherV9Sections() {
        // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
        byte[] snapshot;
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            snapshot = state.snapshot(75);
        }

        // 产品线已改为期权的快照克隆，随后破坏第五段。
        byte[] mismatched = mutateHeaderProductLine(snapshot);
        // 逐段定位第五段的小端读取视图。
        ByteBuffer sections = ByteBuffer.wrap(mismatched).order(ByteOrder.LITTLE_ENDIAN);
        sections.position(ENVELOPE_LENGTH);
        for (/* 当前正在遍历的原协议段号。 */ int sectionId = 1; sectionId <= 5; sectionId++) {
            assertThat(sections.getInt()).isEqualTo(sectionId);
            // 当前段头声明的负载字节数。
            int sectionLength = sections.getInt();
            if (sectionId == 5) {
                Arrays.fill(mismatched, sections.position(), sections.position() + sectionLength, (byte) 0);
                break;
            }
            sections.position(sections.position() + sectionLength);
        }
        // 第五段清零后重新写入外层 CRC 的错误输入。
        byte[] rejected = rewriteOuterChecksum(mismatched);

        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(rejected, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("snapshot product line mismatch: OPTION");
    }

    /**
     * 只篡改头部 Cluster 时间或日志位置且不重算 CRC，要求先在外层校验处拒绝；不是验证重算 CRC 后这两个合法非负字段的业务配对。
     */
    @Test
    void checksumProtectsClusterTimestampAndPositionManifestFields() {
        // 当前正常捕获或轮询得到的完整字节，轮询未完成时为空。
        byte[] snapshot;
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            snapshot = state.snapshot(76);
        }

        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                mutateHeaderLongWithoutChecksum(snapshot, 49), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
        assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                mutateHeaderLongWithoutChecksum(snapshot, 57), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
    }

    /**
     * 合成一个用户、一个品种和一笔 OPEN 买单的 Core 元数据；用户资产为空，不是通过真实下单冻结得到的资金状态，供原生订单逐字段配对使用。
     */
    private static TradingCoreState stateWithOpenBid() {
        // 合成的一号 OPEN 买单元数据，不包含真实账户冻结。
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, "1",
                CoreOrderSide.BUY, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order),
                Map.of("1", CoreInstrument.from(ProductLine.SPOT,
                        new RegisterInstrumentCommand("1", ContractType.SPOT.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1,
                                100_000, 50_000, 0, 0, 0, -1, 0))),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    /**
     * 执行一次二十五 USDT 余额调整，最多五秒轮询直接命令到 APPLIED，再保存指定编号快照并关闭原运行时。
     * @param sequence 测试余额命令生成身份时使用的编号，同时作为快照号
     */
    private static byte[] populatedSnapshot(long sequence) {
        // 生成有效非空资金状态的一次二十五 USDT 余额调整命令。
        CoreMessage adjustment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(0, sequence), ProductLine.SPOT, CommandSource.GATEWAY, sequence, 1, 7,
                1_000, sequence),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 25)));
        try (/* 当前创建的运行时或激活前钩子接收到的候选，具体用途由当前语句决定。 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 人工响应字节，余额辅助场景中则是直接命令的可空响应。
            var response = state.apply(adjustment);
            // 余额调整直接命令等待的五秒截止点。
            long directDeadline = System.nanoTime() + 5_000_000_000L;
            while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
                response = state.directCommand.poll();
                if (response == null) Thread.onSpinWait();
            }
            if (response == null && state.directCommand.active()) {
                throw new AssertionError("Direct command timed out: " + adjustment.header().messageType());
            }
            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            return state.snapshot(sequence);
        }
    }

    /**
     * 创建与合成 Core 挂单相同的一号品种、一号订单、价格一百、数量二的 GTC 买单撮合输入。
     */
    private static CoreMatchingOrder bid() {
        return new CoreMatchingOrder(1, "1", CoreOrderSide.BUY,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, 100, 2);
    }

    /**
     * 克隆字节，把元数据产品线改为 OPTION 并重算外层 CRC，以便越过 CRC 检查测试配对。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     */
    private static byte[] mutateHeaderProductLine(byte[] snapshot) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        mutated[HEADER_PAYLOAD_OFFSET] = (byte) ProductLineWireCode.encode(ProductLine.OPTION);
        return rewriteOuterChecksum(mutated);
    }

    /**
     * 克隆字节，把头部给定相对偏移的 int 加一，再重算外层 CRC；不检查该偏移是否对应某个特定协议字段。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     * @param fieldOffset 相对第一段元数据负载起点的字段字节偏移
     */
    private static byte[] mutateHeaderInt(byte[] snapshot, int fieldOffset) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        // 当前读取或篡改协议字段的小端视图。
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        // 第一段负载起点加相对字段偏移得到的绝对位置。
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putInt(offset, buffer.getInt(offset) + 1);
        return rewriteOuterChecksum(mutated);
    }

    /**
     * 克隆字节，把头部给定相对偏移的 long 加一并重算外层 CRC，用于字段配对错误测试。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     * @param fieldOffset 相对第一段元数据负载起点的字段字节偏移
     */
    private static byte[] mutateHeaderLong(byte[] snapshot, int fieldOffset) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        // 当前读取或篡改协议字段的小端视图。
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        // 第一段负载起点加相对字段偏移得到的绝对位置。
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putLong(offset, buffer.getLong(offset) + 1);
        return rewriteOuterChecksum(mutated);
    }

    /**
     * 克隆字节并将头部指定 long 加一，保留旧 CRC，专门测试外层篡改检测。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     * @param fieldOffset 相对第一段元数据负载起点的字段字节偏移
     */
    private static byte[] mutateHeaderLongWithoutChecksum(byte[] snapshot, int fieldOffset) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        // 当前读取或篡改协议字段的小端视图。
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        // 第一段负载起点加相对字段偏移得到的绝对位置。
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putLong(offset, buffer.getLong(offset) + 1);
        return mutated;
    }

    /**
     * 按原顺序段头遍历到目标段，覆盖其段号并重算 CRC；找不到目标就抛断言错误，不改变负载字节。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     * @param targetSectionId 需要寻找的一基原协议段号
     * @param replacementSectionId 要覆盖写入的段号
     */
    private static byte[] mutateSectionId(byte[] snapshot, int targetSectionId, int replacementSectionId) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        // 当前读取或篡改协议字段的小端视图。
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            // 当前段头中段号字段的绝对位置。
            int idOffset = buffer.position();
            // 当前正在遍历的原协议段号。
            int sectionId = buffer.getInt();
            // 当前遍历段的负载字节长度，用于跳到下一段。
            int length = buffer.getInt();
            if (sectionId == targetSectionId) {
                buffer.putInt(idOffset, replacementSectionId);
                return rewriteOuterChecksum(mutated);
            }
            buffer.position(Math.addExact(buffer.position(), length));
        }
        throw new AssertionError("section not found: " + targetSectionId);
    }

    /**
     * 遍历目标段并检查负载内 int 偏移有效，覆盖指定值后重算 CRC；不存在段或偏移越界时明确抛断言错误。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     * @param targetSectionId 需要寻找的一基原协议段号
     * @param payloadOffset 相对目标段负载起点的 int 字段字节偏移
     * @param value 需要写入目标负载 int 字段的测试值
     */
    private static byte[] mutateSectionPayloadInt(byte[] snapshot, int targetSectionId,
                                                  int payloadOffset, int value) {
        // 为当前独立错误变体克隆并修改的完整快照字节。
        byte[] mutated = snapshot.clone();
        // 当前读取或篡改协议字段的小端视图。
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            // 当前正在遍历的原协议段号。
            int sectionId = buffer.getInt();
            // 当前遍历段的负载字节长度，用于跳到下一段。
            int length = buffer.getInt();
            // 当前段负载的绝对起始位置。
            int start = buffer.position();
            if (sectionId == targetSectionId) {
                if (payloadOffset < 0 || payloadOffset + Integer.BYTES > length) {
                    throw new AssertionError("invalid section payload offset");
                }
                buffer.putInt(start + payloadOffset, value);
                return rewriteOuterChecksum(mutated);
            }
            buffer.position(Math.addExact(start, length));
        }
        throw new AssertionError("section not found: " + targetSectionId);
    }

    /**
     * 统计仍存活且名称符合 core-commit-projector、matching-engine、risk-engine 或 ExchangeCore 条件的线程；没有匹配 core-matcher 等其他命名，因此不是全部恢复线程计数。
     */
    private static long recoveryConsumerThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(/* 当前活线程名称，只按列出的名称条件参与计数。 */ name -> name.startsWith("core-commit-projector-")
                        || name.contains("matching-engine") || name.contains("risk-engine")
                        || name.contains("ExchangeCore"))
                .count();
    }

    /**
     * 对页尾段头之前的全部字节重算 CRC32C，覆盖最后八字节并返回同一个传入数组；不修复段结构、业务哈希或内部负载自己的校验值。
     * @param snapshot 本次待克隆篡改或原位重算 CRC 的完整快照字节，具体所有权见方法说明
     */
    private static byte[] rewriteOuterChecksum(byte[] snapshot) {
        // 重算外层 CRC32C 的临时对象。
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0, snapshot.length - SECTION_HEADER_LENGTH - Long.BYTES);
        ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(snapshot.length - Long.BYTES, checksum.getValue());
        return snapshot;
    }

}
