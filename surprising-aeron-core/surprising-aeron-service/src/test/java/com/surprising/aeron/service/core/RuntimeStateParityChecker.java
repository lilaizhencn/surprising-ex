package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.snapshot.state.TradingRuntimeSnapshot;

/**
 * 测试用运行时一致性检查：物化实际运行时，先比较完整冷状态值和业务哈希；不一致时才构造快照并找首个差异，方便定位资金、订单、风险或恢复事实不一致。它不提供独立业务公式。
 */
public final class RuntimeStateParityChecker {

    /**
     * 只提供静态断言和差异诊断，无需创建实例。
     */
    private RuntimeStateParityChecker() {
    }

    /**
     * 要求三个输入非空，物化实际状态并比较值及哈希；不相同则投影预期状态、捕获双方诊断快照后抛出差异异常，相同则返回实际物化结果。失败分支没有另外关闭预期投影，适用于既有测试冷路径。
     * @param expected 需要匹配的完整预期冷状态
     * @param identities 用于物化和失败诊断投影的同一运行时身份表
     * @param actual 需要物化并比较的实际运行时
     */
    public static TradingCoreState assertMatches(
            TradingCoreState expected, RuntimeIdentityRegistry identities, TradingAccountRuntime actual) {
        if (expected == null || identities == null || actual == null) {
            throw new IllegalArgumentException("parity arguments are required");
        }
        // 按同一身份表物化的实际完整冷状态，先做值和哈希比较
        TradingCoreState materialized = RuntimeStateMaterializer.materialize(actual, identities);
        if (!expected.equals(materialized) || expected.businessStateHash() != materialized.businessStateHash()) {
            // 只在失败诊断分支投影的预期运行时，本方法没有另行关闭它
            TradingAccountRuntime projected = RuntimeStateProjector.project(expected, identities);
            // 按预期修订捕获的预期运行时诊断快照
            TradingRuntimeSnapshot expectedSnapshot = RuntimeSnapshotBuilder.capture(projected, expected.revision());
            // 同样按预期修订捕获的实际运行时诊断快照，不是重新决定业务修订
            TradingRuntimeSnapshot actualSnapshot = RuntimeSnapshotBuilder.capture(actual, expected.revision());
            throw new IllegalStateException("runtime parity mismatch at revision " + expected.revision()
                    + ": " + mismatch(expectedSnapshot, actualSnapshot)
                    + " hashes=" + expected.businessStateHash() + '/' + materialized.businessStateHash()
                    + " component=" + componentMismatch(expected, materialized));
        }
        return materialized;
    }

    /**
     * 依固定字段顺序返回首个快照差异名称，部分映射带键值细节；前面都相同则给出生命周期进度映射的比较文本。它是诊断摘要，不取代外层完整状态断言。
     * @param expected 用于诊断的预期运行时快照
     * @param actual 用于诊断的实际运行时快照
     */
    private static String mismatch(TradingRuntimeSnapshot expected, TradingRuntimeSnapshot actual) {
        if (!expected.users().equals(actual.users())) return mapMismatch("users", expected.users(), actual.users());
        if (!expected.balances().equals(actual.balances())) return "balances";
        if (!expected.orders().equals(actual.orders())) return "orders";
        if (!expected.reservations().equals(actual.reservations())) return "reservations";
        if (!expected.clientOrderIndex().equals(actual.clientOrderIndex())) return "client-order-index";
        if (!expected.positions().equals(actual.positions())) return "positions";
        if (!expected.liquidations().equals(actual.liquidations())) {
            return mapMismatch("liquidations", expected.liquidations(), actual.liquidations());
        }
        if (!expected.markPrices().equals(actual.markPrices())) return "mark-prices";
        if (!expected.riskSnapshots().equals(actual.riskSnapshots())) {
            return mapMismatch("risk-snapshots", expected.riskSnapshots(), actual.riskSnapshots());
        }
        if (!expected.riskScans().equals(actual.riskScans())) {
            return mapMismatch("risk-scans", expected.riskScans(), actual.riskScans());
        }
        if (expected.nextLiquidationId() != actual.nextLiquidationId()) return "next-liquidation-id";
        if (!expected.instruments().equals(actual.instruments())) return "instruments";
        if (!expected.leverages().equals(actual.leverages())) return "leverages";
        if (!expected.algoOrders().equals(actual.algoOrders())) return "algo-orders";
        if (!expected.cancelAllAfterTimers().equals(actual.cancelAllAfterTimers())) return "cancel-all-after";
        if (!expected.triggerOrders().equals(actual.triggerOrders())) return "trigger-orders";
        if (!expected.treasury().equals(actual.treasury())) return "treasury";
        if (!expected.fundingSettlements().equals(actual.fundingSettlements())) return "funding-settlements";
        if (!expected.fundingProgress().equals(actual.fundingProgress())) {
            return mapMismatch("funding-progress", expected.fundingProgress(), actual.fundingProgress());
        }
        if (!expected.lifecycleSettlements().equals(actual.lifecycleSettlements())) return "lifecycle-settlements";
        return mapMismatch("lifecycle-progress", expected.lifecycleProgress(), actual.lifecycleProgress());
    }

    /**
     * 先遍历预期映射比较同键值，再遍历实际映射找多余键；都没找到时仅输出名称与双方大小，不调整原映射。预期值为 null 时，缺键与实际 null 在首轮比较中无法区分。
     * @param name 映射分量名称，用于生成可定位的差异信息
     * @param expected 需要逐键比较的预期映射
     * @param actual 需要逐键比较的实际映射
     */
    private static String mapMismatch(String name, java.util.Map<?, ?> expected, java.util.Map<?, ?> actual) {
        for (java.util.Map.Entry<?, ?> /* 当前检查的预期或实际映射条目 */ entry : expected.entrySet()) {
            // 实际映射中同一键对应的值，缺键时为 null，按 Objects.equals 与预期比较
            Object actualValue = actual.get(entry.getKey());
            if (!java.util.Objects.equals(entry.getValue(), actualValue)) {
                return name + " sizes=" + expected.size() + '/' + actual.size() + " key=" + entry.getKey()
                        + " expected=" + entry.getValue() + " actual=" + actualValue;
            }
        }
        for (java.util.Map.Entry<?, ?> /* 当前检查的预期或实际映射条目 */ entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                return name + " sizes=" + expected.size() + '/' + actual.size() + " unexpected=" + entry;
            }
        }
        return name + " sizes=" + expected.size() + '/' + actual.size();
    }

    /**
     * 按产品线、修订及各业务分量顺序返回首个冷状态差异；没有识别出的分量则返回 unknown 供诊断。
     * @param expected 逐分量比较的预期冷状态
     * @param actual 逐分量比较的实际物化冷状态
     */
    private static String componentMismatch(TradingCoreState expected, TradingCoreState actual) {
        if (expected.productLine() != actual.productLine()) return "product-line";
        if (expected.revision() != actual.revision()) return "revision=" + expected.revision() + '/' + actual.revision();
        if (!expected.users().equals(actual.users())) return "users";
        if (!expected.orders().equals(actual.orders())) return "orders";
        if (!expected.riskState().equals(actual.riskState())) return "risk";
        if (!expected.treasuryState().equals(actual.treasuryState())) return "treasury";
        if (!expected.instruments().equals(actual.instruments())) return "instruments";
        if (!expected.leverages().equals(actual.leverages())) return "leverages";
        if (!expected.algoOrders().equals(actual.algoOrders())) return "algo-orders";
        if (!expected.cancelAllAfterTimers().equals(actual.cancelAllAfterTimers())) return "cancel-all-after";
        if (!expected.clientOrderIndex().equals(actual.clientOrderIndex())) return "client-index";
        if (!expected.triggerOrders().equals(actual.triggerOrders())) return "triggers";
        return "unknown";
    }
}
