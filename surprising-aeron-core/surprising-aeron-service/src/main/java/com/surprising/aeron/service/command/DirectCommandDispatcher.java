package com.surprising.aeron.service.command;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.command.adl.AdlCommands;
import com.surprising.aeron.service.command.balance.BalanceTransferCommands;
import com.surprising.aeron.service.command.fee.FeePolicyCommands;
import com.surprising.aeron.service.command.funding.PerpetualFundingCommands;
import com.surprising.aeron.service.command.insurance.InsuranceFundCommands;
import com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands;
import com.surprising.aeron.service.command.leverage.LeverageCommands;
import com.surprising.aeron.service.command.liquidation.LiquidationCommands;
import com.surprising.aeron.service.command.position.PositionCommands;
import com.surprising.aeron.service.command.risk.RiskCommands;
import com.surprising.aeron.service.command.settlement.InstrumentSettlementCommands;
import com.surprising.aeron.service.command.trigger.TriggerCommandDispatcher;

/** Dispatches commands that complete through one direct business command handler. */
public final class DirectCommandDispatcher {
    private final BalanceTransferCommands balances;
    private final InstrumentConfigurationCommands instruments;
    private final PerpetualFundingCommands funding;
    private final RiskCommands risk;
    private final LiquidationCommands liquidations;
    private final AdlCommands adl;
    private final InsuranceFundCommands insurance;
    private final PositionCommands positions;
    private final LeverageCommands leverage;
    private final InstrumentSettlementCommands settlements;
    private final FeePolicyCommands fees;
    private final TriggerCommandDispatcher triggers;

    public DirectCommandDispatcher(BalanceTransferCommands balances,
                                   InstrumentConfigurationCommands instruments,
                                   PerpetualFundingCommands funding,
                                   RiskCommands risk,
                                   LiquidationCommands liquidations,
                                   AdlCommands adl,
                                   InsuranceFundCommands insurance,
                                   PositionCommands positions,
                                   LeverageCommands leverage,
                                   InstrumentSettlementCommands settlements,
                                   FeePolicyCommands fees,
                                   TriggerCommandDispatcher triggers) {
        this.balances = java.util.Objects.requireNonNull(balances);
        this.instruments = java.util.Objects.requireNonNull(instruments);
        this.funding = java.util.Objects.requireNonNull(funding);
        this.risk = java.util.Objects.requireNonNull(risk);
        this.liquidations = java.util.Objects.requireNonNull(liquidations);
        this.adl = java.util.Objects.requireNonNull(adl);
        this.insurance = java.util.Objects.requireNonNull(insurance);
        this.positions = java.util.Objects.requireNonNull(positions);
        this.leverage = java.util.Objects.requireNonNull(leverage);
        this.settlements = java.util.Objects.requireNonNull(settlements);
        this.fees = java.util.Objects.requireNonNull(fees);
        this.triggers = java.util.Objects.requireNonNull(triggers);
    }

    /**
     * @return {@code true} when the message belongs to a direct business command; otherwise
     *         the caller must continue with the matching/control pipeline.
     */
    public boolean dispatch(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case ADJUST_BALANCE -> balances.executeAdjustBalance(message, clusterTimestamp);
            case TRANSFER_OUT -> balances.executeTransferOut(message, clusterTimestamp);
            case TRANSFER_IN -> balances.executeTransferIn(message, clusterTimestamp);
            case COMPLETE_TRANSFER -> balances.executeCompleteTransfer(message, clusterTimestamp);
            case UPSERT_INSTRUMENT -> instruments.executeUpsertInstrument(message, clusterTimestamp);
            case APPLY_MARK_PRICE -> risk.executeApplyMarkPrice(message, clusterTimestamp);
            case APPLY_FUNDING -> funding.executeApplyFunding(message, clusterTimestamp);
            case EXECUTE_ADL -> adl.executeExecuteAdl(message, clusterTimestamp);
            case RESOLVE_LIQUIDATION -> liquidations.executeResolveLiquidation(message, clusterTimestamp);
            case CONTINUE_RISK_SCAN -> risk.executeContinueRiskScan(message, clusterTimestamp);
            case UPDATE_RISK_SCAN_CONTROL -> risk.executeUpdateRiskScanControl(message, clusterTimestamp);
            case UPDATE_INSTRUMENT_MAINTENANCE -> instruments.executeUpdateInstrumentMaintenance(message, clusterTimestamp);
            case UPSERT_FEE_POLICY -> fees.executeUpsertFeePolicy(message, clusterTimestamp);
            case UPDATE_POSITION_MODE -> positions.executeUpdatePositionMode(message, clusterTimestamp);
            case ADJUST_POSITION_MARGIN -> positions.executeAdjustPositionMargin(message, clusterTimestamp);
            case ADJUST_INSURANCE_FUND -> insurance.executeAdjustInsuranceFund(message, clusterTimestamp);
            case UPDATE_LEVERAGE -> leverage.executeUpdateLeverage(message, clusterTimestamp);
            default -> { return triggers.dispatch(message, clusterTimestamp); }
        }
        return true;
    }
}
