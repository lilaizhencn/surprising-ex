package com.surprising.liquidation.provider.service;

import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LiquidationAeronGateway {

    private final LiquidationProperties properties;
    private final DerivativesAeronClient clients;

    public LiquidationAeronGateway(LiquidationProperties properties, DerivativesAeronClient clients) {
        this.properties = properties;
        this.clients = clients;
    }

    public CoreLiquidationWorkView work(long afterLiquidationId, int limit, int maxBytes) {
        var response = clients.query(CoreMessageType.LIQUIDATION_WORK_QUERY, UUID.randomUUID(), 0,
                CoreLiquidationWorkCodec.encodeQuery(properties.getProductLine(),
                        CoreLiquidationWorkView.Purpose.EXECUTION, afterLiquidationId, limit, maxBytes));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron liquidation work query failed");
        }
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    public CoreLiquidationBatchResultView executeBatch(CoreLiquidationWorkView work,
                                                       long liquidationFeeRatePpm,
                                                       int maxRiskScanUsers) {
        var command = ExecuteLiquidationBatchCommand.fromWork(work, liquidationFeeRatePpm, maxRiskScanUsers);
        byte[] payload = TradingCommandCodec.encodeExecuteLiquidationBatch(command);
        // A risk page can advance within the same lastUserId (lane/position cursor).
        // Each new scheduling attempt is a new command; transport retries retain this id.
        UUID commandId = command.riskScanContinuation() == null ? stableBatchCommandId(payload) : UUID.randomUUID();
        var response = clients.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, commandId, 0,
                payload);
        if (response.commandStatus() != ResponseStatus.APPLIED && response.commandStatus() != ResponseStatus.DUPLICATE) {
            throw new IllegalStateException(response.resultCode() + ": Aeron liquidation batch rejected");
        }
        return CoreLiquidationBatchResultCodec.decode(response.data());
    }

    public CoreRiskScanControlView riskScanControl() {
        var response = clients.query(CoreMessageType.RISK_SCAN_CONTROL_QUERY, UUID.randomUUID(), 0, new byte[0]);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk scan control query failed");
        }
        return CoreRiskScanControlCodec.decodeView(response.data());
    }

    public void continueRiskScan(int maxUsers) {
        var response = clients.command(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), 0,
                TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers)));
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk scan continuation rejected");
        }
    }

    UUID stableBatchCommandId(byte[] canonicalPayload) {
        byte[] productLine = properties.getProductLine().name().getBytes(StandardCharsets.UTF_8);
        ByteBuffer identity = ByteBuffer.allocate(Integer.BYTES + productLine.length + canonicalPayload.length)
                .putInt(productLine.length).put(productLine).put(canonicalPayload);
        return UUID.nameUUIDFromBytes(identity.array());
    }

}
