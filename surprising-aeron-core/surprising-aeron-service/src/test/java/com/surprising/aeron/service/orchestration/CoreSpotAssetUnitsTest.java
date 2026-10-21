package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 现货配置资产单位的冻结、成交、撤改与恢复测试。数量步与资产单位分开，核对手续费后双方资产及 Treasury 总额守恒，并检查快照声明最低读者能力，旧读者不能静默按单位一解释。
 */
class CoreSpotAssetUnitsTest {
    /** 本测试来源递增序号，同时参与原请求时间、日志位置和关联号。 */
    private long sequence;

    /**
     * 注册非单位数量步，检查快照格式及最低读者值；覆盖 maker 拆单、成交前恢复、限价 IOC/市价 IOC/FOK。核对买卖成交、费用、余单冻结、主动撤单解冻及可选反向市价成交后的精确资产总额。
     * @param fragmented maker 五份卖单是否拆为一份加四份以覆盖跨单成交
     * @param restoreBeforeFill 是否在 taker 成交前从快照恢复原运行时
     * @param type 本次 taker 订单为 LIMIT 或 MARKET
     * @param tif 原下单有效期，参数场景覆盖 IOC 或 FOK，挂单使用 GTC
     */
    @ParameterizedTest
    @CsvSource({"false,false,LIMIT,IOC", "true,false,LIMIT,IOC", "false,true,LIMIT,IOC", "true,true,LIMIT,IOC",
            "false,false,MARKET,IOC", "true,false,MARKET,IOC", "false,true,MARKET,IOC", "true,true,MARKET,IOC",
            "false,false,MARKET,FOK", "true,false,MARKET,FOK", "false,true,MARKET,FOK", "true,true,MARKET,FOK"})
    void freezesSettlesAndReleasesConfiguredAssetUnits(boolean fragmented, boolean restoreBeforeFill,
            CoreOrderType type, CoreTimeInForce tif) {
        // 最初创建的现货运行时，finally 必须关闭
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        // 当前操作的运行时引用，恢复后与 original 不同并需分别关闭
        TradingCoreRuntime runtime = original;
        try {
            // 数量步单位十万、价格和数量精度独立配置的原现货合约参数
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            // 本次冷交易状态快照编码字节，检查格式及最低读者字段
            byte[] encoded = com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec.encode(runtime.tradingState());
            // 以小端读取快照字段的 ByteBuffer 游标
            var fields = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertThat(fields.getInt()).isEqualTo(37);
            // 快照字段十二记录的最低读者能力值，非单位资产计账场景应为二
            long minimumReader = 0;
            while (fields.hasRemaining()) {
                // 本次读取的快照字段编号
                int fieldId = fields.getInt(); /* 当前字段有效载荷长度，用于跳到下一个字段 */ int length = fields.getInt(); /* 当前快照字段结束位置，读取后按此继续扫描 */ int end = fields.position() + length;
                if (fieldId == 12) minimumReader = fields.getLong();
                fields.position(end);
            }
            assertThat(minimumReader).as("old readers must refuse non-unit asset accounting").isEqualTo(2);
            fund(runtime, 101, "USDT", 100_000_000_000L);
            fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 202, 1, CoreOrderSide.SELL, fragmented ? 1 : 5, 500_000, CoreTimeInForce.GTC);
            if (fragmented) place(runtime, 202, 2, CoreOrderSide.SELL, 4, 500_000, CoreTimeInForce.GTC);
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isEqualTo(500_000);

            if (restoreBeforeFill) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(71));
            assertThat(runtime.tradingState().instruments().get("1").quantityStepUnits()).isEqualTo(100_000);
            // 买方三份原订单的最终协议视图，预期 FILLED 且无剩余
            var filled = place(runtime, 101, 3, CoreOrderSide.BUY, 3, type == CoreOrderType.MARKET ? 500_050 : 500_000, tif, type);

            // 当前现货运行时导出的已提交冷状态，供精确资金断言
            var state = runtime.tradingState();
            assertThat(filled.remainingQuantitySteps()).isZero();
            assertThat(filled.status()).isEqualTo("FILLED");
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(300_000);
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(84_992_500_000L);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(700_000);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(14_997_000_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(10_500_000L);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
            assertThat(state.user(101).totalUnits("BTC") + state.user(202).totalUnits("BTC")).isEqualTo(1_000_000);
            assertThat(state.user(202).balances().get("BTC").lockedUnits()).isEqualTo(200_000);

            apply(runtime, CoreMessageType.CANCEL_ORDER, 202,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(fragmented ? 2 : 1)));
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
            place(runtime, 101, 4, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(8_004_000_000L);
            apply(runtime, CoreMessageType.CANCEL_ORDER, 101, TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(4)));
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(runtime.tradingState().user(101).totalUnits("USDT")).isEqualTo(84_992_500_000L);
            if (type == CoreOrderType.MARKET) {
                place(runtime, 101, 5, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
                // 可选反向市价卖出的最终协议订单视图
                var sell = place(runtime, 202, 6, CoreOrderSide.SELL, 2, 399_960, tif, CoreOrderType.MARKET);
                assertThat(sell.status()).isEqualTo("FILLED");
                assertThat(runtime.tradingState().user(101).totalUnits("BTC")).isEqualTo(500_000);
                assertThat(runtime.tradingState().user(202).totalUnits("BTC")).isEqualTo(500_000);
                assertThat(runtime.tradingState().user(101).totalUnits("USDT")).isEqualTo(76_990_900_000L);
                assertThat(runtime.tradingState().user(202).totalUnits("USDT")).isEqualTo(22_993_000_000L);
                assertThat(runtime.tradingState().treasuryState().feeBalances().get("USDT")).isEqualTo(16_100_000L);
                assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isZero();
                assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
            }
        } finally {
            if (runtime != original) runtime.close();
            original.close();
        }
    }

    /**
     * 覆盖原单部分成交及改单前恢复；先以资金不足新单检查原订单与原冻结保留，再合法撤改重下并成交，核对剩余冻结归零和含手续费的资产守恒。
     * @param restore 是否在改单或批撤前恢复原快照
     * @param partiallyFilled 原买单是否已先成交一份再进行改单
     */
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void amendedSpotReserveSurvivesRestoreAndInsufficientFundsLeaveTheOriginalOpen(boolean restore, boolean partiallyFilled) {
        // 最初创建的现货运行时，finally 必须关闭
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        // 当前操作的运行时引用，恢复后与 original 不同并需分别关闭
        TradingCoreRuntime runtime = original;
        try {
            // 数量步单位十万、价格和数量精度独立配置的原现货合约参数
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            fund(runtime, 101, "USDT", 100_000_000_000L);
            fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 101, 41, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            if (partiallyFilled) place(runtime, 202, 40, CoreOrderSide.SELL, 1, 400_000, CoreTimeInForce.IOC);
            if (restore) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(73));
            // 撤改或撤单之前原预留仍保留未用费用缓冲：
            // 初始 8_004_000_000 减去实际 maker 成交消耗 4_000_800_000。
            // 改单尝试前原订单仍保留的冻结，部分成交后仍含未用费用缓冲
            long originalReserve = runtime.tradingState().user(101).balances().get("USDT").lockedUnits();
            assertThat(originalReserve).isEqualTo(partiallyFilled ? 4_003_200_000L : 8_004_000_000L);
            // 本次资金不足改单或跨用户批撤的原拒绝响应
            var rejected = execute(runtime, CoreMessageType.AMEND_ORDER, 101,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(41, 42, "amend-rejected",
                            450_000L, 1000L, CoreTimeInForce.GTC, false)));
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(originalReserve);
            // 合法改单实际完成后的原响应，包含旧单取消与新单 OPEN
            var amended = apply(runtime, CoreMessageType.AMEND_ORDER, 101,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(41, 43, "amend-valid",
                            450_000L, 3L, CoreTimeInForce.GTC, false)));
            // 当前执行的原响应或解码出的订单视图列表，按所在方法类型使用
            var result = CoreCommandResultCodec.decode(amended.data()).orders();
            assertThat(result).anySatisfy(/* 从改单或下单结果中逐项核对身份与状态的协议订单视图 */ order -> { assertThat(order.orderId()).isEqualTo(41); assertThat(order.status()).isEqualTo("CANCELED"); });
            assertThat(result).anySatisfy(/* 从改单或下单结果中逐项核对身份与状态的协议订单视图 */ order -> { assertThat(order.orderId()).isEqualTo(43); assertThat(order.status()).isEqualTo("OPEN"); });
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(13_506_750_000L);
            place(runtime, 202, 44, CoreOrderSide.SELL, 3, 450_000, CoreTimeInForce.IOC);
            // 当前现货运行时导出的已提交冷状态，供精确资金断言
            var state = runtime.tradingState();
            assertThat(state.user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(partiallyFilled ? 82_496_500_000L : 86_497_300_000L);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(partiallyFilled ? 17_491_250_000L : 13_493_250_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(partiallyFilled ? 12_250_000L : 9_450_000L);
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(partiallyFilled ? 400_000 : 300_000);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(partiallyFilled ? 600_000 : 700_000);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
        } finally {
            if (runtime != original) runtime.close();
            original.close();
        }
    }

    /**
     * 可选恢复后先用含他人订单的批撤检查整体归属拒绝且冻结不变；再撤两份本用户订单加未知项，检查逐项成功/拒绝及他人原订单仍冻结，最后撤他人自己的订单。
     * @param restore 是否在改单或批撤前恢复原快照
     */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelBatchReleasesOnlyOwnedOrdersAndPreservesMixedResultsAfterRestore(boolean restore) {
        // 最初创建的现货运行时，finally 必须关闭
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        // 当前操作的运行时引用，恢复后与 original 不同并需分别关闭
        TradingCoreRuntime runtime = original;
        try {
            // 数量步单位十万、价格和数量精度独立配置的原现货合约参数
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            fund(runtime, 101, "USDT", 100_000_000_000L); fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 101, 41, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            place(runtime, 101, 43, CoreOrderSide.BUY, 3, 410_000, CoreTimeInForce.GTC);
            place(runtime, 202, 40, CoreOrderSide.SELL, 1, 410_000, CoreTimeInForce.IOC);
            place(runtime, 202, 44, CoreOrderSide.SELL, 1, 500_000, CoreTimeInForce.GTC);
            if (restore) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(74));
            // 含他人订单批撤之前自己的冻结总额，整体拒绝不能改变它
            long beforeRejected = runtime.tradingState().user(101).balances().get("USDT").lockedUnits();
            // 本次资金不足改单或跨用户批撤的原拒绝响应
            var rejected = execute(runtime, CoreMessageType.CANCEL_ORDER_BATCH, 101,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(41), new CancelOrderCommand(44)))));
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.ORDER_OWNER_MISMATCH);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(beforeRejected);
            // 当前执行的原响应或解码出的订单视图列表，按所在方法类型使用
            var result = apply(runtime, CoreMessageType.CANCEL_ORDER_BATCH, 101,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(41), new CancelOrderCommand(43),
                            new CancelOrderCommand(999)))));
            // 合法与未知订单混合批撤的逐项结果清单
            var items = TradingOrderBatchCodec.decodeResult(result.data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status).containsExactly(
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(items.get(2).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            // 当前现货运行时导出的已提交冷状态，供精确资金断言
            var state = runtime.tradingState();
            assertThat(state.user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.user(202).balances().get("BTC").lockedUnits()).isEqualTo(100_000);
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(100_000);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(900_000);
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(95_899_180_000L);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(4_097_950_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(2_870_000L);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
            apply(runtime, CoreMessageType.CANCEL_ORDER, 202, TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(44)));
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
        } finally { if (runtime != original) runtime.close(); original.close(); }
    }

    /**
     * 执行原命令到本辅助能取得的终态，要求 commandStatus 为 APPLIED，再交原响应供业务字段断言。
     * @param runtime 当前要执行原现货业务的运行时，可能已从快照恢复
     * @param type 本次原协议消息类型
     * @param user 原命令所属用户
     * @param payload 原业务已编码载荷
     */
    private CoreResponse apply(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        // 当前执行的原响应或解码出的订单视图列表，按所在方法类型使用
        var result = execute(runtime, type, user, payload);
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return result;
    }

    /**
     * 给当前现货用户指定资产增加原单位数，沿余额调整原业务执行。
     * @param runtime 当前要执行原现货业务的运行时，可能已从快照恢复
     * @param user 原命令所属用户
     * @param asset 要入账的原资产文本
     * @param units 要增加的原资产单位数
     */
    private void fund(TradingCoreRuntime runtime, long user, String asset, long units) {
        apply(runtime, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    /**
     * 以 LIMIT 类型构造标的一订单，复用同一条下单和结果读取辅助。
     * @param runtime 当前要执行原现货业务的运行时，可能已从快照恢复
     * @param user 原命令所属用户
     * @param id 本次原订单号
     * @param side 原订单买卖方向
     * @param quantity 原订单数量步数，不是基础资产最小单位数
     * @param price 订单价格 tick 数，不是已乘资产精度的余额单位
     * @param tif 原下单有效期，参数场景覆盖 IOC 或 FOK，挂单使用 GTC
     */
    private CoreOrderStateView place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side, long quantity, long price, CoreTimeInForce tif) {
        return place(runtime, user, id, side, quantity, price, tif, CoreOrderType.LIMIT);
    }

    /**
     * 构造指定类型、方向、价格 tick、数量步及有效期的原下单；执行后从响应订单视图中选出本订单号，缺失时报错。
     * @param runtime 当前要执行原现货业务的运行时，可能已从快照恢复
     * @param user 原命令所属用户
     * @param id 本次原订单号
     * @param side 原订单买卖方向
     * @param quantity 原订单数量步数，不是基础资产最小单位数
     * @param price 订单价格 tick 数，不是已乘资产精度的余额单位
     * @param tif 原下单有效期，参数场景覆盖 IOC 或 FOK，挂单使用 GTC
     * @param type 本条原下单类型，LIMIT 或 MARKET
     */
    private CoreOrderStateView place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side,
            long quantity, long price, CoreTimeInForce tif, CoreOrderType type) {
        // 本次原下单的最终应用响应
        var response = apply(runtime, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, "1", side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                type, tif, false, "spot-units-" + id)));
        return CoreCommandResultCodec.decode(response.data()).orders().stream()
                .filter(/* 从改单或下单结果中逐项核对身份与状态的协议订单视图 */ order -> order.orderId() == id).findFirst().orElseThrow();
    }

    /**
     * 以原现货消息执行；空响应且控制槽活跃时最多五秒轮询，控制仍未完则失败；MATCHING_PENDING 再交同步完成辅助，不把中间结果当终态。
     * @param runtime 当前要执行原现货业务的运行时，可能已从快照恢复
     * @param type 本次原协议消息类型
     * @param user 原命令所属用户
     * @param payload 原业务已编码载荷
     */
    private CoreResponse execute(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        // 为本条原消息递增的来源序号
        long seq = ++sequence;
        // 当前带现货、原用户、请求业务时间及来源的原消息
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        // 当前执行的原响应或解码出的订单视图列表，按所在方法类型使用
        var result = runtime.apply(message);
        // 本辅助轮询直接控制命令的五秒单调时钟截止值
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && runtime.directCommand.active() && System.nanoTime() < directDeadline) {
            result = runtime.directCommand.poll(runtime);
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && runtime.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + type);
        }
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = com.surprising.aeron.service.orchestration.trade.CoreTestCompletion.completeMatchingSynchronously(
                    runtime, runtime.matchingSequence(message.header().commandId()), 1_700_000_000_000L + seq, seq);
        }
        return result;
    }
}
