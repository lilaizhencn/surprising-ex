package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes server time and product line runtime discovery.
 */
@RestController
public class RuntimeController {

    private final GatewayProductServices products;

    public RuntimeController(GatewayProductServices products) {
        this.products = Objects.requireNonNull(products, "products");
    }

    @GetMapping("/api/v1/runtime")
    public Map<String, List<ProductLine>> runtime() {
        return Map.of("productLines", products.enabled(), "disabledProductLines", products.disabled());
    }

    @GetMapping("/api/v1/time")
    public Map<String, Long> time() {
        return Map.of("serverTime", System.currentTimeMillis());
    }
}
