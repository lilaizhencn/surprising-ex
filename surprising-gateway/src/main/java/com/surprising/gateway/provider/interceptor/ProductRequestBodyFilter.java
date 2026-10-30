package com.surprising.gateway.provider.interceptor;

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
    private static final int MAX_BYTES = 1024 * 1024;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return java.util.List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                || (type != null && (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String path=request.getRequestURI();
        boolean articleSave=("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()))
                && (path.matches("/api/v1/admin/announcements(?:/[0-9]+)?") || path.matches("/api/v1/admin/legal/revisions(?:/[0-9]+)?"));
        int limit=articleSave?3*MAX_BYTES:MAX_BYTES;
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) { response.sendError(413, "product request body is too large"); return; }
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
