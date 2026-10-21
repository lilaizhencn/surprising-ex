package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cProfileRows.OrderStats;
import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.c2c.model.C2cUserOrderFilter;
import com.surprising.gateway.c2c.model.C2cOrderPlacement;
import com.surprising.gateway.c2c.model.C2cOrderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只写 c2c_orders；报价、事件和资金意图由订单Service在同一事务聚合。 */
@Repository
public class C2cOrdersRepository {


    private final JdbcTemplate jdbc;
    public C2cOrdersRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public List<C2cOrder> mine(long userId, C2cUserOrderFilter filter) {
        String status=switch(filter.scope()) {
            case ONGOING -> "o.status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')";
            case APPEALED -> "o.status='APPEALED'";
            case ENDED -> "o.status IN ('COMPLETED','CANCELED','EXPIRED','FAILED')";
            case ALL -> "true";
        };
        String side=filter.side().name();
        return jdbc.query("""
                SELECT o.* FROM c2c_orders o WHERE (o.buyer_user_id=? OR o.seller_user_id=?) AND %s
                    AND (CAST(? AS uuid) IS NULL OR o.market_id=?) AND (CAST(? AS uuid) IS NULL OR o.order_id=?)
                    AND (?='ALL' OR (?='BUY' AND o.buyer_user_id=?) OR (?='SELL' AND o.seller_user_id=?))
                ORDER BY o.created_at DESC,o.order_id DESC LIMIT ? OFFSET ?
                """.formatted(status),this::readOrder,userId,userId,filter.marketId(),filter.marketId(),filter.orderId(),filter.orderId(),
                side,side,userId,side,userId,filter.limit()+1,filter.offset());
    }

    public List<C2cOrder> adminPage(String status, Long userId, UUID orderId, UUID market, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_orders WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS bigint) IS NULL OR buyer_user_id=? OR seller_user_id=?)
                    AND (CAST(? AS uuid) IS NULL OR order_id=?) AND (CAST(? AS uuid) IS NULL OR market_id=?)
                ORDER BY updated_at DESC,order_id DESC LIMIT ? OFFSET ?
                """, this::readOrder, status, status, userId, userId, userId, orderId, orderId, market, market, count, offset);
    }

    public C2cOrder get(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_orders WHERE order_id=?"+(lock?" FOR UPDATE":""),this::readOrder,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("订单不存在");
        return rows.getFirst();
    }
    C2cOrder byQuote(UUID id) {
        return jdbc.query("SELECT * FROM c2c_orders WHERE quote_id=?",this::readOrder,id).stream().findFirst().orElse(null);
    }

    void insert(C2cOrderPlacement order) {
        jdbc.update("""
                INSERT INTO c2c_orders(order_id,quote_id,ad_id,market_id,creator_user_id,buyer_user_id,seller_user_id,
                    quantity_units,buyer_fee_units,fiat_amount,price,payment_account_id,
                    payment_details_ciphertext,payment_window_seconds,terms,status,payment_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, order.id(), order.quoteId(), order.adId(), order.marketId(), order.creatorUserId(),
                order.buyerUserId(), order.sellerUserId(), order.quantityUnits(), order.feeUnits(), order.fiatAmount(),
                order.price(), order.paymentAccountId(), order.paymentCiphertext(), order.paymentWindowSeconds(), order.terms(),
                order.status().name(), order.paymentExpiresAt() == null ? null : Timestamp.from(order.paymentExpiresAt()));
    }

    Long openCount(UUID marketId, long userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM c2c_orders WHERE market_id=? AND (buyer_user_id=? OR seller_user_id=?)
                    AND status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')
                """, Long.class, marketId, userId, userId);
    }

    public Long completedCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_orders WHERE status='COMPLETED' AND (buyer_user_id=? OR seller_user_id=?)",
                Long.class, userId, userId);
    }

    void awaitingPayment(C2cOrder order) {
        jdbc.update("""
                UPDATE c2c_orders SET status='PAYMENT_PENDING',payment_expires_at=now()+payment_window_seconds*interval '1 second',
                    revision=revision+1,updated_at=now() WHERE order_id=? AND status='RESERVING'
                """,order.id());
    }
    void markPaid(C2cOrder order) {
        jdbc.update("UPDATE c2c_orders SET status='PAID',paid_at=now(),revision=revision+1,updated_at=now() WHERE order_id=?",order.id());
    }
    void state(C2cOrder order,C2cOrderStatus status,String terminal,String reason) {
        jdbc.update("""
                UPDATE c2c_orders SET status=?,requested_terminal=?,cancellation_reason=?,
                    revision=revision+1,updated_at=now() WHERE order_id=?
                """,status.name(),terminal,reason,order.id());
    }
    void terminal(C2cOrder order,C2cOrderStatus status,String reason) {
        boolean completed=status==C2cOrderStatus.COMPLETED;
        jdbc.update("""
                UPDATE c2c_orders SET status=?,cancellation_reason=?,completed_at=CASE WHEN ? THEN now() ELSE completed_at END,
                    canceled_at=CASE WHEN ? THEN canceled_at ELSE now() END,revision=revision+1,updated_at=now()
                WHERE order_id=?
                """,status.name(),reason,completed,completed,order.id());
    }
    public List<UUID> expired(int limit) {
        return jdbc.query("SELECT order_id FROM c2c_orders WHERE status='PAYMENT_PENDING' AND payment_expires_at<=now() ORDER BY payment_expires_at LIMIT ?",
                (r,n)->r.getObject(1,UUID.class),limit);
    }
    /**
     * 关联原资金意图的已提交进度，筛选可续办订单；只读EXISTS不扩大订单行数，不读取或复制Core余额。
     * 数据写入仍仅限c2c_orders，续办批次由调用方指定。
     */
    public List<UUID> fundingReady(int limit) {
        return jdbc.query("""
                SELECT o.order_id FROM c2c_orders o WHERE
                 (o.status='RESERVING' AND EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='ORDER' AND f.owner_id=o.order_id
                    AND f.purpose='ORDER_RESERVE' AND f.status IN ('APPLIED','REJECTED')))
                 OR (o.status='REFUND_PENDING' AND EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='ORDER' AND f.owner_id=o.order_id
                    AND f.purpose='ORDER_REFUND' AND f.status='APPLIED'))
                 OR (o.status='RELEASE_PENDING' AND EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='ORDER' AND f.owner_id=o.order_id
                    AND f.purpose='ORDER_RELEASE' AND f.status='APPLIED')
                    AND (o.buyer_fee_units=0 OR EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='ORDER' AND f.owner_id=o.order_id
                    AND f.purpose='BUYER_FEE' AND f.status='APPLIED')))
                ORDER BY o.updated_at LIMIT ?
                """,(r,n)->r.getObject(1,UUID.class),limit);
    }

    C2cOrder readOrder(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new C2cOrder(r.getObject("order_id",UUID.class),r.getObject("quote_id",UUID.class),r.getObject("ad_id",UUID.class),
                r.getObject("market_id",UUID.class),r.getLong("creator_user_id"),r.getLong("buyer_user_id"),r.getLong("seller_user_id"),
                r.getLong("quantity_units"),r.getLong("buyer_fee_units"),
                r.getBigDecimal("fiat_amount"),r.getBigDecimal("price"),r.getObject("payment_account_id",UUID.class),
                r.getString("payment_details_ciphertext"),r.getInt("payment_window_seconds"),r.getString("terms"),
                C2cOrderStatus.valueOf(r.getString("status")),r.getString("requested_terminal"),instant(r,"payment_expires_at"),
                instant(r,"paid_at"),instant(r,"completed_at"),instant(r,"canceled_at"),r.getString("cancellation_reason"),
                r.getLong("revision"),r.getTimestamp("created_at").toInstant());
    }
    private static Instant instant(java.sql.ResultSet r,String field) throws java.sql.SQLException {
        var value=r.getTimestamp(field);return value==null?null:value.toInstant();
    }
    public OrderStats userStats(long userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE status='COMPLETED') AS completed,
                    count(*) FILTER(WHERE status IN ('CANCELED','EXPIRED')) AS canceled,
                    count(*) FILTER(WHERE status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')) AS ongoing,
                    count(*) FILTER(WHERE status='APPEALED') AS appealed
                FROM c2c_orders WHERE buyer_user_id=? OR seller_user_id=?
                """,(r,n)->new OrderStats(r.getLong("completed"),r.getLong("canceled"),r.getLong("ongoing"),r.getLong("appealed")),userId,userId);
    }

    public boolean hasOpenOrders(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_orders WHERE (buyer_user_id=? OR seller_user_id=?)
                    AND status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED'))
                """,Boolean.class,userId,userId));
    }

}
