package com.surprising.trading.order.service;

import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.FeeScheduleSnapshotResponse;
import com.surprising.trading.api.cache.FeeScheduleSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.repository.OrderFeeRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TradingFeeService {

    private static final int DEFAULT_LIMIT = 100;

    private final OrderFeeRepository orderFeeRepository;
    private final OrderIdSequenceStore idSequenceStore;
    private final InstrumentRuleLookup instrumentRuleLookup;
    private final OrderFeeSnapshotLookup feeSnapshotLookup;
    private final FeeScheduleEventPublisher eventPublisher;
    private final FeeScheduleSnapshotCache feeScheduleSnapshotCache;
    private final TradingOrderProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public TradingFeeService(OrderFeeRepository orderFeeRepository,
                             OrderIdSequenceStore idSequenceStore,
                             InstrumentRuleLookup instrumentRuleLookup,
                             OrderFeeSnapshotLookup feeSnapshotLookup,
                             FeeScheduleEventPublisher eventPublisher,
                             FeeScheduleSnapshotCache feeScheduleSnapshotCache,
                             TradingOrderProperties properties) {
        this.orderFeeRepository = orderFeeRepository;
        this.idSequenceStore = idSequenceStore;
        this.instrumentRuleLookup = instrumentRuleLookup;
        this.feeSnapshotLookup = feeSnapshotLookup;
        this.eventPublisher = eventPublisher;
        this.feeScheduleSnapshotCache = feeScheduleSnapshotCache;
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

    public FeeScheduleResponse upsertSchedule(FeeScheduleUpsertRequest request) {
        OrderFeeRepository.validateSchedule(request);
        requireCurrentProductLine(request.productLine());
        long feeScheduleId = request.feeScheduleId() == null
                ? idSequenceStore.next()
                : request.feeScheduleId();
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        Instant now = Instant.now();
        FeeScheduleResponse previous = feeScheduleSnapshotCache == null ? null
                : feeScheduleSnapshotCache.find(request.productLine(), feeScheduleId).orElse(null);
        FeeScheduleResponse response = new FeeScheduleResponse(feeScheduleId, request.productLine(), request.userId(),
                normalizeOptionalSymbol(request.symbol()), request.makerFeeRatePpm(), request.takerFeeRatePpm(),
                request.sourceType() == null ? com.surprising.trading.api.model.FeeScheduleSourceType.USER_OVERRIDE
                        : request.sourceType(),
                emptyToNull(request.tierCode()), request.reason().trim(),
                request.status() == null ? FeeScheduleStatus.ACTIVE : request.status(),
                request.effectiveTime() == null ? now : request.effectiveTime(), request.expireTime(),
                previous == null || previous.createdAt() == null ? now : previous.createdAt(), now);
        if (eventPublisher == null) {
            throw new IllegalStateException("费率事件发布器未配置");
        }
        eventPublisher.publish(response);
        return response;
    }

    public FeeScheduleResponse disableSchedule(long feeScheduleId) {
        return disableSchedule(feeScheduleId, currentProductLine());
    }

    public FeeScheduleResponse disableSchedule(long feeScheduleId, ProductLine productLine) {
        if (feeScheduleId <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        requireCurrentProductLine(productLine);
        FeeScheduleResponse current = feeScheduleSnapshotCache == null ? null
                : feeScheduleSnapshotCache.find(productLine, feeScheduleId).orElse(null);
        if (current == null) {
            throw new IllegalStateException("fee schedule not found in JVM snapshot: " + feeScheduleId);
        }
        if (current.status() == FeeScheduleStatus.DISABLED) {
            return current;
        }
        FeeScheduleResponse response = new FeeScheduleResponse(current.feeScheduleId(), current.productLine(),
                current.userId(), current.symbol(), current.makerFeeRatePpm(), current.takerFeeRatePpm(),
                current.sourceType(), current.tierCode(), current.reason(), FeeScheduleStatus.DISABLED,
                current.effectiveTime(), current.expireTime(), current.createdAt(), Instant.now());
        if (eventPublisher == null) {
            throw new IllegalStateException("费率事件发布器未配置");
        }
        eventPublisher.publish(response);
        return response;
    }

    /** 唯一内部 RPC 的启动快照查询；其他模块不直接读取费率表。 */
    public FeeScheduleSnapshotResponse snapshot(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        requireCurrentProductLine(productLine);
        if (feeScheduleSnapshotCache == null || !feeScheduleSnapshotCache.initialized(productLine)) {
            throw new IllegalStateException("费率 JVM 快照尚未初始化: " + productLine);
        }
        List<FeeScheduleResponse> schedules = feeScheduleSnapshotCache.schedules(productLine);
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

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
