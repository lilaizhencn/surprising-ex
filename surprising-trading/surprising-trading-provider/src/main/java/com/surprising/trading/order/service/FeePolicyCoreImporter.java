package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import java.nio.charset.StandardCharsets;
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
                policy.feeScheduleId(), revision, policy.userId(), policy.symbol(),
                policy.makerFeeRatePpm(), policy.takerFeeRatePpm(), sourcePriority(policy.sourceType()),
                policy.status() == FeeScheduleStatus.ACTIVE, policy.effectiveTime().toEpochMilli(),
                policy.expireTime() == null ? 0 : policy.expireTime().toEpochMilli());
        UUID commandId = UUID.nameUUIDFromBytes(("fee-policy:" + policy.productLine() + ':'
                + policy.feeScheduleId() + ':' + revision + ':' + command.active())
                .getBytes(StandardCharsets.UTF_8));
        aeron.command(CoreMessageType.UPSERT_FEE_POLICY, commandId, policy.userId(),
                TradingCommandCodec.encodeUpsertFeePolicy(command));
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
