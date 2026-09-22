package com.surprising.gateway.provider.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 合并后业务 Controller 由网关本地调用，不能通过其原始 URL 绕过身份和审批。 */
@Configuration
public class BusinessEndpointConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    private final byte[] internalToken;

    public BusinessEndpointConfiguration(
            @org.springframework.beans.factory.annotation.Value("${surprising.business.internal-token:}") String internalToken) {
        this.internalToken = internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String name = method.getBeanType().getName();
        boolean business = name.startsWith("com.surprising.trading.")
                || name.startsWith("com.surprising.account.provider.")
                || name.startsWith("com.surprising.instrument.provider.");
        if (!business) {
            return true;
        }
        // 独立后台进程通过显式内部凭证使用原有 RPC 契约。
        // 普通用户即使伪造 X-User-Id / X-Admin-User-Id，也不能直接调用业务 Controller。
        String supplied = request.getHeader("X-Business-Internal-Token");
        if (internalToken.length > 0 && supplied != null
                && java.security.MessageDigest.isEqual(internalToken,
                        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return true;
        }
        response.sendError(HttpStatus.NOT_FOUND.value());
        return false;
    }
}
