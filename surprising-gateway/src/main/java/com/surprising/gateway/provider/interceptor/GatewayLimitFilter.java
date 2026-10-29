package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.controller.GatewayLimitErrors;
import com.surprising.gateway.provider.controller.GatewayLimitRequestBinding;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class GatewayLimitFilter extends OncePerRequestFilter {
    private final GatewayRequestLimits limits;
    private final GatewayLimitRequestBinding requests;
    private final ClientIpResolver addresses;
    private final ObjectMapper json;
    private final CorsConfigurationSource cors;
    private final DefaultCorsProcessor corsProcessor = new DefaultCorsProcessor();
    public GatewayLimitFilter(GatewayRequestLimits limits, GatewayLimitRequestBinding requests,
                              GatewayProperties properties, ObjectMapper json,
                              @Qualifier("gatewayCorsSource") CorsConfigurationSource cors) {
        this.limits = limits; this.requests = requests; this.addresses = new ClientIpResolver(properties); this.json = json;
        this.cors = cors;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) && (path.equals("/actuator/health") || path.startsWith("/actuator/health/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AutoCloseable admission = null;
        int cost;
        try { cost = requests.publicCost(request); }
        catch (IllegalArgumentException invalid) {
            if (!corsError(request, response)) return;
            response.setStatus(400); response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), java.util.Map.of("code", "INVALID_REQUEST", "message", "Invalid request parameters"));
            return;
        }
        try {
            admission = limits.admit(requests.group(request));
            limits.checkIp(addresses.resolve(request), cost, requests.isLogin(request));
            request.setAttribute(GatewayLimitRequestBinding.ACTIVE, Boolean.TRUE);
            chain.doFilter(request, response);
        } catch (GatewayRequestLimits.Rejection rejection) {
            if (!corsError(request, response)) return;
            var result = GatewayLimitErrors.response(rejection);
            response.setStatus(result.getStatusCode().value());
            result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), result.getBody());
        } finally {
            if (admission != null) {
                if (request.isAsyncStarted()) releaseOnCompletion(request.getAsyncContext(), admission);
                else release(admission);
            }
        }
    }

    private boolean corsError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var configuration = cors.getCorsConfiguration(request);
        return configuration == null || corsProcessor.processRequest(configuration, request, response);
    }

    private static void releaseOnCompletion(AsyncContext context, AutoCloseable admission) {
        try {
            context.addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { release(admission); }
                @Override public void onTimeout(AsyncEvent event) {}
                @Override public void onError(AsyncEvent event) {}
                @Override public void onStartAsync(AsyncEvent event) { releaseOnCompletion(event.getAsyncContext(), admission); }
            });
        } catch (IllegalStateException alreadyComplete) { release(admission); }
    }

    private static void release(AutoCloseable admission) {
        try { admission.close(); }
        catch (Exception impossible) { throw new IllegalStateException("failed to release Gateway admission", impossible); }
    }
}
