package com.surprising.trading.order.service;

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
        if (request.leveragePpm() < MIN_LEVERAGE_PPM) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        if (request.leveragePpm() > rule.maxLeveragePpm()) {
            throw new IllegalArgumentException("leveragePpm exceeds instrument max leverage");
        }
        LeverageSettingRequest normalized = new LeverageSettingRequest(request.userId(), symbol, marginMode,
                request.leveragePpm(), request.reason());
        leverageSettingRepository.upsert(normalized, Instant.now());
        return get(request.userId(), symbol, marginMode);
    }

    public LeverageSettingResponse get(long userId, String symbol, MarginMode marginMode) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        InstrumentRule rule = tradingRule(normalizedSymbol);
        return leverageSettingRepository.userSetting(userId, normalizedSymbol, normalizedMarginMode,
                        rule.maxLeveragePpm())
                .orElseGet(() -> leverageSettingRepository.instrumentDefault(userId, normalizedSymbol,
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
