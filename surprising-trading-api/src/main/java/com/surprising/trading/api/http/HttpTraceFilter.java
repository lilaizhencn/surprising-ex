package com.surprising.trading.api.http;

import com.surprising.trading.api.TraceContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTP lifecycle owner; explicit ids cross processes, scoped MDC only belongs to this thread. */
public class HttpTraceFilter extends OncePerRequestFilter implements Ordered {
    public static final String TRACE_ID_HEADER = TraceContext.TRACE_ID_HEADER;
    public static final String TRACE_ID_ATTRIBUTE = HttpTraceFilter.class.getName() + ".traceId";
    private static final String LIFECYCLE_ATTRIBUTE = HttpTraceFilter.class.getName() + ".lifecycle";
    private static final Logger log = LoggerFactory.getLogger(HttpTraceFilter.class);

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        Lifecycle lifecycle = (Lifecycle) request.getAttribute(LIFECYCLE_ATTRIBUTE);
        if (lifecycle == null) {
            String id = TraceContext.normalizeOrCreate(request.getHeader(TRACE_ID_HEADER));
            lifecycle = new Lifecycle(id, request.getMethod(), request.getRequestURI(), response);
            request.setAttribute(LIFECYCLE_ATTRIBUTE, lifecycle);
            request.setAttribute(TRACE_ID_ATTRIBUTE, id);
            response.setHeader(TRACE_ID_HEADER, id);
            try (var scope = TraceContext.open(id)) {
                log.info("http.start method={} path={}", lifecycle.method, lifecycle.path);
            }
        }
        Throwable failure = null;
        try (var scope = TraceContext.open(lifecycle.traceId)) {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (request.isAsyncStarted()) {
                try { request.getAsyncContext().addListener(lifecycle); }
                catch (IllegalStateException completedBeforeRegistration) { lifecycle.finish(failure); }
            } else {
                lifecycle.finish(failure);
            }
        }
    }

    @jdk.jfr.Name("surprising.HttpRequest")
    @jdk.jfr.Label("HTTP request lifecycle")
    @jdk.jfr.Category("Surprising")
    @jdk.jfr.StackTrace(false)
    @jdk.jfr.Threshold("20 ms")
    static final class HttpRequestEvent extends jdk.jfr.Event {
        String traceId, method, path, error;
        int status;
    }

    private static final class Lifecycle implements AsyncListener {
        private final String traceId, method, path;
        private final HttpServletResponse response;
        private final long started = System.nanoTime();
        private final HttpRequestEvent recording = new HttpRequestEvent();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Throwable asyncFailure;
        private Lifecycle(String traceId, String method, String path, HttpServletResponse response) {
            this.traceId = traceId;
            this.method = method;
            // Query strings, headers and request bodies are intentionally excluded.
            this.path = path.replaceAll("[\\r\\n\\t]", "_");
            this.response = response;
            recording.traceId = traceId;
            recording.method = method;
            recording.path = this.path;
            recording.begin();
        }
        private void finish(Throwable failure) {
            if (!finished.compareAndSet(false, true)) return;
            try (var scope = TraceContext.open(traceId)) {
                int status = failure == null ? response.getStatus() : 500;
                String error = failure == null ? "none" : failure.getClass().getSimpleName();
                recording.status = status;
                recording.error = error;
                recording.end();
                recording.commit();
                log.info("http.end method={} path={} status={} durationMs={} error={}",
                        method, path, status, (System.nanoTime() - started) / 1_000_000.0, error);
            }
        }
        @Override public void onComplete(AsyncEvent event) { finish(asyncFailure); }
        @Override public void onError(AsyncEvent event) { asyncFailure = event.getThrowable(); }
        @Override public void onTimeout(AsyncEvent event) {
            asyncFailure = new java.util.concurrent.TimeoutException("async HTTP timeout");
        }
        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
}
