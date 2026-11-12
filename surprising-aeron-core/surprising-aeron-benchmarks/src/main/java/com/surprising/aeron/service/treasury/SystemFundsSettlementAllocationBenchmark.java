package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.TradeSettlement;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Treasury 的资金写入和守恒校验成本；不代表 Aeron 单节点业务吞吐。 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class SystemFundsSettlementAllocationBenchmark {
    @Param({"1", "16"}) public int assetCount;
    private SystemFunds systemFunds;
    private SystemFundsChange delta;
    private FundsTotal funds;
    private int nextAsset;
    private long direction = 1;

    @Setup
    public void setup() {
        systemFunds = new SystemFunds();
        delta = new SystemFundsChange(Math.max(SystemFundsChange.SINGLE_COMMAND_CAPACITY, assetCount));
        funds = new FundsTotal(8);
        for (int asset = 0; asset < assetCount; asset++) {
            systemFunds.setFee(asset, 1_000_000);
            systemFunds.setInsurance(asset, 1_000_000, 1_000_000);
            systemFunds.setLiquidationFee(asset, 1_000_000);
            systemFunds.setFundingResidual(asset, 1_000_000);
            systemFunds.setRoundingResidual(asset, 1_000_000);
            systemFunds.setClearingPnl(asset, -1_000_000);
        }
        systemFunds.clearChangedKeys();
        for (int iteration = 0; iteration < assetCount * 2; iteration++) applyAndClear();
    }

    @Benchmark
    public long settleFeesAndResiduals() {
        int asset = nextAsset;
        long sign = direction;
        applyContributions(asset, sign);
        funds.add(asset, TradeSettlement.Posting.OwnerKind.USER, 42, TradeSettlement.Posting.Subledger.AVAILABLE, -11 * sign);
        systemFunds.appendFundsDelta(funds);
        funds.requireConserved(false);
        funds.clear();
        return finishTransaction(asset);
    }

    /** 单独测量实际资金写入、事务前值捕获与回收成本。 */
    @Benchmark
    public long applyAndClear() {
        int asset = nextAsset;
        applyContributions(asset, direction);
        return finishTransaction(asset);
    }

    /** One transaction exercises multi-asset overflow preflight and all seven subledger postings. */
    @Benchmark
    public long settleAllAssets() {
        long sign = direction;
        for (int asset = 0; asset < assetCount; asset++) addContributions(asset, sign);
        delta.apply(systemFunds);
        for (int asset = 0; asset < assetCount; asset++) {
            systemFunds.setLiquidationFee(asset, systemFunds.liquidationFee(asset) + 2 * sign);
            funds.add(asset, TradeSettlement.Posting.OwnerKind.USER, 42, TradeSettlement.Posting.Subledger.AVAILABLE, -11 * sign);
        }
        systemFunds.appendFundsDelta(funds);
        funds.requireConserved(false);
        funds.clear();
        delta.clear();
        systemFunds.clearChangedKeys();
        direction = -direction;
        return systemFunds.fee(0);
    }

    private void applyContributions(int asset, long sign) {
        addContributions(asset, sign);
        delta.apply(systemFunds);
        systemFunds.setLiquidationFee(asset, systemFunds.liquidationFee(asset) + 2 * sign);
    }

    private void addContributions(int asset, long sign) {
        delta.addFee(asset, 7 * sign);
        delta.addInsurance(asset, 11 * sign);
        delta.addDeficit(asset, 3 * sign);
        delta.addFundingResidual(asset, -2 * sign);
        delta.addRoundingResidual(asset, sign);
        delta.addClearing(asset, -5 * sign);
    }

    private long finishTransaction(int asset) {
        delta.clear();
        systemFunds.clearChangedKeys();
        if (++nextAsset == assetCount) {
            nextAsset = 0;
            direction = -direction;
        }
        return systemFunds.fee(asset);
    }

    @TearDown
    public void verifyBalances() {
        if (systemFunds.hasChangedValues()) throw new IllegalStateException("uncommitted Treasury funds");
        for (int asset = 0; asset < assetCount; asset++) {
            long feeDelta = systemFunds.fee(asset) - 1_000_000;
            if (feeDelta != 0 && feeDelta != 7) throw new IllegalStateException("fee drift");
            long sign = feeDelta / 7;
            if (systemFunds.insurance(asset) != 1_000_000 + 11 * sign
                    || systemFunds.insuranceDeficit(asset) != 1_000_000 + 3 * sign
                    || systemFunds.liquidationFee(asset) != 1_000_000 + 2 * sign
                    || systemFunds.fundingResidual(asset) != 1_000_000 - 2 * sign
                    || systemFunds.roundingResidual(asset) != 1_000_000 + sign
                    || systemFunds.clearingPnl(asset) != -1_000_000 - 5 * sign) {
                throw new IllegalStateException("Treasury subledger drift");
            }
        }
    }
}
