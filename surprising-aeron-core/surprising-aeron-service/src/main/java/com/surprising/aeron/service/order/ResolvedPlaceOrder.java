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


@NoArgsConstructor
public class ResolvedPlaceOrder {
    @Accessors(fluent = true, chain = false)
    @Getter
    private PlaceOrderCommand intent;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    private boolean resolved;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long matchingPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long reservationPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long markPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long indexPriceTicks;
    /** 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long forwardPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private ReservationKind reservationKind;
    @Accessors(fluent = true, chain = false)
    @Getter
    private String reservationAsset;
    /** Maker 费率，单位为百万分比，负数表示返佣。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long makerFeeRatePpm;
    /** Taker 费率，单位为百万分比，且不能小于 Maker 费率。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerFeeRatePpm;

    /** 校验并保存完整订单决策输入，包括指数、远期价格及冻结资产与费率。 */
    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    /** 清除本对象保存的意图及全部决策字段，供后续安全复用。 */
    public void clear() {
        clearResolvedOrder();
    }

    /** 只允许初始化一次完整决策。若路由已先绑定，必须保持相同意图、币对对象和稠密编号，再校验价格与费率并发布已决策标记。 */
    public final void initializeResolvedOrder(
            PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
            long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
            long indexPriceTicks, long forwardPriceTicks, ReservationKind reservationKind,
            String reservationAsset, long makerFeeRatePpm, long takerFeeRatePpm) {
        if (resolved || intent == null || instrument == null || symbolId < 0
                || (this.intent != null && (this.intent != intent || this.instrument != instrument || this.symbolId != symbolId))
                || matchingPriceTicks <= 0 || reservationPriceTicks <= 0
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (forwardPriceTicks > 0 && indexPriceTicks == 0)
                || reservationKind == null || reservationAsset == null || reservationAsset.isBlank()
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm) {
            throw new IllegalArgumentException("invalid resolved place order");
        }
        // Owner 在投递前已固定路由身份，Lane 补决策时不能改写它。
        if (this.intent == null) initializeOrderIntent(intent, instrument, symbolId);
        this.matchingPriceTicks = matchingPriceTicks;
        this.reservationPriceTicks = reservationPriceTicks;
        this.markPriceTicks = markPriceTicks;
        this.indexPriceTicks = indexPriceTicks;
        this.forwardPriceTicks = forwardPriceTicks;
        this.reservationKind = reservationKind;
        this.reservationAsset = AssetBalance.normalizeAsset(reservationAsset);
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        resolved = true;
    }

    /** 先绑定订单意图和币对路由，确认两者币对编号一致。后续 Lane 只能补决策字段，不能换掉路由身份。 */
    public final void initializeOrderIntent(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId) {
        if (this.intent != null || intent == null || instrument == null || symbolId < 0
                || !intent.instrumentId().equals(instrument.instrumentId()))
            throw new IllegalArgumentException("invalid order routing input");
        this.intent = intent;
        this.instrument = instrument;
        this.symbolId = symbolId;
    }

    /** 释放意图和币对引用，将价格、费率及身份字段清零，并撤销已决策标记。此时不能再读取依赖 intent 的订单属性。 */
    public final void clearResolvedOrder() {
        intent = null;
        instrument = null;
        symbolId = 0;
        matchingPriceTicks = reservationPriceTicks = markPriceTicks = 0;
        indexPriceTicks = forwardPriceTicks = 0;
        reservationKind = null;
        reservationAsset = null;
        makerFeeRatePpm = takerFeeRatePpm = 0;
        resolved = false;
    }

    /** 返回完整价格和预留决策是否已初始化，仅绑定路由不能算完成。 */
    public final boolean resolvedOrderInitialized() { return resolved; }
    /** 返回原下单意图中的唯一订单编号。 */
    public long orderId() { return intent.orderId(); }
    /** 返回原下单意图中的永久币对编号文本。 */
    public String instrumentId() { return intent.instrumentId(); }
    /** 返回原下单意图的买入或卖出方向。 */
    public com.surprising.aeron.protocol.CoreOrderSide side() { return intent.side(); }
    /** 返回原始请求的限价或现货市价保护边界，不等于所有类型实际使用的撮合价。 */
    public long limitPriceTicks() { return intent.limitPriceTicks(); }
    /** 返回原下单意图的数量步数。 */
    public long quantitySteps() { return intent.quantitySteps(); }
    /** 返回原下单意图是否只允许减少已有持仓。 */
    public boolean reduceOnly() { return intent.reduceOnly(); }
    /** 返回原下单意图的全仓或逐仓模式。 */
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return intent.marginMode(); }
    /** 返回原下单意图对应的净持仓或双向持仓腿。 */
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return intent.positionSide(); }
    /** 返回原下单意图的限价或市价类型。 */
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return intent.orderType(); }
    /** 返回原下单意图的有效期策略，如 GTC 或 IOC。 */
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return intent.timeInForce(); }
    /** 返回原下单意图是否只允许成为挂单方。 */
    public boolean postOnly() { return intent.postOnly(); }
    public boolean hasIntent() { return intent != null; }
    /** 返回原下单意图的客户端订单标识。 */
    public String clientOrderId() { return intent == null ? "" : intent.clientOrderId(); }
}

