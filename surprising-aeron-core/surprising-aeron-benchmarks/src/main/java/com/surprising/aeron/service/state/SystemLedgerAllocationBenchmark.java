package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.ledger.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.settlement.FundsPosting;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 实际账本线程处理七个资金子账本；固定256个复用槽，分数不是整链路Core吞吐。 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class SystemLedgerAllocationBenchmark {
    private static final int WINDOW = 256;
    private final TreasuryRuntime treasury = new TreasuryRuntime();
    private final MoneySlot[] slots = new MoneySlot[WINDOW];
    private SystemLedgerLane ledger;
    private long rounds;

    @Setup public void setup() {
        for (int index = 0; index < WINDOW; index++) slots[index] = new MoneySlot();
        ledger = new SystemLedgerLane(treasury, WINDOW, ignored -> { });
    }

    @Benchmark
    @OperationsPerInvocation(WINDOW)
    public long settleAndConserve() {
        for (MoneySlot slot : slots) {
            slot.completed = false;
            while (!ledger.offer(slot)) Thread.onSpinWait();
        }
        long checksum = 0;
        for (MoneySlot slot : slots) {
            while (!slot.completed) { ledger.assertHealthy(); Thread.onSpinWait(); }
            checksum ^= slot.fees;
        }
        rounds++;
        return checksum;
    }

    @TearDown public void verifyBalances() {
        ledger.close();
        long transactions = Math.multiplyExact(rounds, WINDOW);
        if (treasury.fee(0) != transactions || treasury.insurance(0) != transactions * 2
                || treasury.insuranceDeficit(0) != transactions
                || treasury.liquidationFee(0) != transactions || treasury.fundingResidual(0) != transactions
                || treasury.roundingResidual(0) != transactions || treasury.clearingPnl(0) != transactions)
            throw new IllegalStateException("Ledger funds differ from completed transactions");
        if (treasury.hasChangedValues()) throw new IllegalStateException("Ledger transaction not cleared");
    }

    private static final class MoneySlot implements SystemLedgerLane.Entry {
        private final RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator(8);
        private long fees;
        private volatile boolean completed;

        MoneySlot() {
            delta.addFee(0, 1); delta.addInsurance(0, 2); delta.addDeficit(0, 1);
            delta.addFundingResidual(0, 1); delta.addRoundingResidual(0, 1); delta.addClearing(0, 1);
        }

        @Override public void apply(TreasuryRuntime treasury) {
            delta.apply(treasury);
            treasury.setLiquidationFee(0, Math.incrementExact(treasury.liquidationFee(0)));
            funds.add(0, FundsPosting.OwnerKind.USER, 42, FundsPosting.Subledger.AVAILABLE, -6);
            treasury.appendFundsDelta(funds);
            funds.requireConserved(false);
            funds.clear();
            fees = treasury.fee(0);
            treasury.clearChangedKeys();
            completed = true;
        }
    }
}
