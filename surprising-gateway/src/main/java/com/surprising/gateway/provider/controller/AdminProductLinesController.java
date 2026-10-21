package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.model.GatewayProductSetting;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 后台保存接入意图；后台任务异步初始化，响应不会把“已保存”误报为“已接入”。 */
@RestController
@RequestMapping("/api/v1/admin/product-lines")
public class AdminProductLinesController {
    public record Status(GatewayProductSetting configuration, String runtimeStatus, String message) { }
    private final AuthService auth;
    private final GatewayProductSettings settings;
    private final GatewayProductServices products;

    public AdminProductLinesController(AuthService auth, GatewayProductSettings settings, GatewayProductServices products) {
        this.auth = auth;
        this.settings = settings;
        this.products = products;
    }

    @GetMapping
    public List<Status> list(@RequestHeader("Authorization") String authorization) {
        admin(authorization, "read");
        var active = products.enabled();
        var errors = products.errors();
        return settings.list().stream().map(setting -> new Status(setting,
                active.contains(setting.productLine()) ? "CONNECTED" : !setting.enabled() ? "NOT_ENABLED"
                        : errors.containsKey(setting.productLine()) ? "FAILED" : "INITIALIZING",
                errors.get(setting.productLine()))).toList();
    }

    @PostMapping("/{productLine}/enable")
    public GatewayProductSetting enable(@RequestHeader("Authorization") String authorization,
            @PathVariable ProductLine productLine, @RequestBody tools.jackson.databind.JsonNode request) {
        var principal = admin(authorization, "write");
        if (request == null || !request.isObject()
                || !new java.util.HashSet<>(request.propertyNames()).equals(java.util.Set.of("expectedVersion", "reason"))
                || !request.get("expectedVersion").isIntegralNumber() || !request.get("expectedVersion").canConvertToLong()
                || !request.get("reason").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只接受整数 expectedVersion 和文本 reason");
        long version = request.get("expectedVersion").asLong();
        String reason = request.get("reason").asString();
        if (version < 1 || reason.isBlank() || reason.length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要配置版本和 1 至 1000 字的修改原因");
        try { return settings.enable(productLine, version, reason.trim(), principal); }
        catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private JwtPrincipal admin(String authorization, String action) {
        try { return auth.requireAdminPermission(authorization, "admin.gateway.instrument-admin." + action); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage()); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage()); }
    }
}
