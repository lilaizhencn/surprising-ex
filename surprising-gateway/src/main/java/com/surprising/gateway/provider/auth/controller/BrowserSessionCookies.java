package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseCookie;

/**
 * 浏览器登录令牌 Cookie：值就是登录令牌（JWT），HttpOnly，SameSite=Lax，Domain 可配置（如 .tokdou.com）。
 * Web 和后台各用一个 Cookie 名，由请求头 X-Auth-Client（web / admin）区分。
 * Cookie 本身长期保留，会话是否有效由 Valkey 里的 7 天滑动会话决定。
 */
public final class BrowserSessionCookies {

    public static final String CLIENT_HEADER = "X-Auth-Client";
    public static final List<String> CLIENTS = List.of("web", "admin");

    private BrowserSessionCookies() {
    }

    public static String client(HttpServletRequest request) {
        String client = request.getHeader(CLIENT_HEADER);
        return client != null && CLIENTS.contains(client) ? client : null;
    }

    public static String sessionCookieName(String client) {
        return "surprising-" + client + "-session";
    }

    public static String csrfCookieName(String client) {
        return "surprising-" + client + "-csrf";
    }

    /** 请求带着浏览器登录 Cookie 时返回令牌，否则返回 null。 */
    public static String sessionToken(HttpServletRequest request) {
        String client = client(request);
        if (client == null || request.getCookies() == null) return null;
        String name = sessionCookieName(client);
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** WebSocket 握手没有自定义请求头，任一浏览器登录 Cookie 都可使用。 */
    public static String anySessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (String client : CLIENTS) {
            String name = sessionCookieName(client);
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /** 从原始 Cookie 请求头中提取任一浏览器登录 Cookie 令牌（供 Netty WebSocket 握手使用）。 */
    public static String anySessionToken(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        var decoded = io.netty.handler.codec.http.cookie.ServerCookieDecoder.LAX.decode(cookieHeader);
        for (String client : CLIENTS) {
            String name = sessionCookieName(client);
            for (var cookie : decoded) {
                if (name.equals(cookie.name()) && cookie.value() != null && !cookie.value().isBlank()) {
                    return cookie.value();
                }
            }
        }
        return null;
    }

    public static ResponseCookie sessionCookie(GatewayProperties properties, String client, String token, Duration maxAge) {
        var builder = ResponseCookie.from(sessionCookieName(client), token).httpOnly(true)
                .secure(properties.getSecurity().isBrowserCookieSecure()).sameSite("Lax").path("/").maxAge(maxAge);
        String domain = properties.getSecurity().getBrowserCookieDomain();
        if (domain != null && !domain.isBlank()) builder.domain(domain);
        return builder.build();
    }

    public static ResponseCookie clearedSessionCookie(GatewayProperties properties, String client) {
        return sessionCookie(properties, client, "", Duration.ZERO);
    }
}
