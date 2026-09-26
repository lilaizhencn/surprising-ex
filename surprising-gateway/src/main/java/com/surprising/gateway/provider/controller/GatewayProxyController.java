package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关 HTTP 入口，只负责接收协议参数并交给网关代理服务执行。
 */
@RestController
public class GatewayProxyController {

    private final GatewayProxyService gatewayProxyService;

    private final LocalBusinessApi localBusinessApi;

    public GatewayProxyController(GatewayProxyService gatewayProxyService, LocalBusinessApi localBusinessApi) {
        this.gatewayProxyService = gatewayProxyService;
        this.localBusinessApi = localBusinessApi;
    }

    /** The bundled gateway serves exactly its configured account/trading product line. */
    @GetMapping("/api/v1/runtime")
    public Map<String, List<ProductLine>> runtime() {
        return Map.of("productLines", List.of(localBusinessApi.productLine()));
    }

    @RequestMapping(path = {
            GatewayProxyService.GATEWAY_PREFIX + "/{service}",
            GatewayProxyService.GATEWAY_PREFIX + "/{service}/**",
            GatewayProxyService.ADMIN_GATEWAY_PREFIX + "/{service}",
            GatewayProxyService.ADMIN_GATEWAY_PREFIX + "/{service}/**"
    })
    public ResponseEntity<byte[]> proxy(@PathVariable String service,
                                        HttpMethod method,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        return gatewayProxyService.proxy(service, method, request, body);
    }
}
