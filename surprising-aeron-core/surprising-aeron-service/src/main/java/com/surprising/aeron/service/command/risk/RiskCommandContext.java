package com.surprising.aeron.service.command.risk;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskScanCoordinator;
import java.util.function.BooleanSupplier;

/** Owner capabilities required by mark-price and risk-scan commands. */
public interface RiskCommandContext extends CommandResultContext {
    PositionUserIndex positionUserIndex();

    int pendingRiskScanCount();

    void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt);

    void initializeTriggerScan(ApplyMarkPriceCommand command);

    BooleanSupplier pendingTriggerScan(String symbol, int maxWork);

    void evaluatePendingTriggerScan(String symbol, int maxWork);

    RiskScanCoordinator reusableRiskScanCoordinator(int maxUsers);
}
