package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.TriggerOrderRpcApi;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import com.surprising.trading.order.repository.CancelAllAfterRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CancelAllAfterService {

    private static final Logger log = LoggerFactory.getLogger(CancelAllAfterService.class);
    private static final int CANCEL_LIMIT = 1000;
    private static final int CLAIM_LIMIT = 100;
    private static final long MAX_COUNTDOWN_MS = 120_000L;

    private final TradingOrderProperties properties;
    private final CancelAllAfterRepository repository;
    private final OrderService orderService;
    private final TriggerOrderRpcApi triggerOrderRpcApi;
    private final OrderScheduleIndex scheduleIndex;

    @Autowired
    public CancelAllAfterService(TradingOrderProperties properties,
                                 CancelAllAfterRepository repository,
                                 OrderService orderService,
                                 TriggerOrderRpcApi triggerOrderRpcApi) {
        this(properties, repository, orderService, triggerOrderRpcApi, OrderScheduleIndex.disabled());
    }

    public CancelAllAfterService(TradingOrderProperties properties,
                                 CancelAllAfterRepository repository,
                                 OrderService orderService,
                                 TriggerOrderRpcApi triggerOrderRpcApi,
                                 OrderScheduleIndex scheduleIndex) {
        this.properties = properties;
        this.repository = repository;
        this.orderService = orderService;
        this.triggerOrderRpcApi = triggerOrderRpcApi;
        this.scheduleIndex = scheduleIndex;
    }

    @Transactional
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

        String symbolScope = normalizeSymbolScope(request.symbol());
        Instant now = Instant.now();
        boolean active = request.countdownMs() > 0;
        Instant triggerAt = active ? now.plusMillis(request.countdownMs()) : null;
        CancelAllAfterTimer timer = repository.upsert(currentProductLine(), request.userId(), symbolScope,
                request.countdownMs(), triggerAt, active ? "ACTIVE" : "DISABLED",
                now, TraceContext.currentOrCreate());
        afterCommit(() -> scheduleIndex.synchronizeTimer(currentProductLine(), timer));
        return toResponse(timer);
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.cancel-all-after.scan-delay-ms:250}")
    public void scanDueTimers() {
        Instant now = Instant.now();
        List<CancelAllAfterTimer> timers = scheduleIndex.dueTimers(currentProductLine(), now, CLAIM_LIMIT)
                .map(candidates -> candidates.stream()
                        .map(candidate -> repository.claimDueTimer(currentProductLine(), candidate.userId(),
                                candidate.symbolScope(), now))
                        .flatMap(java.util.Optional::stream)
                        .toList())
                .orElseGet(() -> repository.claimDueTimers(currentProductLine(), now, CLAIM_LIMIT));
        for (CancelAllAfterTimer timer : timers) {
            cancelDueTimer(timer);
        }
    }

    void cancelDueTimer(CancelAllAfterTimer timer) {
        String symbol = publicSymbol(timer.symbolScope());
        try {
            OrderBatchResponse orderResponse = orderService.cancelOpenOrders(
                    new CancelOpenOrdersRequest(timer.userId(), symbol, CANCEL_LIMIT));
            TriggerOrderBatchResponse triggerResponse = triggerOrderRpcApi.cancelOpen(
                    new CancelOpenTriggerOrdersRequest(timer.userId(), symbol, CANCEL_LIMIT));
            repository.markTriggered(currentProductLine(), timer.userId(), timer.symbolScope(),
                    orderResponse.completed(), triggerResponse.completed(), Instant.now());
            scheduleIndex.removeTimer(currentProductLine(), timer.userId(), timer.symbolScope());
        } catch (RuntimeException ex) {
            repository.releaseForRetry(currentProductLine(), timer.userId(), timer.symbolScope(),
                    ex.getMessage(), Instant.now());
            scheduleIndex.synchronizeTimer(currentProductLine(), timer);
            log.warn("cancel-all-after execution failed for userId={} symbolScope={}",
                    timer.userId(), timer.symbolScope(), ex);
        }
    }

    private static CancelAllAfterResponse toResponse(CancelAllAfterTimer timer) {
        return new CancelAllAfterResponse(
                timer.userId(),
                publicSymbol(timer.symbolScope()),
                timer.countdownMs(),
                "ACTIVE".equals(timer.status()),
                timer.triggerAt(),
                timer.updatedAt(),
                timer.canceledOrders(),
                timer.canceledTriggerOrders());
    }

    private static String normalizeSymbolScope(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return CancelAllAfterRepository.ALL_SYMBOLS_SCOPE;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9][A-Z0-9_-]{1,63}$")) {
            throw new IllegalArgumentException("symbol format is invalid");
        }
        return normalized;
    }

    private static String publicSymbol(String symbolScope) {
        return CancelAllAfterRepository.ALL_SYMBOLS_SCOPE.equals(symbolScope) ? null : symbolScope;
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
