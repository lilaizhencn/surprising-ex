package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeverageService {

    private static final long MIN_LEVERAGE_PPM = 1_000_000L;

    private final LeverageSettingRepository leverageSettingRepository;
    private final InstrumentRuleLookup instrumentRuleLookup;

    public LeverageService(LeverageSettingRepository leverageSettingRepository,
                           InstrumentRuleLookup instrumentRuleLookup) {
        this.leverageSettingRepository = leverageSettingRepository;
        this.instrumentRuleLookup = instrumentRuleLookup;
    }

    @Transactional
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
        if (request.leveragePpm() < MIN_LEVERAGE_PPM) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        if (request.leveragePpm() > rule.maxLeveragePpm()) {
            throw new IllegalArgumentException("leveragePpm exceeds instrument max leverage");
        }
        LeverageSettingRequest normalized = new LeverageSettingRequest(request.userId(), productLine, symbol, marginMode,
                request.leveragePpm(), request.reason());
        leverageSettingRepository.upsert(normalized, Instant.now());
        return get(request.userId(), symbol, marginMode, productLine);
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
        return leverageSettingRepository.userSetting(resolvedProductLine, userId, normalizedSymbol, normalizedMarginMode,
                        rule.maxLeveragePpm())
                .orElseGet(() -> leverageSettingRepository.instrumentDefault(resolvedProductLine, userId, normalizedSymbol,
                        normalizedMarginMode, rule.maxLeveragePpm(), rule.initialMarginRatePpm()));
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
