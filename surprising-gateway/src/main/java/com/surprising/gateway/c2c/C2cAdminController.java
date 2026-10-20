package com.surprising.gateway.c2c;

import jakarta.servlet.http.HttpServletRequest;
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
    private final C2cAdminAccess access;
    private final C2cAdminMarkets markets;
    private final C2cAdminReview review;
    private final C2cAdminQueries queries;
    private final C2cAdminInitialization initialization;
    private final ObjectMapper json;
    public C2cAdminController(C2cAdminAccess access,C2cAdminMarkets markets,C2cAdminReview review,C2cAdminQueries queries,
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
        return audited(authorization,request,body,()->markets.configure(authorization,request,body,read(body,C2cAdminMarkets.ConfigurationInput.class)));
    }
    @PostMapping("/markets")
    public Map<String,Object> market(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->markets.market(authorization,request,body,read(body,C2cAdminMarkets.MarketInput.class)));
    }
    @PostMapping("/payment-methods")
    public Map<String,Object> method(@RequestHeader(value="Authorization",required=false) String authorization,
                                    @RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->markets.method(authorization,request,body,read(body,C2cAdminMarkets.MethodInput.class)));
    }
    @GetMapping("/reconciliation")
    public Map<String,Object> reconciliation(@RequestHeader(value="Authorization",required=false) String authorization) {
        return markets.reconciliation(authorization);
    }
    @GetMapping("/merchants")
    public Map<String,Object> merchants(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.merchants(status,userId,limit,offset);
    }
    @PostMapping("/merchants/{id}/review")
    public Map<String,Object> merchantReview(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable long id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.merchant(authorization,request,body,id,read(body,C2cAdminReview.MerchantDecision.class)));
    }
    @GetMapping("/payment-accounts")
    public Map<String,Object> accounts(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.paymentAccounts(status,userId,limit,offset);
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
    public Map<String,Object> ads(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,@RequestParam(required=false) UUID marketId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.ads(status,userId,marketId,limit,offset);
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
    public Map<String,Object> orders(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,@RequestParam(required=false) UUID orderId,
            @RequestParam(required=false) UUID marketId,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.orders(status,userId,orderId,marketId,limit,offset);
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
    public Map<String,Object> funds(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) UUID ownerId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.funds(status,ownerId,limit,offset);
    }
    @GetMapping("/appeals")
    public Map<String,Object> appeals(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) UUID orderId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.appeals(status,orderId,limit,offset);
    }
    @PostMapping("/appeals/{id}/decision")
    public Map<String,Object> appeal(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.appeal(authorization,request,body,id,read(body,C2cAdminReview.AppealDecision.class)));
    }
    @GetMapping("/reports")
    public Map<String,Object> reports(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        access.read(authorization,false);return queries.reports(status,userId,limit,offset);
    }
    @PostMapping("/reports/{id}/review")
    public Map<String,Object> report(@RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable UUID id,@RequestBody byte[] body,HttpServletRequest http) {
        var request=access.request(http);
        return audited(authorization,request,body,()->review.report(authorization,request,body,id,read(body,C2cAdminReview.ReportDecision.class)));
    }
    private <T> T audited(String authorization,C2cAdminAccess.Request request,byte[] body,Supplier<T> action) {
        try {return action.get();}
        catch(RuntimeException failure) {access.failure(authorization,request,body,failure);throw failure;}
    }
    private <T> T read(byte[] body,Class<T> type) {
        try {return json.readValue(body,type);}
        catch(JacksonException failure) {throw new IllegalArgumentException("后台请求 JSON 格式无效");}
    }
}
