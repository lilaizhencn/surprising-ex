package com.surprising.gateway.provider.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class GatewayTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = GatewayTraceFilter.class.getName() + ".traceId";

    private static final String TRACE_ID_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Store the normalized id on the request so proxying code can forward the generated value.
        String traceId = normalizeOrCreate(request.getHeader(TRACE_ID_HEADER));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        filterChain.doFilter(request, response);
    }

    private String normalizeOrCreate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = traceId.trim();
        return normalized.matches(TRACE_ID_PATTERN) ? normalized : UUID.randomUUID().toString();
    }
}
