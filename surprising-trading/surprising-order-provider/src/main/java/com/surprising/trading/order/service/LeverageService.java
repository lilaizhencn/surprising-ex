package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.repository.OrderLeverageMath;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class LeverageService {

    private static final long MIN_LEVERAGE_PPM = 1_000_000L;

    private final InstrumentRuleLookup instrumentRuleLookup;
    private final OrderMarginSnapshotCache marginSnapshotCache;
    private final LeverageSettingEventPublisher eventPublisher;
    private final OrderIdSequenceStore idSequenceStore;

    @org.springframework.beans.factory.annotation.Autowired
    public LeverageService(InstrumentRuleLookup instrumentRuleLookup,
                           OrderMarginSnapshotCache marginSnapshotCache,
                           LeverageSettingEventPublisher eventPublisher,
                           OrderIdSequenceStore idSequenceStore) {
        this.instrumentRuleLookup = instrumentRuleLookup;
        this.marginSnapshotCache = marginSnapshotCache;
        this.eventPublisher = eventPublisher;
        this.idSequenceStore = idSequenceStore;
    }

    public LeverageSettingResponse set(LeverageSettingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("leverage setting request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String symbol = normalizeSymbol(request.symbol());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        InstrumentRule rule = tradingRule(symbol);
        ProductLine productLine = productLine(rule, request.productLine());
        if (marginSnapshotCache == null || !marginSnapshotCache.leverageReady(productLine)) {
            throw new IllegalStateException("杠杆 JVM 快照尚未就绪，禁止写入未同步配置: " + productLine);
        }
        if (request.leveragePpm() < MIN_LEVERAGE_PPM) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        if (request.leveragePpm() > rule.maxLeveragePpm()) {
            throw new IllegalArgumentException("leveragePpm exceeds instrument max leverage");
        }
        LeverageSettingRequest normalized = new LeverageSettingRequest(request.userId(), productLine, symbol, marginMode,
                request.leveragePpm(), request.reason());
        Instant updatedAt = Instant.now();
        if (eventPublisher == null || idSequenceStore == null) {
            throw new IllegalStateException("杠杆事件发布器未配置");
        }
        eventPublisher.publish(normalized, idSequenceStore.next(), updatedAt);
        return new LeverageSettingResponse(request.userId(), productLine, symbol, marginMode,
                request.leveragePpm(), rule.maxLeveragePpm(),
                OrderLeverageMath.initialMarginRateFromLeveragePpm(request.leveragePpm()),
                "USER", updatedAt);
    }

    public LeverageSettingResponse get(long userId, String symbol, MarginMode marginMode) {
        return get(userId, symbol, marginMode, null);
    }

    public LeverageSettingResponse get(long userId, String symbol, MarginMode marginMode, ProductLine productLine) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        InstrumentRule rule = tradingRule(normalizedSymbol);
        ProductLine resolvedProductLine = productLine(rule, productLine);
        if (marginSnapshotCache == null || !marginSnapshotCache.leverageReady(resolvedProductLine)) {
            throw new IllegalStateException("杠杆 JVM 快照尚未就绪，禁止查询数据库回退: " + resolvedProductLine);
        }
        return marginSnapshotCache.lookupConfiguredLeverage(resolvedProductLine, userId, normalizedSymbol,
                        normalizedMarginMode)
                .map(leverage -> new LeverageSettingResponse(userId, resolvedProductLine, normalizedSymbol,
                        normalizedMarginMode, leverage, rule.maxLeveragePpm(),
                        OrderLeverageMath.initialMarginRateFromLeveragePpm(leverage), "USER", Instant.EPOCH))
                .orElseGet(() -> instrumentDefault(userId, resolvedProductLine, normalizedSymbol,
                        normalizedMarginMode, rule));
    }

    private InstrumentRule tradingRule(String symbol) {
        InstrumentRule rule = instrumentRuleLookup.currentRule(symbol)
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
        if (!"TRADING".equals(rule.status())) {
            throw new IllegalStateException("instrument is not trading: " + symbol);
        }
        return rule;
    }

    private ProductLine productLine(InstrumentRule rule, ProductLine requestedProductLine) {
        ProductLine instrumentProductLine = ProductLine.requireContractTypeCode(rule.contractType().name());
        if (requestedProductLine != null && requestedProductLine != instrumentProductLine) {
            throw new IllegalArgumentException("productLine does not match instrument contractType");
        }
        return instrumentProductLine;
    }

    /** 快照中没有用户覆盖时使用当前 Instrument 规则计算默认杠杆。 */
    private LeverageSettingResponse instrumentDefault(long userId,
                                                       ProductLine productLine,
                                                       String symbol,
                                                       MarginMode marginMode,
                                                       InstrumentRule rule) {
        long leveragePpm = Math.min(OrderLeverageMath.leveragePpmFromInitialMarginRate(
                rule.initialMarginRatePpm()), rule.maxLeveragePpm());
        long effectiveRate = Math.max(rule.initialMarginRatePpm(),
                OrderLeverageMath.initialMarginRateFromLeveragePpm(leveragePpm));
        return new LeverageSettingResponse(userId, productLine, symbol, marginMode, leveragePpm,
                rule.maxLeveragePpm(), effectiveRate, "INSTRUMENT_DEFAULT", Instant.EPOCH);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }
}
