package com.surprising.gateway.c2c;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMerchantController {
    private final C2cMerchantBonds bonds;
    private final C2cAccess access;
    private final C2cAdminAccess admin;
    private final ObjectMapper json;
    public C2cMerchantController(C2cMerchantBonds bonds,C2cAccess access,C2cAdminAccess admin,ObjectMapper json) {
        this.bonds=bonds;this.access=access;this.admin=admin;this.json=json;
    }
    @GetMapping("/c2c/me/merchant-bond")
    public Map<String,Object> mine(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) UUID requestId) {
        return bonds.mine(access.user(authorization).userId(),requestId);
    }
    @PostMapping("/c2c/me/merchant-bond")
    public Map<String,Object> deposit(@RequestHeader(value="Authorization",required=false) String authorization,
                                     @RequestBody C2cMerchantBonds.Deposit input) {
        return bonds.deposit(access.user(authorization),authorization,input);
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
        var request=admin.request(http);
        try {return bonds.configure(authorization,request,body,json.readValue(body,C2cMerchantBonds.Policy.class));}
        catch(RuntimeException failure) {admin.failure(authorization,request,body,failure);throw failure;}
    }
}
