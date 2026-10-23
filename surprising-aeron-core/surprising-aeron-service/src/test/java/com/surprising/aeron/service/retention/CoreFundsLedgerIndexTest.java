package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证活动资金索引 I/O 归真实 System Ledger，近期响应淘汰后仍按完整 UUID 与指纹去重；覆盖六产品线、账本邮箱满、写库失败和完成通知复用。本类不创建或恢复 Cluster 快照，冷身份导入也不等于恢复。
 */
class CoreFundsLedgerIndexTest {
    /** 固定复制业务毫秒时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 当前测试临时修改的资金索引目录 JVM 属性名。 */
    private static final String INDEX_DIRECTORY = "surprising.aeron.funds-index-directory";
    /** 本实例命令来源递增计数，在两个 Core 间也保持唯一。 */
    private long sequence;

    /**
     * 各产品与独立目标 Core 执行余额调整、划出、划入及完成转账，淘汰响应后用已过期原序号重试仍不改变计数、修订、哈希和余额；改内容或用户冲突，同低 UUID 位不同高位的新命令可执行。
     * @param product 本轮参数化源产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void allFundsKindsRemainIdempotentAfterResultEvictionAcrossProducts(ProductLine product) {
        // 与源独立的目标产品，现货转永续，其余转现货
        ProductLine destination = product == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        try (/* 源产品真实 Core */ var source = new TradingCoreRuntime(product); /* 目标产品真实 Core */ var target = new TradingCoreRuntime(destination)) {
            // 源端最初加两千的余额命令
            var deposit = command(source, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 2_000)));
            applied(source, deposit);
            // 源用户七到目标用户二十二的跨产品转账业务数据
            var transfer = new TransferFundsCommand(511, product, destination,
                    product.accountTypeCode(), destination.accountTypeCode(), "USDT", 250,
                    "ledger-index-511", "资金去重", 7, 22);
            // 源端划出命令
            var out = command(source, CoreMessageType.TRANSFER_OUT, 7, TradingCommandCodec.encodeTransferFunds(transfer));
            // 目标端划入命令
            var in = command(target, CoreMessageType.TRANSFER_IN, 22, TradingCommandCodec.encodeTransferFunds(transfer));
            // 源端完成转账命令
            var complete = command(source, CoreMessageType.COMPLETE_TRANSFER, 7,
                    TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(511)));
            applied(source, out); applied(target, in); applied(source, complete);
            assertOwnerCannotReadOrWriteIndex(source, deposit);
            assertOwnerCannotReadOrWriteIndex(target, in);
            evictOldResults(source, 7); evictOldResults(target, 22);
            for (var /* 当前要检查、重试或等待的真实命令 */ request : List.of(deposit, out, complete))
                assertThat(source.resultLedger().get(request.header().commandId())).isNull();
            assertThat(target.resultLedger().get(in.header().commandId())).isNull();

            // 重复前的源 Core 应用计数
            // 重复前的目标 Core 应用计数
            long sourceCount = source.appliedCommandCount(), targetCount = target.appliedCommandCount();
            // 重复前的源业务修订号
            // 重复前的目标业务修订号
            long sourceRevision = source.runtimeState().revision(), targetRevision = target.runtimeState().revision();
            // 重复前的源业务哈希
            // 重复前的目标业务哈希
            long sourceHash = source.stateHash(), targetHash = target.stateHash();
            // 这些原来源序号已经过期，但长期资金身份先判原 UUID 重复，不允许再次入账。
            for (var /* 当前要检查、重试或等待的真实命令 */ request : List.of(deposit, out, complete)) duplicate(source, request);
            duplicate(target, in);
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount);
            assertThat(target.appliedCommandCount()).isEqualTo(targetCount);
            assertThat(source.runtimeState().revision()).isEqualTo(sourceRevision);
            assertThat(target.runtimeState().revision()).isEqualTo(targetRevision);
            assertThat(source.stateHash()).isEqualTo(sourceHash);
            assertThat(target.stateHash()).isEqualTo(targetHash);

            // 沿用原 UUID 但改变金额的冲突请求
            var changedPayload = new CoreMessage(deposit.header(), TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 2_001)));
            assertThat(apply(source, changedPayload).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            // 逐步选择的另一账户 Lane 用户，构造同 UUID 身份冲突
            long anotherUser = 8;
            while (source.runtimeState().topology().accountLaneId(anotherUser)
                    == source.runtimeState().topology().accountLaneId(7)) anotherUser++;
            // 原余额命令的完整协议头
            var header = deposit.header();
            // 原 UUID 与载荷不变、但改用户的冲突请求
            var changedUser = new CoreMessage(CoreMessageHeader.command(header.messageType(), header.commandId(),
                    product, header.source(), header.sourceId(), header.sourceSequence(), anotherUser,
                    header.submittedAtEpochMillis(), header.correlationId()), deposit.payloadUnsafe());
            assertThat(apply(source, changedUser).resultCode()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount);
            assertThat(source.stateHash()).isEqualTo(sourceHash);

            // UUID 低位相同而高位不同仍是另一资金身份，磁盘索引不能把它们混为同一命令。
            // 低位相同而高位不同的另一完整 UUID
            var distinctId = new UUID(header.commandId().getMostSignificantBits() ^ 0x4000L,
                    header.commandId().getLeastSignificantBits());
            applied(source, command(source, CoreMessageType.ADJUST_BALANCE, distinctId, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            assertThat(source.appliedCommandCount()).isEqualTo(sourceCount + 1);
            assertThat(CoreTestCompletion.userState(source, 7).balances()).singleElement().satisfies(/* 本场景解码出的单资产余额，用来核对可用与冻结 */ b -> {
                assertThat(b.availableUnits()).isEqualTo(1_751); assertThat(b.lockedUnits()).isZero();
            });
            assertThat(CoreTestCompletion.userState(target, 22).balances()).singleElement()
                    .satisfies(/* 本场景解码出的单资产余额，用来核对可用与冻结 */ b -> assertThat(b.availableUnits()).isEqualTo(250));
            source.assertClusterCallbackComplete(); target.assertClusterCallbackComplete();
        }
    }

    /**
     * 用未就绪账本任务堵住真实邮箱并填满后续任务，余额命令先挂起且不增加应用数或结果记录；放行后只入账十七一次，再重试仍重复。
     */
    @Test @Timeout(15)
        void fullLedgerMailboxLeavesOwnerPendingAndAppliesFundsExactlyOnceAfterDrain() {
        try (/* 本场景自动关闭或故障后显式关闭的真实 Core */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            applied(core, command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
            // 堵住 Ledger 队首的未就绪 Entry
            var gate = new LedgerGate();
            assertThat(core.runtimeState().dispatchLedgerQuery(gate)).isTrue();
            // 填满后续邮箱的无资金变化 Entry
            SystemLedgerLane.Entry noop = /* 空账本任务附带的 Treasury 参数，当前不读取 */ ignored -> { };
            // 已经成功加入满邮箱的空任务数
            int offered = 0;
            while (core.runtimeState().dispatchLedgerQuery(noop)) {
                if (++offered > 100_000) throw new AssertionError("Ledger mailbox did not fill");
            }
            // 本次新命令前保存的应用计数
            long count = core.appliedCommandCount();
            // 当前要检查、重试或等待的真实命令
            var request = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 17)));
            try {
                assertThat(core.apply(request)).isNull();
                assertThat(core.directCommand.active()).isTrue();
                for (/* 邮箱仍满期间重复轮询的次数 */ int retry = 0; retry < 8; retry++) assertThat(core.directCommand.poll()).isNull();
                assertThat(core.appliedCommandCount()).isEqualTo(count);
                assertThat(core.resultLedger().get(request.header().commandId())).isNull();
            } finally { gate.released = true; }
            assertThat(drain(core).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(core.appliedCommandCount()).isEqualTo(count + 1);
            duplicate(core, request);
            assertThat(core.appliedCommandCount()).isEqualTo(count + 1);
            assertThat(CoreTestCompletion.userState(core, 7).balances()).singleElement()
                    .satisfies(/* 本场景解码出的单资产余额，用来核对可用与冻结 */ b -> assertThat(b.availableUnits()).isEqualTo(117));
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 只在冷索引导入原命令指纹，没有执行原加一百或恢复快照；激活后原命令被判重复且哈希不变，随后真正加一余额只有一，并禁止 Owner 直接读写索引。
     */
    @Test void coldFundsIndexHandsExistingIdentitiesToActiveLedger() {
        try (/* 本场景自动关闭或故障后显式关闭的真实 Core */ var core = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 冷阶段只导入指纹而没有真正执行的入账命令
            var original = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            // 只在冷阶段导入资金身份，验证它独立于近期响应缓存；这里没有执行快照恢复。
            // 冷索引保存的稳定原命令指纹
            var fingerprint = CommandFingerprint.of(original);
            core.terminalRetention().retainFundsCommand(original.header().commandId(), fingerprint);
            assertThat(core.terminalRetention().fundsCommand(original.header().commandId())).isEqualTo(fingerprint);
            core.activate();
            assertThat(core.resultLedger().get(original.header().commandId())).isNull();
            // 本次新命令前保存的应用计数
            long count = core.appliedCommandCount();
            // 冷身份激活后、重复执行前的业务哈希
            long hash = core.stateHash();
            duplicate(core, original);
            assertThat(core.appliedCommandCount()).isEqualTo(count);
            assertThat(core.stateHash()).isEqualTo(hash);
            assertOwnerCannotReadOrWriteIndex(core, original);
            applied(core, command(core, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            assertThat(CoreTestCompletion.userState(core, 7).balances()).singleElement()
                    .satisfies(/* 本场景解码出的单资产余额，用来核对可用与冻结 */ b -> assertThat(b.availableUnits()).isEqualTo(1));
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 临时将目录配置指向文件，制造活动 Ledger 首次开库失败；要求无最终结果及应用计数、Core 健康失败，关闭传播工作线程故障并清测试文件。不以此断言余额已经回滚。
     */
    @Test @Timeout(15)
        void indexWriteFailureStopsBeforeTerminalResponseAndDoesNotBecomeBusinessRejection() throws Exception {
        // 本次写库故障测试开始前的原目录属性
        String previous = System.getProperty(INDEX_DIRECTORY);
        // 创建故障文件的配置或系统临时根目录
        Path root = Path.of(previous == null ? System.getProperty("java.io.tmpdir") : previous);
        Files.createDirectories(root);
        // 故意作为资金索引根使用的普通文件，finally 删除
        Path invalidRoot = Files.createTempFile(root, "funds-index-invalid-root-", ".file");
        try {
            // 配置在构造时绑定，必须在创建 Core 前注入故障目录。
            System.setProperty(INDEX_DIRECTORY, invalidRoot.toString());
            var core = new TradingCoreRuntime(ProductLine.SPOT);
            try {
                core.activate();
                // 当前要检查、重试或等待的真实命令
                var request = command(core, CoreMessageType.ADJUST_BALANCE, 7,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
                assertThat(core.apply(request)).isNull();
                assertThatThrownBy(() -> drain(core)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("funds command index unavailable");
                assertThat(core.appliedCommandCount()).isZero();
                assertThat(core.directCommand.publicationComplete()).isTrue();
                assertThat(core.directCommand.active()).isTrue();
                assertThat(core.resultLedger().get(request.header().commandId())).isNull();
                assertThatThrownBy(core::assertHealthy).isInstanceOf(IllegalStateException.class);
            } finally {
                assertThatThrownBy(core::close).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Core workers stopped with a failure")
                        .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            }
        } finally {
            if (previous == null) System.clearProperty(INDEX_DIRECTORY);
            else System.setProperty(INDEX_DIRECTORY, previous);
            Files.deleteIfExists(invalidRoot);
        }
    }

    /**
     * 同一任务依次查询六十四个不存在身份，看到完成后立即 clear；整体十秒内仍必须收到六十四次通知且应用数为零，防止完成发布后任务复用清掉通知输入。
     */
    @Test @Timeout(15)
        void indexCompletionSignalsOwnerEvenWhenTheRequestIsImmediatelyReused() {
        try (/* 本场景自动关闭或故障后显式关闭的真实 Core */ var core = new TradingCoreRuntime(ProductLine.SPOT)) {
            core.activate();
            // 真实 Ledger 完成后通知 Owner 的原子次数
            var signals = new java.util.concurrent.atomic.AtomicInteger();
            core.runtimeState().ownerCompletionSignal(signals::incrementAndGet);
            // 立即 clear 并反复准备的实际资金索引任务
            var work = new TerminalStateRetention.FundsIndexWork();
            // 当前整个通知场景或直接控制等待的纳秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                for (/* 本轮不存在资金 UUID 的查找序号 */ int index = 0; index < 64; index++) {
                    work.prepareLookup(core.terminalRetention(), new UUID(998, index));
                    while (!work.poll(core)) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("funds lookup completion timed out");
                        Thread.onSpinWait();
                    }
                    assertThat(work.retained()).isNull();
                    // 完成位先于通知发布；任务立即复用也不能清掉工作线程已经捕获的通知运行时。
                    work.clear();
                }
                while (signals.get() != 64) {
                    core.assertHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("funds completion signal lost");
                    Thread.onSpinWait();
                }
                assertThat(core.appliedCommandCount()).isZero();
                core.assertHealthy();
            } finally { core.runtimeState().ownerCompletionSignal(null); }
        }
    }

    /**
     * 连续提交版本递增的费率策略，以非资金命令挤出近期响应，长期资金身份不受这段窗口影响。
     * @param core 当前执行真实资金流程的 Core
     * @param user 当前命令或查询用户 ID
     */
    private void evictOldResults(TradingCoreRuntime core, long user) {
        for (/* 挤出响应缓存时逐次增加的费率版本 */ int revision = 1; revision <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 1; revision++) {
            // 本轮用于填近期结果窗口的费率策略
            var policy = new UpsertFeePolicyCommand(33, revision, user, "", 0, 0, 1, true, TIME - 1, 0);
            applied(core, command(core, CoreMessageType.UPSERT_FEE_POLICY, user, TradingCommandCodec.encodeUpsertFeePolicy(policy)));
        }
    }

    /**
     * 活动后从 Owner 线程直接查或写资金索引都必须被拒绝，错误明确指出 SystemLedgerLane。
     * @param core 当前执行真实资金流程的 Core
     * @param request 需要执行、重试或检查索引归属的原命令
     */
    private void assertOwnerCannotReadOrWriteIndex(TradingCoreRuntime core, CoreMessage request) {
        assertThatThrownBy(() -> core.terminalRetention().fundsCommand(request.header().commandId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SystemLedgerLane");
        assertThatThrownBy(() -> core.terminalRetention().retainFundsCommand(request.header().commandId(),
                CommandFingerprint.of(request))).isInstanceOf(IllegalStateException.class).hasMessageContaining("SystemLedgerLane");
    }

    /**
     * 用真实异步完成助手执行并要求原命令终态 APPLIED。
     * @param core 当前执行真实资金流程的 Core
     * @param request 需要执行、重试或检查索引归属的原命令
     */
    private void applied(TradingCoreRuntime core, CoreMessage request) {
        assertThat(apply(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 要求本次为 DUPLICATE，原命令终态仍是 APPLIED 且码为 NONE。
     * @param core 当前执行真实资金流程的 Core
     * @param request 需要执行、重试或检查索引归属的原命令
     */
    private void duplicate(TradingCoreRuntime core, CoreMessage request) {
        // 异步完成助手或直接槽返回的最新结果
        var result = apply(core, request);
        assertThat(result.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(result.resultCode()).isEqualTo(CoreResultCode.NONE);
    }

    /**
     * 复用现有助手等待真实 Core 异步资金和控制工作完成。
     * @param core 当前执行真实资金流程的 Core
     * @param request 需要执行、重试或检查索引归属的原命令
     */
    private CoreResponse apply(TradingCoreRuntime core, CoreMessage request) {
        return CoreTestCompletion.applyAsynchronously(core, request);
    }

    /**
     * 按当前递增来源计数准备默认唯一 UUID，再交显式 UUID 入口构造命令。
     * @param core 当前执行真实资金流程的 Core
     * @param type 当前资金或费率命令类型
     * @param user 当前命令或查询用户 ID
     * @param payload 与该类型配对的编码载荷
     */
    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        return command(core, type, new UUID(997, sequence + 1), user, payload);
    }

    /**
     * 递增本实例来源序号，按指定 UUID、用户和当前 Core 产品构造命令。
     * @param core 当前执行真实资金流程的 Core
     * @param type 当前资金或费率命令类型
     * @param id 本次显式完整命令 UUID
     * @param user 当前命令或查询用户 ID
     * @param payload 与该类型配对的编码载荷
     */
    private CoreMessage command(TradingCoreRuntime core, CoreMessageType type, UUID id, long user, byte[] payload) {
        // 新命令递增后的来源序号
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, id, core.productLine(),
                CommandSource.OPERATIONS, 997, next, user, TIME, next), payload);
    }

    /**
     * 最多十秒轮询当前直接控制槽，返回最后一次结果；没有活动任务时可返回空，不在这里伪造终态。
     * @param core 当前执行真实资金流程的 Core
     */
    private CoreResponse drain(TradingCoreRuntime core) {
        // 异步完成助手或直接槽返回的最新结果
        CoreResponse result = null;
        // 当前整个通知场景或直接控制等待的纳秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (core.directCommand.active()) {
            result = core.directCommand.poll();
            if (System.nanoTime() >= deadline) throw new AssertionError("funds direct command timed out");
            Thread.onSpinWait();
        }
        return result;
    }

    /**
     * 阻塞真实账本 FIFO 的测试 Entry，用 volatile 就绪位控制后续任务何时可前进，不创建独立线程。
     */
    private static final class LedgerGate implements SystemLedgerLane.Entry {
        /** 跨线程可见的账本任务放行位。 */
        private volatile boolean released;
        /**
         * 只有测试显式放行才让该账本队首就绪。
         */
        @Override public boolean ready() { return released; }
        /**
         * 放行后不做资金变化，只用于解除账本队列阻塞。
         * @param ignored 账本 Entry 附带的 Treasury，测试门闩不读取
         */
        @Override public void apply(TreasuryRuntime ignored) { }
    }
}
