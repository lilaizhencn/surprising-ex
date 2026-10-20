package com.surprising.gateway.c2c;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/c2c")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMeController {
    public record Settings(String revision,String nickname,boolean receiveStrangers,int verificationMinutes,C2cVerification verification) { }
    public record MerchantApplication(String revision,String application) { }
    public record QuickReply(UUID replyId,String text) { }
    public record Report(UUID reportId,String targetUserId,UUID orderId,String reason) { }
    public record Alert(UUID alertId,String revision,UUID marketId,String side,String targetPrice,boolean enabled) { }
    public record ReadNotifications(String throughId) { }
    private final C2cAccess access;
    private final C2cProfiles profiles;

    public C2cMeController(C2cAccess access,C2cProfiles profiles) {this.access=access;this.profiles=profiles;}

    @GetMapping("/me")
    public Map<String,Object> me(@RequestHeader(value="Authorization",required=false) String authorization) {
        return profiles.me(access.user(authorization));
    }
    @PutMapping("/me")
    public Map<String,Object> update(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Settings request) {
        return profiles.update(access.user(authorization),version(request.revision()),request.nickname(),request.receiveStrangers(),
                request.verificationMinutes(),request.verification());
    }
    @PostMapping("/me/merchant-application")
    public Map<String,Object> apply(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody MerchantApplication request) {
        return profiles.applyMerchant(access.user(authorization),version(request.revision()),request.application());
    }
    @GetMapping("/me/quick-replies")
    public Map<String,Object> quickReplies(@RequestHeader(value="Authorization",required=false) String authorization) {
        return Map.of("items",profiles.quickReplies(access.user(authorization).userId()));
    }
    @PostMapping("/me/quick-replies")
    public Map<String,Object> quickReply(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody QuickReply request) {
        return profiles.saveReply(access.user(authorization).userId(),request.replyId(),request.text());
    }
    @DeleteMapping("/me/quick-replies/{id}")
    public Map<String,Object> deleteReply(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        profiles.deleteReply(access.user(authorization).userId(),id);return Map.of("deleted",true);
    }
    @GetMapping("/me/relations")
    public Map<String,Object> relations(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam String kind,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        return profiles.relations(access.user(authorization).userId(),kind,limit,offset);
    }
    @PutMapping("/me/relations/{kind}/{userId}")
    public Map<String,Object> relation(@RequestHeader(value="Authorization",required=false) String authorization,
                                      @PathVariable String kind,@PathVariable long userId) {
        profiles.relation(access.user(authorization).userId(),userId,kind,true);return Map.of("enabled",true);
    }
    @DeleteMapping("/me/relations/{kind}/{userId}")
    public Map<String,Object> removeRelation(@RequestHeader(value="Authorization",required=false) String authorization,
                                            @PathVariable String kind,@PathVariable long userId) {
        profiles.relation(access.user(authorization).userId(),userId,kind,false);return Map.of("enabled",false);
    }
    @PostMapping("/me/reports")
    public Map<String,Object> report(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Report request) {
        UUID id=profiles.report(access.user(authorization).userId(),request.reportId(),version(request.targetUserId()),request.orderId(),request.reason());
        return Map.of("reportId",id.toString());
    }
    @GetMapping("/me/reports")
    public Map<String,Object> reports(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        return profiles.reports(access.user(authorization).userId(),limit,offset);
    }
    @GetMapping("/me/price-alerts")
    public Map<String,Object> alerts(@RequestHeader(value="Authorization",required=false) String authorization) {
        return Map.of("items",profiles.alerts(access.user(authorization).userId()));
    }
    @PostMapping("/me/price-alerts")
    public Map<String,Object> alert(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Alert request) {
        UUID id=profiles.saveAlert(access.user(authorization).userId(),request.alertId(),version(request.revision()),request.marketId(),
                request.side(),request.targetPrice(),request.enabled());
        return Map.of("alertId",id.toString());
    }
    @DeleteMapping("/me/price-alerts/{id}")
    public Map<String,Object> deleteAlert(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        profiles.deleteAlert(access.user(authorization).userId(),id);return Map.of("deleted",true);
    }
    @GetMapping("/me/notifications")
    public Map<String,Object> notifications(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String beforeId,@RequestParam(defaultValue="30") int limit) {
        return profiles.notifications(access.user(authorization).userId(),beforeId==null?null:version(beforeId),limit);
    }
    @PostMapping("/me/notifications/read")
    public Map<String,Object> read(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody ReadNotifications request) {
        profiles.readNotifications(access.user(authorization).userId(),version(request.throughId()));return Map.of("read",true);
    }

    @GetMapping("/help")
    public Map<String,Object> help() {
        return Map.of("items",List.of(
                Map.of("title","如何购买？","text","选择广告和收款方式，核对金额后下单。按订单当时的收款资料使用本人账户转账，再确认已付款。"),
                Map.of("title","如何出售？","text","先设置并审核本人实名收款账号。下单时数字资产进入托管，核对实际到账后再完成安全验证并放币。"),
                Map.of("title","平台是否代扣法币？","text","C2C 法币转账由双方在站外完成。确认已付款或上传凭证不会自动认定到账，也不会自动放币。"),
                Map.of("title","付款超时怎么办？","text","付款窗口结束后不要继续转账。已付款但出现争议时，保留凭证并发起订单申诉或联系客服。"),
                Map.of("title","何时可以取消？","text","买方可取消尚未确认付款的订单。已付款订单不自动退款，需双方核对或由申诉流程处理。"),
                Map.of("title","如何保护验证信息？","text","验证码只填写在平台安全验证表单，不在聊天中提供给交易对手。放币前以实际账户到账为准。")));
    }
    private static long version(String value) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("版本或游标必须为十进制字符串");
        try {return Long.parseLong(value);}
        catch(NumberFormatException failure) {throw new IllegalArgumentException("版本或游标超出范围");}
    }
}
