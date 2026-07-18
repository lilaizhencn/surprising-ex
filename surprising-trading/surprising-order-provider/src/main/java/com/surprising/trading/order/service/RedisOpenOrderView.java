package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis projection for a user's active orders.
 *
 * <p>Every per-user key shares the {@code {ProductLine:userId}} Cluster hash tag. PostgreSQL is
 * authoritative: an unreadable, stale, or incomplete projection makes callers use PostgreSQL.
 * The revision hash retains terminal tombstones so delayed Kafka messages cannot resurrect an order.
 */
@Component
public class RedisOpenOrderView {
    static final long MAX_EXACT_ORDER_ID_SCORE = 9_007_199_254_740_991L;
    private static final Duration ENTRY_TTL = Duration.ofDays(1);
    private static final int MAX_CANDIDATES_PER_QUERY = 10_000;
    private static final int CANDIDATE_BATCH_SIZE = 256;

    private static final DefaultRedisScript<Long> INITIALIZE_USER = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[4]) == ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> SYNCHRONIZE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[4]) ~= ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
              redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[7])
            end
            local current = tonumber(redis.call('HGET', KEYS[3], ARGV[5]) or '0')
            if tonumber(ARGV[6]) <= current then return 0 end
            redis.call('HSET', KEYS[3], ARGV[5], ARGV[6])
            redis.call('PEXPIRE', KEYS[3], ARGV[7])
            redis.call('PEXPIRE', KEYS[4], ARGV[7])
            if ARGV[2] == '1' then
              redis.call('HSET', KEYS[2], ARGV[5], ARGV[3])
              redis.call('ZADD', KEYS[1], ARGV[4], ARGV[5])
              redis.call('PEXPIRE', KEYS[1], ARGV[7])
              redis.call('PEXPIRE', KEYS[2], ARGV[7])
            else
              redis.call('ZREM', KEYS[1], ARGV[5])
              redis.call('HDEL', KEYS[2], ARGV[5])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TradingOrderProperties properties;

    public RedisOpenOrderView(StringRedisTemplate redis,
                              ObjectMapper mapper,
                              TradingOrderProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties == null ? new TradingOrderProperties() : properties;
    }

    /** Starts a new product-line epoch; user keys become readable only after this epoch is ready. */
    public long startRebuild(ProductLine line) {
        markNotReady(line);
        Long epoch = redis.opsForValue().increment(epochKey(line));
        if (epoch == null || epoch <= 0L) {
            throw new IllegalStateException("failed to allocate open-order Redis epoch");
        }
        return epoch;
    }

    public boolean rebuildRequired(ProductLine line, Duration maxAge) {
        try {
            Long epoch = currentEpoch(line);
            if (epoch == null || !Long.toString(epoch).equals(redis.opsForValue().get(readyKey(line)))) {
                return true;
            }
            String rebuiltAt = redis.opsForValue().get(rebuiltAtKey(line));
            if (rebuiltAt == null) {
                return true;
            }
            long age = Math.max(0L, System.currentTimeMillis() - Long.parseLong(rebuiltAt));
            return age >= Math.max(1L, maxAge.toMillis());
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Clears one user only when it belongs to an older epoch; safe to repeat while rebuilding. */
    public void initializeUser(ProductLine line, long userId, long epoch) {
        requireEpoch(epoch);
        redis.execute(INITIALIZE_USER, userKeys(line, userId), Long.toString(epoch), ttlMillis());
    }

    public void synchronize(OrderRecord order) {
        assertScoreable(order.orderId());
        Long epoch;
        try {
            epoch = currentEpoch(order.productLine());
        } catch (RuntimeException ex) {
            markNotReadyQuietly(order.productLine());
            throw ex;
        }
        if (epoch == null) {
            // Startup rebuild is the recovery path. Do not acknowledge a partial projection as ready.
            return;
        }
        try {
            String member = Long.toString(order.orderId());
            redis.execute(SYNCHRONIZE, userKeys(order.productLine(), order.userId()),
                    Long.toString(epoch),
                    open(order) ? "1" : "0",
                    open(order) ? mapper.writeValueAsString(order) : "",
                    member,
                    member,
                    Long.toString(order.revision()),
                    ttlMillis());
        } catch (Exception ex) {
            markNotReadyQuietly(order.productLine());
            throw new IllegalStateException("failed to synchronize Redis open-order projection", ex);
        }
    }

    public Optional<Page> orders(ProductLine line,
                                 long userId,
                                 String symbol,
                                 long beforeOrderId,
                                 int limit) {
        try {
            Long epoch = currentEpoch(line);
            if (epoch == null || !Long.toString(epoch).equals(redis.opsForValue().get(readyKey(line)))) {
                return Optional.empty();
            }
            if (!Long.toString(epoch).equals(redis.opsForValue().get(userEpochKey(line, userId)))) {
                return Optional.empty();
            }
            long cursor = beforeOrderId <= 0L ? Long.MAX_VALUE : beforeOrderId;
            List<OrderRecord> matches = new ArrayList<>(Math.min(limit + 1, 1_001));
            long offset = 0L;
            int scanned = 0;
            while (matches.size() <= limit && scanned < MAX_CANDIDATES_PER_QUERY) {
                int batch = Math.min(CANDIDATE_BATCH_SIZE, MAX_CANDIDATES_PER_QUERY - scanned);
                var ids = redis.opsForZSet().reverseRangeByScore(allIndexKey(line, userId), 0D,
                        scoreBefore(cursor), offset, batch);
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                for (String id : ids) {
                    Object raw = redis.opsForHash().get(snapshotKey(line, userId), id);
                    if (!(raw instanceof String value)) {
                        return Optional.empty();
                    }
                    OrderRecord order = read(value);
                    if (!valid(order, line, userId, id)) {
                        return Optional.empty();
                    }
                    if (symbol == null || symbol.equals(order.symbol())) {
                        matches.add(order);
                        if (matches.size() > limit) {
                            break;
                        }
                    }
                }
                scanned += ids.size();
                offset += ids.size();
                if (ids.size() < batch) {
                    break;
                }
            }
            if (scanned >= MAX_CANDIDATES_PER_QUERY && matches.size() <= limit) {
                return Optional.empty();
            }
            boolean hasMore = matches.size() > limit;
            List<OrderRecord> page = hasMore ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
            Long nextOrderId = hasMore && !page.isEmpty() ? page.get(page.size() - 1).orderId() : null;
            return Optional.of(new Page(page, hasMore, nextOrderId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void markReady(ProductLine line, long epoch, Duration ttl) {
        requireEpoch(epoch);
        redis.opsForValue().set(readyKey(line), Long.toString(epoch), ttl);
        redis.opsForValue().set(rebuiltAtKey(line), Long.toString(System.currentTimeMillis()));
    }

    public void refreshReady(ProductLine line, Duration ttl) {
        redis.expire(readyKey(line), ttl);
    }

    public void markNotReady(ProductLine line) {
        redis.delete(readyKey(line));
    }

    String allIndexKey(ProductLine line, long userId) {
        return userPrefix(line, userId) + ":open";
    }

    String snapshotKey(ProductLine line, long userId) {
        return userPrefix(line, userId) + ":open:orders";
    }

    String revisionKey(ProductLine line, long userId) {
        return userPrefix(line, userId) + ":open:revisions";
    }

    String userEpochKey(ProductLine line, long userId) {
        return userPrefix(line, userId) + ":open:epoch";
    }

    String epochKey(ProductLine line) {
        return prefix() + ":open:epoch:" + line.name();
    }

    String readyKey(ProductLine line) {
        return prefix() + ":open:ready:" + line.name();
    }

    private String rebuiltAtKey(ProductLine line) {
        return prefix() + ":open:rebuilt-at:" + line.name();
    }

    private List<String> userKeys(ProductLine line, long userId) {
        return List.of(allIndexKey(line, userId), snapshotKey(line, userId), revisionKey(line, userId),
                userEpochKey(line, userId));
    }

    private String userPrefix(ProductLine line, long userId) {
        return prefix() + ":{" + line.name() + ":" + userId + "}";
    }

    private String prefix() {
        String configured = properties.getRedisIndex().getKeyPrefix();
        return configured == null || configured.isBlank() ? "surprising:order:v1" : configured.trim();
    }

    private Long currentEpoch(ProductLine line) {
        String raw = redis.opsForValue().get(epochKey(line));
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw);
    }

    private boolean open(OrderRecord order) {
        return order.status() == OrderStatus.ACCEPTED || order.status() == OrderStatus.PARTIALLY_FILLED;
    }

    private boolean valid(OrderRecord order, ProductLine line, long userId, String member) {
        return order != null
                && order.productLine() == line
                && order.userId() == userId
                && Long.toString(order.orderId()).equals(member)
                && open(order)
                && order.revision() > 0L;
    }

    private OrderRecord read(String value) {
        try {
            return mapper.readValue(value, OrderRecord.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private double scoreBefore(long beforeOrderId) {
        if (beforeOrderId == Long.MAX_VALUE) {
            return MAX_EXACT_ORDER_ID_SCORE;
        }
        if (beforeOrderId <= 1L) {
            return 0D;
        }
        assertScoreable(beforeOrderId - 1L);
        return (double) (beforeOrderId - 1L);
    }

    private void assertScoreable(long orderId) {
        if (orderId <= 0L || orderId > MAX_EXACT_ORDER_ID_SCORE) {
            throw new IllegalArgumentException("orderId cannot be represented exactly as a Redis sorted-set score");
        }
    }

    private void requireEpoch(long epoch) {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("open-order Redis epoch must be positive");
        }
    }

    private String ttlMillis() {
        return Long.toString(ENTRY_TTL.toMillis());
    }

    private void markNotReadyQuietly(ProductLine line) {
        try {
            markNotReady(line);
        } catch (RuntimeException ignored) {
            // The original Redis failure is more useful to the Kafka retry path.
        }
    }

    public record Page(List<OrderRecord> orders, boolean hasMore, Long nextOrderId) {
    }
}
