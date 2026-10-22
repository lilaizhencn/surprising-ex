package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.model.C2cPaymentAccountInput;
import com.surprising.gateway.c2c.utils.C2cPaymentFormats;

import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAds;
import com.surprising.gateway.c2c.service.C2cOrders;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;
import com.surprising.gateway.c2c.utils.C2cViews;

import com.surprising.gateway.c2c.utils.C2cMoney;

import com.surprising.gateway.c2c.model.C2cOfferFilter;
import com.surprising.gateway.c2c.model.C2cAdPublish;
import com.surprising.gateway.c2c.model.C2cUserOrderFilter;
import com.surprising.gateway.c2c.model.C2cQuickQuoteSpec;
import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.c2c.service.C2cQuickQuoteService;
import com.surprising.gateway.c2c.service.C2cSubmissionService;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 新建请求先独立接收，再调用业务事务。查询与明确停止都不重新发送资金命令。 */
@RestController
@RequestMapping("/api/v1/c2c")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cController {
    public record Publish(UUID marketId,String side,String price,String quantity,String minFiat,String maxFiat,
                           List<String> methodCodes,List<UUID> paymentAccountIds,int paymentWindowSeconds,String terms,int minCompleted) { }
    public record PublishRequest(String requestKey, Publish payload, C2cVerification verification) { }
    public record OrderRequest(String requestKey, UUID quoteId, C2cVerification verification) { }
    public record SubmissionIdentity(String kind,String requestHash) { }
    public record QuoteRequest(UUID adId,String amountType,String amount,UUID paymentAccountId) { }
    public record QuickQuoteRequest(UUID marketId,String side,String amount,String methodCode,UUID paymentAccountId) { }
    public record PaidRequest(UUID proofId) { }
    public record Reason(String reason) { }
    public record AdAction(String revision,String action,C2cVerification verification) { }
    public record AdEdit(String revision,String price,String minFiat,String maxFiat,String terms,int minCompleted) { }
    public record PaymentSave(UUID accountId,String revision,String fiat,String methodCode,C2cPaymentAccountInput.Details details,C2cVerification verification) { }
    public record PaymentDisable(String revision,C2cVerification verification) { }

    private final C2cAccess access;
    private final C2cAds ads;
    private final C2cOrders orders;
    private final C2cSubmissionService submissions;
    private final C2cQueryService queries;
    private final C2cQuickQuoteService quickQuotes;
    private final C2cPaymentAccounts payments;
    private final ObjectMapper json;

    public C2cController(C2cAccess access,C2cAds ads,C2cOrders orders,C2cSubmissionService submissions,
                         C2cQueryService queries,C2cQuickQuoteService quickQuotes,C2cPaymentAccounts payments,ObjectMapper json) {
        this.access=access;this.ads=ads;this.orders=orders;this.submissions=submissions;this.queries=queries;
        this.quickQuotes=quickQuotes;this.payments=payments;this.json=json;
    }

    @GetMapping("/markets")
    public Map<String,Object> markets() {
        return queries.markets();
    }

    @GetMapping("/ads")
    public Map<String,Object> offers(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam UUID marketId,@RequestParam(defaultValue="BUY") String side,
            @RequestParam(required=false) String amount,@RequestParam(required=false) String methodCode,
            @RequestParam(required=false) Long merchantId,@RequestParam(defaultValue="false") boolean vipOnly,
            @RequestParam(defaultValue="0") int minCompleted,@RequestParam(defaultValue="0") int minCompletionRate,
            @RequestParam(defaultValue="3600") int maxPaymentSeconds,@RequestParam(defaultValue="PRICE") String sort,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        access.requireNewTrading();
        var viewer=authorization==null?null:access.user(authorization);
        if(limit<1 || limit>50 || offset<0 || offset>10000 || minCompleted<0 || minCompleted>1000000
                || minCompletionRate<0 || minCompletionRate>100 || maxPaymentSeconds<60 || maxPaymentSeconds>3600
                || (merchantId!=null && merchantId<=0)) throw new IllegalArgumentException("筛选或分页参数无效");
        requireMethod(methodCode);
        var filter=new C2cOfferFilter(marketId,side(side),amount==null || amount.isBlank()?null:C2cMoney.decimal(amount),
                methodCode,merchantId,vipOnly,minCompleted,minCompletionRate,maxPaymentSeconds,
                choice(C2cOfferFilter.Sort.class,sort,"排序方式无效"),limit,offset);
        return queries.offers(filter,viewer);
    }

    @GetMapping("/ads/{id}")
    public Map<String,Object> offer(@PathVariable UUID id) {
        access.requireNewTrading();return queries.offer(id);
    }
    @GetMapping("/merchants/{userId}")
    public Map<String,Object> merchant(@PathVariable long userId) {return queries.merchant(userId);}

    @GetMapping("/me/ads")
    public Map<String,Object> myAds(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestParam(defaultValue="100") int limit) {
        var user=access.user(authorization);requirePage(limit,0);
        return Map.of("items",queries.myAds(user.userId(),limit));
    }
    @PostMapping("/ads")
    public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @RequestBody PublishRequest request) {
        var user=access.user(authorization);
        requireKey(request.requestKey());
        if(request.payload()==null) throw new IllegalArgumentException("广告资料不完整");
        String hash=C2cDigests.sha256(json.writeValueAsString(request.payload()));
        C2cAdPublish payload;
        try {payload=publishParameters(request.payload());}
        catch(IllegalArgumentException | ArithmeticException invalid) {return submissions.rejectInput(user.userId(),request.requestKey(),"AD_PUBLISH",hash,invalid);}
        var codes=C2cVerification.orEmpty(request.verification());
        return submissions.publish(user,authorization,request.requestKey(),hash,payload,codes.emailCode(),codes.totpCode());
    }

    @PostMapping("/ads/{id}/status")
    public Map<String,Object> adStatus(@RequestHeader(value="Authorization",required=false) String authorization,
                                      @PathVariable UUID id,@RequestBody AdAction request) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(request.verification());
        var ad=ads.status(user,authorization,id,revision(request.revision()),request.action(),codes.emailCode(),codes.totpCode());
        return queries.ownAdView(ad);
    }
    @PatchMapping("/ads/{id}")
    public Map<String,Object> edit(@RequestHeader(value="Authorization",required=false) String authorization,
                                  @PathVariable UUID id,@RequestBody AdEdit request) {
        var user=access.user(authorization);
        var ad=ads.edit(user,id,revision(request.revision()),request.price(),request.minFiat(),request.maxFiat(),request.terms(),request.minCompleted());
        return queries.ownAdView(ad);
    }

    @PostMapping("/quotes")
    public Map<String,Object> quote(@RequestHeader(value="Authorization",required=false) String authorization,
                                   @RequestBody QuoteRequest request) {
        var user=access.user(authorization);access.requireNewTrading();
        var quote=orders.quote(user,request.adId(),request.amountType(),request.amount(),request.paymentAccountId());
        return queries.quoteView(quote);
    }
    @PostMapping("/quotes/quick")
    public Map<String,Object> quick(@RequestHeader(value="Authorization",required=false) String authorization,
                                   @RequestBody QuickQuoteRequest request) {
        var user=access.user(authorization);access.requireNewTrading();
        if(request.marketId()==null) throw new IllegalArgumentException("请选择交易市场");
        var side=side(request.side());requireMethod(request.methodCode());
        if(side==C2cOfferFilter.Side.SELL && request.paymentAccountId()==null)
            throw new IllegalArgumentException("请先选择本人已审核收款账号");
        var spec=new C2cQuickQuoteSpec(request.marketId(),side,C2cMoney.decimal(request.amount()),request.methodCode(),request.paymentAccountId());
        return queries.quoteView(quickQuotes.quote(user,spec));
    }
    @GetMapping("/quotes/{id}")
    public Map<String,Object> quote(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        var user=access.user(authorization);var quote=orders.ownedQuote(user.userId(),id);
        return queries.quoteView(quote);
    }

    @PostMapping("/orders")
    public Map<String,Object> create(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody OrderRequest request) {
        var user=access.user(authorization);
        requireKey(request.requestKey());
        if(request.quoteId()==null) throw new IllegalArgumentException("请选择有效报价");
        String hash=C2cDigests.sha256(request.quoteId().toString());
        var codes=C2cVerification.orEmpty(request.verification());
        return submissions.create(user,authorization,request.requestKey(),request.quoteId(),hash,codes.emailCode(),codes.totpCode());
    }
    @GetMapping("/orders")
    public Map<String,Object> orders(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(defaultValue="ONGOING") String state,@RequestParam(required=false) UUID marketId,
            @RequestParam(defaultValue="ALL") String side,@RequestParam(required=false) UUID orderId,
            @RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset) {
        var user=access.user(authorization);requirePage(limit,offset);
        var filter=new C2cUserOrderFilter(choice(C2cUserOrderFilter.Scope.class,state,"订单查询条件无效"),marketId,
                choice(C2cUserOrderFilter.Side.class,side,"订单查询条件无效"),orderId,limit,offset);
        return queries.myOrders(user.userId(),filter);
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
        var user=access.user(authorization);requireIdentity(key,kind,requestHash);return receipt(user,key,kind,requestHash);
    }
    @PostMapping("/submissions/{key}/stop")
    public Map<String,Object> stop(@RequestHeader(value="Authorization",required=false) String authorization,
                                  @PathVariable String key,@RequestBody SubmissionIdentity request) {
        var user=access.user(authorization);requireIdentity(key,request.kind(),request.requestHash());
        return submissions.stop(user.userId(),key,request.kind(),request.requestHash());
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
        var user=access.user(authorization);validatePayment(request);
        var codes=C2cVerification.orEmpty(request.verification());
        return C2cViews.paymentAccount(payments.saveVerified(user,request.accountId(),revisionAllowZero(request.revision()),
                request.fiat(),request.methodCode(),C2cPaymentFormats.input(request.details()),codes.emailCode(),codes.totpCode()));
    }
    @PostMapping("/payment-accounts/{id}/disable")
    public Map<String,Object> disablePaymentAccount(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody PaymentDisable request) {
        var user=access.user(authorization);var codes=C2cVerification.orEmpty(request.verification());
        payments.disableVerified(user,id,revision(request.revision()),codes.emailCode(),codes.totpCode());return Map.of("disabled",true);
    }

    public static C2cAdPublish publishParameters(Publish request) {
        if(request.marketId()==null) throw new IllegalArgumentException("请选择交易市场");
        var side=choice(C2cAdPublish.Side.class,request.side(),"广告方向无效");
        var price=C2cMoney.decimal(request.price());var quantity=C2cMoney.decimal(request.quantity());
        var low=C2cMoney.decimal(request.minFiat());var high=C2cMoney.decimal(request.maxFiat());
        if(price.scale()>18 || low.compareTo(high)>0 || request.minCompleted()<0 || request.minCompleted()>1000000
                || (request.terms()!=null && request.terms().length()>2000) || request.paymentWindowSeconds()<60 || request.paymentWindowSeconds()>3600)
            throw new IllegalArgumentException("广告价格、限额或交易条件无效");
        if((request.methodCodes()!=null && request.methodCodes().stream().anyMatch(Objects::isNull))
                || (request.paymentAccountIds()!=null && request.paymentAccountIds().stream().anyMatch(Objects::isNull)))
            throw new IllegalArgumentException("收付款方式不完整");
        var methods=request.methodCodes()==null?List.<String>of():request.methodCodes().stream().distinct().sorted().toList();
        var accounts=request.paymentAccountIds()==null?List.<UUID>of():request.paymentAccountIds().stream().distinct().sorted().toList();
        if(methods.isEmpty() || methods.size()>8 || methods.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]{0,31}")))
            throw new IllegalArgumentException("请选择收付款方式");
        if(side==C2cAdPublish.Side.SELL && (accounts.isEmpty() || accounts.size()>8)) throw new IllegalArgumentException("请选择已审核的收款账号");
        if(side==C2cAdPublish.Side.BUY && !accounts.isEmpty()) throw new IllegalArgumentException("买入广告由出售方选择本人收款账号");
        return new C2cAdPublish(request.marketId(),side,price,quantity,low,high,methods,accounts,request.paymentWindowSeconds(),
                request.terms()==null?"":request.terms().trim(),request.minCompleted());
    }
    private static void validatePayment(PaymentSave request) {
        var details=request.details();
        if(request.fiat()==null || !request.fiat().matches("[A-Z]{3,8}") || request.methodCode()==null
                || !request.methodCode().matches("[A-Z][A-Z0-9_]{0,31}") || details==null
                || details.holderName()==null || details.holderName().trim().length()<2 || details.holderName().length()>80
                || details.accountNumber()==null || details.accountNumber().trim().length()<3 || details.accountNumber().length()>128
                || (details.bankName()!=null && details.bankName().length()>120)) throw new IllegalArgumentException("收款资料不完整");
    }
    private static void requireKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalArgumentException("请提供稳定的原请求标识");
    }
    private static void requireIdentity(String key,String kind,String hash) {
        requireKey(key);
        if(kind==null || !List.of("AD_PUBLISH","ORDER_CREATE").contains(kind) || hash==null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原请求身份无效");
    }
    private Map<String,Object> receipt(JwtPrincipal user,String key,String kind,String hash) {
        return queries.submission(user.userId(),key,kind,hash);
    }
    private static C2cOfferFilter.Side side(String value) {
        return choice(C2cOfferFilter.Side.class,value,"请选择购买或出售");
    }
    private static <T extends Enum<T>> T choice(Class<T> type,String value,String message) {
        try { return Enum.valueOf(type,value); }
        catch(IllegalArgumentException | NullPointerException failure) {throw new IllegalArgumentException(message);}
    }
    private static void requireMethod(String value) {
        if(value!=null && !value.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException("收款方式格式无效");
    }
    private static void requirePage(int limit,int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>10000) throw new IllegalArgumentException("分页数量无效");
    }
    public static long revision(String value) {
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
