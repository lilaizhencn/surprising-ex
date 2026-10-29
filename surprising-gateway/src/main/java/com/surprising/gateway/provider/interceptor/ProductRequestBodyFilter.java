package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.product.ProductInternalHandlerMapping;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 产品选择与批量计费先读取同一份有界正文，随后由 MVC 正常绑定。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ProductRequestBodyFilter extends OncePerRequestFilter {
    public static final String BODY = ProductRequestBodyFilter.class.getName() + ".body";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(ProductInternalHandlerMapping.isProductEndpoint(request.getRequestURI())
                    || (request.getRequestURI().startsWith("/api/") && request.getRequestURI().contains("batch")))
                || !("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()) || "PATCH".equals(request.getMethod()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) { response.sendError(413, "product request body is too large"); return; }
        request.setAttribute(BODY, body);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int off, int len) { return input.read(bytes, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try {
                            if (!isFinished()) listener.onDataAvailable();
                            if (isFinished()) listener.onAllDataRead();
                        } catch (IOException ex) { listener.onError(ex); }
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(),
                        getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
            }
        }, response);
    }
}
