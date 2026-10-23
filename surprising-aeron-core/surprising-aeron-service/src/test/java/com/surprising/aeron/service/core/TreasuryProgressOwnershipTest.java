package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 财库进度的跨线程可读视图、真实账本写入、回滚前值及订单批量修改限制测试；进度槽与实际资金所有权分开核对。
 */
class TreasuryProgressOwnershipTest {
    /** 所有进度槽共同引用的固定U本位永续标的1配置。 */
    private static final CoreInstrument INSTRUMENT = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
            new RegisterInstrumentCommand(
                    "1", "BTC", "USDT", "USDT", 1, 1, 1,
                    new CoreInstrumentRiskLimits(
                            100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                            java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                    0, 0, 0, -1, 0));
    /** 各进度记录共同引用的固定命令UUID，便于核对发布后对象身份。 */
    private static final UUID COMMAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");

    /**
     * 交给真实账本后外部可读原进度及完成号，资金读取和修改进度仍拒绝；账本设置完成号后进度清空，原不可变记录内容保持。
     */
    @Test
    void admissionReadsOriginalProgressAfterLedgerHandoffButCannotAccessMoneyOrWriteProgress() throws Exception {
        // 本例唯一财库，冷阶段初始化后可交给真实账本线程
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 修改前资金费进度记录，用于核对只读发布或回滚身份
        var funding = funding(1);
        // 修改前生命周期进度记录，用于检查域间隔离和回滚
        var lifecycle = lifecycle(2);
        ledger.setFundingProgress(0, funding);
        ledger.setLifecycleProgress(0, lifecycle);
        ledger.clearChangedKeys();
        // 实际账本线程发布的故障引用
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (/* 本例真实账本工作对象，资源退出时关闭 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThat(ledger.fundingProgress(0)).isSameAs(funding);
            assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
            assertThat(ledger.incompleteFundingCount()).isEqualTo(1);
            assertThatThrownBy(() -> ledger.fee(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.insurance(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setFundingSettlement(0, 3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setFundingProgress(0, funding(3))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setLifecycleSettlement(0, 4)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setLifecycleProgress(0, lifecycle(4))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::fundingProgresses).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::rollbackChangedValues).isInstanceOf(IllegalStateException.class);
            // 确认账本全部更新已经执行完的门闩
            CountDownLatch completed = new CountDownLatch(1);
            assertThat(lane.offer(/* 回调在账本线程实际获得的财库拥有者引用 */ actual -> {
                actual.setFundingSettlement(0, 3);
                actual.setLifecycleSettlement(0, 4);
                actual.clearChangedKeys();
                completed.countDown();
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
            assertThat(ledger.fundingSettlement(0)).isEqualTo(3);
            assertThat(ledger.lifecycleSettlement(0)).isEqualTo(4);
            assertThat(ledger.fundingProgress(0)).isNull();
            assertThat(ledger.lifecycleProgress(0)).isNull();
            assertThat(ledger.incompleteFundingCount()).isZero();
        }
        assertThat(failure.get()).isNull();
        assertThat(funding.settlementId()).isEqualTo(1);
        assertThat(lifecycle.settlementId()).isEqualTo(2);
    }

    /**
     * 独立读取线程与账本写入并发，扩展64个进度槽并替换0号进度，核对发布记录引用及完成号单调。各槽共用固定标的配置，本例不是64个真实标的业务测试。
     */
    @Test
    void concurrentAdmissionReadsRemainSafeAsLedgerAddsSymbolsAndReplacesProgress() throws Exception {
        // 本例唯一财库，冷阶段初始化后可交给真实账本线程
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 实际账本线程发布的故障引用
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 独立读取线程遇到的原故障引用
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        // finally通知读取线程退出的原子标记
        AtomicBoolean finished = new AtomicBoolean();
        // 确认读取线程已开始运行的门闩
        // 账本已发布第一条进度的门闩，异常清理时也释放以便退出
        CountDownLatch readerStarted = new CountDownLatch(1), firstPublished = new CountDownLatch(1);
        // 确认读取线程已见第一条进度的门闩，故障时同样释放
        // 确认账本全部更新已经执行完的门闩
        CountDownLatch firstRead = new CountDownLatch(1), completed = new CountDownLatch(1);
        // 并发读取发布进度及完成号的独立线程
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            try {
                if (!firstPublished.await(3, TimeUnit.SECONDS)) throw new AssertionError("progress publication timeout");
                assertThat(ledger.fundingProgress(0)).isNotNull();
                firstRead.countDown();
                // 读取线程上次见到的0号完成结算号，下一次不得变小
                long lastSettlement = 0;
                do {
                    // 本次读到的0号已完成结算号
                    long current = ledger.fundingSettlement(0);
                    assertThat(current).isGreaterThanOrEqualTo(lastSettlement);
                    lastSettlement = current;
                    for (/* 正在读取或写入的进度表槽号，并非不同真实配置对象 */ int symbol = 0; symbol < 64; symbol++) {
                        // 当前发布的可空资金费进度，存在时核对固定对象及正结算号
                        var value = ledger.fundingProgress(symbol);
                        if (value != null) {
                            assertThat(value.instrument()).isSameAs(INSTRUMENT);
                            assertThat(value.settlementId()).isPositive();
                        }
                        ledger.lifecycleProgress(symbol);
                        ledger.lifecycleSettlement(symbol);
                    }
                } while (!finished.get());
            } catch (/* 读取线程捕获的Throwable，保存并释放首次读取门闩 */ Throwable problem) {
                readerFailure.set(problem);
                firstRead.countDown();
            }
        }, "admission-progress-reader");
        try (/* 本例真实账本工作对象，资源退出时关闭 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            reader.start();
            assertThat(readerStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(lane.offer(/* 回调在账本线程实际获得的财库拥有者引用 */ actual -> {
                actual.setFundingProgress(0, funding(1));
                firstPublished.countDown();
                try {
                    if (!firstRead.await(3, TimeUnit.SECONDS)) throw new AssertionError("admission reader timeout");
                } catch (/* 账本等待首次读取被中断，恢复中断标记后转断言失败 */ InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                for (/* 正在读取或写入的进度表槽号，并非不同真实配置对象 */ int symbol = 1; symbol < 64; symbol++) {
                    actual.setFundingProgress(symbol, funding(symbol + 1));
                    actual.setLifecycleProgress(symbol, lifecycle(symbol + 1));
                    actual.setFundingSettlement(0, symbol);
                    actual.setFundingProgress(0, funding(symbol + 1));
                }
                assertThat(actual.fundingProgresses().size()).isEqualTo(64);
                assertThat(actual.lifecycleProgresses().size()).isEqualTo(63);
                actual.clearChangedKeys();
                completed.countDown();
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
            assertThat(ledger.fundingSettlement(0)).isEqualTo(63);
            assertThat(ledger.fundingProgress(63).settlementId()).isEqualTo(64);
            assertThat(ledger.incompleteFundingCount()).isEqualTo(64);
        } finally {
            finished.set(true);
            firstPublished.countDown();
            reader.join(3_000L);
        }
        assertThat(reader.isAlive()).isFalse();
        assertThat(readerFailure.get()).isNull();
        assertThat(failure.get()).isNull();
    }

    /**
     * 资金费和生命周期交错修改后回滚，要求恢复首次前值、完成号及原对象身份，删除新32号槽，并清变化标记。
     */
    @Test
    void rollbackRestoresFirstProgressAndCompletedIdsForBothDomainsAndRemovesNewSymbols() {
        // 本例唯一财库，冷阶段初始化后可交给真实账本线程
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 修改前资金费进度记录，用于核对只读发布或回滚身份
        var funding = funding(3);
        // 修改前生命周期进度记录，用于检查域间隔离和回滚
        var lifecycle = lifecycle(4);
        ledger.setFundingSettlement(0, 1);
        ledger.setLifecycleSettlement(0, 2);
        ledger.setFundingProgress(0, funding);
        ledger.setLifecycleProgress(0, lifecycle);
        ledger.clearChangedKeys();

        ledger.setFundingSettlement(0, 5);
        assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
        ledger.setFundingProgress(0, funding(6));
        ledger.setLifecycleSettlement(0, 7);
        assertThat(ledger.fundingProgress(0).settlementId()).isEqualTo(6);
        ledger.setLifecycleProgress(0, lifecycle(8));
        ledger.setFundingProgress(32, funding(9));
        ledger.setLifecycleSettlement(32, 10);
        assertThat(ledger.incompleteFundingCount()).isEqualTo(2);
        ledger.rollbackChangedValues();

        assertThat(ledger.fundingSettlement(0)).isEqualTo(1);
        assertThat(ledger.lifecycleSettlement(0)).isEqualTo(2);
        assertThat(ledger.fundingProgress(0)).isSameAs(funding);
        assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
        assertThat(ledger.fundingProgress(32)).isNull();
        assertThat(ledger.lifecycleSettlement(32)).isZero();
        assertThat(ledger.incompleteFundingCount()).isEqualTo(1);
        assertThat(ledger.fundingSettlements().size()).isEqualTo(1);
        assertThat(ledger.lifecycleSettlements().size()).isEqualTo(1);
        assertThat(ledger.hasChangedValues()).isFalse();
    }

    /**
     * 订单批量范围内四类进度写入均拒绝且不留变化，退出后整体clear清进度及未完成计数。
     */
    @Test
    void orderBatchCannotChangeProgressAndClearResetsAdmissionStatus() {
        // 本例唯一财库，冷阶段初始化后可交给真实账本线程
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setFundingProgress(0, funding(1));
        ledger.setLifecycleProgress(0, lifecycle(2));
        ledger.clearChangedKeys();
        ledger.beginOrderBatchMutationScope();
        assertThatThrownBy(() -> ledger.setFundingSettlement(0, 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setLifecycleSettlement(0, 4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setFundingProgress(0, funding(3))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setLifecycleProgress(0, lifecycle(4))).isInstanceOf(IllegalStateException.class);
        ledger.endOrderBatchMutationScope();
        assertThat(ledger.hasChangedValues()).isFalse();
        assertThat(ledger.fundingProgress(0).settlementId()).isEqualTo(1);
        ledger.clear();
        assertThat(ledger.fundingProgress(0)).isNull();
        assertThat(ledger.lifecycleProgress(0)).isNull();
        assertThat(ledger.incompleteFundingCount()).isZero();
        assertThat(ledger.fundingProgresses().isEmpty()).isTrue();
        assertThat(ledger.lifecycleProgresses().isEmpty()).isTrue();
    }

    /**
     * 使用固定标的、用户及命令身份构造指定结算号的资金费进度记录。
     * @param settlementId 该测试进度记录对应的结算号
     */
    private static TreasuryRuntime.FundingProgressRuntime funding(long settlementId) {
        return new TreasuryRuntime.FundingProgressRuntime(settlementId, INSTRUMENT, 100, 7, 100, 1);
    }

    /**
     * 使用同一固定标的及命令身份构造指定结算号的生命周期进度记录。
     * @param settlementId 该测试进度记录对应的结算号
     */
    private static TreasuryRuntime.LifecycleProgressRuntime lifecycle(long settlementId) {
        return new TreasuryRuntime.LifecycleProgressRuntime(settlementId, INSTRUMENT, 100, true, 0, 7, 0);
    }
}
