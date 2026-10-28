package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六产品线分别验证非空内存快照恢复与后续命令重放：包含部分成交、批量下改撤、触发 OCO、幂等和继续成交。通过业务哈希、Matcher 哈希及提交序号对照两实例；这里直接应用构造的尾部命令，不读取真实 Aeron Archive 日志，也不测试节点故障切换。
 */
@ExtendWith(PipelinedExecutionExtension.class)
class ProductRecoveryLifecycleTest {

    /**
     * 为当前产品构造部分成交和待触发单，快照恢复后重发旧命令并依次执行批量下改单撤单、触发取消和继续成交；在多个边界再次快照，对照金额和哈希。随后验证非法 OCO 不落半对、合法 OCO 恢复后只触发一腿，最后成交清空活动订单与预留。
     * @param product 当前参数化测试的独立产品线
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductLine.class)
    void nonemptySnapshotAndLogRecoverOrdersBatchesTriggersAndContinueTrading(ProductLine product) {
        // 当前产品对应的合约类型，用来选择资产、乘数及到期参数。
        ContractType type = ContractType.valueOf(product.contractTypeCode());
        // 币本位使用 BTC，其他本场景使用 USDT 的结算资产名称。
        String asset = type.isInverse() ? "BTC" : "USDT";
        try (/* 基准交易实例，场景结束时自动关闭。 */ TradingCoreRuntime live = new TradingCoreRuntime(product)) {
            apply(live, command(product, 1, 1, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0), product)));
            apply(live, new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), product, CommandSource.OPERATIONS, 992, 1, 1,
                    1_700_000_000_000L, 99), TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                    ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, 1_700_000_000_000L)
                    : new ApplyMarkPriceCommand("1", 100, 1, 1_700_000_000_000L), product)));
            apply(live, command(product, 2, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(
                            product == ProductLine.SPOT ? "BTC" : asset, product == ProductLine.SPOT ? 20 : 20_000))));
            apply(live, command(product, 3, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            apply(live, command(product, 4, 11, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(order(101, CoreOrderSide.SELL, 100, 10))));
            // 使原卖单只成交四份的买单命令，恢复后原样重发以检查幂等。
            CoreMessage partial = command(product, 5, 22, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(order(102, CoreOrderSide.BUY, 100, 4)));
            // 首次部分成交命令的原响应字节参照。
            CoreResponse partialResult = apply(live, partial);
            assertThat(live.tradingState().orders().get(101L).executedQuantitySteps()).isEqualTo(4);
            // 首份快照前预置的待触发止损单。
            var trigger = new CoreTriggerOrderStateView(501, product, 22, "recovery-trigger", "", "1",
                    product == ProductLine.SPOT ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                    CoreTriggerOrderType.STOP_LOSS, CoreTriggerCondition.GREATER_OR_EQUAL,
                    200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                    0, 0, 0, "", 0, 0, 0, 0, 1);
            apply(live, command(product, 6, 22, CoreMessageType.PLACE_TRIGGER_ORDER,
                    CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
            // 含部分成交及待触发单的内存快照字节。
            byte[] checkpoint = live.snapshot(100);
            try (/* 从首份快照恢复并继续接收相同命令的实例。 */ TradingCoreRuntime recovered = TradingCoreRuntime.fromSnapshot(product, checkpoint)) {
                parity(live, recovered);
                assertThat(apply(recovered, partial).data()).isEqualTo(partialResult.data());
                parity(live, recovered);
                // 构造的批量下单、改单、撤单、触发单取消和继续成交命令序列。
                List<CoreMessage> tail = List.of(
                        command(product, 7, 22, CoreMessageType.PLACE_ORDER_BATCH,
                                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                        order(201, CoreOrderSide.BUY, 90, 1), order(202, CoreOrderSide.BUY, 80, 1))))),
                        command(product, 8, 22, CoreMessageType.AMEND_ORDER_BATCH,
                                TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                                        new AmendOrderCommand(201, 203, "amended", 85L, 1L, CoreTimeInForce.GTC, false))))),
                        command(product, 9, 22, CoreMessageType.CANCEL_ORDER_BATCH,
                                TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                                        new CancelOrderCommand(203), new CancelOrderCommand(202))))),
                        command(product, 10, 22, CoreMessageType.CANCEL_TRIGGER_ORDER, CoreTriggerOrderCodec.encodeId(501)),
                        command(product, 11, 22, CoreMessageType.PLACE_ORDER,
                                TradingCommandCodec.encodePlaceOrder(order(103, CoreOrderSide.BUY, 100, 6))));
                for (CoreMessage /* 尾部序列中当前同送两个实例并在恢复实例重发的命令。 */ message : tail) {
                    // 当前尾部命令在基准实例上的响应。
                    CoreResponse expected = apply(live, message);
                    // 同一尾部命令在恢复实例上的响应。
                    CoreResponse actual = apply(recovered, message);
                    assertThat(actual.data()).isEqualTo(expected.data());
                    if (message.header().messageType().name().endsWith("_BATCH")) {
                        assertThat(TradingOrderBatchCodec.decodeResult(actual.data()).items())
                                .allSatisfy(/* 批量响应中当前子命令结果，逐项要求已应用。 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
                    }
                    assertThat(apply(recovered, message).data()).isEqualTo(actual.data());
                    parity(live, recovered);
                    try (/* 每条尾部命令之后再次快照恢复出的临时实例，核对后立即关闭。 */ TradingCoreRuntime boundary = TradingCoreRuntime.fromSnapshot(product, recovered.snapshot(200 + message.header().sourceSequence()))) {
                        parity(recovered, boundary);
                    }
                }
                assertThat(recovered.tradingState().orders()).isEmpty();
                assertThat(recovered.tradingState().users().get(11L).reservations()).isEmpty();
                assertThat(recovered.tradingState().users().get(22L).reservations()).isEmpty();
                if (product == ProductLine.SPOT) {
                    assertThat(CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(11L), "BTC")).isEqualTo(10);
                    assertThat(CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(22L), "BTC")).isEqualTo(10);
                    assertThat(CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(11L), "USDT")).isEqualTo(1_000);
                    assertThat(CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(22L), "USDT")).isEqualTo(19_000);
                } else {
                    assertThat(CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(11L), asset)
                            + CoreStateTestFixtures.totalUnits(recovered.tradingState().users().get(22L), asset)).isEqualTo(40_000);
                }
                // 编号五百零二、价格一百二十的 OCO 止盈腿。
                var takeProfit = new CoreTriggerOrderStateView(502, product, 22, "recovery-take-profit", "recovery-oco", "1",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        120, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 120, 2,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1);
                // 与止盈腿同组的合法止损腿。
                var stopLoss = ocoStopLoss(product, "recovery-stop-loss");
                // 止损腿客户编号为空白的非法 OCO 配对请求。
                var invalidPair = command(product, 12, 22, CoreMessageType.PLACE_TRIGGER_OCO_PAIR,
                        CoreTriggerOrderCodec.encodePlacementList(List.of(takeProfit, ocoStopLoss(product,
                                " ")).stream().map(placement -> new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        placement.triggerOrderId(), placement.clientTriggerOrderId(), placement.ocoGroupId(),
                                placement.instrumentId(), placement.side(), placement.triggerType(),
                                placement.triggerCondition(), placement.triggerPriceTicks(),
                                placement.activationPriceTicks(), placement.callbackRatePpm(), placement.orderType(),
                                placement.timeInForce(), placement.priceTicks(), placement.quantitySteps(),
                                placement.marginMode(), placement.positionSide(), placement.expiresAtEpochMillis(),
                                placement.priceSource())).toList()));
                assertThat(applyDirect(live, invalidPair).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(live.tradingState().triggerOrders()).doesNotContainKeys(502L, 503L);
                // 在两个实例上应用并检查重复返回的合法 OCO 配对命令。
                CoreMessage placeTrigger = command(product, 13, 22, CoreMessageType.PLACE_TRIGGER_OCO_PAIR,
                        CoreTriggerOrderCodec.encodePlacementList(List.of(takeProfit,
                                stopLoss).stream().map(placement -> new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        placement.triggerOrderId(), placement.clientTriggerOrderId(), placement.ocoGroupId(),
                                placement.instrumentId(), placement.side(), placement.triggerType(),
                                placement.triggerCondition(), placement.triggerPriceTicks(),
                                placement.activationPriceTicks(), placement.callbackRatePpm(), placement.orderType(),
                                placement.timeInForce(), placement.priceTicks(), placement.quantitySteps(),
                                placement.marginMode(), placement.positionSide(), placement.expiresAtEpochMillis(),
                                placement.priceSource())).toList()));
                // 恢复实例也应用同一条被拒命令，保持源序号与状态边界一致。
                assertThat(applyDirect(recovered, invalidPair).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                // 基准实例首次放置 OCO 对的响应，用来核对两条记录及幂等字节。
                var placed = apply(live, placeTrigger);
                assertThat(CoreTriggerOrderCodec.decodeList(placed.data())).hasSize(2);
                assertThat(apply(live, placeTrigger).data()).isEqualTo(placed.data());
                apply(recovered, placeTrigger);
                try (/* 合法 OCO 仍等待触发时，从快照恢复的临时实例。 */ TradingCoreRuntime afterPendingTrigger = TradingCoreRuntime.fromSnapshot(product, recovered.snapshot(300))) {
                    // 把参考价格更新到止盈触发点并推进价格序号的命令。
                    CoreMessage price = command(product, 14, 1, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                    ? new ApplyMarkPriceCommand("1", 120, 120, 120, 2, 1_700_000_000_013L)
                                    : new ApplyMarkPriceCommand("1", 120, 2, 1_700_000_000_013L), product));
                    apply(live, price);
                    apply(afterPendingTrigger, price);
                    // 明确执行止盈腿的触发命令，要求同时取消其 OCO 另一腿。
                    CoreMessage execute = command(product, 15, 22, CoreMessageType.EXECUTE_TRIGGER_ORDER,
                            CoreTriggerOrderCodec.encodeExecute(502, 2, 120, 1_700_000_000_014L));
                    apply(live, execute);
                    apply(afterPendingTrigger, execute);
                    parity(live, afterPendingTrigger);
                    assertThat(afterPendingTrigger.tradingState().triggerOrders().get(502L).status())
                            .isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                    assertThat(afterPendingTrigger.tradingState().triggerOrders().get(503L).status())
                            .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                    assertThat(afterPendingTrigger.tradingState().orders()).hasSize(1);
                    apply(afterPendingTrigger, price);
                    assertThat(afterPendingTrigger.tradingState().orders()).hasSize(1);
                    try (/* 一腿触发、另一腿取消后再次从快照恢复的临时实例。 */ TradingCoreRuntime afterTriggered = TradingCoreRuntime.fromSnapshot(product, afterPendingTrigger.snapshot(301))) {
                        parity(live, afterTriggered);
                        // 对触发生成的订单继续成交、最终清空活动订单和预留的买单命令。
                        CoreMessage close = command(product, 16, 11, CoreMessageType.PLACE_ORDER,
                                TradingCommandCodec.encodePlaceOrder(order(104, CoreOrderSide.BUY, 120, 2)));
                        apply(live, close);
                        apply(afterTriggered, close);
                        parity(live, afterTriggered);
                        assertThat(afterTriggered.tradingState().orders()).isEmpty();
                        assertThat(afterTriggered.tradingState().users().get(11L).reservations()).isEmpty();
                        assertThat(afterTriggered.tradingState().users().get(22L).reservations()).isEmpty();
                    }
                }
            }
        }
    }

    /**
     * 构造固定编号五百零三、价格八十的 OCO 止损腿；clientId 可故意传空白字符串以构造非法配对测试。
     * @param product 当前参数化测试的独立产品线
     * @param clientId OCO 止损腿的客户订单号，非法场景使用空白值
     */
    private static CoreTriggerOrderStateView ocoStopLoss(ProductLine product, String clientId) {
        return new CoreTriggerOrderStateView(503, product, 22, clientId, "recovery-oco", "1",
                CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS, CoreTriggerCondition.LESS_OR_EQUAL,
                80, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 80, 2,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", 0, 0, 0, 0, 1);
    }

    /**
     * 同时核对交易业务哈希、完整快照中的 Matcher 引擎哈希及已提交核心序号；本方法不逐字段比对所有实体。
     * @param expected 作为对照基准的运行时
     * @param actual 需要核对恢复一致性的运行时
     */
    private static void parity(TradingCoreRuntime expected, TradingCoreRuntime actual) {
        assertThat(actual.tradingState().businessStateHash()).isEqualTo(expected.tradingState().businessStateHash());
        assertThat(TradingCoreRuntime.inspectSnapshot(actual.productLine(), actual.snapshot()).engineStateHash()).isEqualTo(TradingCoreRuntime.inspectSnapshot(expected.productLine(), expected.snapshot()).engineStateHash());
        assertThat(actual.committedCoreSequence()).isEqualTo(expected.committedCoreSequence());
    }

    /**
     * 构造币对一的普通限价 GTC 测试订单，客户订单号由业务编号生成。
     * @param id 普通订单业务编号
     * @param side 普通订单买卖方向
     * @param price 普通限价价格
     * @param qty 普通订单数量
     */
    private static PlaceOrderCommand order(long id, CoreOrderSide side, long price, long qty) {
        return new PlaceOrderCommand(id, "1", side, price, qty, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "qa-" + id);
    }

    /**
     * 按显式源序号构造当前产品线的运营命令，UUID 随机，提交时间与模拟位置按源序号派生。
     * @param product 当前参数化测试的独立产品线
     * @param sequence 显式安排的命令源序号
     * @param user 命令关联的用户编号
     * @param type 协议命令类型
     * @param payload 已编码的业务载荷
     */
    private static CoreMessage command(ProductLine product, long sequence, long user, CoreMessageType type, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), product, CommandSource.OPERATIONS,
                991, sequence, user, 1_700_000_000_000L + sequence, sequence), payload);
    }

    /**
     * 应用命令并在五秒内轮询直接控制任务，完成本命令撮合及随后所有待撮合前缀，再要求命令已应用；后续撮合清空循环不另设置本地截止点。
     * @param state 当前要应用命令的交易运行时
     * @param message 可供两实例及重复请求原样使用的命令
     */
    private static CoreResponse apply(TradingCoreRuntime state, CoreMessage message) {
        // 本条命令当前取得的原始或续步响应。
        CoreResponse result = state.apply(message);
        // 当前辅助方法直接控制续步的五秒纳秒截止点。
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            result = state.directCommand.poll();
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + message.header().messageType());
        }
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = CoreTestCompletion.completeMatchingSynchronously(state, state.matchingSequence(message.header().commandId()),
                    message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        }
        while (state.pendingMatching().firstSequence() != 0) {
            CoreTestCompletion.completeMatchingSynchronously(state, state.pendingMatching().firstSequence(),
                    message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        }
        assertThat(result.commandStatus()).as("%s: %s", message.header().messageType(), result.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        return result;
    }

    /**
     * 只应用并在五秒截止点内完成直接控制命令，直接返回响应供拒绝断言使用，不强制要求 APPLIED，也不额外清空待撮合前缀。
     * @param state 当前要应用命令的交易运行时
     * @param message 可供两实例及重复请求原样使用的命令
     */
    private static CoreResponse applyDirect(TradingCoreRuntime state, CoreMessage message) {
        // 本条命令当前取得的原始或续步响应。
        CoreResponse result = state.apply(message);
        // 当前辅助方法直接控制续步的五秒纳秒截止点。
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            result = state.directCommand.poll();
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + message.header().messageType());
        }
        return result;
    }
}
