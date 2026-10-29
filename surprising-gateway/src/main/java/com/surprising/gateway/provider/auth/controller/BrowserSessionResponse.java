package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.AuthResponse;
import com.surprising.gateway.provider.config.GatewayProperties;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class BrowserSessionResponse implements ResponseBodyAdvice<Object> {
    private final GatewayProperties properties;
    public BrowserSessionResponse(GatewayProperties properties) { this.properties = properties; }
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof AuthResponse session) || !(request instanceof ServletServerHttpRequest servlet)) return body;
        var http = servlet.getServletRequest();
        if (!"cookie".equals(http.getHeader("X-Auth-Transport")) && !http.getRequestURI().startsWith("/api/v1/auth/browser/")) return body;
        response.getHeaders().add(HttpHeaders.SET_COOKIE, BrowserSessionController.cookie(http, session.refreshToken(),
                java.time.Duration.between(java.time.Instant.now(), session.refreshTokenExpiresAt()),
                properties.getSecurity().isBrowserCookieSecure()).toString());
        return new AuthResponse(session.user(), session.accessToken(), null, session.accessTokenExpiresAt(),
                session.refreshTokenExpiresAt(), session.requiresEmailVerification(), true);
    }
}
