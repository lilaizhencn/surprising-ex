package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/instruments/admin/order-settings")
public class OrderBusinessSettingsMvcController {
    private final OrderBusinessSettingsRequestHandler orderSettings;

    public OrderBusinessSettingsMvcController(OrderBusinessSettingsRequestHandler orderSettings) {
        this.orderSettings = orderSettings;
    }

    @GetMapping
    public Object current(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("productLine") ProductLine productLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return orderSettings.current(productLine);
    }

    @PostMapping
    public Object save(
            @RequestParam("productLine") ProductLine productLine,
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        return orderSettings.save(productLine, body, GatewayUserContext.requireAdminUserId(adminUserId));
    }
}
