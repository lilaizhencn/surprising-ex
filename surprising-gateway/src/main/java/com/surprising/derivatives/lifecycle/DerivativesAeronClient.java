package com.surprising.derivatives.lifecycle;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import io.aeron.driver.MediaDriver;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Owns the single Aeron client pool shared by all derivatives lifecycle services. */
@Component
public final class DerivativesAeronClient implements AutoCloseable {

    private final AeronClientPool pool;

    public DerivativesAeronClient(RiskProperties risk, LiquidationProperties liquidation,
                                  InsuranceProperties insurance, AdlProperties adl) {
        this(risk, liquidation, insurance, adl, null);
    }

    @Autowired
    public DerivativesAeronClient(RiskProperties risk, LiquidationProperties liquidation,
                                  InsuranceProperties insurance, AdlProperties adl,
                                  @Autowired(required = false) @Qualifier("productCommandMediaDriver") MediaDriver driver) {
        ProductLine productLine = risk.getProductLine();
        if (liquidation.getProductLine() != productLine
                || insurance.getProductLine() != productLine
                || adl.getProductLine() != productLine) {
            throw new IllegalStateException("all lifecycle providers must use the same product line");
        }
        RiskProperties.Aeron aeron = risk.getAeron();
        pool = driver != null
                ? new AeronClientPool("derivatives-lifecycle", productLine, aeron.getHostnames(),
                        aeron.getEgressHostname(), aeron.getResponseTimeout(), 1,
                        "derivatives-lifecycle", driver)
                : new AeronClientPool("derivatives-lifecycle", productLine, aeron.getHostnames(),
                        aeron.getEgressHostname(), aeron.getResponseTimeout(), 1);
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
