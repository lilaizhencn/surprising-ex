package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ReduceOnlyPositionLookup;
import com.surprising.trading.order.model.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReduceOnlyValidator {

    private final ReduceOnlyPositionLookup positionLookup;
    private final TradingOrderProperties properties;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    public ReduceOnlyValidator(ReduceOnlyPositionLookup positionLookup) {
        this(positionLookup, new TradingOrderProperties(), null);
    }

    public ReduceOnlyValidator(ReduceOnlyPositionLookup positionLookup, TradingOrderProperties properties) {
        this(positionLookup, properties, null);
    }

    @Autowired
    public ReduceOnlyValidator(ReduceOnlyPositionLookup positionLookup,
                               TradingOrderProperties properties,
                               OrderMarginSnapshotCache marginSnapshotCache) {
        this.positionLookup = positionLookup;
        this.properties = properties;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    public ValidationResult validate(PlaceOrderRequest request) {
        if (!request.reduceOnly()) {
            return ValidationResult.ok();
        }
        ProductLine productLine = currentProductLine();
        if (marginSnapshotCache != null && productLine == ProductLine.LINEAR_PERPETUAL) {
            if (!marginSnapshotCache.ready(productLine)) {
                return ValidationResult.reject("reduce-only position snapshot unavailable");
            }
            var cached = marginSnapshotCache.lookupReduceOnly(productLine, request.userId(), request.symbol(),
                    request.marginMode(), request.positionSide(), request.side());
            if (cached.isEmpty()) {
                return ValidationResult.reject("reduce-only requires an open position");
            }
            ValidationResult cacheDecision = validateSnapshot(request, cached.get());
            if (!cacheDecision.accepted()) {
                return cacheDecision;
            }
            // 账户快照和订单用户分区状态由同一条事实流驱动；通过快照校验后直接返回，
            // 禁止下单热路径再执行持仓或开放订单 SQL。
            return cacheDecision;
        }
        var position = positionLookup.lockedPosition(productLine, request.userId(), request.symbol(),
                request.marginMode(), request.positionSide()).orElse(null);
        long signedQuantity = position == null ? 0L : position.signedQuantitySteps();
        if (signedQuantity == 0) {
            return ValidationResult.reject("reduce-only requires an open position");
        }
        if (position.instrumentVersion() <= 0) {
            return ValidationResult.reject("reduce-only position instrument version is missing");
        }
        OrderSide closeSide = signedQuantity > 0 ? OrderSide.SELL : OrderSide.BUY;
        if (request.side() != closeSide) {
            return ValidationResult.reject("reduce-only side does not reduce current position");
        }
        long pendingCloseSteps = positionLookup.lockedOpenReduceOnlySteps(productLine, request.userId(), request.symbol(),
                request.marginMode(), position.instrumentVersion(), request.positionSide(), closeSide);
        long availableCloseSteps = Math.subtractExact(Math.absExact(signedQuantity), pendingCloseSteps);
        if (availableCloseSteps <= 0) {
            return ValidationResult.reject("no reducible quantity is available");
        }
        if (request.quantitySteps() > availableCloseSteps) {
            return ValidationResult.reject("reduce-only quantity exceeds available position");
        }
        return ValidationResult.ok(position.instrumentVersion());
    }

    private ValidationResult validateSnapshot(PlaceOrderRequest request,
                                               OrderMarginSnapshotCache.ReduceOnlySnapshot snapshot) {
        long signedQuantity = snapshot.signedQuantitySteps();
        if (signedQuantity == 0L) {
            return ValidationResult.reject("reduce-only requires an open position");
        }
        if (snapshot.instrumentVersion() <= 0L) {
            return ValidationResult.reject("reduce-only position instrument version is missing");
        }
        OrderSide closeSide = signedQuantity > 0L ? OrderSide.SELL : OrderSide.BUY;
        if (request.side() != closeSide) {
            return ValidationResult.reject("reduce-only side does not reduce current position");
        }
        long available = Math.subtractExact(Math.absExact(signedQuantity), snapshot.pendingReduceOnlySteps());
        if (available <= 0L) {
            return ValidationResult.reject("no reducible quantity is available");
        }
        if (request.quantitySteps() > available) {
            return ValidationResult.reject("reduce-only quantity exceeds available position");
        }
        return ValidationResult.ok(snapshot.instrumentVersion());
    }

    private ProductLine currentProductLine() {
        TradingOrderProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka == null ? ProductLine.LINEAR_PERPETUAL : kafka.getProductLine();
    }

}
