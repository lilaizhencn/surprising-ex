package com.surprising.gateway.c2c;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 订单、报价和状态时间线在同一事务提交；这里只保留业务状态，不写钱包余额。 */
@Repository
public class C2cOrdersRepository {
    record Quote(UUID id,long userId,UUID adId,long adRevision,long marketRevision,long quantityUnits,long feeUnits,
                 BigDecimal fiatAmount,BigDecimal price,UUID paymentAccountId,long accountRevision,Instant expiresAt) { }
    record Order(UUID id,UUID quoteId,UUID adId,UUID marketId,long creatorUserId,long buyerUserId,long sellerUserId,
                 long quantityUnits,long feeUnits,BigDecimal fiatAmount,BigDecimal price,
                 UUID paymentAccountId,String paymentCiphertext,int paymentWindowSeconds,String terms,C2cOrderStatus status,
                 String requestedTerminal,Instant paymentExpiresAt,Instant paidAt,Instant completedAt,Instant canceledAt,
                 String cancelReason,long revision,Instant createdAt) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public C2cOrdersRepository(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    Quote quote(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_quotes WHERE quote_id=?"+(lock?" FOR UPDATE":""),this::readQuote,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("报价不存在或已过期");
        return rows.getFirst();
    }
    Quote createQuote(long userId,C2cAdsRepository.Ad ad,C2cMarkets.Market market,long units,long fee,BigDecimal fiat,C2cPaymentAccounts.Account account) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO c2c_quotes(quote_id,user_id,ad_id,ad_revision,market_revision,quantity_units,buyer_fee_units,
                    fiat_amount,price,payment_account_id,payment_account_revision,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,now()+interval '60 seconds')
                """,id,userId,ad.id(),ad.revision(),market.revision(),units,fee,fiat,ad.price(),account.id(),account.revision());
        return quote(id,false);
    }

    Order get(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_orders WHERE order_id=?"+(lock?" FOR UPDATE":""),this::readOrder,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("订单不存在");
        return rows.getFirst();
    }
    Order byQuote(UUID id) {
        return jdbc.query("SELECT * FROM c2c_orders WHERE quote_id=?",this::readOrder,id).stream().findFirst().orElse(null);
    }

    Order create(long userId,Quote quote,C2cAdsRepository.Ad ad,C2cPaymentAccounts.Account account) {
        UUID id=UUID.randomUUID();boolean reserves=ad.side().equals("BUY");
        long buyer=ad.side().equals("SELL")?userId:ad.makerUserId();
        long seller=ad.side().equals("SELL")?ad.makerUserId():userId;
        jdbc.update("""
                INSERT INTO c2c_orders(order_id,quote_id,ad_id,market_id,creator_user_id,buyer_user_id,seller_user_id,
                    quantity_units,buyer_fee_units,fiat_amount,price,payment_account_id,
                    payment_details_ciphertext,payment_window_seconds,terms,status,payment_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,quote.id(),ad.id(),ad.marketId(),userId,buyer,seller,quote.quantityUnits(),quote.feeUnits(),
                quote.fiatAmount(),quote.price(),account.id(),account.ciphertext(),ad.paymentWindowSeconds(),ad.terms(),
                reserves?"RESERVING":"PAYMENT_PENDING",reserves?null:Timestamp.from(Instant.now().plusSeconds(ad.paymentWindowSeconds())));
        event(id,userId,"ORDER_CREATED",Map.of("status",reserves?"RESERVING":"PAYMENT_PENDING"));
        return get(id,false);
    }

    void awaitingPayment(Order order) {
        jdbc.update("""
                UPDATE c2c_orders SET status='PAYMENT_PENDING',payment_expires_at=now()+payment_window_seconds*interval '1 second',
                    revision=revision+1,updated_at=now() WHERE order_id=? AND status='RESERVING'
                """,order.id());
        event(order.id(),null,"ESCROW_CONFIRMED",Map.of());
    }
    void markPaid(Order order,UUID proof) {
        jdbc.update("UPDATE c2c_orders SET status='PAID',paid_at=now(),revision=revision+1,updated_at=now() WHERE order_id=?",order.id());
        event(order.id(),order.buyerUserId(),"BUYER_MARKED_PAID",proof==null?Map.of():Map.of("proofId",proof.toString()));
    }
    void state(Order order,C2cOrderStatus status,String terminal,String reason,Long actor,String eventType) {
        jdbc.update("""
                UPDATE c2c_orders SET status=?,requested_terminal=?,cancellation_reason=?,
                    revision=revision+1,updated_at=now() WHERE order_id=?
                """,status.name(),terminal,reason,order.id());
        event(order.id(),actor,eventType,Map.of("status",status.name()));
    }
    void terminal(Order order,C2cOrderStatus status,String reason) {
        boolean completed=status==C2cOrderStatus.COMPLETED;
        jdbc.update("""
                UPDATE c2c_orders SET status=?,cancellation_reason=?,completed_at=CASE WHEN ? THEN now() ELSE completed_at END,
                    canceled_at=CASE WHEN ? THEN canceled_at ELSE now() END,revision=revision+1,updated_at=now()
                WHERE order_id=?
                """,status.name(),reason,completed,completed,order.id());
        event(order.id(),null,status.name(),Map.of());
    }
    void event(UUID orderId,Long userId,String type,Map<String,?> details) {
        jdbc.update("INSERT INTO c2c_order_events(order_id,actor_user_id,event_type,details) VALUES(?,?,?,?::jsonb)",
                orderId,userId,type,json.writeValueAsString(details));
    }
    List<UUID> expired(int limit) {
        return jdbc.query("SELECT order_id FROM c2c_orders WHERE status='PAYMENT_PENDING' AND payment_expires_at<=now() ORDER BY payment_expires_at LIMIT ?",
                (r,n)->r.getObject(1,UUID.class),limit);
    }
    List<UUID> fundingReady(int limit) {
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

    private Quote readQuote(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Quote(r.getObject("quote_id",UUID.class),r.getLong("user_id"),r.getObject("ad_id",UUID.class),
                r.getLong("ad_revision"),r.getLong("market_revision"),r.getLong("quantity_units"),r.getLong("buyer_fee_units"),
                r.getBigDecimal("fiat_amount"),r.getBigDecimal("price"),r.getObject("payment_account_id",UUID.class),
                r.getLong("payment_account_revision"),r.getTimestamp("expires_at").toInstant());
    }
    Order readOrder(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Order(r.getObject("order_id",UUID.class),r.getObject("quote_id",UUID.class),r.getObject("ad_id",UUID.class),
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
}
