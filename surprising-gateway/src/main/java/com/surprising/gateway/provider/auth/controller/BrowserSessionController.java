package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 浏览器登录：CSRF 令牌下发与登出。登录令牌只在 HttpOnly Cookie 里，不经过 JavaScript。 */
@RestController
@RequestMapping("/api/v1/auth")
public class BrowserSessionController {
    private final AuthService auth;
    private final GatewayProperties properties;

    public BrowserSessionController(AuthService auth, GatewayProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    /** 登出：作废当前会话（Cookie 或 Authorization 均可），浏览器客户端同时清除 Cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       HttpServletRequest request) {
        if (authorization != null) {
            try {
                auth.logout(auth.verifyBearer(authorization));
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                // 令牌已无效，视为已登出
            }
        }
        var response = ResponseEntity.noContent();
        String client = BrowserSessionCookies.client(request);
        if (client != null) {
            response.header(HttpHeaders.SET_COOKIE,
                    BrowserSessionCookies.clearedSessionCookie(properties, client).toString());
        }
        return response.build();
    }
}
