package com.surprising.gateway.c2c;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 后台列表只选择业务字段；收款密文与完整账号不进入常规读取权限。 */
@Repository
public class C2cAdminQueries {
    private final JdbcTemplate jdbc;
    private final C2cMarkets markets;
    private final C2cAdsRepository ads;
    private final C2cOrdersRepository orders;
    public C2cAdminQueries(JdbcTemplate jdbc,C2cMarkets markets,C2cAdsRepository ads,C2cOrdersRepository orders) {
        this.jdbc=jdbc;this.markets=markets;this.ads=ads;this.orders=orders;
    }
    Map<String,Object> merchants(String status,Long userId,int limit,int offset) {
        page(limit,offset);state(status,List.of("PENDING","APPROVED","REJECTED","SUSPENDED"));
        var rows=jdbc.query("""
                SELECT c.*,COALESCE(p.nickname,'用户'||right(c.user_id::text,6)) AS nickname,k.status AS kyc_status,k.expires_at AS kyc_expires_at
                FROM c2c_merchants c LEFT JOIN c2c_profiles p USING(user_id) LEFT JOIN gateway_user_kyc_profiles k USING(user_id)
                WHERE (CAST(? AS text) IS NULL OR c.status=?) AND (CAST(? AS bigint) IS NULL OR c.user_id=?)
                ORDER BY c.updated_at DESC,c.user_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("userId",Long.toString(r.getLong("user_id")));
                    view.put("nickname",r.getString("nickname"));view.put("status",r.getString("status"));view.put("tier",r.getString("tier"));
                    view.put("application",r.getString("application_text"));view.put("reviewReason",r.getString("review_reason"));
                    view.put("revision",Long.toString(r.getLong("revision")));view.put("kycStatus",r.getString("kyc_status"));
                    view.put("kycExpiresAt",r.getTimestamp("kyc_expires_at")==null?null:r.getTimestamp("kyc_expires_at").toInstant().toString());
                    view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
                },status,status,userId,userId,limit+1,offset);
        return pageResult(rows,limit,offset);
    }
    Map<String,Object> paymentAccounts(String status,Long userId,int limit,int offset) {
        page(limit,offset);state(status,List.of("PENDING","APPROVED","REJECTED","DISABLED"));
        var rows=jdbc.query("""
                SELECT a.account_id,a.user_id,a.fiat,a.method_code,a.masked_label,a.status,a.revision,a.review_reason,a.created_at,
                    COALESCE(p.nickname,'用户'||right(a.user_id::text,6)) AS nickname
                FROM c2c_payment_accounts a LEFT JOIN c2c_profiles p USING(user_id)
                WHERE (CAST(? AS text) IS NULL OR a.status=?) AND (CAST(? AS bigint) IS NULL OR a.user_id=?)
                ORDER BY a.updated_at DESC,a.account_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("accountId",r.getObject("account_id").toString());
                    view.put("userId",Long.toString(r.getLong("user_id")));view.put("nickname",r.getString("nickname"));
                    view.put("fiat",r.getString("fiat"));view.put("methodCode",r.getString("method_code"));
                    view.put("maskedLabel",r.getString("masked_label"));view.put("status",r.getString("status"));
                    view.put("revision",Long.toString(r.getLong("revision")));view.put("reviewReason",r.getString("review_reason"));
                    view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
                },status,status,userId,userId,limit+1,offset);
        return pageResult(rows,limit,offset);
    }
    Map<String,Object> ads(String status,Long userId,UUID market,int limit,int offset) {
        page(limit,offset);state(status,List.of("RESERVING","ACTIVE","PAUSED","CLOSING","CLOSED","REJECTED"));
        var rows=jdbc.query("""
                SELECT * FROM c2c_ads WHERE (CAST(? AS text) IS NULL OR status=?) AND (CAST(? AS bigint) IS NULL OR maker_user_id=?)
                    AND (CAST(? AS uuid) IS NULL OR market_id=?) ORDER BY updated_at DESC,ad_id DESC LIMIT ? OFFSET ?
                """,ads::read,status,status,userId,userId,market,market,limit+1,offset);
        return pageResult(rows.stream().map(ad->C2cViews.ad(ad,markets.market(ad.marketId(),false),true)).toList(),limit,offset);
    }
    Map<String,Object> orders(String status,Long userId,UUID orderId,UUID market,int limit,int offset) {
        page(limit,offset);state(status,Arrays.stream(C2cOrderStatus.values()).map(Enum::name).toList());
        var rows=jdbc.query("""
                SELECT * FROM c2c_orders WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS bigint) IS NULL OR buyer_user_id=? OR seller_user_id=?)
                    AND (CAST(? AS uuid) IS NULL OR order_id=?) AND (CAST(? AS uuid) IS NULL OR market_id=?)
                ORDER BY updated_at DESC,order_id DESC LIMIT ? OFFSET ?
                """,orders::readOrder,status,status,userId,userId,userId,orderId,orderId,market,market,limit+1,offset);
        return pageResult(rows.stream().map(order->C2cViews.order(order,markets.market(order.marketId(),false))).toList(),limit,offset);
    }
    Map<String,Object> funds(String status,UUID ownerId,int limit,int offset) {
        page(limit,offset);state(status,List.of("PREPARED","DEBITED","CREDITED","APPLIED","REJECTED"));
        var rows=jdbc.query("""
                SELECT * FROM c2c_fund_intents WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS uuid) IS NULL OR owner_id=?) ORDER BY updated_at DESC,intent_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("intentId",r.getObject("intent_id").toString());
                    view.put("transferId",Long.toString(r.getLong("transfer_id")));view.put("ownerType",r.getString("owner_type"));
                    view.put("ownerId",r.getObject("owner_id").toString());view.put("purpose",r.getString("purpose"));
                    view.put("sourceUserId",Long.toString(r.getLong("source_user_id")));view.put("targetUserId",Long.toString(r.getLong("target_user_id")));
                    view.put("asset",r.getString("asset"));view.put("amountUnits",Long.toString(r.getLong("amount_units")));view.put("status",r.getString("status"));
                    view.put("errorCode",r.getString("error_code"));view.put("attempts",r.getInt("attempts"));
                    view.put("retryAfter",r.getTimestamp("retry_after").toInstant().toString());view.put("updatedAt",r.getTimestamp("updated_at").toInstant().toString());return view;
                },status,status,ownerId,ownerId,limit+1,offset);
        return pageResult(rows,limit,offset);
    }
    Map<String,Object> appeals(String status,UUID orderId,int limit,int offset) {
        page(limit,offset);state(status,List.of("OPEN","REVIEWING","RESOLVED","WITHDRAWN"));
        var rows=jdbc.query("""
                SELECT * FROM c2c_appeals WHERE (CAST(? AS text) IS NULL OR status=?) AND (CAST(? AS uuid) IS NULL OR order_id=?)
                ORDER BY created_at DESC,appeal_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("appealId",r.getObject("appeal_id").toString());
                    view.put("orderId",r.getObject("order_id").toString());view.put("openerUserId",Long.toString(r.getLong("opener_user_id")));
                    view.put("status",r.getString("status"));view.put("reason",r.getString("reason"));view.put("decision",r.getString("decision"));
                    view.put("decisionReason",r.getString("decision_reason"));view.put("approvalId",r.getObject("approval_id")==null?null:Long.toString(r.getLong("approval_id")));
                    view.put("decidedBy",r.getObject("decided_by")==null?null:Long.toString(r.getLong("decided_by")));
                    view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
                },status,status,orderId,orderId,limit+1,offset);
        return pageResult(rows,limit,offset);
    }
    Map<String,Object> reports(String status,Long userId,int limit,int offset) {
        page(limit,offset);state(status,List.of("OPEN","REVIEWED","DISMISSED"));
        var rows=jdbc.query("""
                SELECT * FROM c2c_reports WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS bigint) IS NULL OR user_id=? OR target_user_id=?) ORDER BY created_at DESC,report_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("reportId",r.getObject("report_id").toString());
                    view.put("userId",Long.toString(r.getLong("user_id")));view.put("targetUserId",Long.toString(r.getLong("target_user_id")));
                    view.put("orderId",r.getObject("order_id")==null?null:r.getObject("order_id").toString());
                    view.put("reason",r.getString("reason"));view.put("status",r.getString("status"));view.put("reviewReason",r.getString("review_reason"));
                    view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
                },status,status,userId,userId,userId,limit+1,offset);
        return pageResult(rows,limit,offset);
    }
    private static Map<String,Object> pageResult(List<? extends Map<String,Object>> rows,int limit,int offset) {
        return Map.of("items",rows.stream().limit(limit).toList(),"hasMore",rows.size()>limit,"offset",offset);
    }
    private static void page(int limit,int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>10000) throw new IllegalArgumentException("后台分页参数无效");
    }
    private static void state(String value,List<String> allowed) {
        if(value!=null && !allowed.contains(value)) throw new IllegalArgumentException("查询状态无效");
    }
}
