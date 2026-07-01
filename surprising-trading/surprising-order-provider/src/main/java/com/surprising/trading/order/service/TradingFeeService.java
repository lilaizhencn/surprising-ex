package com.surprising.trading.order.service;

import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingFeeService {

    private static final int DEFAULT_LIMIT = 100;

    private final OrderFeeRepository orderFeeRepository;
    private final OrderRepository orderRepository;
    private final InstrumentRuleLookup instrumentRuleLookup;

    public TradingFeeService(OrderFeeRepository orderFeeRepository,
                             OrderRepository orderRepository,
                             InstrumentRuleLookup instrumentRuleLookup) {
        this.orderFeeRepository = orderFeeRepository;
        this.orderRepository = orderRepository;
        this.instrumentRuleLookup = instrumentRuleLookup;
    }

    public EffectiveTradingFeeResponse effectiveFee(long userId, String symbol, long instrumentVersion) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        long resolvedVersion = instrumentVersion > 0 ? instrumentVersion : currentVersion(normalizedSymbol);
        Instant now = Instant.now();
        OrderFeeSnapshot snapshot = orderFeeRepository.snapshot(userId, normalizedSymbol, resolvedVersion, now)
                .orElseThrow(() -> new IllegalStateException("fee schedule unavailable"));
        return new EffectiveTradingFeeResponse(userId, normalizedSymbol, resolvedVersion,
                snapshot.makerFeeRatePpm(), snapshot.takerFeeRatePpm(), snapshot.source(), now);
    }

    @Transactional
    public FeeScheduleResponse upsertSchedule(FeeScheduleUpsertRequest request) {
        OrderFeeRepository.validateSchedule(request);
        long feeScheduleId = request.feeScheduleId() == null
                ? orderRepository.nextSequence("fee-schedule")
                : request.feeScheduleId();
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        Instant now = Instant.now();
        orderFeeRepository.upsertSchedule(request, feeScheduleId, now);
        return orderFeeRepository.findSchedule(feeScheduleId)
                .orElseThrow(() -> new IllegalStateException("fee schedule upsert failed: " + feeScheduleId));
    }

    @Transactional
    public FeeScheduleResponse disableSchedule(long feeScheduleId) {
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        orderFeeRepository.disableSchedule(feeScheduleId, Instant.now());
        return orderFeeRepository.findSchedule(feeScheduleId)
                .orElseThrow(() -> new IllegalStateException("fee schedule not found: " + feeScheduleId));
    }

    public FeeScheduleQueryResponse querySchedules(long userId, String symbol, FeeScheduleStatus status, int limit) {
        return orderFeeRepository.querySchedules(userId, normalizeOptionalSymbol(symbol), status,
                limit <= 0 ? DEFAULT_LIMIT : limit);
    }

    private long currentVersion(String symbol) {
        InstrumentRule rule = instrumentRuleLookup.currentRule(symbol)
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
        return rule.version();
    }

    private String normalizeSymbol(String symbol) {
        String normalized = normalizeOptionalSymbol(symbol);
        if (normalized == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        return normalized;
    }

    private String normalizeOptionalSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }
}
