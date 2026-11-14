package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.client.MarketMakerClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport.GatewayIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 做市商管理端网关控制器：仅暴露需管理员权限与审计的 /api/v1/admin/market-maker/** 入口，
 * 校验权限并记录审计后委托 MarketMakerClient 请求做市商服务。
 */
@RestController
public class MarketMakerController {

    private static final String ADMIN_MARKET_MAKER_PREFIX = "/api/v1/admin/market-maker";

    private final GatewayProperties properties;
    private final MarketMakerClient marketMakerClient;
    private final AuthService authService;
    private final AdminApprovalService adminApprovalService;

    @Autowired
    public MarketMakerController(GatewayProperties properties,
                                 MarketMakerClient marketMakerClient,
                                 AuthService authService,
                                 AdminApprovalService adminApprovalService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.marketMakerClient = Objects.requireNonNull(marketMakerClient, "marketMakerClient");
        this.authService = Objects.requireNonNull(authService, "authService");
        this.adminApprovalService = adminApprovalService;
    }

    @RequestMapping(path = {ADMIN_MARKET_MAKER_PREFIX, ADMIN_MARKET_MAKER_PREFIX + "/**"})
    public ResponseEntity<byte[]> handleAdmin(HttpMethod method,
                                              HttpServletRequest request,
                                              @RequestBody(required = false) byte[] body) {
        long startedNanos = System.nanoTime();
        GatewayIdentity identity = GatewayAccessSupport.authenticateAdmin(
                authService, properties, request, "admin authorization required");
        boolean readMethod = GatewayAccessSupport.isReadMethod(method.name());
        GatewayAccessSupport.enforceAdminPermission(authService, "market-maker", readMethod, identity);

        String bodyHash = GatewayAccessSupport.bodySha256(body, false);
        URI targetUri = marketMakerClient.resolveTargetUri(request);
        try {
            ResponseEntity<byte[]> response = marketMakerClient.forwardAdmin(request, method, body, identity);
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    response.getStatusCode().value(), elapsedMillis(startedNanos), true, null);
            return response;
        } catch (ResponseStatusException ex) {
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    ex.getStatusCode().value(), elapsedMillis(startedNanos), false, ex.getReason());
            throw ex;
        } catch (RuntimeException ex) {
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(), elapsedMillis(startedNanos), false, ex.getMessage());
            throw ex;
        }
    }

    private void recordAdminOperation(HttpMethod method,
                                        HttpServletRequest request,
                                        GatewayIdentity identity,
                                        URI target,
                                        String bodyHash,
                                        int statusCode,
                                        long elapsedMs,
                                        boolean success,
                                        String error) {
        if (adminApprovalService == null) {
            return;
        }
        GatewayAccessSupport.recordAdminOperation(adminApprovalService, identity, "market-maker",
                method.name(), request, target.toString(), bodyHash, statusCode, elapsedMs, success, error,
                request.getRemoteAddr());
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
