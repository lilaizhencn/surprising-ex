package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** 运行开关关闭时无需新表；业务关闭和 SPOT 暂不可用不能伪装为用户资金为零。 */
@RestController
@RequestMapping("/api/v1/c2c")
public class C2cStatusController {
    private final boolean runtimeEnabled;
    private final C2cMarketService markets;
    private final GatewayProductServices products;

    public C2cStatusController(@Value("${surprising.c2c.enabled:false}") boolean runtimeEnabled,
                               C2cMarketService markets, GatewayProductServices products) {
        this.runtimeEnabled = runtimeEnabled; this.markets = markets; this.products = products;
    }

    @GetMapping("/status")
    public Map<String,Object> status() {
        if (!runtimeEnabled) return Map.of("runtimeEnabled",false,"businessEnabled",false,"spotReady",false,
                "available",false,"reason","C2C 尚未启用","serverTime",Instant.now().toString());
        boolean business = markets.configuration().enabled();
        boolean spot = products.enabled().contains(ProductLine.SPOT);
        return Map.of("runtimeEnabled",true,"businessEnabled",business,"spotReady",spot,"available",business && spot,
                "reason",!business?"C2C 市场暂未开放":!spot?"现货资金服务暂不可用":"","serverTime",Instant.now().toString());
    }
}
