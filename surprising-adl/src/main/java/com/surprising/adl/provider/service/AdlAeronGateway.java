package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreAdlQueryCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdlAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;
    private final ProductLine productLine;

    public AdlAeronGateway(AdlProperties properties) {
        productLine = properties.getKafka().getProductLine();
        AdlProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("adl", properties.getKafka().getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public List<CoreAdlCandidateView> candidates(String asset, int limit) {
        var response = clients.query(CoreMessageType.ADL_CANDIDATE_QUERY, UUID.randomUUID(), 0,
                CoreAdlQueryCodec.encodeQuery(asset, limit));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron ADL candidate query failed");
        }
        return CoreAdlQueryCodec.decodeCandidates(response.data());
    }

    public CoreLiquidationWorkView resolutionWork(CoreLiquidationWorkView.Purpose purpose,
                                                   long afterLiquidationId, int limit, int maxBytes) {
        var response = clients.query(CoreMessageType.LIQUIDATION_WORK_QUERY, UUID.randomUUID(), 0,
                CoreLiquidationWorkCodec.encodeQuery(productLine, purpose, afterLiquidationId, limit, maxBytes));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron ADL work query failed");
        }
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    public void execute(UUID commandId, byte[] payload) {
        var response = clients.command(CoreMessageType.EXECUTE_ADL, commandId, 0, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode() + ": Aeron ADL command rejected");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
