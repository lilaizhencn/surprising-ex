package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 本人的 C2C 设置、关系、举报与通知。资金展示只派生已确认意图，不产生 SQL 钱包余额。 */
@Service
public class C2cProfiles {
    private record Profile(String nickname,boolean strangers,int verificationMinutes,long revision) { }
    private final JdbcTemplate jdbc;
    private final C2cAccess access;
    private final ComplianceService compliance;
    private final GatewayUserRepository users;
    private final C2cOrdersRepository orders;
    private final C2cMarkets markets;
    private final ObjectMapper json;

    public C2cProfiles(JdbcTemplate jdbc,C2cAccess access,ComplianceService compliance,GatewayUserRepository users,
                       C2cOrdersRepository orders,C2cMarkets markets,ObjectMapper json) {
        this.jdbc=jdbc;this.access=access;this.compliance=compliance;this.users=users;
        this.orders=orders;this.markets=markets;this.json=json;
    }

    Map<String,Object> me(JwtPrincipal user) {
        var view=profileView(profile(user.userId(),false),user.userId());
        view.put("accountStatus",user.status());
        var kyc=compliance.kyc(user.userId());
        view.put("kycStatus",kyc==null?"UNVERIFIED":kyc.status());
        view.put("kycLevel",kyc==null?"NONE":kyc.kycLevel());
        view.put("kycVerified",kyc!=null && "VERIFIED".equals(kyc.status()) && (kyc.expiresAt()==null || kyc.expiresAt().isAfter(Instant.now())));
        view.put("merchant",merchant(user.userId()));
        view.put("statistics",jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE status='COMPLETED') AS completed,
                    count(*) FILTER(WHERE status IN ('CANCELED','EXPIRED')) AS canceled,
                    count(*) FILTER(WHERE status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')) AS ongoing,
                    count(*) FILTER(WHERE status='APPEALED') AS appealed
                FROM c2c_orders WHERE buyer_user_id=? OR seller_user_id=?
                """,(r,n)->Map.of("completed",Long.toString(r.getLong("completed")),"canceled",Long.toString(r.getLong("canceled")),
                        "ongoing",Long.toString(r.getLong("ongoing")),"appealed",Long.toString(r.getLong("appealed"))),user.userId(),user.userId()));
        view.put("escrow",escrow(user.userId()));
        view.put("serverTime",Instant.now().toString());
        return view;
    }

    @Transactional
    Map<String,Object> update(JwtPrincipal user,long revision,String nickname,boolean strangers,int minutes,C2cVerification verification) {
        nickname=text(nickname,2,40,"昵称");
        if(nickname.codePoints().anyMatch(c->Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))
            throw new IllegalArgumentException("昵称不能包含控制字符");
        if(!List.of(0,5,15,30).contains(minutes)) throw new IllegalArgumentException("请选择始终、5、15 或 30 分钟验证");
        lockUser(user.userId());var current=profile(user.userId(),true);
        if(current.revision()!=revision) throw new IllegalStateException("个人设置已变更，请刷新");
        if(current.verificationMinutes()!=minutes) {
            var codes=C2cVerification.orEmpty(verification);access.verifySettings(user,codes.emailCode(),codes.totpCode());
        }
        if(current.revision()==0) jdbc.update("""
                INSERT INTO c2c_profiles(user_id,nickname,receive_strangers,verification_minutes) VALUES(?,?,?,?)
                """,user.userId(),nickname,strangers,minutes);
        else jdbc.update("""
                UPDATE c2c_profiles SET nickname=?,receive_strangers=?,verification_minutes=?,revision=revision+1,updated_at=now()
                WHERE user_id=?
                """,nickname,strangers,minutes,user.userId());
        if(current.verificationMinutes()!=minutes) jdbc.update("DELETE FROM c2c_security_grants WHERE user_id=?",user.userId());
        return profileView(profile(user.userId(),false),user.userId());
    }

    @Transactional
    Map<String,Object> applyMerchant(JwtPrincipal user,long revision,String application) {
        application=text(application,10,2000,"商家申请资料");
        if(!"NORMAL".equals(user.status())) throw new IllegalStateException("账户当前不能申请商家");
        var kyc=compliance.kyc(user.userId());
        if(kyc==null || !"VERIFIED".equals(kyc.status()) || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now())))
            throw new IllegalStateException("请先完成有效身份认证");
        lockUser(user.userId());
        var rows=jdbc.queryForList("SELECT status,revision FROM c2c_merchants WHERE user_id=? FOR UPDATE",user.userId());
        if(rows.isEmpty()) {
            if(revision!=0) throw new IllegalStateException("商家申请状态已变更");
            jdbc.update("INSERT INTO c2c_merchants(user_id,application_text) VALUES(?,?)",user.userId(),application);
        } else {
            var row=rows.getFirst();
            if(((Number)row.get("revision")).longValue()!=revision) throw new IllegalStateException("商家申请状态已变更");
            if(!List.of("PENDING","REJECTED").contains(row.get("status"))) throw new IllegalStateException("请联系客服处理当前商家状态");
            jdbc.update("""
                    UPDATE c2c_merchants SET status='PENDING',application_text=?,review_reason=NULL,reviewed_by=NULL,reviewed_at=NULL,
                        revision=revision+1,updated_at=now() WHERE user_id=?
                    """,application,user.userId());
        }
        return merchant(user.userId());
    }

    List<Map<String,Object>> quickReplies(long userId) {
        return jdbc.query("SELECT * FROM c2c_quick_replies WHERE user_id=? ORDER BY created_at,reply_id",(r,n)->
                Map.of("replyId",r.getObject("reply_id").toString(),"text",r.getString("text")),userId);
    }
    @Transactional
    Map<String,Object> saveReply(long userId,UUID id,String content) {
        content=text(content,1,1000,"快捷回复");lockUser(userId);
        if(id==null) id=UUID.randomUUID();
        var existing=jdbc.queryForList("SELECT user_id FROM c2c_quick_replies WHERE reply_id=?",id);
        if(!existing.isEmpty() && ((Number)existing.getFirst().get("user_id")).longValue()!=userId)
            throw C2cProblem.forbidden("只能修改本人快捷回复");
        if(existing.isEmpty() && count("SELECT count(*) FROM c2c_quick_replies WHERE user_id=?",userId)>=30)
            throw new IllegalStateException("最多保存 30 条快捷回复");
        jdbc.update("""
                INSERT INTO c2c_quick_replies(reply_id,user_id,text) VALUES(?,?,?)
                ON CONFLICT(reply_id) DO UPDATE SET text=EXCLUDED.text WHERE c2c_quick_replies.user_id=EXCLUDED.user_id
                """,id,userId,content);
        return Map.of("replyId",id.toString(),"text",content);
    }
    @Transactional
    void deleteReply(long userId,UUID id) {jdbc.update("DELETE FROM c2c_quick_replies WHERE user_id=? AND reply_id=?",userId,id);}

    Map<String,Object> relations(long userId,String kind,int limit,int offset) {
        relationKind(kind);page(limit,offset);
        var rows=jdbc.query("""
                SELECT r.target_user_id,r.kind,r.created_at,COALESCE(p.nickname,'用户'||right(r.target_user_id::text,6)) AS nickname,
                    c.status AS merchant_status,c.tier
                FROM c2c_relations r LEFT JOIN c2c_profiles p ON p.user_id=r.target_user_id
                LEFT JOIN c2c_merchants c ON c.user_id=r.target_user_id WHERE r.user_id=? AND r.kind=?
                ORDER BY r.created_at DESC,r.target_user_id DESC LIMIT ? OFFSET ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();
                    view.put("userId",Long.toString(r.getLong("target_user_id")));view.put("nickname",r.getString("nickname"));
                    view.put("kind",r.getString("kind"));view.put("merchantStatus",r.getString("merchant_status"));view.put("tier",r.getString("tier"));
                    return view;
                },userId,kind,limit+1,offset);
        return Map.of("items",rows.stream().limit(limit).toList(),"hasMore",rows.size()>limit,"offset",offset);
    }
    @Transactional
    void relation(long userId,long target,String kind,boolean enabled) {
        relationKind(kind);
        if(target==userId || target<=0 || users.find(target).isEmpty()) throw new IllegalArgumentException("关系对象无效");
        if(enabled) jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(?,?,?) ON CONFLICT DO NOTHING",userId,target,kind);
        else jdbc.update("DELETE FROM c2c_relations WHERE user_id=? AND target_user_id=? AND kind=?",userId,target,kind);
    }

    @Transactional
    UUID report(long userId,UUID id,long target,UUID orderId,String reason) {
        reason=text(reason,5,2000,"举报原因");
        if(userId==target || target<=0 || users.find(target).isEmpty()) throw new IllegalArgumentException("举报对象无效");
        if(orderId!=null) {
            var order=orders.get(orderId,false);C2cOrders.requireParticipant(userId,order);
            if(order.buyerUserId()!=target && order.sellerUserId()!=target) throw new IllegalArgumentException("举报对象不属于当前订单");
        }
        lockUser(userId);if(id==null) id=UUID.randomUUID();
        var existing=jdbc.queryForList("SELECT * FROM c2c_reports WHERE report_id=?",id);
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(((Number)row.get("user_id")).longValue()!=userId || ((Number)row.get("target_user_id")).longValue()!=target
                    || !Objects.equals(orderId,row.get("order_id")) || !reason.equals(row.get("reason")))
                throw new IllegalStateException("原举报内容不匹配");
            return id;
        }
        if(count("SELECT count(*) FROM c2c_reports WHERE user_id=? AND created_at>now()-interval '24 hours'",userId)>=20)
            throw new IllegalStateException("今日举报较多，请等待客服处理");
        jdbc.update("INSERT INTO c2c_reports(report_id,user_id,target_user_id,order_id,reason) VALUES(?,?,?,?,?)",id,userId,target,orderId,reason);
        return id;
    }
    Map<String,Object> reports(long userId,int limit,int offset) {
        page(limit,offset);
        var rows=jdbc.query("SELECT * FROM c2c_reports WHERE user_id=? ORDER BY created_at DESC,report_id DESC LIMIT ? OFFSET ?",(r,n)->{
            var view=new LinkedHashMap<String,Object>();
            view.put("reportId",r.getObject("report_id").toString());view.put("targetUserId",Long.toString(r.getLong("target_user_id")));
            view.put("orderId",r.getObject("order_id")==null?null:r.getObject("order_id").toString());
            view.put("reason",r.getString("reason"));view.put("status",r.getString("status"));view.put("reviewReason",r.getString("review_reason"));
            view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
        },userId,limit+1,offset);
        return Map.of("items",rows.stream().limit(limit).toList(),"hasMore",rows.size()>limit,"offset",offset);
    }

    List<Map<String,Object>> alerts(long userId) {
        return jdbc.query("SELECT * FROM c2c_price_alerts WHERE user_id=? ORDER BY created_at DESC,alert_id LIMIT 100",(r,n)->{
            var view=new LinkedHashMap<String,Object>();
            view.put("alertId",r.getObject("alert_id").toString());view.put("marketId",r.getObject("market_id").toString());
            view.put("side",r.getString("side"));view.put("targetPrice",r.getBigDecimal("target_price").toPlainString());
            view.put("enabled",r.getBoolean("enabled"));view.put("revision",Long.toString(r.getLong("revision")));
            view.put("triggeredAt",r.getTimestamp("triggered_at")==null?null:r.getTimestamp("triggered_at").toInstant().toString());return view;
        },userId);
    }
    @Transactional
    UUID saveAlert(long userId,UUID id,long revision,UUID market,String side,String price,boolean enabled) {
        if(side==null || !List.of("BUY","SELL").contains(side)) throw new IllegalArgumentException("预警方向无效");
        var config=markets.market(market,false);var target=C2cMoney.decimal(price);
        if(target.scale()>18 || target.compareTo(config.minPrice())<0 || target.compareTo(config.maxPrice())>0)
            throw new IllegalArgumentException("预警价格超出市场范围");
        lockUser(userId);
        if(id==null) {
            if(revision!=0) throw new IllegalStateException("预警版本无效");
            if(enabled && count("SELECT count(*) FROM c2c_price_alerts WHERE user_id=? AND enabled",userId)>=20)
                throw new IllegalStateException("最多启用 20 条价格预警");
            id=UUID.randomUUID();jdbc.update("INSERT INTO c2c_price_alerts(alert_id,user_id,market_id,side,target_price,enabled) VALUES(?,?,?,?,?,?)",id,userId,market,side,target,enabled);
        } else {
            if(enabled && count("SELECT count(*) FROM c2c_price_alerts WHERE user_id=? AND enabled AND alert_id<>?",userId,id)>=20)
                throw new IllegalStateException("最多启用 20 条价格预警");
            int changed=jdbc.update("""
                    UPDATE c2c_price_alerts SET market_id=?,side=?,target_price=?,enabled=?,triggered_at=NULL,revision=revision+1
                    WHERE alert_id=? AND user_id=? AND revision=?
                    """,market,side,target,enabled,id,userId,revision);
            if(changed!=1) throw new IllegalStateException("价格预警已变更，请刷新");
        }
        return id;
    }
    @Transactional
    void deleteAlert(long userId,UUID id) {jdbc.update("DELETE FROM c2c_price_alerts WHERE user_id=? AND alert_id=?",userId,id);}

    Map<String,Object> notifications(long userId,Long before,int limit) {
        page(limit,0);
        var rows=jdbc.query("""
                SELECT * FROM c2c_notifications WHERE user_id=? AND (CAST(? AS bigint) IS NULL OR notification_id<?)
                ORDER BY notification_id DESC LIMIT ?
                """,(r,n)->{
                    var view=new LinkedHashMap<String,Object>();view.put("notificationId",Long.toString(r.getLong("notification_id")));
                    view.put("kind",r.getString("kind"));view.put("entityId",r.getObject("entity_id").toString());
                    view.put("details",json.readValue(r.getString("details"),Map.class));
                    view.put("read",r.getTimestamp("read_at")!=null);view.put("createdAt",r.getTimestamp("created_at").toInstant().toString());return view;
                },userId,before,before,limit+1);
        var items=rows.stream().limit(limit).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("hasMore",rows.size()>limit);
        result.put("nextBeforeId",rows.size()>limit?items.getLast().get("notificationId"):null);
        result.put("unreadCount",Long.toString(count("SELECT count(*) FROM c2c_notifications WHERE user_id=? AND read_at IS NULL",userId)));
        return result;
    }
    @Transactional
    void readNotifications(long userId,long through) {
        jdbc.update("UPDATE c2c_notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL AND notification_id<=?",userId,through);
    }

    private List<Map<String,Object>> escrow(long userId) {
        return jdbc.query("""
                SELECT f.asset,a.scale_units,
                    COALESCE(sum(CASE WHEN f.status='APPLIED' AND f.purpose IN ('AD_RESERVE','ORDER_RESERVE') THEN f.amount_units::numeric
                        WHEN f.status='APPLIED' THEN -f.amount_units::numeric ELSE 0 END),0) AS held,
                    count(*) FILTER(WHERE f.status NOT IN ('APPLIED','REJECTED')) AS pending,
                    count(*) FILTER(WHERE f.status='REJECTED' AND f.purpose NOT IN ('AD_RESERVE','ORDER_RESERVE')) AS failed
                FROM c2c_fund_intents f LEFT JOIN c2c_ads ad ON f.owner_type='AD' AND f.owner_id=ad.ad_id
                LEFT JOIN c2c_orders o ON f.owner_type='ORDER' AND f.owner_id=o.order_id JOIN assets a ON a.asset=f.asset
                WHERE COALESCE(ad.maker_user_id,o.seller_user_id)=? GROUP BY f.asset,a.scale_units
                """,(r,n)->{
                    var result=new LinkedHashMap<String,Object>();var held=r.getBigDecimal("held");
                    boolean known=r.getLong("pending")==0 && r.getLong("failed")==0 && held.signum()>=0
                            && held.compareTo(BigDecimal.valueOf(Long.MAX_VALUE))<=0;
                    result.put("asset",r.getString("asset"));result.put("assetScale",Long.toString(r.getLong("scale_units")));
                    result.put("confirmedEscrowUnits",known?held.toBigIntegerExact().toString():null);
                    result.put("pendingIntentCount",Long.toString(r.getLong("pending")));
                    result.put("requiresReview",r.getLong("failed")>0 || held.signum()<0);
                    return result;
                },userId);
    }
    private Profile profile(long userId,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_profiles WHERE user_id=?"+(lock?" FOR UPDATE":""),(r,n)->
                new Profile(r.getString("nickname"),r.getBoolean("receive_strangers"),r.getInt("verification_minutes"),r.getLong("revision")),userId);
        String id=Long.toString(userId);
        return rows.isEmpty()?new Profile("用户"+id.substring(Math.max(0,id.length()-6)),false,0,0):rows.getFirst();
    }
    private Map<String,Object> profileView(Profile profile,long userId) {
        var view=new LinkedHashMap<String,Object>();view.put("userId",Long.toString(userId));view.put("nickname",profile.nickname());
        view.put("receiveStrangers",profile.strangers());view.put("verificationMinutes",profile.verificationMinutes());
        view.put("revision",Long.toString(profile.revision()));return view;
    }
    private Map<String,Object> merchant(long userId) {
        return jdbc.query("SELECT * FROM c2c_merchants WHERE user_id=?",(r,n)->{
            var result=new LinkedHashMap<String,Object>();result.put("status",r.getString("status"));result.put("tier",r.getString("tier"));
            result.put("application",r.getString("application_text"));result.put("reviewReason",r.getString("review_reason"));
            result.put("revision",Long.toString(r.getLong("revision")));return result;
        },userId).stream().findFirst().orElse(null);
    }
    private void lockUser(long id) {
        if(jdbc.query("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",(r,n)->r.getLong(1),id).isEmpty())
            throw new IllegalStateException("账户不可用");
    }
    private long count(String sql,Object... args) {var count=jdbc.queryForObject(sql,Long.class,args);return count==null?0:count;}
    private static String text(String value,int minimum,int maximum,String label) {
        String result=value==null?"":Normalizer.normalize(value.trim().replace("\r\n","\n"),Normalizer.Form.NFKC);
        if(result.length()<minimum || result.length()>maximum || result.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException(label+"长度或格式无效");
        return result;
    }
    private static void relationKind(String kind) {
        if(kind==null || !List.of("FOLLOW","BLOCK").contains(kind)) throw new IllegalArgumentException("关系类型无效");
    }
    private static void page(int limit,int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>10000) throw new IllegalArgumentException("分页参数无效");
    }
}
