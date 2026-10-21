package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.fee.FeePolicyBook;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.order.OrderReservation;

/**
 * 交易步骤 2 的订单规则决策：把原始下单意图和币对、市场价格、费率及结算状态变成确定的准入输入。先决定撮合边界和预留价格，账户再检查容量并执行冻结；这里不创建真实订单或执行撮合。
 */
public final class CoreOrderDecisionResolver {

    /** 百万分比的分母，价格保护、滑点和费率参数均采用这一比例单位。 */
    private static final long PPM = 1_000_000L;

    /**
     * 只提供静态订单决策入口，不允许创建无业务状态的工具实例。
     */
    private CoreOrderDecisionResolver() {
    }

    /**
     * 在 Owner 读取规范币对、身份、市场和账本状态，结合用户费率及集群时间生成新决策对象，缺失币对或身份立即失败。
     * @param runtime 所属交易运行时，用于 Owner 读取币对、市场、费率及账本元数据
     * @param identities 当前运行时身份表，用于核对币对已存在的稠密编号
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    public static ResolvedPlaceOrder resolve(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (runtime == null || identities == null || intent == null || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        // 按永久编号取得的本次币对配置，缺失时不能继续解析订单。
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        // 已注册币对的稠密身份，不允许因决策使用不存在的身份。
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        return resolveValues(null, instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), userId,
                clusterTimestamp, runtime.treasury().lifecycleSettlement(symbolId) != 0, intent);
    }

    /**
     * Owner 先检查币对存在、允许交易且未到禁止下单的到期边界，把路由、市场及费率输入交给池化准入事件，完整价格决策留待所属 Lane 执行。
     * @param target Owner 需要绑定决策输入的池化普通下单准入事件
     * @param runtime 所属交易运行时，用于 Owner 读取币对、市场、费率及账本元数据
     * @param identities 当前运行时身份表，用于核对币对已存在的稠密编号
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    public static void prepare(PlaceAdmissionEvent target, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (target == null || runtime == null || identities == null || intent == null
                || userId <= 0 || clusterTimestamp <= 0)
            throw new IllegalArgumentException("invalid order decision input");
        runtime.assertOwner();
        // 按永久编号取得的本次币对配置，缺失时不能继续解析订单。
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        // 已注册币对的稠密身份，不允许因决策使用不存在的身份。
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis()) {
            // 到期时从账本确认是否已完成结算，用于区分 INSTRUMENT_SETTLED 和一般无效命令。
            boolean lifecycleSettled = runtime.treasury().lifecycleSettlement(symbolId) != 0;
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        }
        target.prepareDecision(intent, instrument, symbolId, runtime.markPrice(symbolId), runtime.feePolicyBook(), clusterTimestamp);
    }

    /**
     * 用 Owner 已固定的输入补齐池化准入事件的价格、预留资产和费率决策，不重新从活动账户取市场配置。
     * @param target 已绑定路由的准入事件，完整价格和费率决策写入同一对象
     * @param mark 本次决策采用的标记、指数和远期价格事实，现货可为空
     * @param policies 按用户及币对选择费率的策略表，未命中时使用币对默认费率
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     * @param lifecycleSettled 账本事实表明该合约是否已经完成到期结算，决定到期拒绝的具体业务码
     */
    public static void resolvePrepared(PlaceAdmissionEvent target, MarkPriceRuntime mark,
            FeePolicyBook policies, long userId, long clusterTimestamp, boolean lifecycleSettled) {
        resolveValues(target, target.instrument(), target.symbolId(), mark, policies, userId,
                clusterTimestamp, lifecycleSettled, target.intent());
    }

    /**
     * 按给定上下文解析订单，并把结果写入调用方提供的可复用 ResolvedPlaceOrder 子类，返回同一对象。
     * @param <T> 保持调用方提供的决策对象子类型，写入后返回同一实例
     * @param target 调用方准备好的可复用决策对象，不另建平行副本
     * @param context 当前命令已准备好的币对、市场、费率和账本事实
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     */
    public static <T extends ResolvedPlaceOrder> T resolveInto(T target, Context context, long userId, PlaceOrderCommand intent) {
        if (target == null || context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        resolveValues(target, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent);
        return target;
    }

    /**
     * 当前命令可借用的币对、价格、费率表及账本结算事实。它保存引用而非账户副本；普通 context 使用原币对，queryContext 会单独捕获查询配置并重绑同一价格事实。
     * @param instrument 本次订单绑定的规范币对或查询捕获副本，须与价格事实身份一致
     * @param symbolId 币对的运行时稠密编号，冷业务状态决策允许为 -1
     * @param mark 本次决策采用的标记、指数和远期价格事实，现货可为空
     * @param policies 按用户及币对选择费率的策略表，未命中时使用币对默认费率
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     * @param settlementStatus 当前命令借用的不可变账本结算状态，提供交割和资金费冲突标记
     */
    public record Context(CoreInstrument instrument, int symbolId, MarkPriceRuntime mark,
                          FeePolicyBook policies, long clusterTimestamp,
                          com.surprising.aeron.service.state.ledger.TreasuryRuntime.SettlementStatus settlementStatus) {
        /**
         * 读取本次借用的账本结算事实，判断合约是否已经完成生命周期结算。
         */
        public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
        /**
         * 读取本次账本事实中的资金费截面处理中标记，后续账户准入用于冲突校验。
         */
        public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
    }

    /**
     * 把永久币对文本解析成正整数编号，再取得当前命令的市场与账本决策输入。
     * @param runtime 所属交易运行时，用于 Owner 读取币对、市场、费率及账本元数据
     * @param identities 当前运行时身份表，用于核对币对已存在的稠密编号
     * @param userId 沿用调用契约的用户编号，本方法只解析币对并传给整数入口，不读取账户
     * @param instrumentId 需要准备决策输入的永久币对编号，文本入口先解析成正整数
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    public static Context context(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, String instrumentId, long clusterTimestamp) {
        return context(runtime, identities, userId, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId), clusterTimestamp);
    }

    /**
     * 在 Owner 按整数编号取得规范币对、稠密身份、标记价、费率表及账本状态，不复制这些引用指向的业务数据。
     * @param runtime 所属交易运行时，用于 Owner 读取币对、市场、费率及账本元数据
     * @param identities 当前运行时身份表，用于核对币对已存在的稠密编号
     * @param userId 沿用调用契约的用户编号，当前实现仅准备币对元数据，按用户选费率在后续 resolve 执行
     * @param instrumentId 需要准备决策输入的永久币对编号，文本入口先解析成正整数
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    public static Context context(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, int instrumentId, long clusterTimestamp) {
        runtime.assertOwner();
        // 按永久编号取得的本次币对配置，缺失时不能继续解析订单。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        // 已注册币对的稠密身份，不允许因决策使用不存在的身份。
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        return new Context(instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), clusterTimestamp, runtime.treasury().settlementStatus(symbolId));
    }

    /**
     * 在已准备上下文上按用户选择费率并解析完整订单决策，调用方负责使用正确的命令边界。
     * @param context 当前命令已准备好的币对、市场、费率和账本事实
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     */
    public static ResolvedPlaceOrder resolve(Context context, long userId, PlaceOrderCommand intent) {
        if (context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        return resolveValues(null, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent);
    }

    /**
     * 为只读预检捕获币对配置副本；价格引用原规范币对时重绑到副本，保持数值和序号相同，其他身份异常仍留给正常校验拒绝。费率表和结算事实沿用原上下文引用。
     * @param runtime 所属交易运行时，用于 Owner 读取币对、市场、费率及账本元数据
     * @param identities 当前运行时身份表，用于核对币对已存在的稠密编号
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param instrumentId 需要准备决策输入的永久币对编号，文本入口先解析成正整数
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    public static Context queryContext(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long userId, String instrumentId, long clusterTimestamp) {
        // 预检开始时取得的原配置上下文，后续仅捕获查询需要固定的币对及价格身份。
        Context source = context(runtime, identities, userId, instrumentId, clusterTimestamp);
        // 本次预检捕获的币对配置副本，阻塞 Lane 等待期间的在线配置更新不能改变它。
        CoreInstrument instrument = source.instrument().captureForQuery();
        // 原上下文的价格事实，若属于原规范币对则把相同数值重绑到查询副本。
        MarkPriceRuntime mark = source.mark();
        // 供预检使用的价格事实；正确身份重绑到配置副本，异常身份保持原样以便后续正常拒绝。
        MarkPriceRuntime captured = mark == null || mark.instrument() != source.instrument() ? mark
                : new MarkPriceRuntime(mark.symbolId(), instrument,
                mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis(), mark.lastPriceTicks());
        return new Context(instrument, source.symbolId(), captured, source.policies(), source.clusterTimestamp(),
                source.settlementStatus());
    }

    /**
     * 统一执行运行态规则：校验币对与到期、市场单价格格式、标记价身份及鲜度、限价保护带，决定撮合价和预留价，按用户费率覆盖或币对默认费率写入决策对象。
     * @param target 可复用的决策目标，null 时创建新 ResolvedPlaceOrder
     * @param instrument 本次订单绑定的规范币对或查询捕获副本，须与价格事实身份一致
     * @param symbolId 币对的运行时稠密编号，冷业务状态决策允许为 -1
     * @param mark 本次决策采用的标记、指数和远期价格事实，现货可为空
     * @param policies 按用户及币对选择费率的策略表，未命中时使用币对默认费率
     * @param userId 下单用户编号，用于决策时选择用户费率，必须符合对应入口的用户约束
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     * @param lifecycleSettled 账本事实表明该合约是否已经完成到期结算，决定到期拒绝的具体业务码
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     */
    private static ResolvedPlaceOrder resolveValues(ResolvedPlaceOrder target,
                                                    CoreInstrument instrument, int symbolId,
                                                    MarkPriceRuntime mark, FeePolicyBook policies, long userId,
                                                    long clusterTimestamp, boolean lifecycleSettled,
                                                    PlaceOrderCommand intent) {
        if (policies == null || userId <= 0) throw new IllegalArgumentException("invalid fee decision input");
        if (!instrument.instrumentId().equals(intent.instrumentId())) throw new IllegalArgumentException("decision context instrumentId mismatch");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis())
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        // 当前是否为现货，决定市场价格格式、估值输入和冻结资产类别。
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() <= 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "spot market order requires a protection bound");
        if (!spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() != 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "derivative market order price must be zero");
        if (!spot) {
            requireFreshMark(mark, instrument, clusterTimestamp);
            if (intent.orderType() == CoreOrderType.LIMIT && instrument.orderProtection().limitPriceProtectionEnabled()
                    && clusterTimestamp - mark.generatedAtEpochMillis() > instrument.orderProtection().limitPriceMaxMarkAgeMs())
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the limit protection freshness bound");
        }
        // 现货的价格边界直接来自已记录的订单指令，这个数值槽不表示额外生成了一份衍生品标记价。
        // 衍生品使用标记价，现货使用订单记录中的价格边界，不伪造衍生品价格事实。
        long markPriceTicks = spot ? intent.limitPriceTicks() : mark.markPriceTicks();
        // 本次决策的指数价格，现货不使用而保存 0。
        long indexPriceTicks = spot ? 0 : mark.indexPriceTicks();
        // 本次决策的远期价格，现货不使用而保存 0。
        long forwardPriceTicks = spot ? 0 : mark.forwardPriceTicks();
        // 限价或现货沿用订单边界，衍生品市价按方向和允许滑点生成保护价。
        long matchingPriceTicks = spot || intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        // 按产品资金公式选择的预留计算价格，与实际送入 Matcher 的边界分开保存。
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        // 现货冻结资产，衍生品冻结保证金，不能混用冻结类别。
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        // 衍生品的结算资产，或现货买单的报价资产、卖单的基础资产。
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        // 按用户、币对和业务时间命中的费率策略，没有策略则使用币对默认费率。
        var fee = policies.select(userId, instrument.instrumentId(), clusterTimestamp);
        // 当前订单最终采用的 Maker 百万分比费率。
        long makerFee = fee == null ? instrument.makerFeeRatePpm() : fee.makerFeeRatePpm();
        // 当前订单最终采用的 Taker 百万分比费率。
        long takerFee = fee == null ? instrument.takerFeeRatePpm() : fee.takerFeeRatePpm();
        if (target == null) {
            return new ResolvedPlaceOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                    markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                    makerFee, takerFee);
        }
        target.resolve(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFee, takerFee);
        return target;
    }

    /**
     * 直接按冷业务状态的币对默认费率和已有价格生成决策，稠密编号使用 -1；此入口没有集群时间，不执行运行态的价格鲜度和到期时间判断。
     * @param state 已物化的冷业务状态，此入口不读取活动账户或当前系统时间
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     */
    public static ResolvedPlaceOrder resolve(TradingCoreState state, PlaceOrderCommand intent) {
        if (state == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        // 按永久编号取得的本次币对配置，缺失时不能继续解析订单。
        CoreInstrument instrument = state.instruments().get(OrderReservation.requireInstrumentId(intent.instrumentId()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        instrument.requireOrderEnabled(intent);
        // 当前是否为现货，决定市场价格格式、估值输入和冻结资产类别。
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() <= 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "spot market order requires a protection bound");
        if (!spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() != 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "derivative market order price must be zero");
        // 冷业务状态保存的标记价，衍生品缺失时拒绝，现货不需要。
        CoreMarkPriceState mark = spot ? null : state.riskState().markPrices().get(instrument.instrumentId());
        if (!spot && mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        // 衍生品使用标记价，现货使用订单记录中的价格边界，不伪造衍生品价格事实。
        long markPriceTicks = spot ? intent.limitPriceTicks() : mark.markPriceTicks();
        // 本次决策的指数价格，现货不使用而保存 0。
        long indexPriceTicks = spot ? 0 : mark.indexPriceTicks();
        // 本次决策的远期价格，现货不使用而保存 0。
        long forwardPriceTicks = spot ? 0 : mark.forwardPriceTicks();
        // 限价或现货沿用订单边界，衍生品市价按方向和允许滑点生成保护价。
        long matchingPriceTicks = spot || intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        // 按产品资金公式选择的预留计算价格，与实际送入 Matcher 的边界分开保存。
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        // 现货冻结资产，衍生品冻结保证金，不能混用冻结类别。
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        // 衍生品的结算资产，或现货买单的报价资产、卖单的基础资产。
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        return new ResolvedPlaceOrder(intent, instrument, -1, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm());
    }

    /**
     * 仅对开启保护的衍生品限价单检查买单上界或卖单下界，现货和其他类型直接跳过。
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     * @param instrument 本次订单绑定的规范币对或查询捕获副本，须与价格事实身份一致
     * @param markPriceTicks 决策采用的正价格刻度，现货时为原订单价格边界
     */
    private static void requireLimitPriceBand(PlaceOrderCommand intent, CoreInstrument instrument, long markPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || intent.orderType() != CoreOrderType.LIMIT || !instrument.orderProtection().limitPriceProtectionEnabled()) return;
        // 由标记价及限价保护比例得到的方向性边界，买价不得高于它，卖价不得低于它。
        long boundary = protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().limitPriceBandPpm());
        if (intent.side() == CoreOrderSide.BUY ? intent.limitPriceTicks() > boundary : intent.limitPriceTicks() < boundary)
            throw new CoreStateRejectedException("INVALID_COMMAND", "limit price exceeds configured mark price band");
    }

    /**
     * 要求价格事实属于同一规范币对，并用精确减法检查它不能来自未来或超过 Core 的最大标记价年龄。
     * @param mark 本次决策采用的标记、指数和远期价格事实，现货可为空
     * @param instrument 本次订单绑定的规范币对或查询捕获副本，须与价格事实身份一致
     * @param clusterTimestamp 本命令的集群业务时间，供到期、价格鲜度及费率有效期检查使用
     */
    private static void requireFreshMark(MarkPriceRuntime mark, CoreInstrument instrument,
                                         long clusterTimestamp) {
        if (mark == null || mark.instrument() != instrument) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        // 集群时间减价格生成时间的精确毫秒差，负数表示未来价格，过大表示价格过期。
        long age = Math.subtractExact(clusterTimestamp, mark.generatedAtEpochMillis());
        if (age < 0 || age > instrument.orderProtection().marketMaxMarkAgeMs()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the Core freshness bound");
        }
    }

    /**
     * 按买卖方向把滑点比例作用于价格：买侧向上取整，卖侧向下取整，最终价格至少为一个刻度。
     * @param side 买卖方向，决定保护价的上下边界和取整方向
     * @param markPriceTicks 决策采用的正价格刻度，现货时为原订单价格边界
     * @param slippagePpm 允许的价格偏移比例，单位为百万分比
     */
    private static long protectedPrice(CoreOrderSide side, long markPriceTicks, long slippagePpm) {
        // 买侧一百万加滑点，卖侧一百万减滑点的整数缩放因子。
        long factor = side == CoreOrderSide.BUY
                ? 1_000_000L + slippagePpm
                : 1_000_000L - slippagePpm;
        return Math.max(1, scalePpm(markPriceTicks, factor, side == CoreOrderSide.BUY));
    }

    /**
     * 现货和期权沿用撮合边界；期货按线性或反向资金公式选择更保守的标记价区间或限价，区分市价、反向买单和线性卖单。
     * @param intent 尚待决策的原始订单意图，包含方向、数量、限价和订单类型
     * @param instrument 本次订单绑定的规范币对或查询捕获副本，须与价格事实身份一致
     * @param markPriceTicks 决策采用的正价格刻度，现货时为原订单价格边界
     * @param matchingPriceTicks 已经决定的实际撮合价格或市价保护边界
     */
    private static long reservationPrice(PlaceOrderCommand intent, CoreInstrument instrument,
                                         long markPriceTicks, long matchingPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return matchingPriceTicks;
        }
        if (instrument.contractType().isOption()) {
            return matchingPriceTicks;
        }
        // 本币对的市价允许滑点，期货预留会同时计算上下保守边界。
        long slippagePpm = instrument.orderProtection().marketMaxSlippagePpm();
        // 按允许滑点向下取整的标记价下界，反向合约预留使用低价侧更保守。
        long lower = boundedMark(markPriceTicks, 1_000_000L - slippagePpm, false);
        // 按允许滑点向上取整的标记价上界，线性合约预留使用高价侧更保守。
        long upper = boundedMark(markPriceTicks, 1_000_000L + slippagePpm, true);
        if (intent.orderType() == CoreOrderType.MARKET) {
            return instrument.contractType().isInverse() ? lower : upper;
        }
        if (instrument.contractType().isInverse() && intent.side() == CoreOrderSide.BUY) {
            return Math.min(intent.limitPriceTicks(), lower);
        }
        if (instrument.contractType().isLinear() && intent.side() == CoreOrderSide.SELL) {
            return Math.max(intent.limitPriceTicks(), upper);
        }
        return intent.limitPriceTicks();
    }

    /**
     * 按百万分比缩放正标记价并选择取整方向，再把结果限制为至少一个价格刻度。
     * @param markPriceTicks 决策采用的正价格刻度，现货时为原订单价格边界
     * @param factor 要乘的百万分比因子，例如一百万加或减允许滑点
     * @param ceiling 为 true 且有小数余量时向上补一，否则保留整数除法结果
     */
    private static long boundedMark(long markPriceTicks, long factor, boolean ceiling) {
        return Math.max(1, scalePpm(markPriceTicks, factor, ceiling));
    }

    /**
     * 把价格的整百万部分和余数分开乘算，组合缩放结果；需要向上取整且仍有余数时补一，精确算术溢出直接向调用方抛出。
     * @param value 需要按百万分比缩放的正价格整数
     * @param factor 要乘的百万分比因子，例如一百万加或减允许滑点
     * @param ceiling 为 true 且有小数余量时向上补一，否则保留整数除法结果
     */
    public static long scalePpm(long value, long factor, boolean ceiling) {
        // 整百万部分乘因子的精确结果，避免直接把完整价格和因子相乘。
        long whole = Math.multiplyExact(value / PPM, factor);
        // 价格除百万后的余数与因子的精确乘积，用于补整数和判断取整余量。
        long remainderProduct = Math.multiplyExact(value % PPM, factor);
        // 整百万贡献加余数整数贡献后的缩放值，需要上取整时可能再补一。
        long scaled = Math.addExact(whole, remainderProduct / PPM);
        return ceiling && remainderProduct % PPM != 0 ? Math.incrementExact(scaled) : scaled;
    }
}
