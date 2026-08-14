package com.surprising.liquidation.provider.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LiquidationAeronGateway implements AutoCloseable {

    private final LiquidationProperties properties;
    private final AeronClientPool clients;

    public LiquidationAeronGateway(LiquidationProperties properties) {
        this.properties = properties;
        LiquidationProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("liquidation", properties.getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public CoreLiquidationWorkView work(int limit) {
        var response = clients.query(CoreMessageType.LIQUIDATION_WORK_QUERY, UUID.randomUUID(), 0,
                CoreLiquidationWorkCodec.encodeQuery(limit));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron liquidation work query failed");
        }
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    public void continueRiskScan(int maxUsers) {
        var response = clients.command(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), 0,
                TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers)));
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk scan continuation rejected");
        }
    }

    public CoreResultCode execute(CoreLiquidationActionView action, long liquidationFeeRatePpm) {
        UUID commandId = stableCommandId(action, liquidationFeeRatePpm);
        var response = clients.command(CoreMessageType.EXECUTE_LIQUIDATION, commandId, action.userId(),
                TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                        action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(),
                        liquidationFeeRatePpm)));
        if (response.commandStatus() == ResponseStatus.APPLIED) return CoreResultCode.NONE;
        if (response.resultCode() == CoreResultCode.LIQUIDATION_STATE_CONFLICT
                || response.resultCode() == CoreResultCode.LIQUIDATION_NOT_FOUND
                || response.resultCode() == CoreResultCode.STALE_MARK_PRICE) {
            return response.resultCode();
        }
        throw new IllegalStateException(response.resultCode() + ": Aeron liquidation command rejected");
    }

    UUID stableCommandId(CoreLiquidationActionView action, long liquidationFeeRatePpm) {
        String identity = properties.getProductLine() + ":LIQUIDATION:" + action.liquidationId() + ':'
                + action.triggerPriceSequence() + ':' + action.markPriceTicks() + ':' + liquidationFeeRatePpm;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
