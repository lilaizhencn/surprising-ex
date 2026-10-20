package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 广告从真实托管到账后开始接受订单；关闭只释放尚未被订单分配的库存。 */
@Service
public class C2cAds {
    record Publish(UUID marketId,String side,String price,String quantity,String minFiat,String maxFiat,
                   List<String> methodCodes,List<UUID> paymentAccountIds,int paymentWindowSeconds,
                   String terms,int minCompleted) { }
    private final C2cAdsRepository ads;
    private final C2cMarkets markets;
    private final C2cPaymentAccounts payments;
    private final C2cAccess access;
    private final C2cFundsRepository funds;
    private final C2cSubmissions submissions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public C2cAds(C2cAdsRepository ads,C2cMarkets markets,C2cPaymentAccounts payments,C2cAccess access,
                   C2cFundsRepository funds,JdbcTemplate jdbc,ObjectMapper json,C2cSubmissions submissions) {
        this.submissions=submissions;
        this.ads=ads;this.markets=markets;this.payments=payments;this.access=access;this.funds=funds;this.jdbc=jdbc;this.json=json;
    }

    @Transactional
    C2cAdsRepository.Ad publish(JwtPrincipal user,String authorization,String key,Publish request,String email,String totp) {
        requireKey(key);
        if(request==null) throw new IllegalArgumentException("广告资料不完整");
        if((request.methodCodes()!=null && request.methodCodes().stream().anyMatch(Objects::isNull))
                || (request.paymentAccountIds()!=null && request.paymentAccountIds().stream().anyMatch(Objects::isNull)))
            throw new IllegalArgumentException("收付款方式不完整");
        String hash=C2cAccess.digest(json.writeValueAsString(request));
        var receipt=submissions.begin(user.userId(),key,"AD_PUBLISH",hash);
        if(receipt.status().equals("SUCCEEDED")) {
            var accepted = ads.get(receipt.resultId(),false);
            requireOwner(user, accepted);
            return accepted;
        }
        var configuration=markets.configuration(true);
        if(!configuration.enabled()) throw new IllegalStateException("C2C 暂未开放");
        if (jdbc.query("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",(r,n)->r.getLong(1),user.userId()).isEmpty())
            throw new IllegalStateException("账户不可用");
        Long active = jdbc.queryForObject("SELECT count(*) FROM c2c_ads WHERE maker_user_id=? AND status NOT IN ('CLOSED','REJECTED')",Long.class,user.userId());
        if (active != null && active >= configuration.maxActiveAds()) throw new IllegalStateException("请先关闭不再使用的广告");
        var market=markets.market(request.marketId(),true);
        access.requireTrading(user,market);
        requireMerchant(user.userId());
        validateTerms(market,request.price(),request.minFiat(),request.maxFiat(),request.terms(),request.minCompleted());
        if(request.side()==null || !List.of("BUY","SELL").contains(request.side())) throw new IllegalArgumentException("广告方向无效");
        long units=C2cMoney.quantityUnits(request.quantity(),market.assetScale());
        BigDecimal price=C2cMoney.decimal(request.price());
        if(C2cMoney.fiatAmount(units,price,market.assetScale(),market.fiatDecimals()).compareTo(C2cMoney.decimal(request.minFiat()))<0)
            throw new IllegalArgumentException("广告库存小于最低交易限额");
        int window=request.paymentWindowSeconds();
        if(window<60 || window>market.paymentWindowSeconds()) throw new IllegalArgumentException("付款窗口超过市场允许范围");
        var methods=request.methodCodes()==null?List.<String>of():request.methodCodes().stream().distinct().sorted().toList();
        var accounts=request.paymentAccountIds()==null?List.<UUID>of():request.paymentAccountIds().stream().distinct().sorted().toList();
        if(methods.isEmpty() || methods.size()>8 || methods.stream().anyMatch(m->!m.matches("[A-Z][A-Z0-9_]{0,31}")))
            throw new IllegalArgumentException("请选择收付款方式");
        for(String method:methods) if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_payment_methods WHERE code=? AND enabled AND ?=ANY(currencies))",Boolean.class,method,market.fiat())))
            throw new IllegalArgumentException("收付款方式尚未开放");
        if(request.side().equals("SELL")) {
            var actual=new HashSet<String>();
            if(accounts.isEmpty() || accounts.size()>8) throw new IllegalArgumentException("请选择已审核的收款账号");
            for(UUID id:accounts) {
                var account=payments.get(user.userId(),id,true);
                if(!market.fiat().equals(account.fiat())) throw new IllegalArgumentException("收款账号法币不匹配");
                actual.add(account.method());
            }
            if(!actual.equals(new HashSet<>(methods))) throw new IllegalArgumentException("收款账号与广告方式不匹配");
        } else if(!accounts.isEmpty()) throw new IllegalArgumentException("买入广告由出售方选择本人收款账号");
        var ad=ads.create(user.userId(),market.id(),request.side(),price,units,
                C2cMoney.decimal(request.minFiat()),C2cMoney.decimal(request.maxFiat()),methods,accounts,window,
                request.terms()==null?"":request.terms().trim(),request.minCompleted());
        if(request.side().equals("SELL")) {
            access.verifyFunds(user,authorization,email,totp);
            funds.createOrGet("AD",ad.id(),"AD_RESERVE",user.userId(),configuration.escrowUserId(),market.asset(),units);
        }
        submissions.succeed(user.userId(),key,ad.id());
        return ad; // Core 资金指令由事务提交后的续办任务发送。
    }

    @Transactional
    C2cAdsRepository.Ad status(JwtPrincipal user,String authorization,UUID id,long revision,String action,String email,String totp) {
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
    C2cAdsRepository.Ad moderate(UUID id,long revision,String action) {
        var ad=ads.get(id,true);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在确认中");
        if("PAUSE".equals(action)) ads.setStatus(id,"PAUSED");
        else if("RESUME".equals(action)) {requirePublishable(ad);ads.setStatus(id,"ACTIVE");}
        else throw new IllegalArgumentException("请选择暂停或恢复广告");
        return ads.get(id,false);
    }

    // 只由已消费双人审批的后台入口调用；退款金额和收款人始终来自锁定的原广告。
    @Transactional
    C2cAdsRepository.Ad closeByAdministrator(UUID id,long revision) {
        var ad=ads.get(id,true);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在确认中");
        closeAvailable(ad);return ads.get(id,false);
    }

    private void closeAvailable(C2cAdsRepository.Ad ad) {
        if(ad.side().equals("SELL") && ad.availableUnits()>0) {
            var config=markets.configuration();var market=markets.market(ad.marketId(),false);
            funds.createOrGet("AD",ad.id(),"AD_REFUND",config.escrowUserId(),ad.makerUserId(),market.asset(),ad.availableUnits());
            ads.closeAvailable(ad,"CLOSING");
        } else ads.closeAvailable(ad,"CLOSED");
    }
    private void requirePublishable(C2cAdsRepository.Ad ad) {
        markets.requireOpen();var market=markets.market(ad.marketId(),true);
        access.requireCounterparty(ad.makerUserId(),market);requireMerchant(ad.makerUserId());
        validateTerms(market,ad.price().toPlainString(),ad.minFiat().toPlainString(),ad.maxFiat().toPlainString(),ad.terms(),ad.minCompleted());
        if(ad.paymentWindowSeconds()>market.paymentWindowSeconds()) throw new IllegalStateException("付款窗口已变更，请关闭后按新规则发布");
        if(ad.side().equals("SELL")) for(UUID account:ad.accounts()) payments.get(ad.makerUserId(),account,true);
    }

    @Transactional
    C2cAdsRepository.Ad edit(JwtPrincipal user,UUID id,long revision,String price,String min,String max,String terms,int minCompleted) {
        var ad=ads.get(id,true);requireOwner(user,ad);
        if(ad.revision()!=revision || !List.of("ACTIVE","PAUSED").contains(ad.status())) throw new IllegalStateException("广告已变更或资金尚在处理中");
        var market=markets.market(ad.marketId(),true);access.requireTrading(user,market);requireMerchant(user.userId());
        validateTerms(market,price,min,max,terms,minCompleted);
        ads.edit(ad,C2cMoney.decimal(price),C2cMoney.decimal(min),C2cMoney.decimal(max),terms==null?"":terms.trim(),minCompleted);
        return ads.get(id,false);
    }

    @Transactional
    void finishFunding(UUID id) {
        var ad=ads.get(id,true);
        if(!List.of("RESERVING","CLOSING").contains(ad.status())) return;
        String purpose=ad.status().equals("RESERVING")?"AD_RESERVE":"AD_REFUND";
        var intents=jdbc.query("SELECT status FROM c2c_fund_intents WHERE owner_type='AD' AND owner_id=? AND purpose=?",(r,n)->r.getString(1),id,purpose);
        if(intents.isEmpty()) throw new IllegalStateException("广告缺少原资金意图");
        if(intents.getFirst().equals("APPLIED")) {
            if(purpose.equals("AD_REFUND")) ads.setStatus(id,"CLOSED");
            else {
                boolean approved=Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM c2c_merchants WHERE user_id=? AND status='APPROVED')
                        """,Boolean.class,ad.makerUserId()));
                boolean paymentReady=ad.accounts().stream().allMatch(account->{
                    try {payments.get(ad.makerUserId(),account,true);return true;}catch(IllegalStateException ex){return false;}
                });
                boolean marketReady;
                try {
                    var current=markets.market(ad.marketId(),true);
                    access.requireCounterparty(ad.makerUserId(),current);
                    markets.requireMerchantBond(ad.makerUserId());
                    validateTerms(current,ad.price().toPlainString(),ad.minFiat().toPlainString(),ad.maxFiat().toPlainString(),ad.terms(),ad.minCompleted());
                    marketReady=ad.paymentWindowSeconds()<=current.paymentWindowSeconds();
                } catch(IllegalArgumentException | IllegalStateException unavailable) {marketReady=false;}
                ads.setStatus(id,approved && paymentReady && marketReady && markets.configuration().enabled()?"ACTIVE":"PAUSED");
            }
        } else if(purpose.equals("AD_RESERVE") && intents.getFirst().equals("REJECTED")) ads.closeAvailable(ad,"REJECTED");
    }

    private void requireMerchant(long userId) {
        markets.requireMerchantBond(userId);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_merchants WHERE user_id=? AND status='APPROVED')",Boolean.class,userId)))
            throw new IllegalStateException("请先完成认证商家申请");
    }
    private static void requireOwner(JwtPrincipal user,C2cAdsRepository.Ad ad) {
        if(user.userId()!=ad.makerUserId()) throw new IllegalStateException("只能管理本人广告");
    }
    static void requireKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalArgumentException("请提供稳定的原请求标识");
    }
    private static void validateTerms(C2cMarkets.Market market,String price,String min,String max,String terms,int minCompleted) {
        var p=C2cMoney.decimal(price);var low=C2cMoney.decimal(min);var high=C2cMoney.decimal(max);
        if(p.compareTo(market.minPrice())<0 || p.compareTo(market.maxPrice())>0 || p.scale()>18
                || low.compareTo(market.minFiat())<0 || high.compareTo(market.maxFiat())>0 || low.compareTo(high)>0
                || low.stripTrailingZeros().scale()>market.fiatDecimals() || high.stripTrailingZeros().scale()>market.fiatDecimals()
                || (terms!=null && terms.length()>2000) || minCompleted<0 || minCompleted>1000000)
            throw new IllegalArgumentException("广告价格、限额或交易条件超出市场范围");
    }
}
