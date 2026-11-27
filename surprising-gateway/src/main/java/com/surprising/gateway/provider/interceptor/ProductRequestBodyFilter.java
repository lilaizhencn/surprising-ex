package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.support.CachedRequestBody;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.Charset;
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
    public static final String BODY = CachedRequestBody.BODY;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern ANNOUNCEMENT_SAVE_PATH =
            Pattern.compile("/api/v1/admin/announcements(?:/[0-9]+)?");
    private static final Pattern LEGAL_REVISION_SAVE_PATH =
            Pattern.compile("/api/v1/admin/legal/revisions(?:/[0-9]+)?");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return BODYLESS_METHODS.contains(request.getMethod())
                || (type != null && (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean articleSave = ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()))
                && (ANNOUNCEMENT_SAVE_PATH.matcher(path).matches() || LEGAL_REVISION_SAVE_PATH.matcher(path).matches());
        int limit = articleSave ? 3 * MAX_BYTES : MAX_BYTES;
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            response.sendError(413, "product request body is too large");
            return;
        }
        request.setAttribute(CachedRequestBody.BODY, body);
        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override public int read() { return input.read(); }
        @Override public int read(byte[] bytes, int off, int len) { return input.read(bytes, off, len); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }

        @Override
        public void setReadListener(ReadListener listener) {
            try {
                if (!isFinished()) listener.onDataAvailable();
                if (isFinished()) listener.onAllDataRead();
            } catch (IOException ex) {
                listener.onError(ex);
            }
        }
    }
}
