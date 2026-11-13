package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.AuthResponse;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 浏览器（X-Auth-Client: web/admin 且 X-Auth-Transport: cookie）登录成功时，把登录令牌写进 HttpOnly Cookie，响应体不带令牌。 */
@RestControllerAdvice
public class BrowserSessionResponse implements ResponseBodyAdvice<Object> {
    private final GatewayProperties properties;

    public BrowserSessionResponse(GatewayProperties properties) { this.properties = properties; }

    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof AuthResponse session) || session.accessToken() == null
                || !(request instanceof ServletServerHttpRequest servlet)) return body;
        var http = servlet.getServletRequest();
        String client = BrowserSessionCookies.client(http);
        if (client == null || !"cookie".equals(http.getHeader("X-Auth-Transport"))) return body;
        response.getHeaders().add(HttpHeaders.SET_COOKIE, BrowserSessionCookies.sessionCookie(properties, client,
                session.accessToken(), properties.getSecurity().getSessionTokenMaxLifetime()).toString());
        return new AuthResponse(session.user(), null, session.sessionExpiresAt(),
                session.requiresEmailVerification(), true);
    }
}
