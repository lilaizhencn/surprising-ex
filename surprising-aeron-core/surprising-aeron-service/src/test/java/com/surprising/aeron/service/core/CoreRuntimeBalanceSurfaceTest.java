package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 余额入口的零调整、物化查询缓存、快照恢复和首次 Owner 线程交接测试。
 */
class CoreRuntimeBalanceSurfaceTest {

    /**
     * 六产品零调整可建立空用户，原请求重发不增资金；快照恢复后充值10000，再用新UUID零调整仍保持10000。
     * @param product 本轮枚举的产品线
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void initializesAnEmptyAccountWithoutMintingMoneyAndKeepsItThroughSnapshot(ProductLine product) {
        // 关闭原实例前捕获的完整快照字节
        byte[] snapshot;
        try (/* 本例实际运行时，线程交接场景由首次业务线程先关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(product)) {
            // 用户1001资产零调整的原请求
            CoreMessage initialize = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), product,
                    CommandSource.OPERATIONS, 9, 1, 1001, 1_000, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 0)));
            assertThat(apply(state, initialize).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001)).isNotNull();
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isZero();
            assertThat(apply(state, initialize).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isZero();
            snapshot = state.snapshot(101);
        }
        try (/* 完整快照恢复后的运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
            assertThat(restored.tradingState().user(1001)).isNotNull();
            assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isZero();
            // 恢复后增加10000的余额命令
            CoreMessage fund = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), product,
                    CommandSource.OPERATIONS, 9, 2, 1001, 2_000, 2),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            assertThat(apply(restored, fund).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 恢复并充值后使用新UUID的零调整
            CoreMessage repeatZero = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), product,
                    CommandSource.OPERATIONS, 9, 3, 1001, 3_000, 3),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 0)));
            assertThat(apply(restored, repeatZero).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        }
    }

    /**
     * 实际余额命令增加10000，核对业务修订为一，连续未变读取复用同一物化对象。
     */
    @Test
    void appliesBalanceCommandThroughAuthoritativeRuntimeAndMaterializesQueryState() {
        try (/* 本例实际运行时，线程交接场景由首次业务线程先关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 本例首次增加10000的实际请求
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 9, 1, 1001, 1_000, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));

            assertThat(apply(state, command).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
            assertThat(state.tradingState().revision()).isEqualTo(1);
            assertThat(state.tradingState()).isSameAs(state.tradingState());
        }
    }

    /**
     * 构造线程建立被动运行时，另一线程首次执行并读取余额，随后由该线程关闭；主线程join后检查原故障、状态及余额。
     */
    @Test
    void handsProjectedRuntimeFromConstructionThreadToFirstCoreCommandThread() throws InterruptedException {
        try (/* 本例实际运行时，线程交接场景由首次业务线程先关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 本例首次增加10000的实际请求
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), ProductLine.SPOT,
                    CommandSource.OPERATIONS, 9, 1, 1001, 1_000, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            // 首次业务线程捕获的Throwable原子引用
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // 业务线程交给测试线程的响应状态
            AtomicReference<ResponseStatus> status = new AtomicReference<>();
            // 业务线程交回的账户总余额
            AtomicReference<Long> balance = new AtomicReference<>();
            // 实际首次绑定Owner并执行命令的线程，finally尝试关闭运行时
            Thread coreThread = new Thread(() -> {
                try {
                    status.set(apply(state, command).status());
                    balance.set(state.tradingState().user(1001).totalUnits("USDT"));
                } catch (/* 该线程业务执行或查询捕获的原故障 */ Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    state.close();
                }
            });

            coreThread.start();
            coreThread.join();

            assertThat(failure.get()).isNull();
            assertThat(status.get()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(balance.get()).isEqualTo(10_000);
        }
    }

    /**
     * 先调用实际入口，只对尚活跃的直接命令槽在五秒内继续轮询；到期仍活跃则抛断言，其他空结果原样返回。
     * @param state 本次实际交易运行时
     * @param command 原余额调整请求
     */
    private static CoreResponse apply(TradingCoreRuntime state, CoreMessage command) {
        // 入口或直接控制槽当前得到的可空响应
        CoreResponse response = state.apply(command);
        // 直接控制轮询共用的五秒单调纳秒截止时间
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            response = state.directCommand.poll(state);
            if (response == null) Thread.onSpinWait();
        }
        if (response == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + command.header().messageType());
        }
        return response;
    }
}
