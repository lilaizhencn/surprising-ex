package com.surprising.trading.order.service;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreCancelAllAfterView;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class CancelAllAfterService {

    private static final int CANCEL_LIMIT = 1000;
    private static final int CLAIM_LIMIT = 100;
    private static final long MAX_COUNTDOWN_MS = 120_000L;

    private final OrderService orderService;
    private final TriggerOrderService triggerOrderService;
    private final AeronCancelAllAfterStore timerStore;

    @Autowired
    public CancelAllAfterService(OrderService orderService,
                                 TriggerOrderService triggerOrderService,
                                 AeronCancelAllAfterStore timerStore) {
        this.orderService = orderService;
        this.triggerOrderService = triggerOrderService;
        this.timerStore = timerStore;
    }

    public CancelAllAfterResponse set(CancelAllAfterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel all after request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request.countdownMs() == null) {
            throw new IllegalArgumentException("countdownMs is required");
        }
        if (request.countdownMs() < 0 || request.countdownMs() > MAX_COUNTDOWN_MS) {
            throw new IllegalArgumentException("countdownMs must be in [0, 120000]");
        }

        String symbolScope = normalizeSymbolScope(request.instrumentId());
        Instant now = Instant.now();
        CoreCancelAllAfterView timer = timerStore.set(request.userId(), symbolScope, request.countdownMs(), now);
        return toResponse(timer);
    }

    public void scanDueTimers() {
        Instant now = Instant.now();
        for (CoreCancelAllAfterView timer : timerStore.due(now, CLAIM_LIMIT)) {
            try {
                var claimed = timerStore.claim(timer, Instant.now());
                if (claimed.isPresent()) cancelDueTimer(claimed.get());
            } catch (RuntimeException failure) {
                log.warn("cancel-all-after step awaits recovery userId={} symbolScope={}",
                        timer.userId(), timer.symbolScope(), failure);
            }
        }
    }

    void cancelDueTimer(CoreCancelAllAfterView timer) {
        String instrumentId = publicSymbol(timer.symbolScope());
        OrderBatchResponse orderResponse;
        TriggerOrderBatchResponse triggerResponse;
        try {
            orderResponse = orderService.cancelTimerOrders(timer.userId(), instrumentId, CANCEL_LIMIT);
            triggerResponse = triggerOrderService.cancelOpenOrders(
                    new CancelOpenTriggerOrdersRequest(timer.userId(), instrumentId, CANCEL_LIMIT));
        } catch (RuntimeException ex) {
            log.warn("cancel-all-after execution failed for userId={} symbolScope={}",
                    timer.userId(), timer.symbolScope(), ex);
            timerStore.retry(timer, Instant.now());
            return;
        }
        if (orderResponse.failed() > 0 || triggerResponse.failed() > 0
                || orderResponse.requested() > 0 || triggerResponse.requested() > 0) {
            timerStore.retry(timer, Instant.now());
        } else {
            timerStore.complete(timer, Instant.now());
        }
    }

    private static CancelAllAfterResponse toResponse(CoreCancelAllAfterView timer) {
        return new CancelAllAfterResponse(
                timer.userId(),
                publicSymbol(timer.symbolScope()),
                timer.countdownMillis(),
                timer.status() == CoreCancelAllAfterStatus.ACTIVE,
                timer.triggerAtEpochMillis() == 0 ? null : Instant.ofEpochMilli(timer.triggerAtEpochMillis()),
                Instant.ofEpochMilli(timer.updatedAtEpochMillis()));
    }

    private static String normalizeSymbolScope(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return "*";
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("instrumentId format is invalid");
        }
        return normalized;
    }

    private static String publicSymbol(String symbolScope) {
        return "*".equals(symbolScope) ? null : symbolScope;
    }

}
