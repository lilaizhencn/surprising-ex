package com.surprising.trading.api.cache;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleEvent;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内费率计划快照。
 *
 * <p>快照使用整体引用替换，订单线程只做无锁读取。数据库恢复和 Kafka 增量事件都通过同一套
 * replace/apply 入口更新，避免不同节点出现不同的优先级解析逻辑。</p>
 */
public final class FeeScheduleSnapshotCache {

    private static final Comparator<FeeScheduleResponse> EFFECTIVE_ORDER =
            Comparator.comparingInt((FeeScheduleResponse value) -> sourcePriority(value.sourceType()))
                    .thenComparingInt(value -> value.symbol() == null ? 1 : 0)
                    .thenComparing(FeeScheduleResponse::effectiveTime,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparingLong(FeeScheduleResponse::feeScheduleId).reversed());

    private final AtomicReference<State> state = new AtomicReference<>(
            new State(Map.of(), Set.of()));

    /** 用数据库启动快照恢复指定产品线；已经收到的更新事件不会被较旧的启动数据覆盖。 */
    public void replace(ProductLine productLine, List<FeeScheduleResponse> schedules) {
        requireProductLine(productLine);
        Map<ScheduleKey, FeeScheduleResponse> next = new HashMap<>();
        for (FeeScheduleResponse schedule : schedules == null ? List.<FeeScheduleResponse>of() : schedules) {
            if (schedule == null || schedule.productLine() != productLine) {
                continue;
            }
            next.put(new ScheduleKey(productLine, schedule.feeScheduleId()), immutable(schedule));
        }
        state.updateAndGet(previous -> {
            Map<ScheduleKey, FeeScheduleResponse> merged = new HashMap<>(previous.schedules());
            next.forEach((key, incoming) -> {
                FeeScheduleResponse existing = merged.get(key);
                if (existing == null || compareRevision(incoming, existing) >= 0) {
                    merged.put(key, incoming);
                }
            });
            Set<ProductLine> initialized = new java.util.HashSet<>(previous.initializedProductLines());
            initialized.add(productLine);
            return new State(Map.copyOf(merged), Set.copyOf(initialized));
        });
    }

    /**
     * <p>同一费率计划、同一更新时间和同一编号必须携带同一完整内容；否则视为事实源
     * 分叉，不能让后到事件静默覆盖前一个费率。</p>
     */
    public ApplyResult apply(FeeScheduleEvent event) {
        if (event == null || event.schedule() == null || event.productLine() != event.schedule().productLine()) {
            return ApplyResult.INVALID;
        }
        ProductLine productLine = event.productLine();
        ScheduleKey key = new ScheduleKey(productLine, event.feeScheduleId());
        FeeScheduleResponse incoming = immutable(event.schedule());
        ApplyResult[] result = {ApplyResult.APPLIED};
        state.updateAndGet(previous -> {
            FeeScheduleResponse existing = previous.schedules().get(key);
            if (existing != null && compareRevision(incoming, existing) < 0) {
                result[0] = ApplyResult.STALE;
                return previous;
            }
            if (existing != null && compareRevision(incoming, existing) == 0) {
                result[0] = existing.equals(incoming) ? ApplyResult.STALE : ApplyResult.CONFLICT;
                return previous;
            }
            Map<ScheduleKey, FeeScheduleResponse> schedules = new HashMap<>(previous.schedules());
            schedules.put(key, incoming);
            Set<ProductLine> initialized = new java.util.HashSet<>(previous.initializedProductLines());
            initialized.add(productLine);
            return new State(Map.copyOf(schedules), Set.copyOf(initialized));
        });
        return result[0];
    }

    public boolean initialized(ProductLine productLine) {
        return productLine != null && state.get().initializedProductLines().contains(productLine);
    }

    public List<FeeScheduleResponse> schedules(ProductLine productLine) {
        if (productLine == null) {
            return List.of();
        }
        return state.get().schedules().entrySet().stream()
                .filter(entry -> entry.getKey().productLine() == productLine)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingLong(FeeScheduleResponse::feeScheduleId))
                .toList();
    }

    /** 读取单条本地费率事实；管理写入和禁用都必须基于该快照，不得回查数据库。 */
    public Optional<FeeScheduleResponse> find(ProductLine productLine, long feeScheduleId) {
        if (productLine == null || feeScheduleId <= 0L) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.get().schedules().get(new ScheduleKey(productLine, feeScheduleId)));
    }

    /** 返回当前时间适用的用户费率计划；没有覆盖时返回空值，由调用方回退到 Instrument 默认费率。 */
    public Optional<FeeScheduleResponse> effective(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   Instant now) {
        if (!initialized(productLine) || userId <= 0 || symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        String normalizedSymbol = normalize(symbol);
        Instant effectiveAt = now == null ? Instant.now() : now;
        return state.get().schedules().values().stream()
                .filter(value -> value.productLine() == productLine && value.userId() == userId
                        && (value.symbol() == null || normalizedSymbol.equals(normalize(value.symbol())))
                        && value.status() == FeeScheduleStatus.ACTIVE
                        && (value.effectiveTime() == null || !value.effectiveTime().isAfter(effectiveAt))
                        && (value.expireTime() == null || value.expireTime().isAfter(effectiveAt)))
                .sorted(EFFECTIVE_ORDER)
                .findFirst();
    }

    private static FeeScheduleResponse immutable(FeeScheduleResponse value) {
        return new FeeScheduleResponse(value.feeScheduleId(), value.productLine(), value.userId(),
                value.symbol() == null ? null : normalize(value.symbol()), value.makerFeeRatePpm(),
                value.takerFeeRatePpm(), value.sourceType(), value.tierCode(), value.reason(), value.status(),
                value.effectiveTime(), value.expireTime(), value.createdAt(), value.updatedAt());
    }

    private static int compareRevision(FeeScheduleResponse left, FeeScheduleResponse right) {
        Instant leftTime = left.updatedAt();
        Instant rightTime = right.updatedAt();
        int time = Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).compare(leftTime, rightTime);
        if (time != 0) {
            return time;
        }
        return Long.compare(left.feeScheduleId(), right.feeScheduleId());
    }

    private static int sourcePriority(FeeScheduleSourceType sourceType) {
        if (sourceType == null) {
            return 5;
        }
        return switch (sourceType) {
            case RISK_OVERRIDE -> 0;
            case USER_OVERRIDE -> 1;
            case PROMOTION -> 2;
            case MARKET_MAKER -> 3;
            case VIP -> 4;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static void requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
    }

    private record ScheduleKey(ProductLine productLine, long feeScheduleId) {
    }

    private record State(Map<ScheduleKey, FeeScheduleResponse> schedules,
                         Set<ProductLine> initializedProductLines) {
    }

    public enum ApplyResult {
        APPLIED,
        STALE,
        CONFLICT,
        INVALID
    }
}
