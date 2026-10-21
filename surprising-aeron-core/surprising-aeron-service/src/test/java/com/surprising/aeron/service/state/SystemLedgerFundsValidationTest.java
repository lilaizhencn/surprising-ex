package com.surprising.aeron.service.state;

import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.ledger.SystemLedgerCommand;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class SystemLedgerFundsValidationTest {
    @Test
    void unbalancedControlPostingFailsOnLedgerBeforeOwnerCanCommit() {
        var runtime = new TradingRuntimeState(LaneTopology.characterization());
        runtime.bindOwner();
        runtime.startSystemLedgerLane();
        var work = new SystemLedgerCommand() {
            { initializeLedgerCommand(runtime); }
            @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFee(1, 7); }
        };
        assertThat(runtime.dispatchLedgerCommand(work)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Throwable rejected = null;
        while (rejected == null) {
            runtime.pollControlFundsValidation(false);
            try { runtime.assertAccountLanesHealthy(); }
            catch (Throwable failure) { rejected = failure; }
            if (System.nanoTime() >= deadline) throw new AssertionError("unbalanced Ledger posting was not rejected");
            Thread.onSpinWait();
        }
        assertThat(rejected).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not conserved");
        assertThatThrownBy(runtime::close).isInstanceOf(RuntimeException.class);
    }
}
