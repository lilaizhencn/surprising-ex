package com.surprising.aeron.service.treasury;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.snapshot.state.StateMapSupport;
import java.util.Map;
import java.util.TreeMap;

/**
 * 快照与恢复使用的系统账本值。保存七类有符号资产余额，以及资金费和生命周期完成编号、分页进度；在线修改由 TreasuryRuntime 单写者执行。普通 Map 会规范化并冻结，既有冻结状态 Map 沿 StateMapSupport 的可信边界复用，不在每次构造全表重新校验。
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
     * 冻结资产金额 Map：已冻结 Map 复用可信边界，其他 Map 规范化全部非零资产并拒绝非零重复规范键。
     * @param source 本次需要规范化并冻结的完整状态 Map
     * @param signed 是否允许负金额，当前七类资金列按 true 处理
     */
    private static Map<String, Long> normalized(Map<String, Long> source, boolean signed) {
        if (StateMapSupport.isFrozen(source)) return source;
        // 本次建立的用于全量规范化的有序 Map。
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
     * 冻结币对完成编号 Map，完整校验规范名称及正编号，已冻结 Map 沿原可信边界复用。
     * @param source 本次需要规范化并冻结的完整状态 Map
     */
    private static Map<String, Long> markers(Map<String, Long> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        // 本次建立的用于全量规范化的有序 Map。
        Map<String, Long> result = new TreeMap<>();
        source.forEach((/* 当前完整进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 当前完成编号 Map 变化项或回调中的结算编号。 */ settlementId) -> {
            // 已经通过业务校验的规范币对名称。
            String normalizedSymbol = OrderReservation.requireInstrumentId(instrumentId);
            if (settlementId == null || settlementId <= 0 || result.put(normalizedSymbol, settlementId) != null) {
                throw new IllegalArgumentException("invalid settlement marker");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    /**
     * 冻结资金费进度 Map，完整检查规范币对及非空值，拒绝普通 Map 规范化后的重复键。
     * @param source 本次需要规范化并冻结的完整状态 Map
     */
    private static Map<String, FundingProgress> progresses(Map<String, FundingProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        // 本次建立的用于全量规范化的有序 Map。
        Map<String, FundingProgress> result = new TreeMap<>();
        source.forEach((/* 当前完整进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 当前完整进度表中的非空记录。 */ progress) -> {
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
     * @param source 本次需要规范化并冻结的完整状态 Map
     */
    private static Map<String, LifecycleProgress> lifecycleProgresses(Map<String, LifecycleProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        // 本次建立的用于全量规范化的有序 Map。
        Map<String, LifecycleProgress> result = new TreeMap<>();
        source.forEach((/* 当前完整进度或完成 Map 回调中的币对名称。 */ instrumentId, /* 当前完整进度表中的非空记录。 */ progress) -> {
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

    /** 资金费续页的固定费率、用户游标和计算价格。 */
    public record FundingProgress(long settlementId, long fundingRatePpm,
            long nextCursorUserId, long markPriceTicks) {
        public FundingProgress {
            if (settlementId <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || markPriceTicks <= 0) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    /** 到期结算的固定价格、撤单/用户游标及本页保险需求。 */
    public record LifecycleProgress(long settlementId, long settlementPriceTicks,
            boolean ordersComplete, long nextCursorOrderId, long nextCursorUserId,
            long requiredInsuranceUnits) {
        public LifecycleProgress {
            if (settlementId <= 0 || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
    }
}
