package com.surprising.aeron.service.query.state;

import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.CoreRiskPolicy;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 按查询时的持仓和行情即时计算风险及 ADL 候选，不把后台分批强平扫描的旧风险缓存直接当查询结果。生产查询由 Owner 固定市场输入，再由所属账户 Lane 读取账户；这里只构造查询结果，不改资金、风险缓存或扫描进度。 构造约定：只提供只读查询计算，不创建额外的账户状态持有者。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeRiskQueryService {

    /** 候选顺序依次为优先分数从大到小、未实现盈利从大到小、用户编号从小到大、币对编号字符串从小到大。 */
    private static final Comparator<CoreAdlCandidateView> ADL_ORDER =
            Comparator.comparingLong(CoreAdlCandidateView::priorityScorePpm).reversed()
                    .thenComparing(Comparator.comparingLong(
                            CoreAdlCandidateView::unrealizedProfitUnits).reversed())
                    .thenComparingLong(CoreAdlCandidateView::userId)
                    .thenComparing(CoreAdlCandidateView::instrumentId);

    /** 比例放大一百万倍后的统一分母，避免用浮点数计算查询风险和 ADL 分数。 */
    private static final long PPM = 1_000_000L;

    /**
     * 生产账户 Lane 的单用户入口，要求正用户编号和 Owner 已固定的市场输入，再按当前持仓计算风险结果。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    public static List<CoreRiskSnapshotView> userSnapshots(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, long userId, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("risk query Lane inputs are required");
        ArrayList<CoreRiskSnapshotView> result = new ArrayList<>();
        if (runtime.positionCountForUser(userId) > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        // 当前用户各非空持仓的临时估值列表，先收集再生成组合口径的风险行。
        var values = new ArrayList<PositionValuation>();
        // 按结算资产归组的本次查询临时累计值，不回写 Core 账户或扫描状态。
        // 仅在本次用户查询中使用的全仓资产汇总，下标 0 为权益变化、1 为维持保证金。
        var cross = new java.util.HashMap<String, long[]>();
        // 当前用户各结算资产的全仓共享钱包缓存，每种资产只计算一次逐仓扣减。
        var wallets = new java.util.HashMap<String, Long>();
        for (long /* 当前用户需要读取的一个持仓整数键。 */ key : runtime.positionKeysForUser(userId)) {
            // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
            PositionRuntime position = runtime.position(key);
            if (position == null || position.signedQuantitySteps() == 0) continue;
            if (values.size() >= RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            // 本仓在查询准备时捕获的配置。
            CoreInstrument instrument = markets.instrument(position.symbolId());
            // 本仓在查询准备时捕获的行情引用。
            MarkPriceRuntime mark = markets.mark(position.symbolId());
            if (instrument == null || mark == null)
                throw new IllegalStateException("risk query source is missing");
            // 本仓带方向的数量步数，分别用于盈亏、维持保证金及市值计算。
            long quantity = position.signedQuantitySteps();
            // 当前持仓的未实现盈亏，查询行独立展示它，不能与期权权益市值混为一项。
            long pnl = CoreContractMath.pnlUnits(instrument, quantity, position.entryPriceTicks(), mark.markPriceTicks());
            // 当前单个持仓的维持保证金要求，先保存到临时估值，再按资产合并全仓金额。
            long maintenance = CoreContractMath.maintenanceMarginUnits(instrument, quantity,
                    mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks());
            // 本仓实际计入权益的估值变化，期货为浮盈亏，期权为带方向市值。
            long equityDelta = instrument.contractType().isOption()
                    ? OptionContractMath.optionMarketValueUnits(instrument, quantity, mark.markPriceTicks()) : pnl;
            // 本仓按标记价和合约精度算出的名义金额，供风险输出或 ADL 评分使用。
            long notional = CoreContractMath.notionalUnits(instrument, Math.absExact(quantity), mark.markPriceTicks());
            values.add(new PositionValuation(position, instrument, mark, pnl, maintenance, equityDelta, notional));
            wallets.computeIfAbsent(instrument.settleAsset(), /* 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。 */ asset -> crossWalletBalance(runtime, identities, userId, asset, markets));
            if (position.marginMode() == CoreMarginMode.CROSS) {
                // 同一结算资产的全仓累计值，下标 0 是权益变化合计，下标 1 是维持保证金合计。
                long[] totals = cross.computeIfAbsent(instrument.settleAsset(), /* 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。 */ asset -> new long[2]);
                totals[0] = Math.addExact(totals[0], equityDelta);
                totals[1] = Math.addExact(totals[1], maintenance);
            }
        }
        for (PositionValuation /* 当前用户已经算好的单持仓估值，结合共享钱包生成最终风险行。 */ value : values) {
            // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
            PositionRuntime position = value.position();
            // 当前资产汇总的结算资产代码，或按该代码建立钱包与组合累计值的映射键。
            String asset = value.instrument().settleAsset();
            // 当前结算资产扣掉逐仓持仓与委托占用后的全仓共享钱包。
            long wallet = wallets.get(asset);
            // 本行风险占比使用的维持保证金，全仓取该资产全仓合计，逐仓取单仓要求；输出行仍单独展示本仓维持保证金。
            // 本行风险占比采用的权益，全仓为共享钱包加组合估值变化，逐仓只看本仓保证金和市值变化。
            long equity, maintenance;
            if (position.marginMode() == CoreMarginMode.CROSS) {
                // 同一结算资产的全仓累计值，下标 0 是权益变化合计，下标 1 是维持保证金合计。
                long[] totals = cross.get(asset);
                equity = Math.addExact(wallet, totals[0]);
                maintenance = totals[1];
            } else {
                equity = Math.addExact(position.positionMarginUnits(), value.equityDelta());
                maintenance = value.maintenance();
            }
            // 维持保证金相对权益的 ppm 风险占比，不需维持时为 0，权益不正时用最大 long。
            long ratio = maintenance == 0 ? 0 : equity <= 0 ? Long.MAX_VALUE
                    : multiplyDivideCapped(maintenance, PPM, equity);
            result.add(new CoreRiskSnapshotView(userId, value.instrument().instrumentId(), position.marginMode(),
                    position.positionSide(), asset, position.signedQuantitySteps(), position.entryPriceTicks(),
                    value.mark().markPriceTicks(), value.notional(), position.positionMarginUnits(),
                    value.mark().priceSequence(), wallet, equity, value.pnl(), value.maintenance(), ratio,
                    CoreRiskPolicy.status(ratio).name()));
        }
        result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                .thenComparing(CoreRiskSnapshotView::instrumentId)
                .thenComparingInt(/* 排序时当前比较的查询结果行，最后按持仓腿的枚举顺序打破同用户同币对的顺序。 */ value -> value.positionSide().ordinal()));
        return List.copyOf(result);
    }

    /**
     * 生产 Lane 按 Owner 准备的整数持仓键读取目标用户仓位，核对路由用户一致，再用固定市场输入计算候选；缺失持仓返回 null。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param positionKey Owner 为该用户准备的持仓整数键，0 表示没有定位到持仓
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    public static CoreAdlCandidateView adlCandidate(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long userId, long positionKey, String asset, MarketInputs markets) {
        if (userId <= 0 || markets == null) throw new IllegalArgumentException("ADL query Lane inputs are required");
        // 本轮读取的实际持仓或临时估值所关联的持仓，空仓不会成为输出行。
        PositionRuntime position = positionKey == 0 ? null : runtime.position(positionKey);
        if (position == null) return null;
        if (position.userId() != userId) throw new IllegalStateException("ADL query position route differs");
        // 本仓在查询准备时捕获的行情引用。
        MarkPriceRuntime mark = markets.mark(position.symbolId());
        // 本仓在查询准备时捕获的配置。
        CoreInstrument instrument = markets.instrument(position.symbolId());
        if (position.signedQuantitySteps() == 0 || !identities.asset(position.assetId()).equals(asset)
                || mark == null || instrument == null
                || !(instrument.contractType().isPerpetual() || instrument.contractType().isDelivery()
                || instrument.contractType().isOption()) || !instrument.settleAsset().equals(asset)) return null;
        // 目标持仓当前正的未实现盈利，非正值不能进入 ADL 候选。
        long profit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        if (profit <= 0) return null;
        // 本仓按标记价和合约精度算出的名义金额，供风险输出或 ADL 评分使用。
        long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                instrument.contractType(), position.signedQuantitySteps(), mark.markPriceTicks(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        // ADL 杠杆评分使用的保证金基数，逐仓取本仓保证金，全仓取账户该资产总余额。
        long margin = position.marginMode() == CoreMarginMode.ISOLATED
                ? position.positionMarginUnits() : totalBalance(runtime, userId, asset, markets);
        // 盈利相对名义金额的 ppm 比率，参与 ADL 优先分数。
        long profitRate = ratio(profit, notional);
        // 名义金额相对保证金基数的 ppm 杠杆率，基数不正时用最大 long。
        long leverage = margin <= 0 ? Long.MAX_VALUE : ratio(notional, margin);
        // 盈利率乘杠杆率后按 ppm 缩放的 ADL 分数，中间乘法溢出时封顶。
        long priority = multiplyDivideCapped(profitRate, leverage, PPM);
        return new CoreAdlCandidateView(userId, instrument.instrumentId(), asset, position.marginMode(),
                position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                mark.markPriceTicks(), mark.priceSequence(), notional, profit, margin, profitRate, leverage, priority);
    }

    /**
     * 按统一 ADL 比较规则二分插入候选，超过条数限制时删除最末项，调用方传入的列表应已按同一规则排序。
     * @param candidates 已经按 ADL_ORDER 排列的当前 Lane 候选列表
     * @param candidate 本次要按优先级插入的有效盈利候选
     * @param limit 最多返回或保留的候选条数，调用方应提供非负上限
     */
    public static void addAdlCandidate(ArrayList<CoreAdlCandidateView> candidates, CoreAdlCandidateView candidate, int limit) {
        // 候选按统一比较器二分查找的插入位置，未命中时从负返回值还原插入点。
        int insertAt = java.util.Collections.binarySearch(candidates, candidate, ADL_ORDER);
        candidates.add(insertAt < 0 ? -insertAt - 1 : insertAt, candidate);
        if (candidates.size() > limit) candidates.removeLast();
    }

    /**
     * 对各 Lane 已排序的有限候选列表做多路归并，每次选最优头项，达到 limit 或全部耗尽后结束；比较器完全相同时，先遍历到的 Lane 优先。
     * @param laneCandidates 各 Lane 已独立排序并限制数量的候选列表，外层下标就是 Lane 顺序
     * @param limit 最多返回或保留的候选条数，调用方应提供非负上限
     */
    public static List<CoreAdlCandidateView> mergeAdlCandidates(List<? extends List<CoreAdlCandidateView>> laneCandidates,
            int limit) {
        // 各 Lane 候选列表已经取走的数量，下次只比较该 Lane 的当前头项。
        int[] cursors = new int[laneCandidates.size()];
        // 已经归并出的最优候选列表，条数不能超过本次限制。
        ArrayList<CoreAdlCandidateView> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            // 本轮最优头项所属 Lane，-1 表示所有列表均已耗尽。
            int bestLane = -1;
            // 本轮从各 Lane 头项中选出的最优候选，尚未找到时为空。
            CoreAdlCandidateView best = null;
            for (/* 本轮初始化或归并候选列表的账户 Lane 下标。 */ int laneId = 0; laneId < laneCandidates.size(); laneId++) {
                // 当前 Lane 已排序的候选列表，只有游标尚未到末尾才参与比较。
                List<CoreAdlCandidateView> lane = laneCandidates.get(laneId);
                if (cursors[laneId] >= lane.size()) continue;
                // 当前可用的 ADL 候选，缺失、空仓、资产不符或不盈利时为空。
                CoreAdlCandidateView candidate = lane.get(cursors[laneId]);
                if (best == null || ADL_ORDER.compare(candidate, best) < 0) {
                    best = candidate;
                    bestLane = laneId;
                }
            }
            if (bestLane < 0) break;
            result.add(best);
            cursors[bestLane]++;
        }
        return List.copyOf(result);
    }

    /**
     * 同一次查询共用的市场输入，由 Owner 捕获配置查询副本、行情引用和资产编号，派发后只读使用。这里不复制用户账户或持仓，查询结束后由调用方释放引用。
     */
    public static final class MarketInputs {
        /** Owner 捕获的本次查询配置映射，账户 Lane 只按整数币对键读取。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments;
        /** Owner 捕获的本次查询行情引用映射，不随之后的行情替换而更换引用。 */
        private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime> marks;
        /** 本次查询结算资产代码到编号的只读映射，账户 Lane 不需要再扩展全局身份表。 */
        private final java.util.Map<String, Integer> assetIds;

        /**
         * 保存本次查询专用的配置和行情映射，并复制资产编号映射为不可修改 Map；这些输入由捕获方法构造后只读使用。
         * @param instruments 本次查询按币对整数编号保存的配置查询副本映射
         * @param marks 本次查询按币对整数编号保存的行情引用映射
         * @param assetIds 本次查询涉及的结算资产代码到整数编号映射
         */
        private MarketInputs(org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments,
                org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime> marks,
                java.util.Map<String, Integer> assetIds) {
            this.instruments = instruments;
            this.marks = marks;
            this.assetIds = java.util.Map.copyOf(assetIds);
        }

        /**
         * Owner 逐币对捕获当前配置的查询副本与行情引用，跳过未知或重复币对，并记录可用的结算资产编号，让后续账户查询不受 Owner 配置更新影响。
         * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
         * @param identities 币对、资产和持仓业务编号与整数键的映射
         * @param instrumentIds 本次查询需要固定市场输入的外部币对编号集合
         */
        public static MarketInputs capture(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                Iterable<String> instrumentIds) {
            // 本次新建的整数币对配置映射，逐个保存配置查询副本。
            var instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument>();
            // 本次新建的整数币对行情映射，逐个保存捕获时行情引用。
            var marks = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPriceRuntime>();
            // 查询涉及的结算资产编号映射，未知资产不加入。
            var assets = new java.util.HashMap<String, Integer>();
            for (String /* 当前币对的外部编号，用来查配置或捕获市场输入。 */ instrumentId : instrumentIds) {
                // 当前外部币对编号已注册的整数身份，未知或重复身份直接跳过。
                Integer symbolId = identities.findSymbolId(instrumentId);
                if (symbolId == null || instruments.containsKey(symbolId)) continue;
                // Owner 当前持有的币对规范配置，下一步用 captureForQuery 生成本次查询副本。
                CoreInstrument instrument = runtime.instrument(instrumentId);
                instruments.put(symbolId, instrument == null ? null : instrument.captureForQuery());
                marks.put(symbolId, runtime.markPrice(symbolId));
                // 当前配置结算资产的整数身份，缺失时不访问账户余额。
                Integer assetId = instrument == null ? null : identities.findAssetId(instrument.settleAsset());
                if (assetId != null) assets.put(instrument.settleAsset(), assetId);
            }
            return new MarketInputs(instruments, marks, assets);
        }

        /**
         * 按币对整数编号读取这次查询捕获的配置，缺失时返回 null，不再转读后来的全局配置。
         * @param symbolId 本次查询中的币对整数编号
         */
        private CoreInstrument instrument(int symbolId) { return instruments.get(symbolId); }
        /**
         * 按币对整数编号读取这次查询捕获的行情引用，缺失时返回 null。
         * @param symbolId 本次查询中的币对整数编号
         */
        private MarkPriceRuntime mark(int symbolId) { return marks.get(symbolId); }
    }

    /**
     * 从用户该资产可用加冻结总余额中，扣掉逐仓持仓保证金和逐仓委托预占，得到全仓可共享的钱包；索引超限或逐仓占用超过总余额时直接报错。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param identities 币对、资产和持仓业务编号与整数键的映射
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    private static long crossWalletBalance(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId, String asset, MarketInputs markets) {
        // 当前结算资产扣掉逐仓持仓与委托占用后的全仓共享钱包。
        long wallet = totalBalance(runtime, userId, asset, markets);
        if (runtime.reservationCountForUser(userId) > RuntimeOperationalQueryService.MAX_INDEX_SCAN) {
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        }
        for (long /* 候选路由对应的整数持仓键，未注册时为空，或在钱包扣减时表示当前持仓键。 */ positionKey : runtime.positionKeysForUser(userId)) {
            // 当前用户该资产的实际持仓，只扣除逐仓保证金，不把其他资产或全仓持仓再次扣出钱包。
            PositionRuntime position = runtime.position(positionKey);
            if (position != null && position.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(position.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, position.positionMarginUnits());
            }
        }
        for (long /* 当前需要检查是否占用逐仓资金的冻结订单编号。 */ orderId : runtime.reservationIdsForUser(userId)) {
            // 当前订单的资金预占记录，只有属于该资产逐仓委托时才扣出全仓钱包。
            ReservationRuntime reservation = runtime.reservation(orderId);
            // 当前预占关联的订单，用它的保证金模式区分逐仓和全仓。
            OrderRuntime order = runtime.order(orderId);
            if (reservation != null && order != null && order.marginMode() == CoreMarginMode.ISOLATED
                    && identities.asset(reservation.assetId()).equals(asset)) {
                wallet = Math.subtractExact(wallet, reservation.reservedUnits());
            }
        }
        if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
        return wallet;
    }

    /**
     * 按结算资产定位用户余额，缺少资产编号或余额记录时按 0，存在时返回可用与冻结金额之和并检查整数溢出。
     * @param runtime 当前查询读取的运行时，调用方必须持有对应账户数据的读取权限
     * @param userId 所属账户 Lane 本次计算的正用户编号
     * @param asset 本次候选或钱包计算限定的结算资产代码，集合查询入口会规范化该代码
     * @param markets Owner 在派发前捕获的非空市场输入
     */
    private static long totalBalance(
            TradingAccountRuntime runtime, long userId, String asset, MarketInputs markets) {
        // 使用本次查询捕获的资产编号；缺少编号就不读取余额。
        Integer assetId = markets.assetIds.get(asset);
        // 用户在目标结算资产的余额，缺失时查询按零余额处理。
        BalanceRuntime balance = assetId == null ? null : runtime.balance(userId, assetId);
        return balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
    }

    /**
     * 把正的分子除以正的分母并放大一百万倍，任一不为正则返回 0，用于 ADL 盈利率和杠杆率。
     * @param numerator 需要换算成 ppm 比例的正分子，非正时比例为 0
     * @param denominator 比例换算的正分母，非正时比例为 0
     */
    private static long ratio(long numerator, long denominator) {
        return numerator <= 0 || denominator <= 0 ? 0 : multiplyDivideCapped(numerator, PPM, denominator);
    }

    /**
     * 先精确整数乘法再做整数除法，发生乘法溢出等算术异常时返回最大 long；按中间运算是否溢出来封顶，不重试大整数计算。
     * @param left 缩放乘法的第一个整数因子
     * @param right 缩放乘法的第二个整数因子
     * @param divisor 乘积缩放所用的正整数除数
     */
    private static long multiplyDivideCapped(long left, long right, long divisor) {
        try {
            return Math.multiplyExact(left, right) / divisor;
        } catch (/* 缩放乘法或除法发生算术异常，返回最大 long 作为饱和上限。 */ ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 单个持仓在本次查询中的临时估值，先算并保留本仓盈亏、维持保证金和市值变化，再在用户内按结算资产合并全仓风险。
     * @param position 当前账户 Lane 的目标持仓，按它的数量、模式与入场信息估值
     * @param instrument 该持仓本次估值采用的配置查询副本或当前有效配置
     * @param mark 该持仓本次估值采用的行情引用
     * @param pnl 本仓在标记价下的未实现盈亏，单位为结算资产最小单位
     * @param maintenance 本仓自身要求的维持保证金，合并全仓时还会按资产累计
     * @param equityDelta 本仓计入权益的变化，期货用未实现盈亏，期权用带方向市值
     * @param notional 本仓按标记价换算的名义金额，单位按产品的结算口径
     */
    private record PositionValuation(PositionRuntime position, CoreInstrument instrument, MarkPriceRuntime mark,
                                     long pnl, long maintenance, long equityDelta, long notional) {
    }
}
