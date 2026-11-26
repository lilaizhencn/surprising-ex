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


public final class DecodedOrderCommand {

    private Object value;
    private PlaceOrderCommand placeSlot;
    private PlaceOrderBatchCommand placeBatchSlot;
    private CancelOrderCommand cancelSlot;
    private CancelOrderBatchCommand cancelBatchSlot;
    private AmendOrderCommand amendSlot;
    private AmendOrderBatchCommand amendBatchSlot;
    private ReplaceOrderCommand replaceSlot;
    private boolean commandSlot;
    private String routedSymbol;
    private int routedShard;

    /** 同标的复用本命令作用域缓存的 Matcher 分片，换标的才查适配器。调用方提供非空规范标的和匹配的拓扑，缓存不跨线程同步。 */
    public int matcherShard(com.surprising.aeron.service.matcher.OrderBookEngine adapter, String instrumentId) {
        if (!instrumentId.equals(routedSymbol)) {
            routedShard = adapter.matcherShardId(instrumentId);
            routedSymbol = instrumentId;
        }
        return routedShard;
    }

    /** 普通构造出来的实例不能按窗口槽接口清理或重解码。 */
    private DecodedOrderCommand(Object value) {
        bindValue(value);
    }

    /** 建立可反复解码和清理的空窗口槽，具体协议载体在首次需要时延迟创建。 */
    public static DecodedOrderCommand commandSlot() {
        var result = new DecodedOrderCommand(null);
        result.commandSlot = true;
        return result;
    }

    /** 只允许复用槽操作，先清旧值、路由及各载体，再按消息类型原位解码常用订单类型。 */
    public void decodeInto(CoreMessage message) {
        if (!commandSlot) throw new IllegalStateException("decoded command is not a window slot");
        clearCommandSlot();
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER) {
            if (placeSlot == null) placeSlot = PlaceOrderCommand.commandSlot();
            TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), placeSlot);
            bindValue(placeSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER_BATCH) {
            if (placeBatchSlot == null) placeBatchSlot = PlaceOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodePlaceOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), placeBatchSlot);
            bindValue(placeBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER) {
            if (cancelSlot == null) cancelSlot = CancelOrderCommand.commandSlot();
            TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), cancelSlot);
            bindValue(cancelSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER_BATCH) {
            if (cancelBatchSlot == null) cancelBatchSlot = CancelOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeCancelOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), cancelBatchSlot);
            bindValue(cancelBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER) {
            if (amendSlot == null) amendSlot = AmendOrderCommand.commandSlot();
            TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), amendSlot);
            bindValue(amendSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER_BATCH) {
            if (amendBatchSlot == null) amendBatchSlot = AmendOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeAmendOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), amendBatchSlot);
            bindValue(amendBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.REPLACE_ORDER) {
            if (replaceSlot == null) replaceSlot = ReplaceOrderCommand.commandSlot();
            TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), replaceSlot);
            bindValue(replaceSlot);
        } else {
            bindValue(decodeRawPayload(message));
        }
    }

    /** 只允许复用槽清理：解除当前值和路由缓存，清空已创建的各协议载体，但保留载体对象供后续复用。 */
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable decoded command");
        clearValues();
        routedSymbol = null;
        routedShard = 0;
        if (placeSlot != null) placeSlot.clearCommandSlot();
        if (placeBatchSlot != null) placeBatchSlot.clearCommandSlot();
        if (cancelSlot != null) cancelSlot.clearCommandSlot();
        if (cancelBatchSlot != null) cancelBatchSlot.clearCommandSlot();
        if (amendSlot != null) amendSlot.clearCommandSlot();
        if (amendBatchSlot != null) amendBatchSlot.clearCommandSlot();
        if (replaceSlot != null) replaceSlot.clearCommandSlot();
    }

    /** 判断当前 value 是否非空。 */
    public boolean isDecoded() { return value != null; }

    private void clearValues() { value = null; }

    private void bindValue(Object value) { this.value = value; }

    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalStateException("decoded matching command type mismatch");
        return type.cast(value);
    }

    /** 创建单次解码持有者，不借用本类的窗口复用槽。 */
    public static DecodedOrderCommand decode(CoreMessage message) {
        return new DecodedOrderCommand(decodeRawPayload(message));
    }

    private static Object decodeRawPayload(CoreMessage message) {
        return switch (message.header().messageType()) {
            case PLACE_ORDER -> TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case CANCEL_ORDER -> TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case REPLACE_ORDER -> TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER -> TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case EXECUTE_TRIGGER_ORDER ->
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION -> TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION_BATCH ->
                    TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
            case SETTLE_INSTRUMENT -> TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
            default -> message;
        };
    }

    /** 核对并借出当前普通下单载体。 */
    public PlaceOrderCommand placeOrder() { return required(PlaceOrderCommand.class); }
    /** 核对并借出当前批量下单载体。 */
    public PlaceOrderBatchCommand placeOrderBatch() { return required(PlaceOrderBatchCommand.class); }
    /** 核对并借出当前普通撤单载体。 */
    public CancelOrderCommand cancelOrder() { return required(CancelOrderCommand.class); }
    /** 核对并借出当前批量撤单载体。 */
    public CancelOrderBatchCommand cancelOrderBatch() { return required(CancelOrderBatchCommand.class); }
    /** 核对并借出当前撤改重下命令载体。 */
    public ReplaceOrderCommand replaceOrder() { return required(ReplaceOrderCommand.class); }
    /** 核对并借出当前普通改单载体。 */
    public AmendOrderCommand amendOrder() { return required(AmendOrderCommand.class); }
    /** 核对并借出当前批量改单载体。 */
    public AmendOrderBatchCommand amendOrderBatch() { return required(AmendOrderBatchCommand.class); }
    /** 核对并借出当前单项强平执行参数。 */
    public ExecuteLiquidationCommand liquidation() { return required(ExecuteLiquidationCommand.class); }
    /** 核对并借出当前批量强平执行参数。 */
    public ExecuteLiquidationBatchCommand liquidationBatch() { return required(ExecuteLiquidationBatchCommand.class); }
    /** 核对并借出当前合约到期结算参数。 */
    public SettleInstrumentCommand settlement() { return required(SettleInstrumentCommand.class); }
    /** 核对并借出触发执行解码器返回的原 long 参数数组。字段顺序沿用现有协议，调用方不得跨槽清理保留引用。 */
    public long[] trigger() { return required(long[].class); }
}
