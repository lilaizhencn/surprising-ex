package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.service.C2cAdminAccess;
import com.surprising.gateway.c2c.service.C2cAdminInitialization;
import com.surprising.gateway.c2c.service.C2cAdminMarkets;
import com.surprising.gateway.c2c.service.C2cAdminReview;

import com.surprising.gateway.c2c.utils.C2cMoney;

import com.surprising.gateway.c2c.model.C2cAdminViews;
import com.surprising.gateway.c2c.model.C2cConfigurationChange;
import com.surprising.gateway.c2c.model.C2cMarketChange;
import com.surprising.gateway.c2c.model.C2cPaymentMethod;
import com.surprising.gateway.c2c.model.C2cOrderStatus;
import com.surprising.gateway.c2c.model.C2cPage;
import com.surprising.gateway.c2c.service.C2cAdminQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/c2c")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cAdminController {
    public record ConfigurationInput(String revision,boolean enabled,int maxActiveAds,String reason) { }
    public record MarketInput(UUID marketId,String revision,String asset,String fiat,int fiatDecimals,boolean enabled,
                              String minFiat,String maxFiat,String minPrice,String maxPrice,int buyerFeePpm,
                              int paymentWindowSeconds,int maxOpenOrders,boolean requireKyc,int vipMinCompleted,String reason) { }
    public record MethodInput(String code,String revision,String label,boolean enabled,List<String> currencies,
                              boolean requiresBankName,boolean supportsQr,String reason) { }
    private final C2cAdminAccess access;
    private final C2cAdminMarkets markets;
    private final C2cAdminReview review;
    private final C2cAdminQueryService queries;
    private final C2cAdminInitialization initialization;
    private final ObjectMapper json;
    public C2cAdminController(C2cAdminAccess access,C2cAdminMarkets markets,C2cAdminReview review,C2cAdminQueryService queries,
                              C2cAdminInitialization initialization,ObjectMapper json) {
        this.access=access;this.markets=markets;this.review=review;this.queries=queries;this.initialization=initialization;this.json=json;
    }

    @PostMapping("/initialization")
    public Map<String,Object> initialize(@RequestHeader(value="Authorization",required=false) String authorization,
                                         @RequestBody(required=false) byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->{
            if(body!=null && body.length>0) throw new IllegalArgumentException("账户初始化不接受金额、账户或其他请求正文");
            return initialization.initialize(authorization,request,body);
        });
    }

    @GetMapping("/configuration")
    public Map<String,Object> configuration(@RequestHeader(value="Authorization",required=false) String authorization) {
        return markets.configuration(authorization);
    }
    @PutMapping("/configuration")
    public Map<String,Object> configure(@RequestHeader(value="Authorization",required=false) String authorization,
                                       @RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->{
            var input=read(body,ConfigurationInput.class);reason(input.reason());
            if(input.maxActiveAds()<1 || input.maxActiveAds()>100) throw new IllegalArgumentException("广告限额应为 1 至 100");
            return markets.configure(authorization,request,body,new C2cConfigurationChange(version(input.revision(),false),input.enabled(),input.maxActiveAds()));
        });
    }
    @PostMapping("/markets")
    public Map<String,Object> market(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->{
            var input=read(body,MarketInput.class);reason(input.reason());
            if(input.asset()==null || !input.asset().matches("[A-Z0-9]{2,20}") || input.fiat()==null || !input.fiat().matches("[A-Z]{3,8}")
                    || input.fiatDecimals()<0 || input.fiatDecimals()>8 || input.buyerFeePpm()<0 || input.buyerFeePpm()>=1000000
                    || input.paymentWindowSeconds()<60 || input.paymentWindowSeconds()>3600 || input.maxOpenOrders()<1 || input.maxOpenOrders()>20
                    || input.vipMinCompleted()<0 || input.vipMinCompleted()>1000000) throw new IllegalArgumentException("市场准入或费率配置无效");
            var change=new C2cMarketChange(input.marketId(),version(input.revision(),true),input.asset(),input.fiat(),
                    input.fiatDecimals(),input.enabled(),C2cMoney.decimal(input.minFiat()),C2cMoney.decimal(input.maxFiat()),
                    C2cMoney.decimal(input.minPrice()),C2cMoney.decimal(input.maxPrice()),input.buyerFeePpm(),input.paymentWindowSeconds(),
                    input.maxOpenOrders(),input.requireKyc(),input.vipMinCompleted());
            if(change.minFiat().compareTo(change.maxFiat())>0 || change.minFiat().stripTrailingZeros().scale()>change.fiatDecimals()
                    || change.maxFiat().stripTrailingZeros().scale()>change.fiatDecimals() || integerDigits(change.maxFiat())>30
                    || change.minPrice().compareTo(change.maxPrice())>0 || change.maxPrice().scale()>18 || change.minPrice().scale()>18 || integerDigits(change.maxPrice())>20)
                throw new IllegalArgumentException("价格或法币限额范围、精度无效");
            return markets.market(authorization,request,body,change);
        });
    }
    @PostMapping("/payment-methods")
    public Map<String,Object> method(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->{
            var input=read(body,MethodInput.class);reason(input.reason());
            if(input.code()==null || !input.code().matches("[A-Z][A-Z0-9_]{0,31}") || input.label()==null || input.label().isBlank()
                    || input.label().length()>80 || input.currencies()==null || input.currencies().size()>50
                    || input.currencies().stream().anyMatch(value -> value==null || !value.matches("[A-Z]{3,8}")))
                throw new IllegalArgumentException("收款方式资料无效");
            if(input.enabled() && input.currencies().isEmpty()) throw new IllegalArgumentException("请配置适用法币");
            return markets.method(authorization,request,body,new C2cPaymentMethod(input.code(),input.label().trim(),input.enabled(),
                    input.currencies().stream().distinct().sorted().toList(),input.requiresBankName(),input.supportsQr(),version(input.revision(),true)));
        });
    }
    @GetMapping("/reconciliation")
    public Map<String,Object> reconciliation(@RequestHeader(value="Authorization",required=false) String authorization) {
        return markets.reconciliation(authorization);
    }
    @GetMapping("/merchants")
    public C2cPage<C2cAdminViews.Merchant> merchants(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,userId,limit,offset,List.of("PENDING","APPROVED","REJECTED","SUSPENDED","WITHDRAWN"));
        return queries.merchants(status,userId,limit,offset);
    }
    @PostMapping("/merchants/{id}/review")
    public Map<String,Object> merchantReview(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable long id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.merchant(authorization,request,body,id,read(body,C2cAdminReview.MerchantDecision.class)));
    }
    @GetMapping("/payment-accounts")
    public C2cPage<C2cAdminViews.PaymentAccount> accounts(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,userId,limit,offset,List.of("PENDING","APPROVED","REJECTED","DISABLED"));
        return queries.paymentAccounts(status,userId,limit,offset);
    }
    @GetMapping("/payment-accounts/{id}")
    public Map<String,Object> account(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @PathVariable UUID id,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,null,()->review.paymentDetails(authorization,request,id));
    }
    @PostMapping("/payment-accounts/{id}/review")
    public Map<String,Object> accountReview(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.payment(authorization,request,body,id,read(body,C2cAdminReview.PaymentDecision.class)));
    }
    @GetMapping("/ads")
    public C2cPage<Map<String,Object>> ads(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,@RequestParam(required=false) UUID marketId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,userId,limit,offset,List.of("RESERVING","ACTIVE","PAUSED","CLOSING","CLOSED","REJECTED"));
        return queries.ads(status,userId,marketId,limit,offset);
    }
    @PostMapping("/ads/{id}/status")
    public Map<String,Object> ad(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.ad(authorization,request,body,id,read(body,C2cAdminReview.AdDecision.class)));
    }
    @PostMapping("/ads/{id}/close")
    public Map<String,Object> closeAd(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.closeAd(authorization,request,body,id,read(body,C2cAdminReview.AdDecision.class)));
    }
    @GetMapping("/orders")
    public C2cPage<Map<String,Object>> orders(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,@RequestParam(required=false) UUID orderId,
            @RequestParam(required=false) UUID marketId,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,userId,limit,offset,java.util.Arrays.stream(C2cOrderStatus.values()).map(Enum::name).toList());
        return queries.orders(status,userId,orderId,marketId,limit,offset);
    }
    @GetMapping("/orders/{id}")
    public Map<String,Object> order(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        return review.order(authorization,id);
    }
    @GetMapping("/orders/{id}/payment")
    public Map<String,Object> payment(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @PathVariable UUID id,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,null,()->review.orderPayment(authorization,request,id));
    }
    @GetMapping("/fund-intents")
    public C2cPage<C2cAdminViews.FundIntent> funds(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) UUID ownerId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,null,limit,offset,List.of("PREPARED","DEBITED","CREDITED","APPLIED","REJECTED"));
        return queries.funds(status,ownerId,limit,offset);
    }
    @GetMapping("/appeals")
    public C2cPage<C2cAdminViews.Appeal> appeals(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) UUID orderId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,null,limit,offset,List.of("OPEN","REVIEWING","RESOLVED","WITHDRAWN"));
        return queries.appeals(status,orderId,limit,offset);
    }
    @PostMapping("/appeals/{id}/decision")
    public Map<String,Object> appeal(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.appeal(authorization,request,body,id,read(body,C2cAdminReview.AppealDecision.class)));
    }
    @GetMapping("/reports")
    public C2cPage<C2cAdminViews.Report> reports(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);
        validateList(status,userId,limit,offset,List.of("OPEN","REVIEWED","DISMISSED"));
        return queries.reports(status,userId,limit,offset);
    }
    @PostMapping("/reports/{id}/review")
    public Map<String,Object> report(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.report(authorization,request,body,id,read(body,C2cAdminReview.ReportDecision.class)));
    }
    private static int integerDigits(java.math.BigDecimal value) {return value.precision()-value.scale();}
    private static long version(String value,boolean zero) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("请提供十进制版本字符串");
        try {
            long version=Long.parseLong(value);
            if(!zero && version==0) throw new IllegalArgumentException("请提供当前版本");
            return version;
        } catch(NumberFormatException failure) {throw new IllegalArgumentException("版本超出范围");}
    }
    private static void reason(String value) {
        if(value==null || value.trim().length()<5 || value.length()>1000) throw new IllegalArgumentException("请填写至少 5 字、最多 1000 字的管理原因");
    }
    private static void validateList(String status, Long userId, int limit, int offset, List<String> allowed) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000)
            throw new IllegalArgumentException("后台分页参数无效");
        if (status != null && !allowed.contains(status)) throw new IllegalArgumentException("查询状态无效");
        if (userId != null && userId <= 0) throw new IllegalArgumentException("用户编号无效");
    }

    private <T> T audited(String authorization,C2cAdminAccess.Request request,byte[] body,Supplier<T> action) {
        try {return action.get();}
        catch(RuntimeException failure) {access.failure(authorization,request,body,failure);throw failure;}
    }
    private <T> T read(byte[] body,Class<T> type) {
        try {
            T value=json.readValue(body,type);
            if(value==null) throw new IllegalArgumentException("后台请求资料不完整");
            return value;
        }
        catch(JacksonException failure) {throw new IllegalArgumentException("后台请求 JSON 格式无效");}
    }
}
