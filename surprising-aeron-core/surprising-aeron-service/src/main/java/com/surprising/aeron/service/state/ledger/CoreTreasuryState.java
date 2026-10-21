package com.surprising.aeron.service.state.ledger;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.snapshot.StateMapSupport;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 快照、恢复和冷状态推进使用的系统账本值。保存七类有符号资产余额，以及资金费和生命周期完成编号、分页进度；修改入口返回新值，不替代 TreasuryRuntime 在线单写者。普通 Map 会规范化并冻结，既有冻结或延迟状态 Map 沿 StateMapSupport 的可信边界复用，不在每次构造全表重新校验。
 * @param feeBalances 按规范资产名保存的有符号手续费余额
 * @param insuranceBalances 保险基金有符号余额
 * @param insuranceDeficits 保险亏空有符号余额
 * @param liquidationFeeBalances 独立强平费余额
 * @param fundingResidualBalances 资金费残差余额
 * @param roundingResidualBalances 舍入残差余额
 * @param clearingPnlBalances 系统清算盈亏余额
 * @param fundingSettlements 业务币对到最近完成资金费编号的状态 Map
 * @param lifecycleSettlements 业务币对到最近完成交割或行权编号的状态 Map
 * @param fundingProgress 业务币对到当前资金费分页进度的状态 Map
 * @param lifecycleProgress 业务币对到当前生命周期分页进度的状态 Map
 */
public record CoreTreasuryState(
        Map<String, Long> feeBalances,
        Map<String, Long> insuranceBalances,
        Map<String, Long> insuranceDeficits,
        Map<String, Long> liquidationFeeBalances,
        Map<String, Long> fundingResidualBalances,
        Map<String, Long> roundingResidualBalances,
        Map<String, Long> clearingPnlBalances,
        Map<String, Long> fundingSettlements,
        Map<String, Long> lifecycleSettlements,
        Map<String, FundingProgress> fundingProgress,
        Map<String, LifecycleProgress> lifecycleProgress) {

    /**
     * 要求全部状态列非空，七类金额均按允许正负值规则规范化，完成编号和进度按各自状态 Map 边界冻结；不要求保险或费用在此统一非负。
     */
    public CoreTreasuryState {
        if (feeBalances == null || insuranceBalances == null || insuranceDeficits == null
                || liquidationFeeBalances == null || fundingResidualBalances == null
                || roundingResidualBalances == null || clearingPnlBalances == null
                || fundingSettlements == null || lifecycleSettlements == null || fundingProgress == null
                || lifecycleProgress == null) {
            throw new IllegalArgumentException("invalid treasury state");
        }
        feeBalances = normalized(feeBalances, true);
        insuranceBalances = normalized(insuranceBalances, true);
        insuranceDeficits = normalized(insuranceDeficits, true);
        liquidationFeeBalances = normalized(liquidationFeeBalances, true);
        fundingResidualBalances = normalized(fundingResidualBalances, true);
        roundingResidualBalances = normalized(roundingResidualBalances, true);
        clearingPnlBalances = normalized(clearingPnlBalances, true);
        fundingSettlements = markers(fundingSettlements);
        lifecycleSettlements = markers(lifecycleSettlements);
        fundingProgress = progresses(fundingProgress);
        lifecycleProgress = lifecycleProgresses(lifecycleProgress);
    }

    /**
     * 从费用、保险、亏空及完成编号构造冷账本，资金费和生命周期进度默认空。
     * @param feeBalances 按规范资产名保存的有符号手续费余额
     * @param insuranceBalances 保险基金有符号余额
     * @param insuranceDeficits 保险亏空有符号余额
     * @param fundingSettlements 业务币对到最近完成资金费编号的状态 Map
     * @param lifecycleSettlements 业务币对到最近完成交割或行权编号的状态 Map
     */
    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements) {
        this(feeBalances, insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements,
                Map.of(), Map.of());
    }

    /**
     * 在基础资金及完成编号上带入资金费进度，生命周期进度默认空。
     * @param feeBalances 按规范资产名保存的有符号手续费余额
     * @param insuranceBalances 保险基金有符号余额
     * @param insuranceDeficits 保险亏空有符号余额
     * @param fundingSettlements 业务币对到最近完成资金费编号的状态 Map
     * @param lifecycleSettlements 业务币对到最近完成交割或行权编号的状态 Map
     * @param fundingProgress 业务币对到当前资金费分页进度的状态 Map
     */
    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements,
                             Map<String, FundingProgress> fundingProgress) {
        this(feeBalances, insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements,
                fundingProgress, Map.of());
    }

    /**
     * 带入基础资金和两类进度，其余四个独立系统子账默认空，委托完整 record 构造。
     * @param feeBalances 按规范资产名保存的有符号手续费余额
     * @param insuranceBalances 保险基金有符号余额
     * @param insuranceDeficits 保险亏空有符号余额
     * @param fundingSettlements 业务币对到最近完成资金费编号的状态 Map
     * @param lifecycleSettlements 业务币对到最近完成交割或行权编号的状态 Map
     * @param fundingProgress 业务币对到当前资金费分页进度的状态 Map
     * @param lifecycleProgress 业务币对到当前生命周期分页进度的状态 Map
     */
    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements,
                             Map<String, FundingProgress> fundingProgress,
                             Map<String, LifecycleProgress> lifecycleProgress) {
        this(feeBalances, insuranceBalances, insuranceDeficits,
                Map.of(), Map.of(), Map.of(), Map.of(),
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
    }

    /**
     * 由七类系统资产余额构造冷账本，两类完成编号及进行中进度均为空。
     * @param feeBalances 按规范资产名保存的有符号手续费余额
     * @param insuranceBalances 保险基金有符号余额
     * @param liquidationFeeBalances 独立强平费余额
     * @param fundingResidualBalances 资金费残差余额
     * @param roundingResidualBalances 舍入残差余额
     * @param clearingPnlBalances 系统清算盈亏余额
     * @param deficitBalances 保险亏空余额的别名输入
     */
    public static CoreTreasuryState ofSubledgers(
            Map<String, Long> feeBalances,
            Map<String, Long> insuranceBalances,
            Map<String, Long> liquidationFeeBalances,
            Map<String, Long> fundingResidualBalances,
            Map<String, Long> roundingResidualBalances,
            Map<String, Long> clearingPnlBalances,
            Map<String, Long> deficitBalances) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, deficitBalances,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 创建七类资金、完成编号和进度均为空的冷账本值。
     */
    public static CoreTreasuryState empty() {
        return ofSubledgers(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 以亏空别名返回同一 insuranceDeficits 状态 Map，不复制或增加权威来源。
     */
    public Map<String, Long> deficitBalances() {
        return insuranceDeficits;
    }

    /**
     * 规范化资产并精确增加手续费余额，保留其他状态列，返回新冷账本。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustFee(String asset, long deltaUnits) {
        return new CoreTreasuryState(adjust(feeBalances, asset, deltaUnits, true),
                insuranceBalances, insuranceDeficits, liquidationFeeBalances, fundingResidualBalances,
                roundingResidualBalances, clearingPnlBalances, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    /**
     * 精确增加保险基金有符号余额，返回保留其他子账和进度的新值。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustInsurance(String asset, long deltaUnits) {
        return new CoreTreasuryState(feeBalances, adjust(insuranceBalances, asset, deltaUnits, true),
                insuranceDeficits, liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances,
                clearingPnlBalances, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    /**
     * 精确调整独立强平费子账并返回新值，不自动并入保险基金。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustLiquidationFee(String asset, long deltaUnits) {
        return withSubledger(liquidationFeeBalances, adjust(liquidationFeeBalances, asset, deltaUnits, true), 0);
    }

    /**
     * 精确调整资金费残差子账。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustFundingResidual(String asset, long deltaUnits) {
        return withSubledger(fundingResidualBalances, adjust(fundingResidualBalances, asset, deltaUnits, true), 1);
    }

    /**
     * 精确调整舍入残差子账。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustRoundingResidual(String asset, long deltaUnits) {
        return withSubledger(roundingResidualBalances, adjust(roundingResidualBalances, asset, deltaUnits, true), 2);
    }

    /**
     * 精确调整系统清算盈亏子账。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustClearingPnl(String asset, long deltaUnits) {
        return withSubledger(clearingPnlBalances, adjust(clearingPnlBalances, asset, deltaUnits, true), 3);
    }

    /**
     * 精确调整保险亏空有符号余额，其他状态列保留。
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     */
    public CoreTreasuryState adjustDeficit(String asset, long deltaUnits) {
        return new CoreTreasuryState(feeBalances, insuranceBalances,
                adjust(insuranceDeficits, asset, deltaUnits, true), liquidationFeeBalances,
                fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
    }

    /**
     * 写入严格递增的资金费完成编号，并从新值中移除该币对当前资金费进度。
     * @param instrumentId 需要读写的业务币对名称
     * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
     */
    public CoreTreasuryState recordFunding(String instrumentId, long settlementId) {
        // 从资金费进度建立的可写增量 Map，或当前回调校验的非空进度记录。
        Map<String, FundingProgress> progress = StateMapSupport.delta(fundingProgress);
        progress.remove(OrderReservation.requireInstrumentId(instrumentId));
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                recordMarker(fundingSettlements, instrumentId, settlementId), lifecycleSettlements, progress,
                lifecycleProgress);
    }

    /**
     * 写入严格递增的交割或行权完成编号，并移除该币对生命周期进度。
     * @param instrumentId 需要读写的业务币对名称
     * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
     */
    public CoreTreasuryState recordLifecycle(String instrumentId, long settlementId) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, recordMarker(lifecycleSettlements, instrumentId, settlementId), fundingProgress,
                clearLifecycleProgress(instrumentId));
    }

    /**
     * 校验并规范化业务币对名称后读取资金费进度，缺失返回空。
     * @param instrumentId 需要读写的业务币对名称
     */
    public FundingProgress fundingProgress(String instrumentId) {
        return fundingProgress.get(OrderReservation.requireInstrumentId(instrumentId));
    }

    /**
     * 校验业务币对名称后读取生命周期进度，缺失返回空。
     * @param instrumentId 需要读写的业务币对名称
     */
    public LifecycleProgress lifecycleProgress(String instrumentId) {
        return lifecycleProgress.get(OrderReservation.requireInstrumentId(instrumentId));
    }

    /**
     * 读取该币对最近完成的资金费编号，缺失为零。
     * @param instrumentId 需要读写的业务币对名称
     */
    public long fundingSettlement(String instrumentId) {
        return fundingSettlements.getOrDefault(OrderReservation.requireInstrumentId(instrumentId), 0L);
    }

    /**
     * 读取该币对最近完成的生命周期编号，缺失为零。
     * @param instrumentId 需要读写的业务币对名称
     */
    public long lifecycleSettlement(String instrumentId) {
        return lifecycleSettlements.getOrDefault(OrderReservation.requireInstrumentId(instrumentId), 0L);
    }

    /**
     * 在原进度 Map 的增量后继中写入或删除指定币对资金费进度，返回其他状态列不变的新值。
     * @param instrumentId 需要读写的业务币对名称
     * @param progress 待设置或校验的对应进度值，with 入口可传空表示删除
     */
    public CoreTreasuryState withFundingProgress(String instrumentId, FundingProgress progress) {
        // 已经通过业务校验的规范币对名称。
        String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
        // 本次写入后的进度增量 Map，或资产精确加法的新金额，按所在入口区分。
        Map<String, FundingProgress> next = StateMapSupport.delta(fundingProgress);
        if (progress == null) next.remove(normalizedSymbol);
        else next.put(normalizedSymbol, progress);
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, next, lifecycleProgress);
    }

    /**
     * 在生命周期进度增量后继中写入或删除当前币对进度，不改变已完成编号。
     * @param instrumentId 需要读写的业务币对名称
     * @param progress 待设置或校验的对应进度值，with 入口可传空表示删除
     */
    public CoreTreasuryState withLifecycleProgress(String instrumentId, LifecycleProgress progress) {
        // 已经通过业务校验的规范币对名称。
        String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
        // 本次写入后的进度增量 Map，或资产精确加法的新金额，按所在入口区分。
        Map<String, LifecycleProgress> next = StateMapSupport.delta(lifecycleProgress);
        if (progress == null) next.remove(normalizedSymbol);
        else next.put(normalizedSymbol, progress);
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, fundingProgress, next);
    }

    /**
     * 合并七类余额状态 Map 暴露的变化键，返回按资产排序的只读集合，不包含纯进度变化。
     */
    public Set<String> changedAssets() {
        // 手续费状态 Map 暴露的本次变化资产键集合。
        Set<String> fee = StateMapSupport.changedKeys(feeBalances);
        // 保险余额状态 Map 暴露的本次变化资产键集合。
        Set<String> insurance = StateMapSupport.changedKeys(insuranceBalances);
        // 保险亏空状态 Map 暴露的本次变化资产键集合。
        Set<String> deficits = StateMapSupport.changedKeys(insuranceDeficits);
        // 七个子账变化键合并后按资产名称排序的集合。
        TreeSet<String> assets = new TreeSet<>();
        assets.addAll(fee);
        assets.addAll(insurance);
        assets.addAll(deficits);
        assets.addAll(StateMapSupport.changedKeys(liquidationFeeBalances));
        assets.addAll(StateMapSupport.changedKeys(fundingResidualBalances));
        assets.addAll(StateMapSupport.changedKeys(roundingResidualBalances));
        assets.addAll(StateMapSupport.changedKeys(clearingPnlBalances));
        return Collections.unmodifiableSet(assets);
    }

    /**
     * 按内部列号替换强平费、资金费残差、舍入或清算子账；原新 Map 同一引用时直接复用本值。
     * @param current 当前独立子账 Map，用于判断是否可以直接复用本对象
     * @param next 本次调整后的独立子账状态 Map
     * @param ledger 内部子账列号：零强平费、一资金费残差、二舍入残差、三清算盈亏
     */
    private CoreTreasuryState withSubledger(Map<String, Long> current, Map<String, Long> next, int ledger) {
        if (current == next) return this;
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                ledger == 0 ? next : liquidationFeeBalances,
                ledger == 1 ? next : fundingResidualBalances,
                ledger == 2 ? next : roundingResidualBalances,
                ledger == 3 ? next : clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
    }

    /**
     * 规范化资产后精确计算新金额，按 signed 开关决定是否允许负值，在源 Map 的增量后继中写入或删零。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     * @param asset 要校验或调整的资产名称
     * @param deltaUnits 本次有符号金额增减
     * @param signed 是否允许负金额，当前七类资金列按 true 处理
     */
    private static Map<String, Long> adjust(
            Map<String, Long> source,
            String asset,
            long deltaUnits,
            boolean signed) {
        // 本条金额使用的规范资产名称。
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        // 本次写入后的进度增量 Map，或资产精确加法的新金额，按所在入口区分。
        long next = Math.addExact(source.getOrDefault(normalizedAsset, 0L), deltaUnits);
        if (!signed && next < 0) {
            throw new IllegalArgumentException("treasury balance must not be negative");
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, Long> result = StateMapSupport.delta(source);
        putOrRemove(result, normalizedAsset, next);
        return result;
    }

    /**
     * 冻结资产金额 Map：已冻结或延迟 Map 复用可信边界，增量只校验存活变化键，普通 Map 则规范化全部非零资产并拒绝非零重复规范键。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     * @param signed 是否允许负金额，当前七类资金列按 true 处理
     */
    private static Map<String, Long> normalized(Map<String, Long> source, boolean signed) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
        if (StateMapSupport.isDelta(source)) {
            for (Object /* 当前增量中需要校验的变化键，删除项不再检查旧值。 */ key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) {
                    validateNormalized((String) key, source.get(key), signed);
                }
            }
            return StateMapSupport.freezeSorted(source);
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, Long> result = new TreeMap<>();
        source.forEach((/* 普通金额 Map 当前回调输入的原资产名称。 */ asset, /* 普通金额 Map 当前回调输入的可空金额。 */ units) -> {
            // 本条金额使用的规范资产名称。
            String normalizedAsset = AssetBalance.normalizeAsset(asset);
            if (units == null || (!signed && units < 0)) {
                throw new IllegalArgumentException("invalid treasury units");
            }
            if (units != 0 && result.put(normalizedAsset, units) != null) {
                throw new IllegalArgumentException("duplicate treasury asset");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    /**
     * 冻结币对完成编号 Map，普通 Map 全量校验规范名称及正编号，增量只校验存活变化项，已冻结或延迟 Map 沿原可信边界复用。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     */
    private static Map<String, Long> markers(Map<String, Long> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
        if (StateMapSupport.isDelta(source)) {
            for (Object /* 当前增量中需要校验的变化键，删除项不再检查旧值。 */ key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) {
                    // 当前完成编号 Map 变化项或回调中的结算编号。
                    Long settlementId = source.get(key);
                    if (!OrderReservation.requireInstrumentId((String) key).equals(key)
                            || settlementId == null || settlementId <= 0) {
                        throw new IllegalArgumentException("invalid settlement marker");
                    }
                }
            }
            return StateMapSupport.freezeSorted(source);
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, Long> result = new TreeMap<>();
        source.forEach((/* 当前普通进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 当前完成编号 Map 变化项或回调中的结算编号。 */ settlementId) -> {
            // 已经通过业务校验的规范币对名称。
            String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
            if (settlementId == null || settlementId <= 0 || result.put(normalizedSymbol, settlementId) != null) {
                throw new IllegalArgumentException("invalid settlement marker");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    /**
     * 冻结资金费进度 Map，普通或增量输入检查对应范围的规范币对及非空值，拒绝普通 Map 规范化后的重复键。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     */
    private static Map<String, FundingProgress> progresses(Map<String, FundingProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
        if (StateMapSupport.isDelta(source)) {
            for (Object /* 当前增量中需要校验的变化键，删除项不再检查旧值。 */ key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) validateProgress((String) key, source.get(key));
            }
            return StateMapSupport.freezeSorted(source);
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, FundingProgress> result = new TreeMap<>();
        source.forEach((/* 当前普通进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 从资金费进度建立的可写增量 Map，或当前回调校验的非空进度记录。 */ progress) -> {
            // 已经通过业务校验的规范币对名称。
            String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
            validateProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate funding progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    /**
     * 按与资金费相同的状态 Map 边界冻结生命周期进度，校验需要检查范围的规范键和非空值。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     */
    private static Map<String, LifecycleProgress> lifecycleProgresses(Map<String, LifecycleProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
        if (StateMapSupport.isDelta(source)) {
            for (Object /* 当前增量中需要校验的变化键，删除项不再检查旧值。 */ key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) validateLifecycleProgress((String) key, source.get(key));
            }
            return StateMapSupport.freezeSorted(source);
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, LifecycleProgress> result = new TreeMap<>();
        source.forEach((/* 当前普通进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 从资金费进度建立的可写增量 Map，或当前回调校验的非空进度记录。 */ progress) -> {
            // 已经通过业务校验的规范币对名称。
            String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
            validateLifecycleProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate lifecycle progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    /**
     * 创建生命周期进度的增量后继并删除指定币对，不直接修改原值。
     * @param instrumentId 需要读写的业务币对名称
     */
    private Map<String, LifecycleProgress> clearLifecycleProgress(String instrumentId) {
        // 本次写入后的进度增量 Map，或资产精确加法的新金额，按所在入口区分。
        Map<String, LifecycleProgress> next = StateMapSupport.delta(lifecycleProgress);
        next.remove(OrderReservation.requireInstrumentId(instrumentId));
        return next;
    }

    /**
     * 要求本次结算编号严格大于已有编号，否则按 STALE_SETTLEMENT_ID 拒绝；在增量后继记录新编号。
     * @param source 本次需要读取、规范化或建立增量后继的原状态 Map
     * @param instrumentId 需要读写的业务币对名称
     * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
     */
    private static Map<String, Long> recordMarker(Map<String, Long> source, String instrumentId, long settlementId) {
        // 已经通过业务校验的规范币对名称。
        String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
        // 当前币对已经保存的完成编号，缺失为零。
        long current = source.getOrDefault(normalizedSymbol, 0L);
        if (settlementId <= current) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        // 本次建立的增量 Map 或用于全量规范化的有序 Map。
        Map<String, Long> result = StateMapSupport.delta(source);
        result.put(normalizedSymbol, settlementId);
        return result;
    }

    /**
     * 检查资产名已经规范化、金额非空，signed 为 false 时还拒绝负数；此校验本身不移除零。
     * @param asset 要校验或调整的资产名称
     * @param units 待校验或保存的有符号金额
     * @param signed 是否允许负金额，当前七类资金列按 true 处理
     */
    private static void validateNormalized(String asset, Long units, boolean signed) {
        if (!AssetBalance.normalizeAsset(asset).equals(asset)
                || units == null || (!signed && units < 0)) {
            throw new IllegalArgumentException("invalid treasury units");
        }
    }

    /**
     * 检查资金费进度的业务币对键已经规范化且值非空，不在此重复校验进度 record 内部字段。
     * @param instrumentId 需要读写的业务币对名称
     * @param progress 待设置或校验的对应进度值，with 入口可传空表示删除
     */
    private static void validateProgress(String instrumentId, FundingProgress progress) {
        if (!OrderReservation.requireInstrumentId(instrumentId).equals(instrumentId) || progress == null) {
            throw new IllegalArgumentException("invalid funding progress");
        }
    }

    /**
     * 检查生命周期进度键规范化且值非空。
     * @param instrumentId 需要读写的业务币对名称
     * @param progress 待设置或校验的对应进度值，with 入口可传空表示删除
     */
    private static void validateLifecycleProgress(String instrumentId, LifecycleProgress progress) {
        if (!OrderReservation.requireInstrumentId(instrumentId).equals(instrumentId) || progress == null) {
            throw new IllegalArgumentException("invalid lifecycle progress");
        }
    }

    /**
     * 向本次可写增量 Map 保存非零金额，金额为零删除该资产键。
     * @param values 本次可写的增量金额 Map
     * @param asset 要校验或调整的资产名称
     * @param units 待校验或保存的有符号金额
     */
    private static void putOrRemove(Map<String, Long> values, String asset, long units) {
        if (units == 0) {
            values.remove(asset);
        } else {
            values.put(asset, units);
        }
    }

    /**
     * 快照及冷状态资金费分页进度，保留同一结算的费率、Lane、用户游标及价格证据。
     * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
     * @param fundingRatePpm 资金费百万分比费率，绝对值不超过一百万
     * @param accountLaneId 分页当前账户 Lane，record 校验范围零至六十三
     * @param nextCursorUserId 后续账户处理的非负用户游标，撤单未完成阶段须为零
     * @param commandId 当前结算或分页命令 UUID
     * @param markPriceTicks 资金费计算采用的正标记价
     * @param priceSequence 对应正价格版本序号
     */
    public record FundingProgress(long settlementId, long fundingRatePpm,
                                 int accountLaneId, long nextCursorUserId, UUID commandId,
                                 long markPriceTicks, long priceSequence) {
        /**
         * 校验正结算编号、费率绝对值上限、零至六十三 Lane、非负用户游标、非空命令及正标记价和价格序号。
         */
        public FundingProgress {
            if (settlementId <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorUserId < 0 || commandId == null || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    /**
     * 快照及冷状态交割或期权到期分页进度，保存撤单与账户处理阶段、游标及当前页保险需求。
     * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
     * @param settlementPriceTicks 生命周期采用的非负结算价
     * @param optionCashUnitsPerContract 期权每合约非负现金结算额
     * @param ordersComplete 撤单阶段是否已结束，决定合法游标及保险需求范围
     * @param accountLaneId 分页当前账户 Lane，record 校验范围零至六十三
     * @param nextCursorOrderId 后续撤单的非负订单游标，撤单完成后须为零
     * @param nextCursorUserId 后续账户处理的非负用户游标，撤单未完成阶段须为零
     * @param commandId 当前结算或分页命令 UUID
     * @param requiredInsuranceUnits 本页非负保险需求，撤单结束后才允许非零
     */
    public record LifecycleProgress(long settlementId, long settlementPriceTicks,
                                   long optionCashUnitsPerContract, boolean ordersComplete,
                                   int accountLaneId, long nextCursorOrderId,
                                   long nextCursorUserId, UUID commandId, long requiredInsuranceUnits) {
        /**
         * 使用显式账户 Lane 和阶段游标构造生命周期进度，默认保险需求为零。
         * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
         * @param settlementPriceTicks 生命周期采用的非负结算价
         * @param optionCashUnitsPerContract 期权每合约非负现金结算额
         * @param ordersComplete 撤单阶段是否已结束，决定合法游标及保险需求范围
         * @param accountLaneId 分页当前账户 Lane，record 校验范围零至六十三
         * @param nextCursorOrderId 后续撤单的非负订单游标，撤单完成后须为零
         * @param nextCursorUserId 后续账户处理的非负用户游标，撤单未完成阶段须为零
         * @param commandId 当前结算或分页命令 UUID
         */
        public LifecycleProgress(long settlementId, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, boolean ordersComplete, int accountLaneId,
                                 long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, settlementPriceTicks, optionCashUnitsPerContract, ordersComplete,
                    accountLaneId, nextCursorOrderId, nextCursorUserId, commandId, 0);
        }
        /**
         * 构造已完成撤单、账户 Lane 零的用户分页进度，订单游标及保险需求默认零。
         * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
         * @param settlementPriceTicks 生命周期采用的非负结算价
         * @param optionCashUnitsPerContract 期权每合约非负现金结算额
         * @param nextCursorUserId 后续账户处理的非负用户游标，撤单未完成阶段须为零
         * @param commandId 当前结算或分页命令 UUID
         */
        public LifecycleProgress(long settlementId, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, long nextCursorUserId, UUID commandId) {
            this(settlementId, settlementPriceTicks, optionCashUnitsPerContract,
                    true, 0, 0, nextCursorUserId, commandId);
        }

        /**
         * 校验非负价格、权利金及保险额、有效 Lane 与非负游标；撤单未结束时用户游标及保险额为零，撤单结束时订单游标为零。
         */
        public LifecycleProgress {
            if (settlementId <= 0 || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || optionCashUnitsPerContract < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)
                    || commandId == null) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
        /**
         * 省略账户 Lane 时采用零，委托带阶段和游标的构造入口。
         * @param settlementId 本次资金费或生命周期结算编号，完成记录入口要求严格递增
         * @param settlementPriceTicks 生命周期采用的非负结算价
         * @param optionCashUnitsPerContract 期权每合约非负现金结算额
         * @param ordersComplete 撤单阶段是否已结束，决定合法游标及保险需求范围
         * @param nextCursorOrderId 后续撤单的非负订单游标，撤单完成后须为零
         * @param nextCursorUserId 后续账户处理的非负用户游标，撤单未完成阶段须为零
         * @param commandId 当前结算或分页命令 UUID
         */
        public LifecycleProgress(long settlementId, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, boolean ordersComplete,
                                 long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, settlementPriceTicks, optionCashUnitsPerContract,
                    ordersComplete, 0, nextCursorOrderId, nextCursorUserId, commandId);
        }
    }
}
