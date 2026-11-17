package com.surprising.gateway.provider.interceptor;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 产品选择与批量计费先读取同一份有界正文，随后由 MVC 正常绑定。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ProductRequestBodyFilter extends OncePerRequestFilter {
    public static final String BODY = ProductRequestBodyFilter.class.getName() + ".body";
    private static final String JSON_TREE = ProductRequestBodyFilter.class.getName() + ".jsonTree";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern ANNOUNCEMENT_SAVE_PATH =
            Pattern.compile("/api/v1/admin/announcements(?:/[0-9]+)?");
    private static final Pattern LEGAL_REVISION_SAVE_PATH =
            Pattern.compile("/api/v1/admin/legal/revisions(?:/[0-9]+)?");

    private record CachedJsonTree(byte[] body, tools.jackson.databind.JsonNode tree) {}

    public static tools.jackson.databind.JsonNode requestTree(
            HttpServletRequest request, byte[] body, tools.jackson.databind.ObjectMapper mapper) {
        if (body == null || body.length == 0) return null;
        if (request != null && request.getAttribute(JSON_TREE) instanceof CachedJsonTree cached && cached.body() == body) {
            return cached.tree();
        }
        tools.jackson.databind.JsonNode tree = mapper.readTree(body);
        if (request != null) {
            request.setAttribute(JSON_TREE, new CachedJsonTree(body, tree));
        }
        return tree;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return BODYLESS_METHODS.contains(request.getMethod())
                || (type != null && (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean articleSave = ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()))
                && (ANNOUNCEMENT_SAVE_PATH.matcher(path).matches() || LEGAL_REVISION_SAVE_PATH.matcher(path).matches());
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
