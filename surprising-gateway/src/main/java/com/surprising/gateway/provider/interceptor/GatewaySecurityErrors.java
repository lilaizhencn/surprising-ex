package com.surprising.gateway.provider.interceptor;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Fixed public errors; never serialize exception messages, credentials or provider responses. */
public final class GatewaySecurityErrors {
    private GatewaySecurityErrors() {}
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}");
    }
}
