package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReduceOnlyValidator {

    private final TradingOrderProperties properties;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    @Autowired
    public ReduceOnlyValidator(TradingOrderProperties properties,
                               OrderMarginSnapshotCache marginSnapshotCache) {
        this.properties = properties;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    /** 测试或嵌入式调用使用默认产品线配置；生产环境必须注入统一快照。 */
    public ReduceOnlyValidator(OrderMarginSnapshotCache marginSnapshotCache) {
        this(new TradingOrderProperties(), marginSnapshotCache);
    }

    public ValidationResult validate(PlaceOrderRequest request) {
        if (!request.reduceOnly()) {
            return ValidationResult.ok();
        }
        ProductLine productLine = currentProductLine();
        if (marginSnapshotCache == null || !marginSnapshotCache.ready(productLine)) {
            return ValidationResult.reject("reduce-only position snapshot unavailable");
        }
        var cached = marginSnapshotCache.lookupReduceOnly(productLine, request.userId(), request.symbol(),
                request.marginMode(), request.positionSide(), request.side());
        if (cached.isEmpty()) {
            return ValidationResult.reject("reduce-only requires an open position");
        }
        // 账户仓位事件和订单事件由同一条事实流驱动；快照校验通过后直接返回，
        // 禁止下单热路径再执行持仓或开放订单 SQL。
        return validateSnapshot(request, cached.get());
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
