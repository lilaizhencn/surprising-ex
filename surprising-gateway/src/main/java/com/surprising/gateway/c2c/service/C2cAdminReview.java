package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.model.C2cAdminRequest;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;
import com.surprising.gateway.c2c.utils.C2cViews;

import com.surprising.gateway.c2c.service.C2cQueryService;

import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.repository.C2cAppealRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cReportRepository;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import java.time.Instant;
import java.util.*;
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
    private final C2cMerchantRepository merchants;
    private final C2cAppealRepository appeals;
    private final C2cPaymentAccountRepository paymentRows;
    private final C2cReportRepository reports;
    private final C2cMarketService markets;
    private final C2cAdsRepository adRows;
    private final C2cAds ads;
    private final C2cOrdersRepository orderRows;
    private final C2cOrders orders;
    private final C2cPaymentAccounts payments;
    private final C2cQueryService queries;
    private final ComplianceService compliance;
    private final GatewayUserRepository users;

    public C2cAdminReview(C2cAdminAccess access,C2cMerchantRepository merchants,C2cAppealRepository appeals,
                          C2cPaymentAccountRepository paymentRows,C2cReportRepository reports,C2cMarketService markets,C2cAdsRepository adRows,C2cAds ads,
                          C2cOrdersRepository orderRows,C2cOrders orders,C2cPaymentAccounts payments,C2cQueryService queries,
                          ComplianceService compliance,GatewayUserRepository users) {
        this.access=access;this.merchants=merchants;this.appeals=appeals;this.paymentRows=paymentRows;this.reports=reports;this.markets=markets;this.adRows=adRows;this.ads=ads;
        this.orderRows=orderRows;this.orders=orders;this.payments=payments;this.queries=queries;this.compliance=compliance;this.users=users;
    }

    @Transactional
    public Map<String,Object> merchant(String authorization,C2cAdminRequest request,byte[] body,long userId,MerchantDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if(administrator.userId()==userId) throw C2cProblem.forbidden("不能审核本人的商家资格");
        if(!users.lockForNoKeyUpdate(userId)) throw new IllegalArgumentException("用户不存在");
        var current=merchants.find(userId,true).orElseThrow(()->new IllegalArgumentException("商家申请不存在"));
        if(current.revision()!=C2cAdminMarkets.version(input.revision(),false)) throw C2cProblem.conflict("商家状态已变更，请刷新");
        String state;
        String tier=current.tier();
        if("APPROVE".equals(input.decision())) {
            markets.requireMerchantBond(userId);
            var user=users.find(userId).orElseThrow();var kyc=compliance.kyc(userId);
            if(!"NORMAL".equals(user.status()) || kyc==null || !"VERIFIED".equals(kyc.status())
                    || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now()))) throw C2cProblem.conflict("用户当前没有有效身份认证或账户受限");
            if(input.tier()==null || !List.of("VERIFIED","VIP").contains(input.tier())) throw new IllegalArgumentException("商家等级无效");
            state="APPROVED";tier=input.tier();
        } else if("REJECT".equals(input.decision()) && "PENDING".equals(current.status())) state="REJECTED";
        else if("SUSPEND".equals(input.decision())) {
            adRows.pauseByMerchant(userId);
            state="SUSPENDED";
        } else throw new IllegalArgumentException("商家审核操作无效");
        merchants.review(userId,state,tier,input.reason().trim(),administrator.userId());
        access.audit(administrator,request,body);
        return Map.of("userId",Long.toString(userId),"status",state,"tier",tier,"revision",Long.toString(current.revision()+1));
    }

    @Transactional
    public Map<String,Object> payment(String authorization,C2cAdminRequest request,byte[] body,UUID id,PaymentDecision input) {
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
    public Map<String,Object> paymentDetails(String authorization,C2cAdminRequest request,UUID id) {
        var administrator=access.read(authorization,true);var account=payment(id);
        var result=new LinkedHashMap<>(C2cViews.paymentAccount(account));result.put("details",payments.details(account));
        result.put("manualIdentityVerificationRequired",true);
        var kyc=compliance.kyc(account.userId());result.put("kycStatus",kyc==null?"UNVERIFIED":kyc.status());
        result.put("kycExpiresAt",kyc==null || kyc.expiresAt()==null?null:kyc.expiresAt().toString());
        access.audit(administrator,request,null);return result;
    }

    public Map<String,Object> order(String authorization,UUID id) {access.read(authorization,false);return queries.adminOrder(id,false);}
    @Transactional
    public Map<String,Object> orderPayment(String authorization,C2cAdminRequest request,UUID id) {
        var administrator=access.read(authorization,true);var result=queries.adminOrder(id,true);access.audit(administrator,request,null);return result;
    }

    @Transactional
    public Map<String,Object> ad(String authorization,C2cAdminRequest request,byte[] body,UUID id,AdDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if("CLOSE".equals(input.action())) throw new IllegalArgumentException("强制关闭与退款须使用双人审批入口");
        var ad=ads.moderate(id,C2cAdminMarkets.version(input.revision(),false),input.action());
        access.audit(administrator,request,body);return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    @Transactional
    public Map<String,Object> closeAd(String authorization,C2cAdminRequest request,byte[] body,UUID id,AdDecision input) {
        if(!"CLOSE".equals(input.action())) throw new IllegalArgumentException("强制关闭操作无效");
        C2cAdminMarkets.reason(input.reason());var approved=access.resolve(authorization,request,body);
        var owner=adRows.get(id,false).makerUserId();requireIndependent(approved,owner,owner);
        var ad=ads.closeByAdministrator(id,C2cAdminMarkets.version(input.revision(),false));
        access.audit(approved.administrator(),request,body);return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    @Transactional
    public Map<String,Object> appeal(String authorization,C2cAdminRequest request,byte[] body,UUID id,AppealDecision input) {
        C2cAdminMarkets.reason(input.reason());var approved=access.resolve(authorization,request,body);
        UUID orderId=appeals.orderId(id).orElseThrow(()->new IllegalArgumentException("申诉不存在"));
        var order=orderRows.get(orderId,false);
        requireIndependent(approved,order.buyerUserId(),order.sellerUserId());
        orders.resolveAppeal(id,input.decision(),input.reason(),approved.administrator().userId(),approved.approval().approvalId());
        access.audit(approved.administrator(),request,body);return queries.adminOrder(order.id(),false);
    }

    @Transactional
    public Map<String,Object> report(String authorization,C2cAdminRequest request,byte[] body,UUID id,ReportDecision input) {
        var administrator=access.write(authorization,request,body);C2cAdminMarkets.reason(input.reason());
        if(input.decision()==null || !List.of("REVIEWED","DISMISSED").contains(input.decision())) throw new IllegalArgumentException("举报结论无效");
        var current=reports.find(id,true).orElseThrow(()->new IllegalArgumentException("举报不存在"));
        if(current.userId()==administrator.userId() || current.targetUserId()==administrator.userId())
            throw C2cProblem.forbidden("不能处理涉及本人的举报");
        if(!"OPEN".equals(current.status())) throw C2cProblem.conflict("举报已处理，请刷新");
        reports.decide(id,input.decision(),input.reason().trim(),administrator.userId());
        access.audit(administrator,request,body);return Map.of("reportId",id.toString(),"status",input.decision());
    }
    private C2cPaymentAccount payment(UUID id) {
        long owner=paymentRows.owner(id).orElseThrow(()->new IllegalArgumentException("收款资料不存在"));
        return payments.get(owner,id,false);
    }
    private static void requireIndependent(C2cAdminAccess.Approved approved,long buyer,long seller) {
        long operator=approved.administrator().userId(),reviewer=approved.approval().approverUserId();
        if(operator==buyer || operator==seller || reviewer==buyer || reviewer==seller)
            throw C2cProblem.forbidden("裁决人与审批人均不能参与当前交易");
    }
}
