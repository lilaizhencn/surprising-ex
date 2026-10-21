package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.C2cAdsRepository;
import com.surprising.gateway.c2c.C2cOrders;
import com.surprising.gateway.c2c.C2cOrdersRepository;
import com.surprising.gateway.c2c.C2cPaymentAccounts;
import com.surprising.gateway.c2c.C2cProblem;
import com.surprising.gateway.c2c.C2cSubmissions;
import com.surprising.gateway.c2c.C2cViews;
import com.surprising.gateway.c2c.model.*;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合市场资格、本人记录和不可变收款快照；不读取Core余额、不发送资金命令。 */
@Service
@Transactional(readOnly=true)
public class C2cQueryService {
    private final C2cAdsRepository ads;
    private final C2cOrdersRepository orders;
    private final C2cMarketService markets;
    private final C2cMarketRepository marketRows;
    private final C2cPaymentAccounts payments;
    private final C2cMerchantRepository merchants;
    private final C2cPaymentMethodRepository methods;
    private final C2cPaymentAccountRepository accounts;
    private final C2cOrderEventRepository events;
    private final C2cAppealRepository appeals;
    private final C2cSubmissions submissions;

    public C2cQueryService(C2cAdsRepository ads,C2cOrdersRepository orders,C2cMarketService markets,
                           C2cMarketRepository marketRows,C2cPaymentAccounts payments,C2cMerchantRepository merchants,
                           C2cPaymentMethodRepository methods,C2cPaymentAccountRepository accounts,
                           C2cOrderEventRepository events,C2cAppealRepository appeals,C2cSubmissions submissions) {
        this.ads=ads;this.orders=orders;this.markets=markets;this.marketRows=marketRows;this.payments=payments;
        this.merchants=merchants;this.methods=methods;this.accounts=accounts;this.events=events;
        this.appeals=appeals;this.submissions=submissions;
    }

    public Map<String,Object> markets() {
        return Map.of("markets",markets.markets(true).stream().map(C2cViews::market).toList(),
                "paymentMethods",paymentMethods(true),"serverTime",Instant.now().toString());
    }

    public Map<String,Object> offers(C2cOfferFilter filter,JwtPrincipal viewer) {
        var market=markets.market(filter.marketId(),true);
        var rows=readOffers(filter,viewer,market);
        var enabled=methods.enabledCodes(market.fiat());
        var visible=rows.stream().limit(filter.limit()).map(row -> {
            var view=C2cViews.ad(row.ad(),market,false);
            view.put("methodCodes",row.ad().methods().stream().filter(enabled::contains).toList());
            view.put("merchant",stats(row.ad().makerUserId(),row.nickname(),row.tier(),row.completed(),row.ended(),
                    row.completedTotal(),row.paidSeconds(),row.releasedSeconds()));
            return view;
        }).toList();
        return Map.of("items",visible,"hasMore",rows.size()>filter.limit(),"offset",filter.offset(),"serverTime",Instant.now().toString());
    }

    /** 快捷报价直接消费原生候选，避免从HTTP字段反推业务身份与金额。 */
    public List<C2cOffer> candidates(C2cOfferFilter filter,JwtPrincipal viewer) {
        return readOffers(filter,viewer,markets.market(filter.marketId(),true)).stream().limit(filter.limit()).toList();
    }

    private List<C2cOffer> readOffers(C2cOfferFilter filter,JwtPrincipal viewer,C2cMarket market) {
        if(filter.amount()!=null && filter.amount().stripTrailingZeros().scale()>market.fiatDecimals())
            throw new IllegalArgumentException("法币金额精度超出市场范围");
        if(filter.vipOnly()) {
            if(viewer==null) throw C2cProblem.forbidden("请登录后查看 VIP 市场");
            if(orders.completedCount(viewer.userId())<market.vipMinCompleted()) throw C2cProblem.forbidden("尚未满足 VIP 市场成交资格");
        }
        return ads.eligiblePage(filter,market,filter.side()==C2cOfferFilter.Side.BUY?"SELL":"BUY",viewer==null?0:viewer.userId());
    }

    public Map<String,Object> offer(UUID id) {
        var ad=ads.get(id,false);
        var market=markets.market(ad.marketId(),true);
        if(!ad.status().equals("ACTIVE") || ad.availableUnits()<=0) throw new IllegalStateException("广告暂不可交易");
        var view=C2cViews.ad(ad,market,false);
        view.put("merchant",merchant(ad.makerUserId()));
        var enabled=methods.enabledCodes(market.fiat());
        view.put("methodCodes",ad.methods().stream().filter(enabled::contains).toList());
        view.put("paymentOptions",ad.side().equals("SELL")?paymentOptions(ad).stream().map(option -> Map.<String,Object>of(
                "accountId",option.accountId().toString(),"methodCode",option.methodCode(),"maskedLabel",option.maskedLabel())).toList():List.of());
        view.put("requiresOwnPaymentAccount",ad.side().equals("BUY"));
        return view;
    }

    public List<C2cPaymentOption> paymentOptions(C2cAd ad) {
        return accounts.approvedOptions(ad.makerUserId(),ad.accounts());
    }

    public Map<String,Object> merchant(long userId) {
        var row=merchants.stats(userId).filter(value -> "APPROVED".equals(value.status()))
                .orElseThrow(() -> new IllegalArgumentException("商家当前不可用"));
        var view=stats(row.userId(),row.nickname(),row.tier(),row.completed(),row.ended(),row.total(),row.paidSeconds(),row.releasedSeconds());
        view.put("status",row.status());
        return view;
    }

    public Map<String,Object> ownAdView(C2cAd ad) {
        return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    public List<Map<String,Object>> myAds(long userId,int limit) {
        var rows=ads.mine(userId,limit);
        var pageMarkets=pageMarkets(rows.stream().map(C2cAd::marketId).toList());
        return rows.stream().map(ad -> C2cViews.ad(ad,pageMarket(pageMarkets,ad.marketId()),true)).toList();
    }

    public Map<String,Object> myOrders(long userId,C2cUserOrderFilter filter) {
        var rows=orders.mine(userId,filter);
        var visible=rows.stream().limit(filter.limit()).toList();
        var pageMarkets=pageMarkets(visible.stream().map(C2cOrder::marketId).toList());
        return Map.of("items",visible.stream().map(order -> C2cViews.order(order,pageMarket(pageMarkets,order.marketId()))).toList(),
                "hasMore",rows.size()>filter.limit(),"offset",filter.offset(),"serverTime",Instant.now().toString());
    }

    private Map<UUID,C2cMarket> pageMarkets(List<UUID> ids) {
        return marketRows.byIds(ids).stream().collect(Collectors.toMap(C2cMarket::id,Function.identity()));
    }
    private static C2cMarket pageMarket(Map<UUID,C2cMarket> values,UUID id) {
        var market=values.get(id);
        if(market==null) throw new IllegalArgumentException("交易市场不存在");
        return market;
    }

    public Map<String,Object> order(long userId,UUID id) {
        var order=orders.get(id,false);
        C2cOrders.requireParticipant(userId,order);
        return orderDetails(order,true);
    }

    public Map<String,Object> adminOrder(UUID id,boolean includePayment) {
        return orderDetails(orders.get(id,false),includePayment);
    }

    private Map<String,Object> orderDetails(C2cOrder order,boolean includePayment) {
        var market=markets.market(order.marketId(),false);
        var view=C2cViews.order(order,market);
        if(includePayment) {
            var payment=payments.details(order.sellerUserId(),order.paymentCiphertext());
            if(!market.fiat().equals(payment.fiat())) throw new IllegalStateException("订单收款快照法币不匹配");
            view.put("payment",payment);
        }
        view.put("events",events.forOrder(order.id()).stream().map(event -> {
            var item=new LinkedHashMap<String,Object>();
            item.put("eventId",Long.toString(event.id()));
            item.put("actorUserId",event.actorUserId()==null?null:Long.toString(event.actorUserId()));
            item.put("type",event.type());item.put("details",event.details());item.put("createdAt",event.createdAt().toString());
            return item;
        }).toList());
        view.put("appeals",appeals.timeline(order.id()).stream().map(appeal -> {
            var item=new LinkedHashMap<String,Object>();
            item.put("appealId",appeal.appealId().toString());item.put("openerUserId",Long.toString(appeal.openerUserId()));
            item.put("reason",appeal.reason());item.put("status",appeal.status());item.put("decision",appeal.decision());
            item.put("decisionReason",appeal.decisionReason());item.put("createdAt",appeal.createdAt().toString());
            item.put("resolvedAt",appeal.resolvedAt()==null?null:appeal.resolvedAt().toString());return item;
        }).toList());
        view.put("serverTime",Instant.now().toString());return view;
    }

    public List<Map<String,Object>> paymentMethods(boolean enabledOnly) {
        return methods.list(enabledOnly).stream().map(method -> Map.<String,Object>of(
                "code",method.code(),"label",method.label(),"enabled",method.enabled(),"currencies",method.currencies(),
                "requiresBankName",method.requiresBankName(),"supportsQr",method.supportsQr(),"revision",Long.toString(method.revision()))).toList();
    }

    public Map<String,Object> submission(long userId,String key,String kind,String hash) {
        var receipt=submissions.lookup(userId,key,kind,hash);
        var view=C2cViews.submission(userId,key,kind,hash,receipt);
        if(receipt!=null && receipt.status().equals("SUCCEEDED")) {
            if(kind.equals("ORDER_CREATE")) {
                var order=orders.get(receipt.resultId(),false);
                if(order.creatorUserId()!=userId) throw C2cProblem.forbidden("原订单归属不匹配");
                view.put("result",C2cViews.order(order,markets.market(order.marketId(),false)));
            } else {
                var ad=ads.get(receipt.resultId(),false);
                if(ad.makerUserId()!=userId) throw C2cProblem.forbidden("原广告归属不匹配");
                view.put("result",ownAdView(ad));
            }
        }
        return view;
    }

    public Map<String,Object> quoteView(C2cQuote quote) {
        var ad=ads.get(quote.adId(),false);var market=markets.market(ad.marketId(),false);
        var view=C2cViews.quote(quote,market);
        boolean accountReady;
        try {
            long seller=ad.side().equals("SELL")?ad.makerUserId():quote.userId();
            accountReady=payments.get(seller,quote.paymentAccountId(),true).revision()==quote.accountRevision();
        } catch(IllegalArgumentException | IllegalStateException unavailable) {accountReady=false;}
        view.put("valid",accountReady && quote.expiresAt().isAfter(Instant.now()) && quote.adRevision()==ad.revision() && quote.marketRevision()==market.revision()
                && "ACTIVE".equals(ad.status()) && ad.availableUnits()>=quote.quantityUnits() && market.enabled() && markets.configuration().enabled());
        return view;
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
}
