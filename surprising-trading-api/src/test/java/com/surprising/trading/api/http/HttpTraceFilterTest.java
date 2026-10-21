package com.surprising.trading.api.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.surprising.trading.api.TraceContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class HttpTraceFilterTest {
    private final ListAppender<ILoggingEvent> output = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpTraceFilter.class);
    private final HttpTraceFilter filter = new HttpTraceFilter();
    @BeforeEach void attach() { output.start(); logger.addAppender(output); }
    @AfterEach void detach() { logger.detachAppender(output); TraceContext.clear(); }

    @Test void tracesBothBoundariesRestoresNestedContextAndExcludesSecrets() throws Exception {
        TraceContext.set("outer");
        var request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("X-Trace-Id", "request-1");
        request.addHeader("Authorization", "Bearer SECRET");
        request.setQueryString("password=SECRET");
        request.setContent("SECRET".getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(TraceContext.current()).isEqualTo("request-1");
            assertThat(MDC.get("traceId")).isEqualTo("request-1");
            response.setStatus(403);
        });
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("request-1");
        assertThat(TraceContext.current()).isEqualTo("outer");
        assertThat(MDC.get("traceId")).isEqualTo("outer");
        assertThat(output.list).hasSize(2).allSatisfy(e ->
                assertThat(e.getMDCPropertyMap()).containsEntry("traceId", "request-1"));
        assertThat(output.list.getLast().getFormattedMessage()).contains("status=403", "durationMs=");
        assertThat(output.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("SECRET"));
    }

    @Test void recordsThrownFailureAndCleansAReusedWorkerThread() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/fail");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("SECRET");
        })).isInstanceOf(ServletException.class);
        assertThat(TraceContext.current()).isNull();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(output.list.getLast().getFormattedMessage()).contains("status=500", "error=ServletException");
    }

    @Test void asynchronousCompletionLogsOneEndOnlyAndKeepsTheOriginalId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/async");
        request.setAsyncSupported(true);
        request.addHeader("X-Trace-Id", "async-1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> request.startAsync(request, response));
        assertThat(output.list).hasSize(1);
        assertThat(MDC.get("traceId")).isNull();
        var context = (MockAsyncContext) request.getAsyncContext();
        response.setStatus(202);
        for (var listener : List.copyOf(context.getListeners())) {
            listener.onComplete(new AsyncEvent(context));
            listener.onComplete(new AsyncEvent(context));
        }
        assertThat(output.list).hasSize(2);
        assertThat(output.list.getLast().getMDCPropertyMap()).containsEntry("traceId", "async-1");
        assertThat(output.list.getLast().getFormattedMessage()).contains("status=202");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void jfrCarriesTheSameHttpRootAndStatus() throws Exception {
        var path = java.nio.file.Files.createTempFile("http-trace-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(HttpTraceFilter.HttpRequestEvent.class).withThreshold(java.time.Duration.ZERO);
            recording.start();
            var request = new MockHttpServletRequest("GET", "/api/jfr");
            request.addHeader("X-Trace-Id", "http-jfr-root");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> response.setStatus(202));
            recording.stop(); recording.dump(path);
            var events = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.HttpRequest")).toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getString("traceId")).isEqualTo("http-jfr-root");
            assertThat(events.getFirst().getInt("status")).isEqualTo(202);
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test void invalidHeaderCannotInjectNewLinesIntoTheLogs() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Trace-Id", "bad\r\nsecret");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("X-Trace-Id")).matches("[A-Za-z0-9._:-]{1,128}");
        assertThat(output.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("secret"));
    }
}
