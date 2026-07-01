package com.surprising.trading.order.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.service.OrderMarginMath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMarginRepository {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;

    public OrderMarginRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
    }

    public Optional<MarginRequirement> requirement(String symbol,
                                                   long instrumentVersion,
                                                   OrderSide side,
                                                   OrderType orderType,
                                                   long priceTicks,
                                                   long quantitySteps,
                                                   long marketMaxSlippagePpm,
                                                   long marketMaxMarkAgeMs) {
        String sql = """
                SELECT i.contract_type,
                       i.settle_asset AS asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       ss.scale_units AS settle_scale_units,
                       pm.mark_ticks
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                  LEFT JOIN LATERAL (
                      SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_ticks
                        FROM price_mark_ticks m
                       WHERE m.symbol = i.symbol
                         AND m.event_time >= now() - (? * INTERVAL '1 millisecond')
                       ORDER BY m.event_time DESC
                       LIMIT 1
                  ) pm ON TRUE
                 WHERE i.symbol = ?
                   AND i.version = ?
                   AND (? <> 'MARKET' OR pm.mark_ticks IS NOT NULL)
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            long markTicks = rs.getLong("mark_ticks");
            Long nullableMarkTicks = rs.wasNull() ? null : markTicks;
            long initialMarginUnits = OrderMarginMath.initialMarginUnits(
                    ContractType.valueOf(rs.getString("contract_type")),
                    side,
                    orderType,
                    priceTicks,
                    quantitySteps,
                    nullableMarkTicks,
                    marketMaxSlippagePpm,
                    rs.getLong("notional_multiplier_units"),
                    rs.getLong("price_tick_units"),
                    rs.getLong("settle_scale_units"),
                    rs.getLong("initial_margin_rate_ppm"));
            return new MarginRequirement(rs.getString("asset"), initialMarginUnits);
        }, marketMaxMarkAgeMs, symbol, instrumentVersion, orderType.name()).stream().findFirst();
    }

    public boolean reserve(long userId,
                           String asset,
                           long orderId,
                           String symbol,
                           MarginMode marginMode,
                           long amountUnits,
                           Instant now) {
        if (amountUnits <= 0) {
            return true;
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        var balance = jdbcTemplate.query("""
                SELECT available_units, locked_units
                  FROM account_balances
                 WHERE user_id = ? AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("available_units"), rs.getLong("locked_units")},
                userId, asset).stream().findFirst().orElse(new long[] {0L, 0L});
        if (balance[0] < amountUnits) {
            return false;
        }
        long reservationId = orderRepository.nextSequence("margin-reservation");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_margin_reservations (
                    reservation_id, user_id, asset, order_id, symbol,
                    margin_mode, reserved_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'ORDER_INITIAL_MARGIN', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, reservationId, userId, asset, orderId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                amountUnits,
                Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to insert margin reservation for order " + orderId);
        }
        rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND available_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("failed to reserve order margin for order " + orderId);
        }
        return true;
    }
}
