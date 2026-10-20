package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 审核与裁决只更改原业务对象。金额、资产及退款/收款人不来自后台表单。 */
@Service
public class C2cAdminReview {
    public record MerchantDecision(String revision,String decision,String tier,String reason) { }
    public record PaymentDecision(String revision,String decision,boolean identityChecked,String reason) { }
    public record AdDecision(String revision,String action,String reason) { }
    public record AppealDecision(String decision,String reason) { }
    public record ReportDecision(String decision,String reason) { }
    private final C2cAdminAccess access;
    private final JdbcTemplate jdbc;
    private final C2cMarkets markets;
    private final C2cAdsRepository adRows;
    private final C2cAds ads;
    private final C2cOrdersRepository orderRows;
    private final C2cOrders orders;
    private final C2cPaymentAccounts payments;
    private final C2cQueries queries;
    private final ComplianceService compliance;
    private final GatewayUserRepository users;

    public C2cAdminReview(C2cAdminAccess access,JdbcTemplate jdbc,C2cMarkets markets,C2cAdsRepository adRows,C2cAds ads,
                          C2cOrdersRepository orderRows,C2cOrders orders,C2cPaymentAccounts payments,C2cQueries queries,
                          ComplianceService compliance,GatewayUserRepository users) {
        this.access=access;this.jdbc=jdbc;this.markets=markets;this.adRows=adRows;this.ads=ads;
        this.orderRows=orderRows;this.orders=orders;this.payments=payments;this.queries=queries;this.compliance=compliance;this.users=users;
    }

    @Transactional
    Map<String,Object> merchant(String authorization,C2cAdminAccess.Request request,byte[] body,long userId,MerchantDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if(administrator.userId()==userId) throw C2cProblem.forbidden("不能审核本人的商家资格");
        if(jdbc.query("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",(r,n)->r.getLong(1),userId).isEmpty())
            throw new IllegalArgumentException("用户不存在");
        var rows=jdbc.queryForList("SELECT * FROM c2c_merchants WHERE user_id=? FOR UPDATE",userId);
        if(rows.isEmpty()) throw new IllegalArgumentException("商家申请不存在");
        var current=rows.getFirst();
        if(((Number)current.get("revision")).longValue()!=C2cAdminMarkets.version(input.revision(),false)) throw new IllegalStateException("商家状态已变更，请刷新");
        String state;
        String tier=(String)current.get("tier");
        if("APPROVE".equals(input.decision())) {
            markets.requireMerchantBond(userId);
            var user=users.find(userId).orElseThrow();var kyc=compliance.kyc(userId);
            if(!"NORMAL".equals(user.status()) || kyc==null || !"VERIFIED".equals(kyc.status())
                    || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now()))) throw new IllegalStateException("用户当前没有有效身份认证或账户受限");
            if(input.tier()==null || !List.of("VERIFIED","VIP").contains(input.tier())) throw new IllegalArgumentException("商家等级无效");
            state="APPROVED";tier=input.tier();
        } else if("REJECT".equals(input.decision()) && "PENDING".equals(current.get("status"))) state="REJECTED";
        else if("SUSPEND".equals(input.decision())) {
            jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE maker_user_id=? AND status='ACTIVE'",userId);
            state="SUSPENDED";
        } else throw new IllegalArgumentException("商家审核操作无效");
        jdbc.update("""
                UPDATE c2c_merchants SET status=?,tier=?,review_reason=?,reviewed_by=?,reviewed_at=now(),revision=revision+1,updated_at=now()
                WHERE user_id=?
                """,state,tier,input.reason().trim(),administrator.userId(),userId);
        access.audit(administrator,request,body);
        return Map.of("userId",Long.toString(userId),"status",state,"tier",tier,"revision",Long.toString(((Number)current.get("revision")).longValue()+1));
    }

    @Transactional
    Map<String,Object> payment(String authorization,C2cAdminAccess.Request request,byte[] body,UUID id,PaymentDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        var account=payment(id);
        if(account.userId()==administrator.userId()) throw C2cProblem.forbidden("不能审核本人的收款资料");
        long revision=C2cAdminMarkets.version(input.revision(),false);
        if("DISABLE".equals(input.decision())) payments.disable(account.userId(),id,revision);
        else if("APPROVE".equals(input.decision()) || "REJECT".equals(input.decision())) {
            if("APPROVE".equals(input.decision()) && !input.identityChecked()) throw new IllegalArgumentException("请先核对收款实名与有效认证资料，并确认已完成核验");
            payments.review(id,revision,"APPROVE".equals(input.decision()),input.reason(),administrator);
        } else throw new IllegalArgumentException("收款审核操作无效");
        access.audit(administrator,request,body);return C2cViews.paymentAccount(payments.get(account.userId(),id,false));
    }

    @Transactional
    Map<String,Object> paymentDetails(String authorization,C2cAdminAccess.Request request,UUID id) {
        var administrator=access.read(authorization,true);var account=payment(id);
        var result=new LinkedHashMap<>(C2cViews.paymentAccount(account));result.put("details",payments.details(account));
        result.put("manualIdentityVerificationRequired",true);
        var kyc=compliance.kyc(account.userId());result.put("kycStatus",kyc==null?"UNVERIFIED":kyc.status());
        result.put("kycExpiresAt",kyc==null || kyc.expiresAt()==null?null:kyc.expiresAt().toString());
        access.audit(administrator,request,null);return result;
    }

    Map<String,Object> order(String authorization,UUID id) {access.read(authorization,false);return queries.adminOrder(id,false);}
    @Transactional
    Map<String,Object> orderPayment(String authorization,C2cAdminAccess.Request request,UUID id) {
        var administrator=access.read(authorization,true);var result=queries.adminOrder(id,true);access.audit(administrator,request,null);return result;
    }

    @Transactional
    Map<String,Object> ad(String authorization,C2cAdminAccess.Request request,byte[] body,UUID id,AdDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if("CLOSE".equals(input.action())) throw new IllegalArgumentException("强制关闭与退款须使用双人审批入口");
        var ad=ads.moderate(id,C2cAdminMarkets.version(input.revision(),false),input.action());
        access.audit(administrator,request,body);return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    @Transactional
    Map<String,Object> closeAd(String authorization,C2cAdminAccess.Request request,byte[] body,UUID id,AdDecision input) {
        if(!"CLOSE".equals(input.action())) throw new IllegalArgumentException("强制关闭操作无效");
        C2cAdminMarkets.reason(input.reason());var approved=access.resolve(authorization,request,body);
        var owner=adRows.get(id,false).makerUserId();requireIndependent(approved,owner,owner);
        var ad=ads.closeByAdministrator(id,C2cAdminMarkets.version(input.revision(),false));
        access.audit(approved.administrator(),request,body);return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    @Transactional
    Map<String,Object> appeal(String authorization,C2cAdminAccess.Request request,byte[] body,UUID id,AppealDecision input) {
        C2cAdminMarkets.reason(input.reason());var approved=access.resolve(authorization,request,body);
        var rows=jdbc.query("SELECT order_id FROM c2c_appeals WHERE appeal_id=?",(r,n)->r.getObject(1,UUID.class),id);
        if(rows.isEmpty()) throw new IllegalArgumentException("申诉不存在");
        var order=orderRows.get(rows.getFirst(),false);
        requireIndependent(approved,order.buyerUserId(),order.sellerUserId());
        orders.resolveAppeal(id,input.decision(),input.reason(),approved.administrator().userId(),approved.approval().approvalId());
        access.audit(approved.administrator(),request,body);return queries.adminOrder(order.id(),false);
    }

    @Transactional
    Map<String,Object> report(String authorization,C2cAdminAccess.Request request,byte[] body,UUID id,ReportDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if(input.decision()==null || !List.of("REVIEWED","DISMISSED").contains(input.decision())) throw new IllegalArgumentException("举报结论无效");
        var rows=jdbc.queryForList("SELECT * FROM c2c_reports WHERE report_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("举报不存在");var current=rows.getFirst();
        if(((Number)current.get("user_id")).longValue()==administrator.userId() || ((Number)current.get("target_user_id")).longValue()==administrator.userId())
            throw C2cProblem.forbidden("不能处理涉及本人的举报");
        if(!"OPEN".equals(current.get("status"))) throw new IllegalStateException("举报已处理，请刷新");
        jdbc.update("UPDATE c2c_reports SET status=?,review_reason=?,reviewed_by=?,reviewed_at=now() WHERE report_id=?",
                input.decision(),input.reason().trim(),administrator.userId(),id);
        access.audit(administrator,request,body);return Map.of("reportId",id.toString(),"status",input.decision());
    }
    private C2cPaymentAccounts.Account payment(UUID id) {
        var owner=jdbc.query("SELECT user_id FROM c2c_payment_accounts WHERE account_id=?",(r,n)->r.getLong(1),id);
        if(owner.isEmpty()) throw new IllegalArgumentException("收款资料不存在");return payments.get(owner.getFirst(),id,false);
    }
    private static void requireIndependent(C2cAdminAccess.Approved approved,long buyer,long seller) {
        long operator=approved.administrator().userId(),reviewer=approved.approval().approverUserId();
        if(operator==buyer || operator==seller || reviewer==buyer || reviewer==seller)
            throw C2cProblem.forbidden("裁决人与审批人均不能参与当前交易");
    }
}
