package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查显式全局业务哈希查询能观察已提交资金，恢复快照及同命令幂等重试后仍得到同一结果；不把只读哈希请求计成资金命令。
 */
class CoreAuditHashQueryTest {
    /**
     * 现货用户入账一百二十三前后查询业务哈希，检查其变化且等于冷状态哈希；恢复后两种哈希查询一致，原余额命令重复不再改变哈希。
     */
    @Test
    void explicitGlobalHashObservesCommittedFundsAndSurvivesSnapshot() {
        try (/* 原现货交易运行时，资源退出时关闭 */ TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 资金入账前显式查询得到的业务哈希
            long before = hash(state, CoreMessageType.BUSINESS_STATE_HASH_QUERY);
            // 给用户十一增加一百二十三 USDT 的原余额请求
            CoreMessage adjust = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 77, 1, 11, 1, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 123)));
            assertThat(CoreTestCompletion.applyAsynchronously(state, adjust).status()).isEqualTo(ResponseStatus.APPLIED);
            // 原余额请求成功后查询出的业务哈希
            long after = hash(state, CoreMessageType.BUSINESS_STATE_HASH_QUERY);
            assertThat(after).isNotEqualTo(before).isEqualTo(state.tradingState().businessStateHash());
            assertThat(hash(state, CoreMessageType.STATE_HASH_QUERY)).isEqualTo(after);
            try (/* 从提交后快照恢复的独立运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot(100))) {
                assertThat(hash(restored, CoreMessageType.BUSINESS_STATE_HASH_QUERY)).isEqualTo(after);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, adjust).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(hash(restored, CoreMessageType.STATE_HASH_QUERY)).isEqualTo(after);
            }
        }
    }

    /**
     * 构造本现货运行时的指定哈希查询，要求 OK 后解码响应中的 long 哈希。
     * @param state 本用例已提交的现货交易运行时
     * @param type 本次使用的业务哈希或状态哈希查询消息类型
     */
    private static long hash(TradingCoreRuntime state, CoreMessageType type) {
        // 指定哈希只读查询的响应，要求状态 OK
        var response = state.apply(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.OPERATIONS, 77, 0, 0, 2, 2), new byte[0]));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreStateQueryCodec.decodeStateHash(response.data());
    }
}
