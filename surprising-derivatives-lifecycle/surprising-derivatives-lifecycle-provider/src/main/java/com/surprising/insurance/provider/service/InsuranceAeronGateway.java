package com.surprising.insurance.provider.service;

import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InsuranceAeronGateway {

    private final DerivativesAeronClient clients;
    private final ProductLine productLine;

    public InsuranceAeronGateway(InsuranceProperties properties, DerivativesAeronClient clients) {
        productLine = properties.getKafka().getProductLine();
        this.clients = clients;
    }

    public CoreLiquidationWorkView resolutionWork(CoreLiquidationWorkView.Purpose purpose,
                                                   long afterLiquidationId, int limit, int maxBytes) {
        CoreResponse response = clients.query(CoreMessageType.LIQUIDATION_WORK_QUERY, UUID.randomUUID(), 0,
                CoreLiquidationWorkCodec.encodeQuery(productLine, purpose, afterLiquidationId, limit, maxBytes));
        if (response.status() != ResponseStatus.OK || response.resultCode() != CoreResultCode.NONE) {
            throw new IllegalStateException(response.resultCode() + ": Aeron insurance work query failed");
        }
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    public void command(CoreMessageType type, UUID commandId, byte[] payload) {
        CoreResponse response = clients.command(type, commandId, 0, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode() + ": Aeron insurance command rejected");
        }
    }

    public long balance(String asset) {
        return CoreStateQueryCodec.decodeTreasuryState(treasury()).stream()
                .filter(value -> value.asset().equalsIgnoreCase(asset))
                .mapToLong(value -> value.insuranceBalanceUnits()).findFirst().orElse(0L);
    }

    public byte[] treasury() {
        CoreResponse response = clients.query(CoreMessageType.TREASURY_STATE_QUERY, UUID.randomUUID(), 0, new byte[0]);
        if (response.status() != ResponseStatus.OK || response.resultCode() != CoreResultCode.NONE) {
            throw new IllegalStateException(response.resultCode() + ": Aeron treasury query failed");
        }
        return response.data();
    }

}
