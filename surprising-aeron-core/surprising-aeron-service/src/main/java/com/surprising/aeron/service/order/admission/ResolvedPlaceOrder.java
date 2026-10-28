package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.instrument.CoreInstrument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 一笔订单确定后的准入输入，分开保存原始意图、币对路由、撮合价格、预留价格和费率。普通下单可直接由池化准入事件继承这些字段，复用前必须清理，不能把同一实例并发用于多个命令。 构造约定：创建尚未绑定意图和价格决策的空对象，供命令槽或对象池后续初始化。 */
@NoArgsConstructor
public class ResolvedPlaceOrder {
    /** 原始下单意图，包含订单编号、方向、数量、模式和客户端标识。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private PlaceOrderCommand intent;
    /** 本次决策绑定的币对配置，后续准入须与同一业务身份一致。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    /** 币对已经准备好的非负运行时稠密编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    /** 是否已经完成价格、预留资产和费率决策；仅绑定意图时仍为 false。 */
    private boolean resolved;
    /** 实际送入 Matcher 的价格刻度，市价单也使用已经决定的保护边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long matchingPriceTicks;
    /** 计算预留金额采用的价格刻度，可能比撮合边界更保守。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long reservationPriceTicks;
    /** 本次决策的估值价格刻度，现货在该槽保存订单自己的价格边界。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long markPriceTicks;
    /** 本次决策使用的指数价格刻度，不需要该价格的入口可为 0。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long indexPriceTicks;
    /** 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long forwardPriceTicks;
    /** 应冻结现货资产还是衍生品保证金的类别。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private ReservationKind reservationKind;
    /** 已经规范化的冻结资产代码，衍生品为结算资产，现货按买卖方向选择。 */
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

    /**
     * 校验并保存完整订单决策输入，包括指数、远期价格及冻结资产与费率。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对已经准备好的非负运行时稠密编号
     * @param matchingPriceTicks 实际送入 Matcher 的价格刻度，市价单也使用已经决定的保护边界
     * @param reservationPriceTicks 计算预留金额采用的价格刻度，可能比撮合边界更保守
     * @param markPriceTicks 本次决策的估值价格刻度，现货在该槽保存订单自己的价格边界
     * @param indexPriceTicks 本次决策使用的指数价格刻度，不需要该价格的入口可为 0
     * @param forwardPriceTicks 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格
     * @param reservationKind 应冻结现货资产还是衍生品保证金的类别
     * @param reservationAsset 已经规范化的冻结资产代码，衍生品为结算资产，现货按买卖方向选择
     * @param makerFeeRatePpm Maker 费率，单位为百万分比，负数表示返佣
     * @param takerFeeRatePpm Taker 费率，单位为百万分比，且不能小于 Maker 费率
     */
    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    /**
     * 为不单独提供指数和远期价格的调用方创建已决策输入，两项额外价格均置为 0。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对已经准备好的非负运行时稠密编号
     * @param matchingPriceTicks 实际送入 Matcher 的价格刻度，市价单也使用已经决定的保护边界
     * @param reservationPriceTicks 计算预留金额采用的价格刻度，可能比撮合边界更保守
     * @param markPriceTicks 本次决策的估值价格刻度，现货在该槽保存订单自己的价格边界
     * @param reservationKind 应冻结现货资产还是衍生品保证金的类别
     * @param reservationAsset 已经规范化的冻结资产代码，衍生品为结算资产，现货按买卖方向选择
     * @param makerFeeRatePpm Maker 费率，单位为百万分比，负数表示返佣
     * @param takerFeeRatePpm Taker 费率，单位为百万分比，且不能小于 Maker 费率
     */
    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        this(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks, markPriceTicks,
                0, 0, reservationKind, reservationAsset, makerFeeRatePpm, takerFeeRatePpm);
    }


    /**
     * 清除本对象保存的意图及全部决策字段，供后续安全复用。
     */
    public void clear() {
        clearResolvedOrder();
    }

    /**
     * 只允许初始化一次完整决策；若路由已先绑定，必须保持相同意图、币对对象和稠密编号，再校验价格与费率并发布已决策标记。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对已经准备好的非负运行时稠密编号
     * @param matchingPriceTicks 实际送入 Matcher 的价格刻度，市价单也使用已经决定的保护边界
     * @param reservationPriceTicks 计算预留金额采用的价格刻度，可能比撮合边界更保守
     * @param markPriceTicks 本次决策的估值价格刻度，现货在该槽保存订单自己的价格边界
     * @param indexPriceTicks 本次决策使用的指数价格刻度，不需要该价格的入口可为 0
     * @param forwardPriceTicks 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格
     * @param reservationKind 应冻结现货资产还是衍生品保证金的类别
     * @param reservationAsset 已经规范化的冻结资产代码，衍生品为结算资产，现货按买卖方向选择
     * @param makerFeeRatePpm Maker 费率，单位为百万分比，负数表示返佣
     * @param takerFeeRatePpm Taker 费率，单位为百万分比，且不能小于 Maker 费率
     */
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
        // Owner 在投递前已固定路由身份，Lane 补决策时不能改写它；尚未绑定意图的独立对象在此完成首次绑定。
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

    /**
     * 先绑定订单意图和币对路由，确认两者币对编号一致；后续 Lane 只能补决策字段，不能换掉路由身份。
     * @param intent 原始下单意图，包含订单编号、方向、数量、模式和客户端标识
     * @param instrument 本次决策绑定的币对配置，后续准入须与同一业务身份一致
     * @param symbolId 币对已经准备好的非负运行时稠密编号
     */
    public final void initializeOrderIntent(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId) {
        if (this.intent != null || intent == null || instrument == null || symbolId < 0
                || !intent.instrumentId().equals(instrument.instrumentId()))
            throw new IllegalArgumentException("invalid order routing input");
        this.intent = intent;
        this.instrument = instrument;
        this.symbolId = symbolId;
    }

    /**
     * 释放意图和币对引用，将价格、费率及身份字段清零，并撤销已决策标记；此时不能再读取依赖 intent 的订单属性。
     */
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

    /**
     * 返回完整价格和预留决策是否已初始化，仅绑定路由不能算完成。
     */
    public final boolean resolvedOrderInitialized() { return resolved; }
    /**
     * 返回原下单意图中的唯一订单编号。
     */
    public long orderId() { return intent.orderId(); }
    /**
     * 返回原下单意图中的永久币对编号文本。
     */
    public String instrumentId() { return intent.instrumentId(); }
    /**
     * 返回原下单意图的买入或卖出方向。
     */
    public com.surprising.aeron.protocol.CoreOrderSide side() { return intent.side(); }
    /**
     * 返回原始请求的限价或现货市价保护边界，不等于所有类型实际使用的撮合价。
     */
    public long limitPriceTicks() { return intent.limitPriceTicks(); }
    /**
     * 返回原下单意图的数量步数。
     */
    public long quantitySteps() { return intent.quantitySteps(); }
    /**
     * 返回原下单意图是否只允许减少已有持仓。
     */
    public boolean reduceOnly() { return intent.reduceOnly(); }
    /**
     * 返回原下单意图的全仓或逐仓模式。
     */
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return intent.marginMode(); }
    /**
     * 返回原下单意图对应的净持仓或双向持仓腿。
     */
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return intent.positionSide(); }
    /**
     * 返回原下单意图的限价或市价类型。
     */
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return intent.orderType(); }
    /**
     * 返回原下单意图的有效期策略，如 GTC 或 IOC。
     */
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return intent.timeInForce(); }
    /**
     * 返回原下单意图是否只允许成为挂单方。
     */
    public boolean postOnly() { return intent.postOnly(); }
    public boolean hasIntent() { return intent != null; }
    /**
     * 返回原下单意图的客户端订单标识。
     */
    public String clientOrderId() { return intent == null ? "" : intent.clientOrderId(); }
}
