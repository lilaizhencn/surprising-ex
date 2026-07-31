package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.GatewayProxyService;
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

    public GatewayProxyController(GatewayProxyService gatewayProxyService) {
        this.gatewayProxyService = gatewayProxyService;
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
