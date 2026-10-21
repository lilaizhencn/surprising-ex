package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.utils.C2cMoney;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cAdPublish;
import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentMethodRepository;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 广告从真实托管到账后开始接受订单；关闭只释放尚未被订单分配的库存。 */
@Service
public class C2cAds {
    private final C2cAdsRepository ads;
    private final C2cMarketService markets;
    private final C2cPaymentAccounts payments;
    private final C2cAccess access;
    private final C2cFundsRepository funds;
    private final C2cSubmissions submissions;
    private final GatewayUserRepository users;
    private final C2cMerchantRepository merchants;
    private final C2cPaymentMethodRepository paymentMethods;
    public C2cAds(C2cAdsRepository ads, C2cMarketService markets, C2cPaymentAccounts payments, C2cAccess access,
                  C2cFundsRepository funds, C2cSubmissions submissions,
                  GatewayUserRepository users, C2cMerchantRepository merchants, C2cPaymentMethodRepository paymentMethods) {
        this.ads=ads; this.markets=markets; this.payments=payments; this.access=access; this.funds=funds;
        this.submissions=submissions; this.users=users; this.merchants=merchants; this.paymentMethods=paymentMethods;
    }

    @Transactional
    public C2cAd publish(JwtPrincipal user,String authorization,String key,String hash,C2cAdPublish request,String email,String totp) {
        var receipt=submissions.begin(user.userId(),key,"AD_PUBLISH",hash);
        if(receipt.status().equals("SUCCEEDED")) {
            var accepted = ads.get(receipt.resultId(),false);
            requireOwner(user, accepted);
            return accepted;
        }
        var configuration=markets.configuration(true);
        if(!configuration.enabled()) throw new IllegalStateException("C2C 暂未开放");
        if (!users.lockForNoKeyUpdate(user.userId()))
            throw new IllegalStateException("账户不可用");
        Long active = ads.activeCount(user.userId());
        if (active != null && active >= configuration.maxActiveAds()) throw new IllegalStateException("请先关闭不再使用的广告");
        var market=markets.market(request.marketId(),true);
        access.requireTrading(user,market);
        requireMerchant(user.userId());
        validateTerms(market,request.price(),request.minFiat(),request.maxFiat(),request.terms(),request.minCompleted());
        long units=C2cMoney.quantityUnits(request.quantity().toPlainString(),market.assetScale());
        BigDecimal price=request.price();
        if(C2cMoney.fiatAmount(units,price,market.assetScale(),market.fiatDecimals()).compareTo(request.minFiat())<0)
            throw new IllegalArgumentException("广告库存小于最低交易限额");
        int window=request.paymentWindowSeconds();
        if(window>market.paymentWindowSeconds()) throw new IllegalArgumentException("付款窗口超过市场允许范围");
        var methods=request.methodCodes();var accounts=request.paymentAccountIds();
        for(String method:methods) if(paymentMethods.available(method,market.fiat()).isEmpty())
            throw new IllegalArgumentException("收付款方式尚未开放");
        if(request.side()==C2cAdPublish.Side.SELL) {
            var actual=new HashSet<String>();
            for(UUID id:accounts) {
                var account=payments.get(user.userId(),id,true);
                if(!market.fiat().equals(account.fiat())) throw new IllegalArgumentException("收款账号法币不匹配");
                actual.add(account.method());
            }
            if(!actual.equals(new HashSet<>(methods))) throw new IllegalArgumentException("收款账号与广告方式不匹配");
        }
        UUID adId=UUID.randomUUID();
        String initialStatus=request.side()==C2cAdPublish.Side.SELL?"RESERVING":"ACTIVE";
        var ad=ads.create(adId,user.userId(),market.id(),request.side().name(),price,units,
                request.minFiat(),request.maxFiat(),methods,accounts,window,
                request.terms(),request.minCompleted(),initialStatus);
        if(request.side()==C2cAdPublish.Side.SELL) {
            access.verifyFunds(user,authorization,email,totp);
            funds.createOrGet("AD",ad.id(),"AD_RESERVE",user.userId(),configuration.escrowUserId(),market.asset(),units);
        }
        submissions.succeed(user.userId(),key,ad.id());
        return ad; // Core 资金指令由事务提交后的续办任务发送。
    }

    @Transactional
    C2cAd status(JwtPrincipal user,String authorization,UUID id,long revision,String action,String email,String totp) {
        if(action==null) throw new IllegalArgumentException("广告操作无效");
        var ad=ads.get(id,true);
        requireOwner(user,ad);
        if(ad.revision()!=revision) throw new IllegalStateException("广告版本已变更，请刷新");
        if(action.equals("CLOSE") && ad.status().equals("CLOSED")) return ad;
        if(!List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("请等待广告资金结果确认");
        switch(action) {
            case "PAUSE" -> ads.setStatus(id,"PAUSED");
            case "RESUME" -> {requirePublishable(ad);ads.setStatus(id,"ACTIVE");}
            case "CLOSE" -> {
                if(ad.side().equals("SELL") && ad.availableUnits()>0) access.verifyFunds(user,authorization,email,totp);
                closeAvailable(ad);
            }
            default -> throw new IllegalArgumentException("广告操作无效");
        }
        return ads.get(id,false);
    }

    @Transactional
    C2cAd moderate(UUID id,long revision,String action) {
        var ad=ads.get(id,true);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在确认中");
        if("PAUSE".equals(action)) ads.setStatus(id,"PAUSED");
        else if("RESUME".equals(action)) {requirePublishable(ad);ads.setStatus(id,"ACTIVE");}
        else throw new IllegalArgumentException("请选择暂停或恢复广告");
        return ads.get(id,false);
    }

    // 只由已消费双人审批的后台入口调用；退款金额和收款人始终来自锁定的原广告。
    @Transactional
    C2cAd closeByAdministrator(UUID id,long revision) {
        var ad=ads.get(id,true);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在确认中");
        closeAvailable(ad);return ads.get(id,false);
    }

    private void closeAvailable(C2cAd ad) {
        if(ad.side().equals("SELL") && ad.availableUnits()>0) {
            var config=markets.configuration();var market=markets.market(ad.marketId(),false);
            funds.createOrGet("AD",ad.id(),"AD_REFUND",config.escrowUserId(),ad.makerUserId(),market.asset(),ad.availableUnits());
            ads.closeAvailable(ad,"CLOSING");
        } else ads.closeAvailable(ad,"CLOSED");
    }
    private void requirePublishable(C2cAd ad) {
        markets.requireOpen();var market=markets.market(ad.marketId(),true);
        access.requireCounterparty(ad.makerUserId(),market);requireMerchant(ad.makerUserId());
        validateTerms(market,ad.price(),ad.minFiat(),ad.maxFiat(),ad.terms(),ad.minCompleted());
        if(ad.paymentWindowSeconds()>market.paymentWindowSeconds()) throw new IllegalStateException("付款窗口已变更，请关闭后按新规则发布");
        if(ad.side().equals("SELL")) for(UUID account:ad.accounts()) payments.get(ad.makerUserId(),account,true);
    }

    @Transactional
    C2cAd edit(JwtPrincipal user,UUID id,long revision,String price,String min,String max,String terms,int minCompleted) {
        var ad=ads.get(id,true);requireOwner(user,ad);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在处理中");
        var market=markets.market(ad.marketId(),true);access.requireTrading(user,market);requireMerchant(user.userId());
        validateTerms(market,C2cMoney.decimal(price),C2cMoney.decimal(min),C2cMoney.decimal(max),terms,minCompleted);
        ads.edit(ad,C2cMoney.decimal(price),C2cMoney.decimal(min),C2cMoney.decimal(max),terms==null?"":terms.trim(),minCompleted);
        return ads.get(id,false);
    }

    @Transactional
    public void finishFunding(UUID id) {
        var ad=ads.get(id,true);
        if(!List.of("RESERVING","CLOSING").contains(ad.status())) return;
        String purpose=ad.status().equals("RESERVING")?"AD_RESERVE":"AD_REFUND";
        var intent=funds.find("AD",id,purpose);
        if(intent==null) throw new IllegalStateException("广告缺少原资金意图");
        if(intent.status().equals("APPLIED")) {
            if(purpose.equals("AD_REFUND")) ads.setStatus(id,"CLOSED");
            else {
                boolean approved=merchants.approved(ad.makerUserId());
                boolean paymentReady=ad.accounts().stream().allMatch(account->{
                    try {payments.get(ad.makerUserId(),account,true);return true;}catch(IllegalStateException ex){return false;}
                });
                boolean marketReady;
                try {
                    var current=markets.market(ad.marketId(),true);
                    access.requireCounterparty(ad.makerUserId(),current);
                    markets.requireMerchantBond(ad.makerUserId());
                    validateTerms(current,ad.price(),ad.minFiat(),ad.maxFiat(),ad.terms(),ad.minCompleted());
                    marketReady=ad.paymentWindowSeconds()<=current.paymentWindowSeconds();
                } catch(IllegalArgumentException | IllegalStateException unavailable) {marketReady=false;}
                ads.setStatus(id,approved && paymentReady && marketReady && markets.configuration().enabled()?"ACTIVE":"PAUSED");
            }
        } else if(purpose.equals("AD_RESERVE") && intent.status().equals("REJECTED")) ads.closeAvailable(ad,"REJECTED");
    }

    private void requireMerchant(long userId) {
        markets.requireMerchantBond(userId);
        if(!merchants.approved(userId))
            throw new IllegalStateException("请先完成认证商家申请");
    }
    private static void requireOwner(JwtPrincipal user,C2cAd ad) {
        if(user.userId()!=ad.makerUserId()) throw new IllegalStateException("只能管理本人广告");
    }
    private static void validateTerms(C2cMarket market,BigDecimal p,BigDecimal low,BigDecimal high,String terms,int minCompleted) {
        if(p.compareTo(market.minPrice())<0 || p.compareTo(market.maxPrice())>0 || p.scale()>18
                || low.compareTo(market.minFiat())<0 || high.compareTo(market.maxFiat())>0 || low.compareTo(high)>0
                || low.stripTrailingZeros().scale()>market.fiatDecimals() || high.stripTrailingZeros().scale()>market.fiatDecimals()
                || (terms!=null && terms.length()>2000) || minCompleted<0 || minCompleted>1000000)
            throw new IllegalArgumentException("广告价格、限额或交易条件超出市场范围");
    }
}
