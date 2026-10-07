package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class FeePolicyCoreImporter {

    private final OrderAeronGateway aeron;

    public FeePolicyCoreImporter(OrderAeronGateway aeron) {
        this.aeron = aeron;
    }

    public void importPolicy(FeeScheduleResponse policy) {
        long revision = policy.updatedAt().toEpochMilli();
        UpsertFeePolicyCommand command = new UpsertFeePolicyCommand(
                policy.feeScheduleId(), revision, policy.userId(), policy.instrumentId(),
                policy.makerFeeRatePpm(), policy.takerFeeRatePpm(), sourcePriority(policy.sourceType()),
                policy.status() == FeeScheduleStatus.ACTIVE, policy.effectiveTime().toEpochMilli(),
                policy.expireTime() == null ? 0 : policy.expireTime().toEpochMilli());
        // Policy ID + revision provide durable idempotency in Core. A command identity
        // must be new for each startup/import attempt: old command outcomes can expire
        // from retention even though the fee policy itself remains authoritative.
        UUID commandId = UUID.randomUUID();
        try {
            aeron.command(CoreMessageType.UPSERT_FEE_POLICY, commandId, policy.userId(),
                    TradingCommandCodec.encodeUpsertFeePolicy(command));
        } catch (ResultUnknownException unknown) {
            // The command was admitted. Resolve its existing outcome; never resubmit it.
            var result = aeron.commandResult(commandId);
            if (result.status() != ResponseStatus.OK || result.commandStatus() != ResponseStatus.APPLIED) {
                throw new IllegalStateException("fee policy import is not confirmed: " + result.resultCode(), unknown);
            }
        }
    }

    private static int sourcePriority(FeeScheduleSourceType sourceType) {
        return switch (sourceType) {
            case RISK_OVERRIDE -> 0;
            case USER_OVERRIDE -> 1;
            case PROMOTION -> 2;
            case MARKET_MAKER -> 3;
            case VIP -> 4;
        };
    }
}
