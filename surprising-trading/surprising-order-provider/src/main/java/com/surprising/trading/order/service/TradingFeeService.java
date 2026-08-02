package com.surprising.trading.order.service;

import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.FeeScheduleSnapshotResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingFeeService {

    private static final int DEFAULT_LIMIT = 100;

    private final OrderFeeRepository orderFeeRepository;
    private final OrderRepository orderRepository;
    private final InstrumentRuleLookup instrumentRuleLookup;
    private final OrderFeeSnapshotLookup feeSnapshotLookup;
    private final FeeScheduleEventPublisher eventPublisher;
    private final TradingOrderProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public TradingFeeService(OrderFeeRepository orderFeeRepository,
                             OrderRepository orderRepository,
                             InstrumentRuleLookup instrumentRuleLookup,
                             OrderFeeSnapshotLookup feeSnapshotLookup,
                             FeeScheduleEventPublisher eventPublisher,
                             TradingOrderProperties properties) {
        this.orderFeeRepository = orderFeeRepository;
        this.orderRepository = orderRepository;
        this.instrumentRuleLookup = instrumentRuleLookup;
        this.feeSnapshotLookup = feeSnapshotLookup;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    public EffectiveTradingFeeResponse effectiveFee(long userId, String symbol, long instrumentVersion) {
        return effectiveFee(userId, symbol, instrumentVersion, null);
    }

    public EffectiveTradingFeeResponse effectiveFee(long userId,
                                                    String symbol,
                                                    long instrumentVersion,
                                                    ProductLine productLine) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        long resolvedVersion = instrumentVersion > 0 ? instrumentVersion : currentVersion(normalizedSymbol);
        Instant now = Instant.now();
        OrderFeeSnapshot snapshot = (feeSnapshotLookup == null
                ? java.util.Optional.<OrderFeeSnapshot>empty()
                : feeSnapshotLookup.lookup(productLine, userId, normalizedSymbol, resolvedVersion, now))
                .orElseThrow(() -> new IllegalStateException("fee schedule unavailable"));
        if (productLine != null && snapshot.productLine() != productLine) {
            throw new IllegalStateException("fee schedule unavailable for productLine: " + productLine);
        }
        return new EffectiveTradingFeeResponse(userId, snapshot.productLine(), normalizedSymbol, resolvedVersion,
                snapshot.makerFeeRatePpm(), snapshot.takerFeeRatePpm(), snapshot.source(), now);
    }

    @Transactional
    public FeeScheduleResponse upsertSchedule(FeeScheduleUpsertRequest request) {
        OrderFeeRepository.validateSchedule(request);
        requireCurrentProductLine(request.productLine());
        long feeScheduleId = request.feeScheduleId() == null
                ? orderRepository.nextSequence("fee-schedule")
                : request.feeScheduleId();
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        Instant now = Instant.now();
        orderFeeRepository.upsertSchedule(request, feeScheduleId, now);
        FeeScheduleResponse response = orderFeeRepository.findSchedule(feeScheduleId, request.productLine())
                .orElseThrow(() -> new IllegalStateException("fee schedule upsert failed: " + feeScheduleId));
        if (eventPublisher != null) {
            eventPublisher.publish(response);
        }
        return response;
    }

    @Transactional
    public FeeScheduleResponse disableSchedule(long feeScheduleId) {
        return disableSchedule(feeScheduleId, currentProductLine());
    }

    @Transactional
    public FeeScheduleResponse disableSchedule(long feeScheduleId, ProductLine productLine) {
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        requireCurrentProductLine(productLine);
        boolean changed = orderFeeRepository.disableSchedule(feeScheduleId, productLine, Instant.now());
        FeeScheduleResponse response = orderFeeRepository.findSchedule(feeScheduleId, productLine)
                .orElseThrow(() -> new IllegalStateException("fee schedule not found: " + feeScheduleId));
        if (changed && eventPublisher != null) {
            eventPublisher.publish(response);
        }
        return response;
    }

    /** 唯一内部 RPC 的启动快照查询；其他模块不直接读取费率表。 */
    public FeeScheduleSnapshotResponse snapshot(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        requireCurrentProductLine(productLine);
        List<FeeScheduleResponse> schedules = orderFeeRepository.loadSnapshotSchedules(productLine);
        long sequence = schedules.stream().mapToLong(FeeScheduleResponse::feeScheduleId).max().orElse(0L);
        return new FeeScheduleSnapshotResponse(productLine, sequence,
                Integer.toHexString(schedules.hashCode()), schedules);
    }

    public FeeScheduleQueryResponse querySchedules(long userId, String symbol, FeeScheduleStatus status, int limit) {
        return querySchedules(currentProductLine(), userId, symbol, status, limit);
    }

    public FeeScheduleQueryResponse querySchedules(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   FeeScheduleStatus status,
                                                   int limit) {
        requireCurrentProductLine(productLine);
        return orderFeeRepository.querySchedules(productLine, userId, normalizeOptionalSymbol(symbol), status,
                limit <= 0 ? DEFAULT_LIMIT : limit);
    }

    public FeeScheduleQueryResponse querySchedules(long userId,
                                                   String symbol,
                                                   FeeScheduleStatus status,
                                                   int limit,
                                                   String cursor,
                                                   String sort) {
        return orderFeeRepository.querySchedulesPage(currentProductLine(), userId, normalizeOptionalSymbol(symbol),
                status, limit <= 0 ? DEFAULT_LIMIT : limit, cursor, sort);
    }

    public FeeScheduleQueryResponse querySchedules(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   FeeScheduleStatus status,
                                                   int limit,
                                                   String cursor,
                                                   String sort) {
        requireCurrentProductLine(productLine);
        return orderFeeRepository.querySchedulesPage(productLine, userId, normalizeOptionalSymbol(symbol), status,
                limit <= 0 ? DEFAULT_LIMIT : limit, cursor, sort);
    }

    private long currentVersion(String symbol) {
        InstrumentRule rule = instrumentRuleLookup.currentRule(symbol)
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
        return rule.version();
    }

    private void requireCurrentProductLine(ProductLine requested) {
        if (properties == null || properties.getKafka() == null) {
            throw new IllegalStateException("交易费率服务未配置产品线");
        }
        ProductLineConfiguration.requireSame(properties.getKafka().getProductLine(), requested, "trading fee");
    }

    private ProductLine currentProductLine() {
        if (properties == null || properties.getKafka() == null
                || properties.getKafka().getProductLine() == null) {
            throw new IllegalStateException("交易费率服务未配置产品线");
        }
        return properties.getKafka().getProductLine();
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
