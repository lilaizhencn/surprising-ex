package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.settlement.FundsPosting;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Treasury 的资金写入和守恒校验成本；不代表 Aeron 单节点业务吞吐。 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
@State(Scope.Thread)
public class TreasurySettlementAllocationBenchmark {
    @Param({"1", "16"}) public int assetCount;
    private TreasuryRuntime treasury;
    private RuntimeTreasuryDelta delta;
    private RuntimeFundsAccumulator funds;
    private int nextAsset;
    private long direction = 1;

    @Setup
    public void setup() {
        treasury = new TreasuryRuntime();
        delta = new RuntimeTreasuryDelta();
        funds = new RuntimeFundsAccumulator(8);
        for (int asset = 0; asset < assetCount; asset++) {
            treasury.setFee(asset, 1_000_000);
            treasury.setInsurance(asset, 1_000_000, 1_000_000);
            treasury.setLiquidationFee(asset, 1_000_000);
            treasury.setFundingResidual(asset, 1_000_000);
            treasury.setRoundingResidual(asset, 1_000_000);
            treasury.setClearingPnl(asset, -1_000_000);
        }
        treasury.clearChangedKeys();
        for (int iteration = 0; iteration < assetCount * 2; iteration++) applyAndClear();
    }

    @Benchmark
    public long settleFeesAndResiduals() {
        int asset = nextAsset;
        long sign = direction;
        applyContributions(asset, sign);
        funds.add(asset, FundsPosting.OwnerKind.USER, 42, FundsPosting.Subledger.AVAILABLE, -11 * sign);
        treasury.appendFundsDelta(funds);
        funds.requireConserved(false);
        funds.clear();
        return finishTransaction(asset);
    }

    /** 与修改前的账本接口一致，便于隔离比较前值捕获及写入的分配成本。 */
    @Benchmark
    public long applyAndClear() {
        int asset = nextAsset;
        applyContributions(asset, direction);
        return finishTransaction(asset);
    }

    private void applyContributions(int asset, long sign) {
        delta.addFee(asset, 7 * sign);
        delta.addInsurance(asset, 11 * sign);
        delta.addDeficit(asset, 3 * sign);
        delta.addFundingResidual(asset, -2 * sign);
        delta.addRoundingResidual(asset, sign);
        delta.addClearing(asset, -5 * sign);
        delta.apply(treasury);
        treasury.setLiquidationFee(asset, treasury.liquidationFee(asset) + 2 * sign);
    }

    private long finishTransaction(int asset) {
        delta.clear();
        treasury.clearChangedKeys();
        if (++nextAsset == assetCount) {
            nextAsset = 0;
            direction = -direction;
        }
        return treasury.fee(asset);
    }

    @TearDown
    public void verifyBalances() {
        if (!treasury.changedAssets().isEmpty()) throw new IllegalStateException("uncommitted Treasury funds");
        for (int asset = 0; asset < assetCount; asset++) {
            long feeDelta = treasury.fee(asset) - 1_000_000;
            if (feeDelta != 0 && feeDelta != 7) throw new IllegalStateException("fee drift");
            long sign = feeDelta / 7;
            if (treasury.insurance(asset) != 1_000_000 + 11 * sign
                    || treasury.insuranceDeficit(asset) != 1_000_000 + 3 * sign
                    || treasury.liquidationFee(asset) != 1_000_000 + 2 * sign
                    || treasury.fundingResidual(asset) != 1_000_000 - 2 * sign
                    || treasury.roundingResidual(asset) != 1_000_000 + sign
                    || treasury.clearingPnl(asset) != -1_000_000 - 5 * sign) {
                throw new IllegalStateException("Treasury subledger drift");
            }
        }
    }
}
