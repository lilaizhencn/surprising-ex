package com.surprising.derivatives.lifecycle;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/** Owns the single Aeron client pool shared by all derivatives lifecycle services. */
@Component
public final class DerivativesAeronClient implements AutoCloseable {

    private final AeronClientPool pool;

    public DerivativesAeronClient(RiskProperties risk, LiquidationProperties liquidation,
                                  InsuranceProperties insurance, AdlProperties adl) {
        ProductLine productLine = risk.getProductLine();
        if (liquidation.getProductLine() != productLine
                || insurance.getKafka().getProductLine() != productLine
                || adl.getKafka().getProductLine() != productLine) {
            throw new IllegalStateException("all lifecycle providers must use the same product line");
        }
        RiskProperties.Aeron aeron = risk.getAeron();
        pool = new AeronClientPool("derivatives-lifecycle", productLine, aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public CoreResponse query(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return pool.query(type, commandId, userId, payload);
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return pool.command(type, commandId, userId, payload);
    }

    public CompletableFuture<CoreResponse> commandAsync(CoreMessageType type, UUID commandId,
                                                         long userId, byte[] payload) {
        return pool.commandAsync(type, commandId, userId, payload);
    }

    @Override
    @PreDestroy
    public void close() {
        pool.close();
    }
}
