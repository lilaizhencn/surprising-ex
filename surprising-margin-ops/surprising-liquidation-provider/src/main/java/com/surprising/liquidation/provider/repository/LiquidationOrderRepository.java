package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.TradingOutboxRecord;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationOrderRepository {

    private static final String REDUCE_ONLY_PREEMPT_REASON = "LIQUIDATION_PREEMPTED_REDUCE_ONLY";

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationSequenceRepository sequenceRepository;
    private final LiquidationProperties properties;

    public LiquidationOrderRepository(JdbcTemplate jdbcTemplate,
                                      LiquidationSequenceRepository sequenceRepository,
                                      LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                         long userId,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         PositionSide positionSide,
                                                         long instrumentVersion,
                                                         OrderSide side,
                                                         long quantitySteps,
                                                         Instant now,
                                                         java.util.function.Function<Object, String> serializer) {
        return createReduceOnlyMarketOrders(List.of(new LiquidationOrderRequest(candidateId, userId, symbol,
                marginMode, positionSide, instrumentVersion, side, quantitySteps, now)), serializer)
                .getFirst().command();
    }

    public List<LiquidationOrderSubmission> createReduceOnlyMarketOrders(
            List<LiquidationOrderRequest> requests,
            java.util.function.Function<Object, String> serializer) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, FeeSnapshot> feeSnapshots = feeSnapshots(requests);
        List<PreparedLiquidationOrder> prepared = new ArrayList<>(requests.size());
        for (LiquidationOrderRequest request : requests) {
            long orderId = sequenceRepository.nextTradingSequence("order");
            long eventId = sequenceRepository.nextTradingSequence("event");
            long commandId = sequenceRepository.nextTradingSequence("command");
            long acceptedOutboxId = sequenceRepository.nextTradingSequence("outbox");
            long placeOutboxId = sequenceRepository.nextTradingSequence("outbox");
            String clientOrderId = "LIQ-" + request.candidateId();
            FeeSnapshot feeSnapshot = feeSnapshots.get(request.candidateId());
            if (feeSnapshot == null) {
                throw new IllegalStateException("fee schedule unavailable for liquidation candidate "
                        + request.candidateId());
            }
            OrderEvent event = new OrderEvent(eventId, orderId, request.userId(), request.symbol(),
                    OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, "LIQUIDATION", request.now());
            OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId,
                    request.userId(), clientOrderId, request.symbol(), request.instrumentVersion(), request.side(),
                    OrderType.MARKET, TimeInForce.IOC, 0L, request.quantitySteps(),
                    MarginMode.defaultIfNull(request.marginMode()), PositionSide.defaultIfNull(request.positionSide()),
                    0L, 0L, true, false, request.now(), null);
            prepared.add(new PreparedLiquidationOrder(request, orderId, clientOrderId, feeSnapshot, event, command,
                    acceptedOutboxId, placeOutboxId, serializer.apply(event), serializer.apply(command)));
        }
        int[] orderRows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_orders (
                    order_id, product_line, user_id, client_order_id, symbol, instrument_version, side, order_type, time_in_force,
                    price_ticks, quantity_steps, executed_quantity_steps, remaining_quantity_steps,
                    margin_mode, position_side, maker_fee_rate_ppm, taker_fee_rate_ppm,
                    reduce_only, post_only, status, reject_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MARKET', 'IOC', 0, ?, 0, ?, ?, ?, ?, ?,
                    TRUE, FALSE, 'ACCEPTED', NULL, ?, ?)
                """, prepared.stream().map(row -> new Object[]{
                row.orderId(), row.feeSnapshot().productLine().name(), row.request().userId(), row.clientOrderId(),
                row.request().symbol(), row.request().instrumentVersion(), row.request().side().name(),
                row.request().quantitySteps(), row.request().quantitySteps(),
                MarginMode.defaultIfNull(row.request().marginMode()).name(),
                PositionSide.defaultIfNull(row.request().positionSide()).name(),
                row.feeSnapshot().makerFeeRatePpm(), row.feeSnapshot().takerFeeRatePpm(),
                Timestamp.from(row.request().now()), Timestamp.from(row.request().now())
        }).toList());
        requireBatchRows(orderRows, prepared.size(), "liquidation trading orders");

        int[] eventRows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, prepared.stream().map(row -> new Object[]{
                row.event().eventId(), row.event().orderId(), row.event().userId(), row.event().symbol(),
                row.event().eventType().name(), row.event().status().name(), row.event().reason(),
                Timestamp.from(row.event().eventTime())
        }).toList());
        requireBatchRows(eventRows, prepared.size(), "liquidation order events");

        String orderEventsTopic = properties.getKafka().getOrderEventsTopic();
        String orderCommandsTopic = properties.getKafka().getOrderCommandsTopic();
        requireCurrentProductTopic(orderEventsTopic);
        requireCurrentProductTopic(orderCommandsTopic);
        List<Object[]> outboxArguments = new ArrayList<>(prepared.size() * 2);
        for (PreparedLiquidationOrder row : prepared) {
            Timestamp timestamp = Timestamp.from(row.request().now());
            outboxArguments.add(new Object[]{row.acceptedOutboxId(), "LIQUIDATION_ORDER", row.orderId(),
                    orderEventsTopic, row.request().symbol(), OrderEventType.ACCEPTED.name(), row.eventPayload(),
                    timestamp, timestamp, timestamp});
            outboxArguments.add(new Object[]{row.placeOutboxId(), "LIQUIDATION_ORDER", row.orderId(),
                    orderCommandsTopic, row.request().symbol(), OrderCommandType.PLACE.name(), row.commandPayload(),
                    timestamp, timestamp, timestamp});
        }
        int[] outboxRows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxArguments);
        requireBatchRows(outboxRows, prepared.size() * 2, "liquidation outbox events");
        return prepared.stream().map(row -> new LiquidationOrderSubmission(row.request().candidateId(),
                row.command())).toList();
    }

    public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                         long userId,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long instrumentVersion,
                                                         OrderSide side,
                                                         long quantitySteps,
                                                         Instant now,
                                                         java.util.function.Function<Object, String> serializer) {
        return createReduceOnlyMarketOrder(candidateId, userId, symbol, marginMode, PositionSide.NET,
                instrumentVersion, side, quantitySteps, now, serializer);
    }

    public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                         long userId,
                                                         String symbol,
                                                         long instrumentVersion,
                                                         OrderSide side,
                                                         long quantitySteps,
                                                         Instant now,
                                                         java.util.function.Function<Object, String> serializer) {
        return createReduceOnlyMarketOrder(candidateId, userId, symbol, MarginMode.CROSS, PositionSide.NET,
                instrumentVersion, side, quantitySteps, now, serializer);
    }

    private java.util.Map<Long, FeeSnapshot> feeSnapshots(List<LiquidationOrderRequest> requests) {
        String values = String.join(", ", Collections.nCopies(requests.size(),
                "(?, ?, ?, ?, CAST(? AS timestamptz))"));
        List<Object> args = new ArrayList<>(requests.size() * 5);
        for (LiquidationOrderRequest request : requests) {
            args.add(request.candidateId());
            args.add(request.userId());
            args.add(request.symbol());
            args.add(request.instrumentVersion());
            args.add(Timestamp.from(request.now()));
        }
        String sql = """
                WITH requested(candidate_id, user_id, symbol, instrument_version, effective_time) AS (
                    VALUES %s
                ),
                instrument_fee AS (
                    SELECT r.candidate_id,
                           r.user_id,
                           r.symbol,
                           r.effective_time,
                           i.maker_fee_rate_ppm,
                           i.taker_fee_rate_ppm,
                           %s AS product_line
                      FROM requested r
                      JOIN instruments i
                        ON i.symbol = r.symbol
                       AND i.version = r.instrument_version
                )
                SELECT i.candidate_id,
                       COALESCE(u.maker_fee_rate_ppm, i.maker_fee_rate_ppm) AS maker_fee_rate_ppm,
                       COALESCE(u.taker_fee_rate_ppm, i.taker_fee_rate_ppm) AS taker_fee_rate_ppm,
                       i.product_line
                  FROM instrument_fee i
             LEFT JOIN LATERAL (
                    SELECT f.maker_fee_rate_ppm,
                           f.taker_fee_rate_ppm
                      FROM trading_fee_schedules f
                     WHERE f.user_id = i.user_id
                       AND f.product_line = i.product_line
                       AND f.status = 'ACTIVE'
                       AND (f.symbol = i.symbol OR f.symbol IS NULL)
                       AND f.effective_time <= i.effective_time
                       AND (f.expire_time IS NULL OR f.expire_time > i.effective_time)
                     ORDER BY CASE WHEN f.symbol = i.symbol THEN 0 ELSE 1 END,
                              CASE f.source_type
                                  WHEN 'RISK_OVERRIDE' THEN 0
                                  WHEN 'USER_OVERRIDE' THEN 1
                                  WHEN 'PROMOTION' THEN 2
                                  WHEN 'MARKET_MAKER' THEN 3
                                  WHEN 'VIP' THEN 4
                                  ELSE 5
                              END,
                              f.effective_time DESC,
                              f.fee_schedule_id DESC
                     LIMIT 1
                ) u ON TRUE
                """.formatted(values, ProductLineSql.contractTypeProductLineCase("i.contract_type"));
        java.util.Map<Long, FeeSnapshot> snapshots = new java.util.HashMap<>(requests.size());
        List<FeeSnapshotRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new FeeSnapshotRow(
                rs.getLong("candidate_id"), new FeeSnapshot(ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("maker_fee_rate_ppm"), rs.getLong("taker_fee_rate_ppm"))), args.toArray());
        for (FeeSnapshotRow row : rows) {
            snapshots.put(row.candidateId(), row.snapshot());
        }
        if (snapshots.size() != requests.size()) {
            throw new IllegalStateException("fee schedule unavailable for liquidation batch: expected="
                    + requests.size() + " actual=" + snapshots.size());
        }
        return snapshots;
    }

    public int cancelOpenReduceOnlyCloseOrders(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               long instrumentVersion,
                                               OrderSide closeSide,
                                               Instant now,
                                               java.util.function.Function<Object, String> serializer) {
        return cancelOpenReduceOnlyCloseOrders(List.of(new LiquidationOrderRequest(0L, userId, symbol, marginMode,
                positionSide, instrumentVersion, closeSide, 0L, now)), serializer);
    }

    public int cancelOpenReduceOnlyCloseOrders(List<LiquidationOrderRequest> requests,
                                               java.util.function.Function<Object, String> serializer) {
        if (requests == null || requests.isEmpty()) {
            return 0;
        }
        List<OpenReduceOnlyOrder> orders = lockOpenReduceOnlyCloseOrders(requests);
        for (OpenReduceOnlyOrder order : orders) {
            if (order.status() != OrderStatus.CANCEL_REQUESTED) {
                requestCancel(order, order.preemptedAt(), serializer);
            }
            enqueueCancelCommand(order, order.preemptedAt(), serializer);
        }
        return orders.size();
    }

    public int cancelOpenReduceOnlyCloseOrders(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               long instrumentVersion,
                                               OrderSide closeSide,
                                               Instant now,
                                               java.util.function.Function<Object, String> serializer) {
        return cancelOpenReduceOnlyCloseOrders(userId, symbol, marginMode, PositionSide.NET, instrumentVersion,
                closeSide, now, serializer);
    }

    public int cancelOpenReduceOnlyCloseOrders(long userId,
                                               String symbol,
                                               long instrumentVersion,
                                               OrderSide closeSide,
                                               Instant now,
                                               java.util.function.Function<Object, String> serializer) {
        return cancelOpenReduceOnlyCloseOrders(userId, symbol, MarginMode.CROSS, PositionSide.NET, instrumentVersion,
                closeSide, now, serializer);
    }

    private List<OpenReduceOnlyOrder> lockOpenReduceOnlyCloseOrders(List<LiquidationOrderRequest> requests) {
        String values = String.join(", ", Collections.nCopies(requests.size(),
                "(?, ?, ?, ?, ?, ?, CAST(? AS timestamptz))"));
        List<Object> args = new ArrayList<>(requests.size() * 7 + 1);
        for (LiquidationOrderRequest request : requests) {
            args.add(request.userId());
            args.add(request.symbol());
            args.add(request.marginMode().name());
            args.add(request.positionSide().name());
            args.add(request.instrumentVersion());
            args.add(request.side().name());
            args.add(Timestamp.from(request.now()));
        }
        args.add(currentProductLine().name());
        return jdbcTemplate.query("""
                WITH requested_input(user_id, symbol, margin_mode, position_side, instrument_version, side,
                                     preempted_at) AS (
                    VALUES %s
                ),
                requested AS (
                    SELECT user_id, symbol, margin_mode, position_side, instrument_version, side,
                           min(preempted_at) AS preempted_at
                      FROM requested_input
                     GROUP BY user_id, symbol, margin_mode, position_side, instrument_version, side
                )
                SELECT o.order_id, o.user_id, o.client_order_id, o.symbol, o.instrument_version, o.side,
                       o.order_type, o.time_in_force, o.price_ticks, o.quantity_steps, o.remaining_quantity_steps,
                       o.margin_mode, o.position_side, o.status, o.maker_fee_rate_ppm, o.taker_fee_rate_ppm,
                       o.post_only, r.preempted_at
                  FROM requested r
                  JOIN trading_orders o
                    ON o.user_id = r.user_id
                   AND o.symbol = r.symbol
                   AND o.margin_mode = r.margin_mode
                   AND o.position_side = r.position_side
                   AND o.instrument_version = r.instrument_version
                   AND o.side = r.side
                 WHERE o.product_line = ?
                   AND o.reduce_only = TRUE
                   AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND o.remaining_quantity_steps > 0
                 ORDER BY o.created_at ASC, o.order_id ASC
                 FOR UPDATE OF o
                """.formatted(values), (rs, rowNum) -> new OpenReduceOnlyOrder(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("client_order_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getBoolean("post_only"),
                rs.getTimestamp("preempted_at").toInstant()), args.toArray());
    }

    private void requestCancel(OpenReduceOnlyOrder order,
                               Instant now,
                               java.util.function.Function<Object, String> serializer) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       reject_reason = ?,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, REDUCE_ONLY_PREEMPT_REASON, Timestamp.from(now), order.orderId());
        requireSingleRow(rows, "liquidation reduce-only preempt cancel request");

        OrderEvent event = new OrderEvent(sequenceRepository.nextTradingSequence("event"), order.orderId(),
                order.userId(), order.symbol(), OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED,
                REDUCE_ONLY_PREEMPT_REASON, now);
        int eventRows = jdbcTemplate.update("""
                INSERT INTO trading_order_events (
                    event_id, order_id, user_id, symbol, event_type, status, reason, event_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.userId(), event.symbol(), event.eventType().name(),
                event.status().name(), event.reason(), Timestamp.from(event.eventTime()));
        requireSingleRow(eventRows, "liquidation reduce-only preempt order event");
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderEventsTopic(), order.symbol(),
                OrderEventType.CANCEL_REQUESTED.name(), serializer.apply(event), now);
    }

    private void enqueueCancelCommand(OpenReduceOnlyOrder order,
                                      Instant now,
                                      java.util.function.Function<Object, String> serializer) {
        OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.CANCEL,
                sequenceRepository.nextTradingSequence("command"), order.orderId(), order.userId(),
                order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(), order.orderType(),
                order.timeInForce(), order.priceTicks(), order.quantitySteps(), order.marginMode(),
                order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(), true,
                order.postOnly(), now, null);
        enqueue("ORDER", order.orderId(), properties.getKafka().getOrderCommandsTopic(), order.symbol(),
                OrderCommandType.CANCEL.name(), serializer.apply(command), now);
    }

    public List<TradingOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type = 'LIQUIDATION_ORDER'
                     ORDER BY topic, event_key, id
                ),
                candidates AS (
                    SELECT e.id
                      FROM trading_outbox_events e
                      JOIN earliest c ON c.id = e.id
                     WHERE e.published_at IS NULL
                       AND e.aggregate_type = 'LIQUIDATION_ORDER'
                """);
        appendTopicScope(sql, args);
        sql.append("""
                       AND e.next_attempt_at <= ?
                       AND pg_try_advisory_xact_lock(hashtext(e.topic), hashtext(e.event_key))
                     ORDER BY e.topic, e.event_key, e.id
                     LIMIT ?
                     FOR UPDATE OF e SKIP LOCKED
                )
                UPDATE trading_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                  FROM candidates c
                 WHERE e.id = c.id
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload
                """);
        args.add(Timestamp.from(now));
        args.add(Math.max(1, limit));
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TradingOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), args.toArray());
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "liquidation outbox publish mark");
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        Timestamp timestamp = Timestamp.from(now);
        String placeholders = String.join(", ", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE published_at IS NULL
                   AND aggregate_type = 'LIQUIDATION_ORDER'
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to mark all liquidation outbox events published: expected="
                    + uniqueIds.size() + " actual=" + rows);
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "liquidation outbox failure mark");
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM trading_outbox_events
                     WHERE aggregate_type = 'LIQUIDATION_ORDER'
                       AND published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM trading_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private void enqueue(String aggregateType,
                         long aggregateId,
                         String topic,
                         String key,
                         String eventType,
                         String payload,
                         Instant now) {
        requireCurrentProductTopic(topic);
        long outboxId = sequenceRepository.nextTradingSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, aggregateType, aggregateId, topic, key, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "liquidation outbox enqueue");
    }

    private record OpenReduceOnlyOrder(
            long orderId,
            long userId,
            String clientOrderId,
            String symbol,
            long instrumentVersion,
            OrderSide side,
            OrderType orderType,
            TimeInForce timeInForce,
            long priceTicks,
            long quantitySteps,
            MarginMode marginMode,
            PositionSide positionSide,
            OrderStatus status,
            long makerFeeRatePpm,
            long takerFeeRatePpm,
            boolean postOnly,
            Instant preemptedAt) {
        private OpenReduceOnlyOrder {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    private record FeeSnapshot(ProductLine productLine, long makerFeeRatePpm, long takerFeeRatePpm) {
    }

    private record FeeSnapshotRow(long candidateId, FeeSnapshot snapshot) {
    }

    public record LiquidationOrderRequest(long candidateId,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          PositionSide positionSide,
                                          long instrumentVersion,
                                          OrderSide side,
                                          long quantitySteps,
                                          Instant now) {
        public LiquidationOrderRequest {
            marginMode = MarginMode.defaultIfNull(marginMode);
            positionSide = PositionSide.defaultIfNull(positionSide);
        }
    }

    public record LiquidationOrderSubmission(long candidateId, OrderCommandEvent command) {
    }

    private record PreparedLiquidationOrder(LiquidationOrderRequest request,
                                            long orderId,
                                            String clientOrderId,
                                            FeeSnapshot feeSnapshot,
                                            OrderEvent event,
                                            OrderCommandEvent command,
                                            long acceptedOutboxId,
                                            long placeOutboxId,
                                            String eventPayload,
                                            String commandPayload) {
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND e.topic IN (?, ?)\n");
        args.add(properties.getKafka().getOrderEventsTopic());
        args.add(properties.getKafka().getOrderCommandsTopic());
    }

    private void requireCurrentProductTopic(String topic) {
        LiquidationProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String orderEventsTopic = kafka.getOrderEventsTopic();
        String orderCommandsTopic = kafka.getOrderCommandsTopic();
        if (!orderEventsTopic.equals(topic) && !orderCommandsTopic.equals(topic)) {
            throw new IllegalStateException("liquidation trading outbox topic must match current product line: "
                    + "expected one of [" + orderEventsTopic + ", " + orderCommandsTopic + "] actual=" + topic);
        }
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireBatchRows(int[] rows, int expected, String operation) {
        if (rows.length != expected) {
            throw new IllegalStateException("failed to batch write " + operation + ": expected=" + expected
                    + " actual=" + rows.length);
        }
        for (int row : rows) {
            if (row != 1 && row != java.sql.Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to batch write " + operation + ": row result=" + row);
            }
        }
    }
}
