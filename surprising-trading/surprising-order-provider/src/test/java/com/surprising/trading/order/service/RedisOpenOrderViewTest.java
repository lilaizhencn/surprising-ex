package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

class RedisOpenOrderViewTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void writesStableUserScopedKeysAndRevisionThroughOneLuaOperation() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisOpenOrderView view = new RedisOpenOrderView(redis, mapper, new TradingOrderProperties());
        OrderRecord order = order(ProductLine.LINEAR_PERPETUAL, 1001L, 9001L, 7L, OrderStatus.ACCEPTED);
        when(values.get(view.epochKey(order.productLine()))).thenReturn("12");
        when(mapper.writeValueAsString(order)).thenReturn("{\"orderId\":9001}");
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        view.synchronize(order);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly(
                "surprising:order:v1:{LINEAR_PERPETUAL:1001}:open",
                "surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:orders",
                "surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:revisions",
                "surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:epoch");
        assertThat(args.getValue()).containsExactly("12", "1", "{\"orderId\":9001}", "9001", "9001", "7",
                Long.toString(java.time.Duration.ofDays(1).toMillis()));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void terminalOrderRemovesIndexAndSnapshotButRetainsItsRevisionTombstone() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisOpenOrderView view = new RedisOpenOrderView(redis, mock(ObjectMapper.class), new TradingOrderProperties());
        OrderRecord order = order(ProductLine.LINEAR_PERPETUAL, 1001L, 9001L, 8L, OrderStatus.CANCELED);
        when(values.get(view.epochKey(order.productLine()))).thenReturn("12");
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        view.synchronize(order);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), args.capture());
        assertThat(args.getValue()).containsExactly("12", "0", "", "9001", "9001", "8",
                Long.toString(java.time.Duration.ofDays(1).toMillis()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsDescendingOrderIdPageFromHashSnapshotsInTheSameProductLine() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hashes);
        RedisOpenOrderView view = new RedisOpenOrderView(redis, mapper, new TradingOrderProperties());
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        long userId = 1001L;
        when(values.get(view.epochKey(line))).thenReturn("12");
        when(values.get(view.readyKey(line))).thenReturn("12");
        when(values.get(view.userEpochKey(line, userId))).thenReturn("12");
        when(zset.reverseRangeByScore(view.allIndexKey(line, userId), 0D,
                (double) RedisOpenOrderView.MAX_EXACT_ORDER_ID_SCORE, 0L, 256L))
                .thenReturn(new LinkedHashSet<>(List.of("9003", "9002", "9001")));
        when(hashes.get(view.snapshotKey(line, userId), "9003")).thenReturn("9003");
        when(hashes.get(view.snapshotKey(line, userId), "9002")).thenReturn("9002");
        when(hashes.get(view.snapshotKey(line, userId), "9001")).thenReturn("9001");
        when(mapper.readValue("9003", OrderRecord.class)).thenReturn(order(line, userId, 9003L, 3L, OrderStatus.ACCEPTED));
        when(mapper.readValue("9002", OrderRecord.class)).thenReturn(order(line, userId, 9002L, 2L, OrderStatus.PARTIALLY_FILLED));
        when(mapper.readValue("9001", OrderRecord.class)).thenReturn(order(line, userId, 9001L, 1L, OrderStatus.ACCEPTED));

        var page = view.orders(line, userId, null, Long.MAX_VALUE, 2);

        assertThat(page).hasValueSatisfying(result -> {
            assertThat(result.orders()).extracting(OrderRecord::orderId).containsExactly(9003L, 9002L);
            assertThat(result.hasMore()).isTrue();
            assertThat(result.nextOrderId()).isEqualTo(9002L);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void treatsMissingHashSnapshotAsIncompleteInsteadOfReturningAPartialPage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hashes);
        RedisOpenOrderView view = new RedisOpenOrderView(redis, mock(ObjectMapper.class), new TradingOrderProperties());
        ProductLine line = ProductLine.OPTION;
        long userId = 2002L;
        when(values.get(view.epochKey(line))).thenReturn("4");
        when(values.get(view.readyKey(line))).thenReturn("4");
        when(values.get(view.userEpochKey(line, userId))).thenReturn("4");
        when(zset.reverseRangeByScore(view.allIndexKey(line, userId), 0D,
                (double) RedisOpenOrderView.MAX_EXACT_ORDER_ID_SCORE, 0L, 256L))
                .thenReturn(new LinkedHashSet<>(List.of("7001")));

        assertThat(view.orders(line, userId, null, Long.MAX_VALUE, 10)).isEmpty();
    }

    private OrderRecord order(ProductLine line, long userId, long orderId, long revision, OrderStatus status) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new OrderRecord(orderId, line, userId, "client-" + orderId, "BTC-USDT", 7L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L,
                status == OrderStatus.PARTIALLY_FILLED ? 2L : 0L,
                status == OrderStatus.PARTIALLY_FILLED ? 8L : 10L,
                MarginMode.CROSS, PositionSide.NET, 20L, 50L, false, false, status, null, now, now, revision);
    }
}
