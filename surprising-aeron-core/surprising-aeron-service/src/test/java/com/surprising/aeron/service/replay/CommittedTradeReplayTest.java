package com.surprising.aeron.service.replay;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用独立真实回放 Core 验证订单事实与最终收据、算法子单进度、触发终态及快照恢复。普通下单矩阵覆盖六产品线；各现货算法及期权触发用例只说明其实际产品和断言，不扩大为全部金融生命周期覆盖。
 */
class CommittedTradeReplayTest {
    /**
     * 现货原下单成功后，同 UUID 改价及畸形载荷重试都导出独立冲突拒绝，不能借用原成功统计；原已保存收据仍为成功。
     */
    @Test void conflictingRetryRemainsARejectedStatisticsAttemptInsteadOfBorrowingTheOriginalSuccess() {
        // 当前场景显式选择的现货或期权产品线
        var line=ProductLine.SPOT;
        try(/* 本场景自动关闭的独立真实回放实例 */ var replay=new CommittedTradeReplay(line,null)) {
            send(replay,line,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), line));
            send(replay,line,CoreMessageType.ADJUST_BALANCE,7,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            place(replay,line,7,201,CoreOrderSide.BUY);/* 同 UUID 冲突前独立保存的成功尝试收据 */ var original=replay.commandResult();
            // 本次独立日志和来源序号
            long seq=++sequence;
            // 与原 UUID 相同但价格改变的冲突下单
            var conflict=new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,original.commandId(),line,
                    CommandSource.OPERATIONS,991,seq,7,TIME+seq,seq),TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,
                            101,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"replay-201")));
            replay.apply(CoreMessageCodec.encode(conflict),TIME+seq,seq);
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(original.code()).isEqualTo(CoreResultCode.NONE);
            seq=++sequence;
            // 沿用原 UUID 但载荷畸形的重试
            var malformed=new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,original.commandId(),line,
                    CommandSource.OPERATIONS,991,seq,7,TIME+seq,seq),new byte[]{1});
            replay.apply(CoreMessageCodec.encode(malformed),TIME+seq,seq);
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
        }
    }
    /**
     * 期权成交形成两端持仓，再建立并取消止盈条件，捕获取消帧；快照恢复后活动摘要没有待触发，但分页仍能重建已取消记录，查询不改变业务哈希。
     */
    @Test void capturesTerminalTriggersAndReconstructsThemWithoutChangingReplayBusinessState() {
        // 当前场景显式选择的现货或期权产品线
        var line = ProductLine.OPTION;
        // 关闭原回放前取得的完整独立快照字节
        byte[] snapshot;
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(line, null)) {
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 2_000_000_000_000L, 0, 100), line));
            for (long /* 当前循环需要备资的用户 ID */ user : new long[]{7, 8}) send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000_000)));
            send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("604", 100, 100, 100, 1, TIME)));
            place(replay, line, 7, 201, CoreOrderSide.SELL);
            place(replay, line, 8, 202, CoreOrderSide.BUY);
            // 最大 long ID 的期权待触发止盈记录
            var trigger = new CoreTriggerOrderStateView(Long.MAX_VALUE, line, 8, "retained-tp", "", "604", CoreOrderSide.SELL,
                    CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL, 200, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 0, 0, 1);
            // 创建条件单返回的事实帧
            var created = send(replay, line, CoreMessageType.PLACE_TRIGGER_ORDER, 8,
                    CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource())));
            assertThat(created).anySatisfy(/* 当前筛选或解码的已提交事实帧 */ f -> assertThat(f.kind()).isEqualTo(RealtimeFrame.Kind.TRIGGER));
            // 本次取消条件或普通订单返回的事实列表
            var canceled = send(replay, line, CoreMessageType.CANCEL_TRIGGER_ORDER, 8, CoreTriggerOrderCodec.encodeId(Long.MAX_VALUE));
            assertThat(canceled.stream().filter(/* 当前筛选或解码的已提交事实帧 */ f -> f.kind() == RealtimeFrame.Kind.TRIGGER)
                    .flatMap(/* 当前筛选或解码的已提交事实帧 */ f -> CoreTriggerOrderCodec.decodeList(f.payload()).stream()))
                    .anySatisfy(/* 取消条件单事实解码后的视图，要求状态已取消 */ v -> assertThat(v.status()).isEqualTo(CoreTriggerOrderStatus.CANCELED));
            snapshot = replay.snapshot();
        }
        try (/* 从同产品快照恢复的独立回放或查询 Core */ var restored = new CommittedTradeReplay(line, snapshot)) {
            // 冷查询前保存的业务哈希
            long before = restored.businessHash();
            // 恢复后导出的当前交易活动摘要
            var activity=restored.currentTradingActivity(TIME);
            assertThat(activity.usersWithPositions()).isEqualTo(2);
            assertThat(activity.openPositions()).isEqualTo(2);
            assertThat(activity.openOrders()).isZero();
            assertThat(activity.pendingTriggers()).isZero();
            assertThat(activity.instruments()).singleElement().satisfies(/* 活动摘要的单合约持仓汇总，核对多空各两手 */ v->{
                assertThat(v.longQuantitySteps()).isEqualTo(java.math.BigInteger.valueOf(2));
                assertThat(v.shortQuantitySteps()).isEqualTo(java.math.BigInteger.valueOf(2));
            });
            // 重建查询得到的已保留父单或终态触发视图列表
            var retained = restored.retainedTriggersBefore(0, 1);
            assertThat(retained).hasSize(1);
            assertThat(retained.getFirst().triggerOrderId()).isEqualTo(Long.MAX_VALUE);
            assertThat(retained.getFirst().status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
            assertThat(restored.retainedTriggersBefore(Long.MAX_VALUE, 1)).isEmpty();
            assertThat(restored.businessHash()).isEqualTo(before);
        }
    }
    /**
     * 批撤包含一笔成功和一笔不存在订单，导出收据复制两项结果；后续一百四十条命令推动近期缓存后，早先收据的单项拒绝仍稳定。
     */
    @Test void committedCancelBatchRetainsItsMixedOutcomesBeyondTheCoreCache() {
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1000000)));
            place(replay, ProductLine.SPOT, 7, 201, CoreOrderSide.BUY);
            send(replay, ProductLine.SPOT, CoreMessageType.CANCEL_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(201), new CancelOrderCommand(999)))));
            // 批撤完成后独立保存的混合逐项收据
            var receipt = replay.commandResult();
            assertThat(receipt.code()).isEqualTo(CoreResultCode.NONE);
            assertThat(receipt.cancelResults()).containsExactly(
                    new CommittedTradeReplay.CancelBatchItem(0, 201, true, "NONE"),
                    new CommittedTradeReplay.CancelBatchItem(1, 999, false, "ORDER_NOT_FOUND"));
            for (/* 推动近期结果缓存的一百四十次命令计数 */ int i = 0; i < 140; i++) send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(receipt.cancelResults().get(1).code()).isEqualTo("ORDER_NOT_FOUND");
        }
    }

    /**
     * 现货父单关联活动子单后保存快照，恢复后对手成交应更新父成交与活跃数量，即使父修订不变；再次恢复保留进度，陈旧更新拒绝，终态父单不再被后续普通订单唤出。
     */
    @Test void spotAlgoProgressFollowsCommittedChildFillsAndSurvivesReplayRestart() {
        // 关闭原回放前取得的完整独立快照字节
        byte[] snapshot;
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1000000)));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 8,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1000000)));
            send(replay, ProductLine.SPOT, CoreMessageType.UPSERT_ALGO_ORDER, 7, encodeAlgo(algo(0,1,List.of()), 0));
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {
                assertThat(v.algoOrderId()).isEqualTo(701); assertThat(v.activeQuantitySteps()).isZero();
            });
            send(replay, ProductLine.SPOT, CoreMessageType.PLACE_ORDER, 7, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.algoChanges()).isEmpty();
            send(replay, ProductLine.SPOT, CoreMessageType.UPSERT_ALGO_ORDER, 7, encodeAlgo(algo(1,2,List.of(201L)), 201));
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {
                assertThat(v.activeQuantitySteps()).isEqualTo(2);assertThat(v.executedQuantitySteps()).isZero();
            });
            snapshot=replay.snapshot();
        }
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT,snapshot)) {
            // 冷查询前保存的业务哈希
            long before=replay.businessHash();
            // 恢复后首次枚举的父算法视图列表
            var initial=new ArrayList<CoreAlgoOrderView>();replay.forEachRetainedAlgo(initial::add);
            assertThat(initial).hasSize(1);assertThat(replay.businessHash()).isEqualTo(before);
            place(replay,ProductLine.SPOT,8,202,CoreOrderSide.SELL);
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {
                assertThat(v.revision()).isEqualTo(2);assertThat(v.executedQuantitySteps()).isEqualTo(2);
                assertThat(v.activeQuantitySteps()).isZero();assertThat(v.activeChildOrderCount()).isZero();
            });
            try (/* 成交完成后的再次快照恢复副本 */ var afterFill = new CommittedTradeReplay(ProductLine.SPOT,replay.snapshot())) {
                // 重建查询得到的已保留父单或终态触发视图列表
                var retained = new ArrayList<CoreAlgoOrderView>(); afterFill.forEachRetainedAlgo(retained::add);
                assertThat(retained).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> assertThat(v.executedQuantitySteps()).isEqualTo(2));
                assertThat(afterFill.businessHash()).isEqualTo(replay.businessHash());
            }
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(1,2,List.of(201L)), 201));
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(replay.algoChanges()).isEmpty();
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(3,3,List.of(201L)), 0));
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> assertThat(v.statusCode()).isEqualTo(3));
            place(replay,ProductLine.SPOT,8,203,CoreOrderSide.SELL);
            assertThat(replay.algoChanges()).isEmpty();
        }
    }

    /**
     * 现货首子单在显式关联父单前立即成交，仍能从客户号导出父进度；快照恢复后再提交关联，保留已成交量和零活跃量，不另执行一次成交。
     */
    @Test void immediatelyFilledFirstChildCanLinkAfterRetirementAndSnapshotRestore() {
        // 关闭原回放前取得的完整独立快照字节
        byte[] snapshot;
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,7,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,8,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC",1000000)));
            place(replay,ProductLine.SPOT,8,202,CoreOrderSide.SELL);
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(0,1,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.PLACE_ORDER,7,TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {
                assertThat(v.executedQuantitySteps()).isEqualTo(2);assertThat(v.childOrderIds()).containsExactly(201L);
            });
            snapshot=replay.snapshot();
        }
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT,snapshot)) {
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(1,2,List.of(201L)), 201));
            assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(replay.algoChanges()).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {
                assertThat(v.childOrderIds()).containsExactly(201L);assertThat(v.executedQuantitySteps()).isEqualTo(2);
                assertThat(v.activeQuantitySteps()).isZero();
            });
        }
    }

    /**
     * 现货父单已请求取消或已取消后，迟到的运行中状态更新即使修订更大也拒绝，保留最终取消状态和正确修订。
     */
    @Test void cancellationCannotBeUndoneByALateSchedulerUpdate() {
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(0,1,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(2,2,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(1,3,List.of()), 0));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_ORDER_REVISION);
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(3,3,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(1,4,List.of()), 0));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_ORDER_REVISION);
            // 重建查询得到的已保留父单或终态触发视图列表
            var retained = new ArrayList<CoreAlgoOrderView>();replay.forEachRetainedAlgo(retained::add);
            assertThat(retained).singleElement().satisfies(/* 当前算法父单视图，用来核对状态、修订、成交及活跃进度 */ v -> {assertThat(v.statusCode()).isEqualTo(3);assertThat(v.revision()).isEqualTo(3);});
        }
    }

    /**
     * 父单请求取消后再来子单必须拒绝；恢复快照检查用户无预留、无冻结、可用总额不变，不能先锁钱再伪造成功。
     */
    @Test void cancelRequestedParentRejectsALateChildBeforeItCanReserveFunds() {
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT,null)) {
            send(replay,ProductLine.SPOT,CoreMessageType.REGISTER_INSTRUMENT,0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay,ProductLine.SPOT,CoreMessageType.ADJUST_BALANCE,7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000)));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(0,1,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.UPSERT_ALGO_ORDER,7,encodeAlgo(algo(2,2,List.of()), 0));
            send(replay,ProductLine.SPOT,CoreMessageType.PLACE_ORDER,7,TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(201,"604",CoreOrderSide.BUY,100,2,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"algo-701-0")));
            assertThat(replay.commandResult().code()).isEqualTo(CoreResultCode.INVALID_ALGO_CHILD);
            try (/* 从同产品快照恢复的独立回放或查询 Core */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT,replay.snapshot())) {
                // 恢复副本查询出的完整用户视图，用来检查预留与余额不变
                var user = CoreTestCompletion.userState(restored,7);
                assertThat(user.reservations()).isEmpty();
                assertThat(user.balances()).allMatch(/* 该用户的单资产余额项，核对没有冻结且可用总额不变 */ v->v.lockedUnits()==0);
                assertThat(user.balances().stream().mapToLong(/* 该用户的单资产余额项，核对没有冻结且可用总额不变 */ v->v.availableUnits()).sum()).isEqualTo(1000000);
            }
        }
    }

    /**
     * 构造固定现货父算法单视图，按入参设置状态代码、修订和已列子 ID，供真实命令入口校验。
     * @param status 父算法单要构造的协议状态代码
     * @param revision 父单本次声明修订
     * @param children 父单本次已列出的子订单 ID 顺序列表
     */
    private CoreAlgoOrderView algo(int status,long revision,List<Long> children) {
        return new CoreAlgoOrderView(701,7,"algo-client-701","604",0,CoreOrderSide.BUY,100,4,2,5,20,
                CoreMarginMode.CROSS,CorePositionSide.NET,false,false,CoreTimeInForce.GTC,status,
                children.isEmpty()?0:children.getLast(),"",TIME,TIME+5000,0,TIME,TIME+revision,revision,children,0,0,0);
    }

    /** 全组固定的业务毫秒基准时间，发送时按来源序号增加。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试实例命令来源与日志位置的递增计数。 */
    private long sequence;

    /**
     * 现货可用余额不足导致下单前拒绝，没有订单变化帧，但最终收据仍带原合法客户号、币对及明确余额不足码。
     */
    @Test void spotAdmissionRejectionWithoutAnOrderStillCarriesTheClientIdentity() {
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            send(replay, ProductLine.SPOT, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0), ProductLine.SPOT));
            send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(place(replay, ProductLine.SPOT, 7, 201, CoreOrderSide.BUY)).isEmpty();
            assertThat(replay.commandResult()).satisfies(/* 当前最终命令收据，用来核对用户、客户身份及结果码 */ result -> {
                assertThat(result.clientOrderId()).isEqualTo("replay-201");
                assertThat(result.instrumentId()).isEqualTo("604");
                assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(result.code()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            });
        }
    }

    /**
     * 六产品线依次注册、备资及必要标记价，验证挂单、混合批撤、批量改价与快照；恢复后成交输出一条成交及两端终态订单，再验证成功撤单和不存在订单拒绝不伪造变化帧。
     * @param line 本场景真实独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void capturesCommittedMakerAndTakerOrdersAndResumesFromSnapshot(ProductLine line) {
        // 关闭原回放前取得的完整独立快照字节
        byte[] snapshot;
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(line, null)) {
            // 当前参数产品线对应的实际合约类型
            var type = ContractType.valueOf(line.contractTypeCode());
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0), line));
            for (long /* 当前循环需要备资的用户 ID */ user : new long[]{7, 8}) for (String /* 本轮给用户备资的 BTC 或 USDT */ asset : List.of("BTC", "USDT"))
                send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 1_000_000)));
            if (line != ProductLine.SPOT) send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("604", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("604", 100, 1, TIME)));
            // 第一次挂单产生的订单事实
            var resting = place(replay, line, 7, 201, CoreOrderSide.SELL);
            assertThat(replay.commandResult()).satisfies(/* 当前最终命令收据，用来核对用户、客户身份及结果码 */ result -> {
                assertThat(result.userId()).isEqualTo(7);
                assertThat(result.clientOrderId()).isEqualTo("replay-201");
                assertThat(result.instrumentId()).isEqualTo("604");
                assertThat(result.code()).isEqualTo(CoreResultCode.NONE);
            });
            assertThat(resting).hasSize(1);
            assertThat(order(resting.getFirst()).status()).isEqualTo("OPEN");
            place(replay, line, 7, 209, CoreOrderSide.SELL);
            // 混合批撤返回的订单事实帧列表
            var batchCancel = send(replay, line, CoreMessageType.CANCEL_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(999), new CancelOrderCommand(209)))));
            assertThat(batchCancel.stream().filter(/* 当前筛选或解码的已提交事实帧 */ f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(/* 本轮解码并检查的独立订单视图 */ o -> {
                        assertThat(o.orderId()).isEqualTo(209);
                        assertThat(o.status()).isEqualTo("CANCELED");
                    });
            // 批量改价后返回的订单变化事实
            var amended = send(replay, line, CoreMessageType.AMEND_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(201, 211, "repriced-211", 99L, null, null, null)))));
            assertThat(amended.stream().filter(/* 当前筛选或解码的已提交事实帧 */ f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(/* 本轮解码并检查的独立订单视图 */ o -> {
                        assertThat(o.orderId()).isEqualTo(211);
                        assertThat(o.priceTicks()).isEqualTo(99);
                    });
            snapshot = replay.snapshot();
        }
        try (/* 从同产品快照恢复的独立回放或查询 Core */ var restored = new CommittedTradeReplay(line, snapshot)) {
            // 恢复后对手成交产生的最终事实帧列表
            var filled = place(restored, line, 8, 202, CoreOrderSide.BUY);
            assertThat(restored.commandResult().code()).isEqualTo(CoreResultCode.NONE);
            assertThat(restored.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(filled.stream().filter(/* 当前筛选或解码的已提交事实帧 */ f -> f.kind() == RealtimeFrame.Kind.TRADE)).hasSize(1);
            // 从成交事实筛选出的买卖两端订单状态
            var orders = filled.stream().filter(/* 当前筛选或解码的已提交事实帧 */ f -> f.kind() == RealtimeFrame.Kind.ORDER).map(CommittedTradeReplayTest::order).toList();
            assertThat(orders).hasSize(2).allSatisfy(/* 本轮解码并检查的独立订单视图 */ o -> {
                assertThat(o.status()).isEqualTo("FILLED");
                assertThat(o.executedQuantitySteps()).isEqualTo(2);
                assertThat(o.remainingQuantitySteps()).isZero();
                assertThat(o.getAveragePriceTicks()).isEqualTo("99");
                assertThat(o.instrumentId()).isEqualTo("604");
                assertThat(o.productLine()).isEqualTo(line);
            });
            place(restored, line, 7, 203, CoreOrderSide.SELL);
            // 本次取消条件或普通订单返回的事实列表
            var canceled = send(restored, line, CoreMessageType.CANCEL_ORDER, 7,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(203)));
            assertThat(canceled).hasSize(1);
            assertThat(order(canceled.getFirst()).status()).isEqualTo("CANCELED");
            assertThat(restored.exportSequence()).isPositive();
            // 撤单被拒绝不能凭空生成订单变化，也不能推进该订单修订。
            assertThat(send(restored, line, CoreMessageType.CANCEL_ORDER, 8,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999)))).isEmpty();
            assertThat(restored.commandResult().status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(restored.commandResult().code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
        }
    }

    /**
     * 保存不存在订单撤单的拒绝收据，再执行一百四十次资金命令并关闭回放；独立收据仍保留原 UUID、拒绝码和日志位置。
     */
    @Test void committedRejectionWithoutAnOrderHasAnImmutableReceiptAfterTheCacheMovesOn() {
        // 单独保存在回放实例外的不可变拒绝收据
        CommittedTradeReplay.CommittedCommandResult rejected;
        try (/* 本场景自动关闭的独立真实回放实例 */ var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            assertThat(send(replay, ProductLine.SPOT, CoreMessageType.CANCEL_ORDER, 7,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999)))).isEmpty();
            rejected = replay.commandResult();
            assertThat(rejected.commandId()).isEqualTo(new UUID(99, sequence));
            assertThat(rejected.userId()).isEqualTo(7);
            assertThat(rejected.code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            for (/* 推动近期结果缓存的一百四十次命令计数 */ int i = 0; i < 140; i++) send(replay, ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, 7,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            assertThat(replay.commandResult().commandId()).isNotEqualTo(rejected.commandId());
        }
        assertThat(rejected.code()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
        assertThat(rejected.clusterPosition()).isEqualTo(1);
    }

    /**
     * 通过固定币对、价格一百、数量二的普通限价单发送助手下单，客户号按业务 ID 唯一构造。
     * @param replay 接收已提交帧的独立回放实例
     * @param line 本场景真实独立产品线
     * @param user 本次命令所属用户 ID
     * @param id 普通下单业务订单 ID
     * @param side 买卖方向
     */
    private List<RealtimeFrame> place(CommittedTradeReplay replay, ProductLine line, long user, long id, CoreOrderSide side) {
        return send(replay, line, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "604", side, 100, 2, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replay-" + id)));
    }

    /**
     * 递增来源及日志序号，编码真实命令交给独立回放，核对每个返回事实帧编解码往返一致，再返回该批事实。
     * @param replay 接收已提交帧的独立回放实例
     * @param line 本场景真实独立产品线
     * @param type 本次命令类型
     * @param user 本次命令所属用户 ID
     * @param payload 与当前类型配对的协议载荷
     */
    private List<RealtimeFrame> send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] payload) {
        // 本次独立日志和来源序号
        long seq = ++sequence;
        // 本轮按唯一身份构造的真实命令
        var command = new CoreMessage(CoreMessageHeader.command(type, new UUID(99, seq), line,
                CommandSource.OPERATIONS, 991, seq, user, TIME + seq, seq), payload);
        // 回放本次命令返回的事实帧列表
        var frames = replay.apply(CoreMessageCodec.encode(command), TIME + seq, seq);
        assertThat(frames).allSatisfy(/* 本轮检查编解码往返一致的事实帧 */ frame ->
                assertThat(RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(frame))).isEqualTo(frame));
        return frames;
    }

    /**
     * 先要求传入帧为 ORDER，再解码为独立订单状态视图。
     * @param frame 必须为 ORDER 的已提交事实帧
     */
    private static CoreOrderStateView order(RealtimeFrame frame) {
        assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.ORDER);
        return CoreStateQueryCodec.decodeOrderState(frame.payload());
    }

    private static byte[] encodeAlgo(com.surprising.aeron.protocol.CoreAlgoOrderView value, long appendedChild) {
        com.surprising.aeron.protocol.CoreAlgoOrderCommand command = value.revision() == 1
                ? new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create(
                value.algoOrderId(), value.clientAlgoOrderId(), value.instrumentId(), value.algoTypeCode(),
                        value.side(), value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(),
                        value.intervalSeconds(), value.durationSeconds(), value.marginMode(), value.positionSide(),
                        value.reduceOnly(), value.postOnly(), value.timeInForce(), value.startAtEpochMillis())
                : new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Update(
                value.algoOrderId(), value.revision() - 1, value.statusCode(), value.currentOrderId(),
                        value.rejectReason(), value.nextSliceAtEpochMillis(), value.completedAtEpochMillis(),
                        value.updatedAtEpochMillis(), appendedChild);
        return com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeCommand(command);
    }
}
