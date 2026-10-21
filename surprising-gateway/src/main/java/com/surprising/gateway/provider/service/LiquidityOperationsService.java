package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.model.LiquidityOperationMetadata;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 本机流动性维护：产品归属、审计意图先落库、执行实际资金/配置业务，随后记录结果。 */
@Service
public class LiquidityOperationsService {
    private final GatewayProductServices products;
    private final InstrumentService instruments;
    private final AdminAuditRepository audit;

    public LiquidityOperationsService(GatewayProductServices products, InstrumentService instruments, AdminAuditRepository audit) {
        this.products = products;
        this.instruments = instruments;
        this.audit = audit;
    }

    public ProductBalanceResponse adjust(ProductBalanceAdjustmentRequest body, LiquidityOperationMetadata metadata) {
        ProductLine product = body.accountType().productLine().orElseThrow();
        products.requireEnabled(product);
        return execute(product, metadata, () -> products.service(product, AccountCommandGateway.class)
                .adjustProductBalance(body, null, "SYSTEM:LIQUIDITY_OPERATIONS"));
    }

    public LeverageSettingResponse leverage(LeverageSettingRequest body, LiquidityOperationMetadata metadata) {
        products.requireEnabled(body.productLine());
        return execute(body.productLine(), metadata, () -> products.service(body.productLine(), LeverageService.class).set(body));
    }

    public LeverageSettingResponse leverage(long userId, String instrumentId, ProductLine product, MarginMode marginMode) {
        products.requireEnabled(product);
        return products.service(product, LeverageService.class).get(userId, instrumentId, marginMode, product);
    }

    public InstrumentResponse instrument(InstrumentUpsertRequest body, LiquidityOperationMetadata metadata) {
        ProductLine product = body.contractType().productLine();
        products.requireEnabled(product);
        return execute(product, metadata, () -> instruments.upsert(body, "SYSTEM:LIQUIDITY_OPERATIONS", metadata.reason()));
    }

    private <T> T execute(ProductLine product, LiquidityOperationMetadata metadata, Supplier<T> action) {
        // Durable intent comes first. Funds retries retain the business reference and Core command identity.
        audit.recordRequired(record(product, metadata, 202, false, "INTENT"));
        try {
            T result = action.get();
            audit.recordRequired(record(product, metadata, 200, true, null));
            return result;
        } catch (RuntimeException ex) {
            audit.record(record(product, metadata, 500, false, ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private AdminOperationRecord record(ProductLine product, LiquidityOperationMetadata metadata, int status, boolean success, String error) {
        return new AdminOperationRecord(null, "SYSTEM:LIQUIDITY_OPERATIONS", List.of("OPERATIONS"),
                "liquidity-operations", "POST", metadata.requestPath(),
                "referenceId=" + java.net.URLEncoder.encode(metadata.reference(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&reason=" + java.net.URLEncoder.encode(metadata.reason(), java.nio.charset.StandardCharsets.UTF_8), product.name(),
                metadata.bodyHash(), status, 0L, success, error, "host-local-operations", metadata.clientIp(), Instant.now());
    }
}
