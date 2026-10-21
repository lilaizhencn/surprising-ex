package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cProfiles;

import com.surprising.gateway.c2c.utils.C2cMoney;

import java.util.*;
import java.text.Normalizer;
import com.surprising.gateway.c2c.model.C2cOfferFilter;
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
        var user=access.user(authorization);var nickname=text(request.nickname(),2,40,"昵称");
        if(nickname.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))
            throw new IllegalArgumentException("昵称不能包含控制字符");
        if(!List.of(0,5,15,30).contains(request.verificationMinutes())) throw new IllegalArgumentException("请选择始终、5、15 或 30 分钟验证");
        return profiles.update(user,version(request.revision()),nickname,request.receiveStrangers(),request.verificationMinutes(),request.verification());
    }
    @PostMapping("/me/merchant-application")
    public Map<String,Object> apply(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody MerchantApplication request) {
        return profiles.applyMerchant(access.user(authorization),version(request.revision()),text(request.application(),10,2000,"商家申请资料"));
    }
    @GetMapping("/me/quick-replies")
    public Map<String,Object> quickReplies(@RequestHeader(value="Authorization",required=false) String authorization) {
        return Map.of("items",profiles.quickReplies(access.user(authorization).userId()));
    }
    @PostMapping("/me/quick-replies")
    public Map<String,Object> quickReply(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody QuickReply request) {
        return profiles.saveReply(access.user(authorization).userId(),request.replyId(),text(request.text(),1,1000,"快捷回复"));
    }
    @DeleteMapping("/me/quick-replies/{id}")
    public Map<String,Object> deleteReply(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        profiles.deleteReply(access.user(authorization).userId(),id);return Map.of("deleted",true);
    }
    @GetMapping("/me/relations")
    public Map<String,Object> relations(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam String kind,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        var user=access.user(authorization);relationKind(kind);page(limit,offset);
        return profiles.relations(user.userId(),kind,limit,offset);
    }
    @PutMapping("/me/relations/{kind}/{userId}")
    public Map<String,Object> relation(@RequestHeader(value="Authorization",required=false) String authorization,
                                      @PathVariable String kind,@PathVariable long userId) {
        var user=access.user(authorization);relationKind(kind);positive(userId);
        profiles.relation(user.userId(),userId,kind,true);return Map.of("enabled",true);
    }
    @DeleteMapping("/me/relations/{kind}/{userId}")
    public Map<String,Object> removeRelation(@RequestHeader(value="Authorization",required=false) String authorization,
                                            @PathVariable String kind,@PathVariable long userId) {
        var user=access.user(authorization);relationKind(kind);positive(userId);
        profiles.relation(user.userId(),userId,kind,false);return Map.of("enabled",false);
    }
    @PostMapping("/me/reports")
    public Map<String,Object> report(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Report request) {
        var user=access.user(authorization);long target=version(request.targetUserId());positive(target);
        UUID id=profiles.report(user.userId(),request.reportId(),target,request.orderId(),text(request.reason(),5,2000,"举报原因"));
        return Map.of("reportId",id.toString());
    }
    @GetMapping("/me/reports")
    public Map<String,Object> reports(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        var user=access.user(authorization);page(limit,offset);return profiles.reports(user.userId(),limit,offset);
    }
    @GetMapping("/me/price-alerts")
    public Map<String,Object> alerts(@RequestHeader(value="Authorization",required=false) String authorization) {
        return Map.of("items",profiles.alerts(access.user(authorization).userId()));
    }
    @PostMapping("/me/price-alerts")
    public Map<String,Object> alert(@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Alert request) {
        var user=access.user(authorization);
        if(request.marketId()==null) throw new IllegalArgumentException("请选择预警市场");
        C2cOfferFilter.Side side;
        try {side=C2cOfferFilter.Side.valueOf(request.side());}
        catch(IllegalArgumentException | NullPointerException failure) {throw new IllegalArgumentException("预警方向无效");}
        var target=C2cMoney.decimal(request.targetPrice());
        if(target.scale()>18) throw new IllegalArgumentException("预警价格精度无效");
        UUID id=profiles.saveAlert(user.userId(),request.alertId(),version(request.revision()),request.marketId(),side,target,request.enabled());
        return Map.of("alertId",id.toString());
    }
    @DeleteMapping("/me/price-alerts/{id}")
    public Map<String,Object> deleteAlert(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        profiles.deleteAlert(access.user(authorization).userId(),id);return Map.of("deleted",true);
    }
    @GetMapping("/me/notifications")
    public Map<String,Object> notifications(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) String beforeId,@RequestParam(defaultValue="30") int limit) {
        var user=access.user(authorization);page(limit,0);
        return profiles.notifications(user.userId(),beforeId==null?null:version(beforeId),limit);
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
    private static String text(String value,int minimum,int maximum,String label) {
        String result=value==null?"":Normalizer.normalize(value.trim().replace("\r\n","\n"),Normalizer.Form.NFKC);
        if(result.length()<minimum || result.length()>maximum || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException(label+"长度或格式无效");
        return result;
    }
    private static void relationKind(String kind) {
        if(kind==null || !List.of("FOLLOW","BLOCK").contains(kind)) throw new IllegalArgumentException("关系类型无效");
    }
    private static void page(int limit,int offset) {
        if(limit<1 || limit>100 || offset<0 || offset>10000) throw new IllegalArgumentException("分页参数无效");
    }
    private static void positive(long id) {
        if(id<=0) throw new IllegalArgumentException("用户编号无效");
    }
    private static long version(String value) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("版本或游标必须为十进制字符串");
        try {return Long.parseLong(value);}
        catch(NumberFormatException failure) {throw new IllegalArgumentException("版本或游标超出范围");}
    }
}
