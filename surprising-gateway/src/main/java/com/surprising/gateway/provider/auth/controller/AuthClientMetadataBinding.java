package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import com.surprising.gateway.provider.auth.port.CurrentAuthClient;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Servlet到鉴权原生元数据的协议边界；沿用Spring上下文和既有可信代理规则。 */
@Component
public class AuthClientMetadataBinding implements CurrentAuthClient {
    private final ClientIpResolver clientIpResolver;
    private final com.surprising.gateway.provider.service.GatewayRequestLimits limits;
    private final com.surprising.gateway.provider.controller.GatewayLimitRequestBinding limitRequests;
    private static final String CHARGED = AuthClientMetadataBinding.class.getName() + ".charged";
    private static final String BEARER = AuthClientMetadataBinding.class.getName() + ".bearer";
    private record VerifiedBearer(String authorization, com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal principal) {}

    @Override public com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal cachedBearer(String authorization) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(BEARER) instanceof VerifiedBearer cached
                && cached.authorization().equals(authorization)) return cached.principal();
        return null;
    }

    @Override public void cacheBearer(String authorization, com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal principal) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet)
            servlet.getRequest().setAttribute(BEARER, new VerifiedBearer(authorization, principal));
    }
    public AuthClientMetadataBinding(GatewayProperties properties,
            com.surprising.gateway.provider.service.GatewayRequestLimits limits,
            com.surprising.gateway.provider.controller.GatewayLimitRequestBinding limitRequests) {
        clientIpResolver = new ClientIpResolver(properties);
        this.limits = limits;
        this.limitRequests = limitRequests;
    }

    public AuthClientMetadata bind(HttpServletRequest request) {
        return new AuthClientMetadata(userAgent(request), truncate(clientIpResolver.resolve(request),80), deviceId(request));
    }

    @Override
    public AuthClientMetadata current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
        // Bearer鉴权原来只检查请求IP；不得在此新增设备格式检查或改变权限判断顺序。
        return new AuthClientMetadata(null, truncate(clientIpResolver.resolve(servlet.getRequest()),80), null);
    }

    @Override public void authenticated(long userId, String apiKey) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return;
        var request = servlet.getRequest();
        if (request.getAttribute(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.ACTIVE) == null) return;
        Object charged = request.getAttribute(CHARGED);
        if (charged != null) {
            if (!charged.equals(userId)) throw new IllegalStateException("request authentication identity changed");
            return;
        }
        var policy = limitRequests.bind(request);
        limits.checkUser(userId, apiKey, policy.group(), policy.product(), policy.cost());
        request.setAttribute(CHARGED, userId);
    }

    @Override public void loginAttempt(String identifier) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.ACTIVE) != null)
            limits.checkLoginAccount(identifier);
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : truncate(request.getHeader("User-Agent"), 300);
    }

    private static String deviceId(HttpServletRequest request) {
        if (request == null || request.getHeader("X-Device-Id") == null) return null;
        String value = request.getHeader("X-Device-Id").trim();
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException("invalid device ID");
            return canonical;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid device ID", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
