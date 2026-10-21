package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** C2C 市场与本人记录的 SQL 查询视图；不向 Core 发查询，也不维护第二份钱包余额。 */
@Repository
public class C2cQueries {
    record Filter(UUID marketId, String side, String amount, String method, Long merchantId, boolean vipOnly,
                  int minCompleted, int minCompletionRate, int maxPaymentSeconds, String sort, int limit, int offset) { }
    private record Offer(C2cAd ad, String nickname, String tier,
                         long completed, long ended, long completedTotal, BigDecimal paidSeconds, BigDecimal releasedSeconds) { }
    private final JdbcTemplate jdbc;
    private final C2cAdsRepository ads;
    private final C2cOrdersRepository orders;
    private final C2cMarketService markets;
    private final C2cPaymentAccounts payments;
    private final ObjectMapper json;

    public C2cQueries(JdbcTemplate jdbc, C2cAdsRepository ads, C2cOrdersRepository orders,
                      C2cMarketService markets, C2cPaymentAccounts payments, ObjectMapper json) {
        this.jdbc = jdbc; this.ads = ads; this.orders = orders;
        this.markets = markets; this.payments = payments; this.json = json;
    }

    Map<String,Object> offers(Filter filter, JwtPrincipal viewer) {
        var market = markets.market(filter.marketId(), true);
        String makerSide = switch (filter.side()) {
            case "BUY" -> "SELL";
            case "SELL" -> "BUY";
            default -> throw new IllegalArgumentException("请选择购买或出售");
        };
        if (filter.limit() < 1 || filter.limit() > 50 || filter.offset() < 0 || filter.offset() > 10000
                || filter.minCompleted() < 0 || filter.minCompleted() > 1000000
                || filter.minCompletionRate() < 0 || filter.minCompletionRate() > 100
                || filter.maxPaymentSeconds() < 60 || filter.maxPaymentSeconds() > 3600
                || (filter.method() != null && !filter.method().matches("[A-Z][A-Z0-9_]{0,31}")))
            throw new IllegalArgumentException("筛选或分页参数无效");
        BigDecimal amount = filter.amount() == null || filter.amount().isBlank() ? null : C2cMoney.decimal(filter.amount());
        if (amount != null && amount.stripTrailingZeros().scale() > market.fiatDecimals())
            throw new IllegalArgumentException("法币金额精度超出市场范围");
        if (filter.vipOnly()) {
            if (viewer == null) throw C2cProblem.forbidden("请登录后查看 VIP 市场");
            if (completed(viewer.userId()) < market.vipMinCompleted()) throw C2cProblem.forbidden("尚未满足 VIP 市场成交资格");
        }
        String sort = switch (filter.sort()) {
            case "PRICE" -> "a.price " + (makerSide.equals("SELL") ? "ASC" : "DESC") + ", a.ad_id";
            case "COMPLETED" -> "s.completed_total DESC,a.price " + (makerSide.equals("SELL") ? "ASC" : "DESC") + ",a.ad_id";
            default -> throw new IllegalArgumentException("排序方式无效");
        };
        long viewerId = viewer == null ? 0 : viewer.userId();
        // 只读资格视图集中基础交易资格；档案和订单统计仅为当前稳定广告页补充展示/筛选，不扩展独立写职责。
        var rows = jdbc.query("""
                SELECT a.*,COALESCE(p.nickname,'商家'||right(a.maker_user_id::text,6)) AS merchant_nickname,c.tier,
                    s.completed_30d,s.ended_30d,s.completed_total,s.paid_seconds,s.released_seconds
                FROM c2c_eligible_ads a
                JOIN c2c_merchants c ON c.user_id=a.maker_user_id
                LEFT JOIN c2c_profiles p ON p.user_id=a.maker_user_id
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE status='COMPLETED' AND completed_at>=now()-interval '30 days') AS completed_30d,
                        count(*) FILTER (WHERE status IN ('COMPLETED','CANCELED','EXPIRED') AND COALESCE(completed_at,canceled_at)>=now()-interval '30 days') AS ended_30d,
                        count(*) FILTER (WHERE status='COMPLETED') AS completed_total,
                        avg(EXTRACT(EPOCH FROM paid_at-(payment_expires_at-payment_window_seconds*interval '1 second'))) FILTER(WHERE status='COMPLETED') AS paid_seconds,
                        avg(EXTRACT(EPOCH FROM completed_at-paid_at)) FILTER(WHERE status='COMPLETED') AS released_seconds
                    FROM c2c_orders WHERE buyer_user_id=a.maker_user_id OR seller_user_id=a.maker_user_id
                ) s ON true
                WHERE a.market_id=? AND a.side=?
                    AND (CAST(? AS numeric) IS NULL OR (? BETWEEN a.min_fiat AND a.max_fiat
                        AND ROUND(a.available_units::numeric*a.price/?,?)>=?))
                    AND (CAST(? AS text) IS NULL OR ?=ANY(a.method_codes))
                    AND (CAST(? AS bigint) IS NULL OR a.maker_user_id=?)
                    AND (?=false OR c.tier='VIP')
                    AND s.completed_total>=? AND (?=0 OR (s.ended_30d>0 AND s.completed_30d*100>=s.ended_30d*?))
                    AND a.payment_window_seconds<=? AND a.maker_user_id<>?
                    AND NOT EXISTS(SELECT 1 FROM c2c_relations r WHERE r.kind='BLOCK' AND
                        ((r.user_id=? AND r.target_user_id=a.maker_user_id) OR (r.target_user_id=? AND r.user_id=a.maker_user_id)))
                ORDER BY %s LIMIT ? OFFSET ?
                """.formatted(sort), (r,n) -> new Offer(ads.read(r,n),r.getString("merchant_nickname"),r.getString("tier"),
                        r.getLong("completed_30d"),r.getLong("ended_30d"),r.getLong("completed_total"),
                        r.getBigDecimal("paid_seconds"),r.getBigDecimal("released_seconds")),
                market.id(),makerSide,amount,amount,market.assetScale(),market.fiatDecimals(),amount,
                filter.method(),filter.method(),filter.merchantId(),filter.merchantId(),filter.vipOnly(),
                filter.minCompleted(),filter.minCompletionRate(),filter.minCompletionRate(),filter.maxPaymentSeconds(),viewerId,viewerId,viewerId,
                filter.limit()+1,filter.offset());
        var methods = enabledMethods(market.fiat());
        var visible = rows.stream().limit(filter.limit()).map(row -> {
            var view = C2cViews.ad(row.ad(),market,false);
            view.put("methodCodes",row.ad().methods().stream().filter(methods::contains).toList());
            view.put("merchant",stats(row.ad().makerUserId(),row.nickname(),row.tier(),row.completed(),row.ended(),
                    row.completedTotal(),row.paidSeconds(),row.releasedSeconds()));
            return view;
        }).toList();
        return Map.of("items",visible,"hasMore",rows.size()>filter.limit(),"offset",filter.offset(),"serverTime",Instant.now().toString());
    }

    Map<String,Object> offer(UUID id) {
        var ad = ads.get(id,false);
        var market = markets.market(ad.marketId(),true);
        if (!ad.status().equals("ACTIVE") || ad.availableUnits() <= 0) throw new IllegalStateException("广告暂不可交易");
        var view = C2cViews.ad(ad,market,false);
        view.put("merchant",merchant(ad.makerUserId()));
        view.put("methodCodes",ad.methods().stream().filter(enabledMethods(market.fiat())::contains).toList());
        view.put("paymentOptions",ad.side().equals("SELL") ? paymentOptions(ad) : List.of());
        view.put("requiresOwnPaymentAccount",ad.side().equals("BUY"));
        return view;
    }

    Map<String,Object> merchant(long userId) {
        var rows = jdbc.query("""
                SELECT COALESCE(p.nickname,'商家'||right(c.user_id::text,6)) AS nickname,c.status,c.tier,
                    count(o.order_id) FILTER(WHERE o.status='COMPLETED' AND o.completed_at>=now()-interval '30 days') AS completed,
                    count(o.order_id) FILTER(WHERE o.status IN ('COMPLETED','CANCELED','EXPIRED') AND COALESCE(o.completed_at,o.canceled_at)>=now()-interval '30 days') AS ended,
                    count(o.order_id) FILTER(WHERE o.status='COMPLETED') AS total,
                    avg(EXTRACT(EPOCH FROM o.paid_at-(o.payment_expires_at-o.payment_window_seconds*interval '1 second'))) FILTER(WHERE o.status='COMPLETED') AS paid_seconds,
                    avg(EXTRACT(EPOCH FROM o.completed_at-o.paid_at)) FILTER(WHERE o.status='COMPLETED') AS released_seconds
                FROM c2c_merchants c LEFT JOIN c2c_profiles p USING(user_id)
                LEFT JOIN c2c_orders o ON o.buyer_user_id=c.user_id OR o.seller_user_id=c.user_id
                WHERE c.user_id=? GROUP BY c.user_id,p.nickname,c.status,c.tier
                """, (r,n) -> {
                    var view = stats(userId,r.getString("nickname"),r.getString("tier"),r.getLong("completed"),r.getLong("ended"),
                            r.getLong("total"),r.getBigDecimal("paid_seconds"),r.getBigDecimal("released_seconds"));
                    view.put("status",r.getString("status"));
                    return view;
                },userId);
        if (rows.isEmpty() || !"APPROVED".equals(rows.getFirst().get("status"))) throw new IllegalArgumentException("商家当前不可用");
        return rows.getFirst();
    }

    List<Map<String,Object>> myAds(long userId,int limit) {
        requireLimit(limit);
        return ads.mine(userId,limit).stream().map(ad -> C2cViews.ad(ad,markets.market(ad.marketId(),false),true)).toList();
    }

    Map<String,Object> myOrders(long userId,String state,UUID marketId,String side,UUID orderId,int limit,int offset) {
        requireLimit(limit);
        if (offset<0 || offset>10000 || !List.of("ONGOING","APPEALED","ENDED","ALL").contains(state)
                || !List.of("BUY","SELL","ALL").contains(side)) throw new IllegalArgumentException("订单查询条件无效");
        String status = switch (state) {
            case "ONGOING" -> "o.status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')";
            case "APPEALED" -> "o.status='APPEALED'";
            case "ENDED" -> "o.status IN ('COMPLETED','CANCELED','EXPIRED','FAILED')";
            default -> "true";
        };
        var rows = jdbc.query("""
                SELECT o.* FROM c2c_orders o WHERE (o.buyer_user_id=? OR o.seller_user_id=?) AND %s
                    AND (CAST(? AS uuid) IS NULL OR o.market_id=?) AND (CAST(? AS uuid) IS NULL OR o.order_id=?)
                    AND (?='ALL' OR (?='BUY' AND o.buyer_user_id=?) OR (?='SELL' AND o.seller_user_id=?))
                ORDER BY o.created_at DESC,o.order_id DESC LIMIT ? OFFSET ?
                """.formatted(status),orders::readOrder,userId,userId,marketId,marketId,orderId,orderId,side,side,userId,side,userId,limit+1,offset);
        return Map.of("items", rows.stream().limit(limit).map(order -> C2cViews.order(order,markets.market(order.marketId(),false))).toList(),
                "hasMore",rows.size()>limit,"offset",offset,"serverTime",Instant.now().toString());
    }

    Map<String,Object> order(long userId,UUID id) {
        var order = orders.get(id,false);
        C2cOrders.requireParticipant(userId,order);
        return orderDetails(order,true);
    }

    Map<String,Object> adminOrder(UUID id,boolean includePayment) {
        return orderDetails(orders.get(id,false),includePayment);
    }

    private Map<String,Object> orderDetails(C2cOrder order,boolean includePayment) {
        UUID id=order.id();
        var market = markets.market(order.marketId(),false);
        var view = C2cViews.order(order,market);
        if(includePayment) {
            var payment = payments.details(order.sellerUserId(),order.paymentCiphertext());
            if (!market.fiat().equals(payment.get("fiat"))) throw new IllegalStateException("订单收款快照法币不匹配");
            view.put("payment",payment);
        }
        view.put("events",jdbc.query("SELECT * FROM c2c_order_events WHERE order_id=? ORDER BY event_id LIMIT 200",(r,n) -> {
            var event = new LinkedHashMap<String,Object>();
            event.put("eventId",Long.toString(r.getLong("event_id")));
            event.put("actorUserId",r.getObject("actor_user_id")==null?null:Long.toString(r.getLong("actor_user_id")));
            event.put("type",r.getString("event_type"));
            event.put("details",json.readValue(r.getString("details"),Map.class));
            event.put("createdAt",r.getTimestamp("created_at").toInstant().toString());
            return event;
        },id));
        view.put("appeals",jdbc.query("SELECT * FROM c2c_appeals WHERE order_id=? ORDER BY created_at",(r,n) -> {
            var appeal = new LinkedHashMap<String,Object>();
            appeal.put("appealId",r.getObject("appeal_id").toString());
            appeal.put("openerUserId",Long.toString(r.getLong("opener_user_id")));
            appeal.put("reason",r.getString("reason"));
            appeal.put("status",r.getString("status"));
            appeal.put("decision",r.getString("decision"));
            appeal.put("decisionReason",r.getString("decision_reason"));
            appeal.put("createdAt",r.getTimestamp("created_at").toInstant().toString());
            appeal.put("resolvedAt",r.getTimestamp("resolved_at")==null?null:r.getTimestamp("resolved_at").toInstant().toString());
            return appeal;
        },id));
        view.put("serverTime",Instant.now().toString());
        return view;
    }

    List<Map<String,Object>> paymentMethods(boolean enabledOnly) {
        return jdbc.query("SELECT * FROM c2c_payment_methods WHERE NOT ? OR enabled ORDER BY code",(r,n) ->
                Map.<String,Object>of("code",r.getString("code"),"label",r.getString("label"),"enabled",r.getBoolean("enabled"),
                        "currencies",Arrays.asList((String[])r.getArray("currencies").getArray()),
                        "requiresBankName",r.getBoolean("requires_bank_name"),"supportsQr",r.getBoolean("supports_qr"),
                        "revision",Long.toString(r.getLong("revision"))),enabledOnly);
    }

    private List<Map<String,Object>> paymentOptions(C2cAd ad) {
        return jdbc.query("""
                SELECT a.* FROM c2c_payment_accounts a JOIN c2c_payment_methods m ON m.code=a.method_code
                WHERE a.user_id=? AND a.account_id=ANY(?::uuid[]) AND a.status='APPROVED' AND m.enabled AND a.fiat=ANY(m.currencies)
                ORDER BY a.account_id
                """,(r,n) -> Map.<String,Object>of("accountId",r.getObject("account_id").toString(),
                        "methodCode",r.getString("method_code"),"maskedLabel",r.getString("masked_label")),
                ad.makerUserId(),"{"+String.join(",",ad.accounts().stream().map(Object::toString).toList())+"}");
    }
    private Set<String> enabledMethods(String fiat) {
        return new HashSet<>(jdbc.query("SELECT code FROM c2c_payment_methods WHERE enabled AND ?=ANY(currencies)",(r,n)->r.getString(1),fiat));
    }
    private long completed(long userId) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM c2c_orders WHERE status='COMPLETED' AND (buyer_user_id=? OR seller_user_id=?)",Long.class,userId,userId);
        return value == null ? 0 : value;
    }
    private static Map<String,Object> stats(long userId,String nickname,String tier,long completed,long ended,
                                            long total,BigDecimal paid,BigDecimal released) {
        var result = new LinkedHashMap<String,Object>();
        result.put("userId",Long.toString(userId));result.put("nickname",nickname);result.put("tier",tier);
        result.put("completed30d",Long.toString(completed));result.put("ended30d",Long.toString(ended));
        result.put("completedTotal",Long.toString(total));
        result.put("completionRate",ended==0?null:BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(ended),2,RoundingMode.HALF_UP).toPlainString());
        result.put("averagePaymentSeconds",paid==null?null:paid.setScale(0,RoundingMode.HALF_UP).toPlainString());
        result.put("averageReleaseSeconds",released==null?null:released.setScale(0,RoundingMode.HALF_UP).toPlainString());
        return result;
    }
    private static void requireLimit(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("分页数量无效");
    }
}
