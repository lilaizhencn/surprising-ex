package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAdminAccess;
import com.surprising.gateway.c2c.service.C2cMerchantBonds;

import com.surprising.gateway.c2c.utils.C2cMoney;

import jakarta.servlet.http.HttpServletRequest;
import com.surprising.gateway.c2c.model.C2cBondDeposit;
import com.surprising.gateway.c2c.model.C2cBondPolicyChange;
import tools.jackson.core.JacksonException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMerchantController {
    public record Deposit(UUID requestId,String policyRevision,C2cVerification verification) { }
    public record Policy(String revision,String asset,String amount,String reason) { }
    private final C2cMerchantBonds bonds;
    private final C2cAccess access;
    private final C2cAdminAccess admin;
    private final ObjectMapper json;
    private final C2cAdminRequestBinding requestBinding;
    public C2cMerchantController(C2cMerchantBonds bonds,C2cAccess access,C2cAdminAccess admin,ObjectMapper json,C2cAdminRequestBinding requestBinding) {
        this.bonds=bonds;this.access=access;this.admin=admin;this.json=json;this.requestBinding=requestBinding;
    }
    @GetMapping("/c2c/me/merchant-bond")
    public Map<String,Object> mine(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) UUID requestId) {
        return bonds.mine(access.user(authorization).userId(),requestId);
    }
    @PostMapping("/c2c/me/merchant-bond")
    public Map<String,Object> deposit(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @RequestBody Deposit input) {
        var user=access.user(authorization);
        if(input.requestId()==null) throw new IllegalArgumentException("请保留原质押请求号");
        var codes=C2cVerification.orEmpty(input.verification());
        return bonds.deposit(user,authorization,new C2cBondDeposit(input.requestId(),version(input.policyRevision(),false),codes.emailCode(),codes.totpCode()));
    }
    @PostMapping("/c2c/me/merchant-bonds/{id}/withdraw")
    public Map<String,Object> withdraw(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID id) {
        return bonds.withdraw(access.user(authorization),id);
    }
    @GetMapping("/admin/c2c/merchant-bond-policy")
    public Map<String,Object> policy(@RequestHeader(value="Authorization",required=false) String authorization) {
        admin.read(authorization,false);return bonds.policy();
    }
    @PutMapping("/admin/c2c/merchant-bond-policy")
    public Map<String,Object> configure(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestBody byte[] body,HttpServletRequest http) {
        var request=requestBinding.bind(http);
        try {
            Policy input;
            try {input=json.readValue(body,Policy.class);}
            catch(JacksonException failure) {throw new IllegalArgumentException("后台请求 JSON 格式无效");}
            if(input==null) throw new IllegalArgumentException("质押政策资料不完整");
            if(input.asset()==null || !input.asset().matches("[A-Z0-9]{2,20}")) throw new IllegalArgumentException("质押币种格式无效");
            if(input.reason()==null || input.reason().trim().length()<5 || input.reason().length()>1000)
                throw new IllegalArgumentException("请填写至少 5 字、最多 1000 字的管理原因");
            return bonds.configure(authorization,request,body,new C2cBondPolicyChange(version(input.revision(),true),input.asset(),C2cMoney.decimal(input.amount())));
        }
        catch(RuntimeException failure) {admin.failure(authorization,request,body,com.surprising.gateway.c2c.controller.advice.C2cHttpErrors.status(failure).value(),failure.getClass().getSimpleName());throw failure;}
    }
    private static long version(String value,boolean zero) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("请提供十进制版本字符串");
        try {
            long version=Long.parseLong(value);
            if(!zero && version==0) throw new IllegalArgumentException("请提供当前版本");
            return version;
        } catch(NumberFormatException failure) {throw new IllegalArgumentException("版本超出范围");}
    }
}
