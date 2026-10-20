package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 新建请求先独立接收，再调用业务事务。查询与明确停止都不重新发送资金命令。 */
@RestController
@RequestMapping("/api/v1/c2c")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cController {
    public record PublishRequest(String requestKey, C2cAds.Publish payload, C2cVerification verification) { }
    public record OrderRequest(String requestKey, UUID quoteId, C2cVerification verification) { }
    public record SubmissionIdentity(String kind,String requestHash) { }
    public record QuoteRequest(UUID adId,String amountType,String amount,UUID paymentAccountId) { }
    public record QuickQuoteRequest(UUID marketId,String side,String amount,String methodCode,UUID paymentAccountId) { }
    public record PaidRequest(UUID proofId) { }
    public record Reason(String reason) { }
    public record AdAction(String revision,String action,C2cVerification verification) { }
    public record AdEdit(String revision,String price,String minFiat,String maxFiat,String terms,int minCompleted) { }
    public record PaymentSave(UUID accountId,String revision,String fiat,String methodCode,C2cPaymentAccounts.Details details,C2cVerification verification) { }
    public record PaymentDisable(String revision,C2cVerification verification) { }

    private final C2cAccess access;
    private final C2cMarkets markets;
    private final C2cAds ads;
    private final C2cAdsRepository adRows;
    private final C2cOrders orders;
    private final C2cOrdersRepository orderRows;
    private final C2cSubmissions submissions;
    private final C2cQueries queries;
    private final C2cPaymentAccounts payments;
    private final GatewayProductServices products;
    private final ObjectMapper json;

    public C2cController(C2cAccess access,C2cMarkets markets,C2cAds ads,C2cAdsRepository adRows,
                         C2cOrders orders,C2cOrdersRepository orderRows,C2cSubmissions submissions,C2cQueries queries,
                         C2cPaymentAccounts payments,GatewayProductServices products,ObjectMapper json) {
        this.access=access;this.markets=markets;this.ads=ads;this.adRows=adRows;this.orders=orders;this.orderRows=orderRows;
        this.submissions=submissions;this.queries=queries;this.payments=payments;this.products=products;this.json=json;
    }

    @GetMapping("/markets")
    public Map<String,Object> markets() {
        return Map.of("markets",markets.markets(true).stream().map(C2cViews::market).toList(),
                "paymentMethods",queries.paymentMethods(true),"serverTime",Instant.now().toString());
    }

    @GetMapping("/ads")
    public Map<String,Object> offers(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam UUID marketId,@RequestParam(defaultValue="BUY") String side,
            @RequestParam(required=false) String amount,@RequestParam(required=false) String methodCode,
            @RequestParam(required=false) Long merchantId,@RequestParam(defaultValue="false") boolean vipOnly,
            @RequestParam(defaultValue="0") int minCompleted,@RequestParam(defaultValue="0") int minCompletionRate,
            @RequestParam(defaultValue="3600") int maxPaymentSeconds,@RequestParam(defaultValue="PRICE") String sort,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        requireNewTrading();
        var viewer=authorization==null?null:access.user(authorization);
        return queries.offers(new C2cQueries.Filter(marketId,side,amount,methodCode,merchantId,vipOnly,
                minCompleted,minCompletionRate,maxPaymentSeconds,sort,limit,offset),viewer);
    }

    @GetMapping("/ads/{id}")
    public Map<String,Object> offer(@PathVariable UUID id) {
        requireNewTrading();return queries.offer(id);
    }
    @GetMapping("/merchants/{userId}")
    public Map<String,Object> merchant(@PathVariable long userId) {return queries.merchant(userId);}

    @GetMapping("/me/ads")
    public Map<String,Object> myAds(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestParam(defaultValue="100") int limit) {
        var user=access.user(authorization);return Map.of("items",queries.myAds(user.userId(),limit));
    }
    @PostMapping("/ads")
    public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @RequestBody PublishRequest request) {
        var user=access.user(authorization);
        if(request.payload()==null) throw new IllegalArgumentException("广告资料不完整");
        String hash=C2cAccess.digest(json.writeValueAsString(request.payload()));
        var receipt=submissions.admit(user.userId(),request.requestKey(),"AD_PUBLISH",hash);
        if(receipt.status().equals("PROCESSING")) {
            try {
                requireNewTrading();
                var codes=C2cVerification.orEmpty(request.verification());
                ads.publish(user,authorization,request.requestKey(),request.payload(),codes.emailCode(),codes.totpCode());
            } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
                rejectValidation(user,request.requestKey(),"AD_PUBLISH",hash,failure);throw failure;
            }
        }
        return receipt(user,request.requestKey(),"AD_PUBLISH",hash);
    }

    @PostMapping("/ads/{id}/status")
    public Map<String,Object> adStatus(@RequestHeader(value="Authorization",required=false) String authorization,
                                      @PathVariable UUID id,@RequestBody AdAction request) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(request.verification());
        var ad=ads.status(user,authorization,id,revision(request.revision()),request.action(),codes.emailCode(),codes.totpCode());
        return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }
    @PatchMapping("/ads/{id}")
    public Map<String,Object> edit(@RequestHeader(value="Authorization",required=false) String authorization,
                                  @PathVariable UUID id,@RequestBody AdEdit request) {
        var user=access.user(authorization);
        var ad=ads.edit(user,id,revision(request.revision()),request.price(),request.minFiat(),request.maxFiat(),request.terms(),request.minCompleted());
        return C2cViews.ad(ad,markets.market(ad.marketId(),false),true);
    }

    @PostMapping("/quotes")
    public Map<String,Object> quote(@RequestHeader(value="Authorization",required=false) String authorization,
                                   @RequestBody QuoteRequest request) {
        var user=access.user(authorization);requireNewTrading();
        var quote=orders.quote(user,request.adId(),request.amountType(),request.amount(),request.paymentAccountId());
        return quoteView(quote);
    }
    @PostMapping("/quotes/quick")
    public Map<String,Object> quick(@RequestHeader(value="Authorization",required=false) String authorization,
                                   @RequestBody QuickQuoteRequest request) {
        var user=access.user(authorization);requireNewTrading();
        var market=markets.market(request.marketId(),true);access.requireTrading(user,market);
        if("SELL".equals(request.side()) && request.paymentAccountId()==null) throw new IllegalArgumentException("请先选择本人已审核收款账号");
        var result=queries.offers(new C2cQueries.Filter(request.marketId(),request.side(),request.amount(),request.methodCode(),
                null,false,0,0,3600,"PRICE",20,0),user);
        for(Object item:(List<?>)result.get("items")) {
            var ad=(Map<?,?>)item;
            UUID id=UUID.fromString((String)ad.get("adId"));
            UUID accountId=request.paymentAccountId();
            if("BUY".equals(request.side())) {
                var detail=queries.offer(id);
                var options=((List<?>)detail.get("paymentOptions")).stream().map(value->(Map<?,?>)value)
                        .filter(value->request.methodCode()==null || request.methodCode().equals(value.get("methodCode"))).toList();
                if(options.isEmpty()) continue;
                accountId=UUID.fromString((String)options.getFirst().get("accountId"));
            }
            try {
                return quoteView(orders.quote(user,id,"FIAT",request.amount(),accountId));
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                // 每个候选仍做完整准入；这里只生成报价，不分配库存或发送资金指令。
            }
        }
        throw new IllegalStateException("暂无满足金额、收款方式及交易资格的报价");
    }
    @GetMapping("/quotes/{id}")
    public Map<String,Object> quote(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        var user=access.user(authorization);var quote=orderRows.quote(id,false);
        if(quote.userId()!=user.userId()) throw C2cProblem.forbidden("只能查看本人报价");
        return quoteView(quote);
    }

    @PostMapping("/orders")
    public Map<String,Object> create(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody OrderRequest request) {
        var user=access.user(authorization);
        if(request.quoteId()==null) throw new IllegalArgumentException("请选择有效报价");
        String hash=C2cAccess.digest(request.quoteId().toString());
        var receipt=submissions.admit(user.userId(),request.requestKey(),"ORDER_CREATE",hash);
        if(receipt.status().equals("PROCESSING")) {
            try {
                requireNewTrading();var codes=C2cVerification.orEmpty(request.verification());
                orders.create(user,authorization,request.requestKey(),request.quoteId(),codes.emailCode(),codes.totpCode());
            } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
                rejectValidation(user,request.requestKey(),"ORDER_CREATE",hash,failure);throw failure;
            }
        }
        return receipt(user,request.requestKey(),"ORDER_CREATE",hash);
    }
    @GetMapping("/orders")
    public Map<String,Object> orders(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(defaultValue="ONGOING") String state,@RequestParam(required=false) UUID marketId,
            @RequestParam(defaultValue="ALL") String side,@RequestParam(required=false) UUID orderId,
            @RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset) {
        var user=access.user(authorization);return queries.myOrders(user.userId(),state,marketId,side,orderId,limit,offset);
    }
    @GetMapping("/orders/{id}")
    public Map<String,Object> order(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        return queries.order(access.user(authorization).userId(),id);
    }
    @PostMapping("/orders/{id}/paid")
    public Map<String,Object> paid(@RequestHeader(value="Authorization",required=false) String authorization,
                                  @PathVariable UUID id,@RequestBody PaidRequest request) {
        var user=access.user(authorization);orders.paid(user,id,request.proofId());return queries.order(user.userId(),id);
    }
    @PostMapping("/orders/{id}/release")
    public Map<String,Object> release(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @PathVariable UUID id,@RequestBody C2cVerification verification) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(verification);
        orders.release(user,authorization,id,codes.emailCode(),codes.totpCode());return queries.order(user.userId(),id);
    }
    @PostMapping("/orders/{id}/cancel")
    public Map<String,Object> cancel(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @PathVariable UUID id,@RequestBody Reason request) {
        var user=access.user(authorization);orders.cancel(user,id,request.reason());return queries.order(user.userId(),id);
    }
    @PostMapping("/orders/{id}/appeal")
    public Map<String,Object> appeal(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @PathVariable UUID id,@RequestBody Reason request) {
        var user=access.user(authorization);var appeal=orders.appeal(user,id,request.reason());
        return Map.of("appealId",appeal.toString(),"order",queries.order(user.userId(),id));
    }

    @GetMapping("/submissions/{key}")
    public Map<String,Object> submission(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable String key,@RequestParam String kind,@RequestParam String requestHash) {
        return receipt(access.user(authorization),key,kind,requestHash);
    }
    @PostMapping("/submissions/{key}/stop")
    public Map<String,Object> stop(@RequestHeader(value="Authorization",required=false) String authorization,
                                  @PathVariable String key,@RequestBody SubmissionIdentity request) {
        var user=access.user(authorization);submissions.stop(user.userId(),key,request.kind(),request.requestHash());
        return receipt(user,key,request.kind(),request.requestHash());
    }

    @GetMapping("/payment-accounts")
    public Map<String,Object> paymentAccounts(@RequestHeader(value="Authorization",required=false) String authorization) {
        var user=access.user(authorization);
        return Map.of("items",payments.list(user.userId()).stream().map(C2cViews::paymentAccount).toList());
    }
    @GetMapping("/payment-accounts/{id}")
    public Map<String,Object> paymentAccount(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        var user=access.user(authorization);var account=payments.get(user.userId(),id,false);
        var view=C2cViews.paymentAccount(account);view=new LinkedHashMap<>(view);view.put("details",payments.details(account));return view;
    }
    @PostMapping("/payment-accounts")
    public Map<String,Object> savePaymentAccount(@RequestHeader(value="Authorization",required=false) String authorization,
                                                @RequestBody PaymentSave request) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(request.verification());
        access.verifySettings(user,codes.emailCode(),codes.totpCode());
        return C2cViews.paymentAccount(payments.save(user.userId(),request.accountId(),revisionAllowZero(request.revision()),
                request.fiat(),request.methodCode(),request.details()));
    }
    @PostMapping("/payment-accounts/{id}/disable")
    public Map<String,Object> disablePaymentAccount(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody PaymentDisable request) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(request.verification());access.verifySettings(user,codes.emailCode(),codes.totpCode());
        payments.disable(user.userId(),id,revision(request.revision()));return Map.of("disabled",true);
    }

    private Map<String,Object> receipt(JwtPrincipal user,String key,String kind,String hash) {
        var receipt=submissions.lookup(user.userId(),key,kind,hash);
        var view=C2cViews.submission(user.userId(),key,kind,hash,receipt);
        if(receipt!=null && receipt.status().equals("SUCCEEDED")) {
            if(kind.equals("ORDER_CREATE")) {
                var order=orderRows.get(receipt.resultId(),false);
                if(order.creatorUserId()!=user.userId()) throw C2cProblem.forbidden("原订单归属不匹配");
                view.put("result",C2cViews.order(order,markets.market(order.marketId(),false)));
            } else {
                var ad=adRows.get(receipt.resultId(),false);
                if(ad.makerUserId()!=user.userId()) throw C2cProblem.forbidden("原广告归属不匹配");
                view.put("result",C2cViews.ad(ad,markets.market(ad.marketId(),false),true));
            }
        }
        return view;
    }
    private Map<String,Object> quoteView(C2cOrdersRepository.Quote quote) {
        var ad=adRows.get(quote.adId(),false);var market=markets.market(ad.marketId(),false);
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
    private void rejectValidation(JwtPrincipal user,String key,String kind,String hash,RuntimeException failure) {
        if(failure instanceof C2cProblem problem && problem.status.is5xxServerError()) return;
        submissions.reject(user.userId(),key,kind,hash,"VALIDATION_REJECTED",
                failure instanceof ArithmeticException?"金额或数量超出精度、范围限制":failure.getMessage());
    }
    private void requireNewTrading() {
        markets.requireOpen();
        if(!products.enabled().contains(ProductLine.SPOT)) throw C2cProblem.unavailable("现货资金服务尚未就绪，请稍后查询原请求");
    }
    static long revision(String value) {
        long revision=revisionAllowZero(value);
        if(revision==0) throw new IllegalArgumentException("请提供当前版本");
        return revision;
    }
    private static long revisionAllowZero(String value) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("版本必须为十进制字符串");
        try { return Long.parseLong(value); }
        catch(NumberFormatException failure) { throw new IllegalArgumentException("版本超出范围"); }
    }
}
