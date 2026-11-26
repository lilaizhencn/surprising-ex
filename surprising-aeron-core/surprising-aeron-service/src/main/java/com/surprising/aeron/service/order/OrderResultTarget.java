package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public interface OrderResultTarget {
    /** 返回本命令需要填充的结果槽数量。 */
    int resultCount();
    /** 返回指定结果槽主要查询的新单或当前订单编号。 */
    long resultOrderId(int index);
    /** 返回指定槽对应的原订单编号，没有改单或替换原单时可为 0。 */
    long resultOriginalOrderId(int index);
    /** 由所属 Lane 把该槽的最终订单及规范币对文本交给原命令捕获，订单已移除时允许为空。 */
    void resultOrder(int index, Order order, String instrumentId);

    /** 默认不额外读取无变化结果。 */
    default boolean captureUnchangedResults() { return false; }

    /** 判断该槽是否已由变化捕获填好，默认 false 让需要补读的实现自行决定。 */
    default boolean resultPrepared(int index) { return false; }

    /** 在最终 Lane 结果捕获后准备原命令响应，必须先于完成回执发布。 */
    void prepareResponse();

    /** 可选地记录当前批次项 Matcher 接受或拒绝结果，默认不额外存状态，原发布流程在最终释放边界前调用。 */
    default void matcherResult(int index, boolean accepted) { }

    /** 响应准备完成后交出后备字节数组，Owner 按原租约消费并结合逻辑长度读取有效前缀。 */
    byte[] preparedResponse();

    /** 返回已准备响应的有效字节长度，可能小于固定后备数组容量。 */
    int preparedResponseLength();

    /** 默认只沿用 Lane 中仍保留的订单。 */
    default boolean includeTerminalAfterImage() { return false; }

    /** 先处理本次变更命中的结果槽，按目标策略选 Lane 当前订单或变化缓冲终态后值，新编号缺失时再取原单。 */
    static void capture(OrderResultTarget target, AccountPartitionCommit.Delta changes,
                        IdIndex identities, AccountPartition lane) {
        for (int i = 0; i < target.resultCount(); i++) {
            long id = target.resultOrderId(i);
            long original = target.resultOriginalOrderId(i);
            if (!changes.orders().containsKey(id) && (original == 0 || !changes.orders().containsKey(original))) continue;
            Order order = lane.order(id);
            if (target.includeTerminalAfterImage() && changes.orders().containsKey(id))
                order = changes.orders().get(id);
            if (order == null && original > 0) {
                order = lane.order(original);
                if (target.includeTerminalAfterImage() && changes.orders().containsKey(original))
                    order = changes.orders().get(original);
            }
            target.resultOrder(i, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        if (target.captureUnchangedResults()) captureUnchanged(target, identities, lane);
        target.prepareResponse();
    }

    /** 仅补读尚未准备的结果槽，从所属 Lane 查询新编号或原单，不查询全局发布表。 */
    static void captureUnchanged(OrderResultTarget target,
                                 IdIndex identities, AccountPartition lane) {
        for (int index = 0; index < target.resultCount(); index++) {
            if (target.resultPrepared(index)) continue;
            Order order = lane.order(target.resultOrderId(index));
            if (order == null) {
                long original = target.resultOriginalOrderId(index);
                if (original > 0) order = lane.order(original);
            }
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
    }

    /** 顺序批次结束后重新读取全部结果槽的 Lane 最终状态，纳入后续项对前面订单的影响，再准备整批响应。 */
    static void captureFinal(OrderResultTarget target,
                             IdIndex identities, AccountPartition lane) {
        for (int index = 0; index < target.resultCount(); index++) {
            Order order = lane.order(target.resultOrderId(index));
            long original = target.resultOriginalOrderId(index);
            if (order == null && original > 0) order = lane.order(original);
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        target.prepareResponse();
    }
}

