package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public record ReplaceOrderCheck(long userId, long originalOrderId, long originalOrderRevision,
                                 long userRevision, PlaceOrderCommand command,
                                 ResolvedPlaceOrder resolved,
                                 long requiredReservationUnits) {

    public ReplaceOrderCheck {
        if (userId <= 0 || originalOrderId <= 0 || originalOrderRevision < 0 || userRevision < 0
                || command == null || resolved == null
                || requiredReservationUnits < 0 || command.orderId() != resolved.orderId()) {
            throw new IllegalArgumentException("invalid resolved matching admission");
        }
    }

}

