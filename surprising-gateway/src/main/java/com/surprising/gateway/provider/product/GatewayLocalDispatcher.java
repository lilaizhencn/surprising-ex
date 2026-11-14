package com.surprising.gateway.provider.product;

import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ServletRequestDataBinderFactory;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Dispatches gateway {@code local:} routes directly to in-process Spring MVC {@code @RestController} handlers. */
@Component
public class GatewayLocalDispatcher {
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final GatewayProductServices products;
    private final RequestMappingHandlerMapping parentMapping;
    private final RequestMappingHandlerAdapter handlerAdapter;
    private final HandlerMethodArgumentResolverComposite argumentResolvers;
    private final ObjectMapper mapper;

    public GatewayLocalDispatcher(
            GatewayProductServices products,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping parentMapping,
            RequestMappingHandlerAdapter handlerAdapter,
            ObjectMapper mapper) {
        this.products = products;
        this.parentMapping = parentMapping;
        this.handlerAdapter = handlerAdapter;
        this.argumentResolvers = new HandlerMethodArgumentResolverComposite();
        if (handlerAdapter != null && handlerAdapter.getArgumentResolvers() != null) {
            this.argumentResolvers.addResolvers(handlerAdapter.getArgumentResolvers());
        }
        this.mapper = mapper;
    }

    public static boolean isLocalService(String service) {
        return switch (service.toLowerCase(Locale.ROOT)) {
            case "trading-market", "trading", "trading-orders", "trading-fees", "trading-leverage", "trading-trigger",
                 "account", "account-public", "instrument", "instrument-admin" -> true;
            default -> false;
        };
    }

    public ResponseEntity<byte[]> invoke(String service, URI target, HttpMethod method,
                                         HttpHeaders headers, byte[] body, Duration timeout,
                                         ProductLine product, boolean shared,
                                         HttpServletRequest originalRequest) {
        try {
            String path = target.getRawPath() == null ? "/" : target.getRawPath();
            if (hasAdminTarget(path)) {
                GatewayUserContext.requireAdminUserId(headers.getFirst("X-Admin-User-Id"));
            }
            verifyBodyUserIdIfNeeded(headers, body);

            HttpServletRequest localRequest = new LocalHttpServletRequest(
                    originalRequest, path, target.getRawQuery(), method, headers, body);
            RequestMappingHandlerMapping mapping = shared ? parentMapping : products.internalMapping(product);
            HandlerExecutionChain chain = mapping == null ? null : mapping.getHandler(localRequest);
            if (chain == null || !(chain.getHandler() instanceof HandlerMethod handlerMethod)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local endpoint: " + path);
            }

            InvocableHandlerMethod invocable = new InvocableHandlerMethod(handlerMethod);
            invocable.setHandlerMethodArgumentResolvers(argumentResolvers);
            invocable.setDataBinderFactory(new ServletRequestDataBinderFactory(
                    null, handlerAdapter.getWebBindingInitializer()));
            invocable.setParameterNameDiscoverer(PARAMETER_NAMES);

            Object result = invocable.invokeForRequest(new ServletWebRequest(localRequest), new ModelAndViewContainer());
            if (result instanceof CompletionStage<?> stage) {
                result = stage.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
            if (result instanceof ResponseEntity<?> response) {
                return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                        .contentType(MediaType.APPLICATION_JSON).body(bytes(response.getBody()));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes(result));
        } catch (ExecutionException exception) {
            return failure(exception.getCause());
        } catch (TimeoutException exception) {
            return error(HttpStatus.GATEWAY_TIMEOUT,
                    "The result is not yet confirmed. Check your orders or settings before submitting again.",
                    "RESULT_UNKNOWN");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "The result is not yet confirmed. Check your orders or settings before submitting again.",
                    "RESULT_UNKNOWN");
        } catch (Exception exception) {
            return failure(exception);
        }
    }

    private void verifyBodyUserIdIfNeeded(HttpHeaders headers, byte[] body) {
        if (body == null || body.length == 0) {
            return;
        }
        String userId = headers.getFirst("X-User-Id");
        String adminUserId = headers.getFirst("X-Admin-User-Id");
        if (userId == null || userId.isBlank() || (adminUserId != null && !adminUserId.isBlank())) {
            return;
        }
        try {
            GatewayUserContext.verifyBodyUserId(mapper.readTree(body), userId, adminUserId);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body", ex);
        }
    }

    private static boolean hasAdminTarget(String path) {
        return path.startsWith("/api/v1/admin/")
                || path.startsWith("/api/v1/accounts/admin/")
                || path.startsWith("/api/v1/instruments/admin/");
    }

    private byte[] bytes(Object value) {
        return value == null ? null : mapper.writeValueAsBytes(value);
    }

    private ResponseEntity<byte[]> failure(Throwable exception) {
        while (exception instanceof CompletionException && exception.getCause() != null) {
            exception = exception.getCause();
        }
        if (exception instanceof com.surprising.realtime.api.exception.ReadViewUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
        if (exception instanceof com.surprising.trading.order.exception.OrderCommandRejectedException rejected) {
            return error(HttpStatus.CONFLICT, rejected.getMessage(), rejected.code().name());
        }
        if (exception instanceof ResponseStatusException status) {
            var properties = status.getBody().getProperties();
            if (properties != null && properties.get("code") instanceof String code) {
                return error(status.getStatusCode(), status.getReason(), code);
            }
            return error(status.getStatusCode(), status.getReason());
        }
        if (exception instanceof AccountCommandRejectedException rejected) {
            return error(HttpStatus.CONFLICT, "The account request could not be completed.", rejected.errorCode());
        }
        if (exception instanceof com.surprising.aeron.client.ResultUnknownException) {
            return error(HttpStatus.GATEWAY_TIMEOUT,
                    "The result is not yet confirmed. Check your orders or settings before submitting again.",
                    "RESULT_UNKNOWN");
        }
        if (exception instanceof com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedException) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "The request was not accepted. Please try again shortly.",
                    "REQUEST_NOT_ACCEPTED");
        }
        if (exception instanceof MethodArgumentNotValidException
                || exception instanceof HandlerMethodValidationException
                || exception instanceof HttpMessageNotReadableException
                || exception instanceof ServletRequestBindingException
                || exception instanceof MethodArgumentTypeMismatchException) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (exception instanceof IllegalArgumentException) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (exception instanceof DuplicateKeyException) {
            return error(HttpStatus.CONFLICT, "conflicting business request");
        }
        throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
    }

    private ResponseEntity<byte[]> error(org.springframework.http.HttpStatusCode status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsBytes(Map.of("status", status.value(), "message", message == null ? "" : message)));
    }

    private ResponseEntity<byte[]> error(org.springframework.http.HttpStatusCode status, String message, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsBytes(Map.of("status", status.value(), "message", message, "code", code)));
    }

    private static final class LocalHttpServletRequest extends HttpServletRequestWrapper {
        private final String path;
        private final String queryString;
        private final String method;
        private final Map<String, List<String>> headers;
        private final Map<String, String[]> parameters;
        private final byte[] body;

        LocalHttpServletRequest(HttpServletRequest delegate, String path, String queryString,
                                HttpMethod method, HttpHeaders httpHeaders, byte[] body) {
            super(delegate);
            this.path = path;
            this.queryString = queryString;
            this.method = method.name();
            this.headers = new LinkedHashMap<>();
            if (httpHeaders != null) {
                httpHeaders.forEach((k, v) -> this.headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
            }
            this.parameters = parseParameters(queryString);
            this.body = body == null ? new byte[0] : body;
        }

        private static Map<String, String[]> parseParameters(String rawQuery) {
            MultiValueMap<String, String> multi = new LinkedMultiValueMap<>();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String pair : rawQuery.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                    String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                    multi.add(name, value);
                }
            }
            Map<String, String[]> result = new LinkedHashMap<>();
            multi.forEach((k, v) -> result.put(k, v.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private static String decode(String value) {
            return UriUtils.decode(value.replace("+", " "), StandardCharsets.UTF_8);
        }

        @Override public String getMethod() { return method; }
        @Override public String getRequestURI() { return path; }
        @Override public String getServletPath() { return path; }
        @Override public String getPathInfo() { return null; }
        @Override public String getContextPath() { return ""; }
        @Override public String getQueryString() { return queryString; }
        @Override public String getContentType() {
            String ct = getHeader(HttpHeaders.CONTENT_TYPE);
            return ct != null ? ct : MediaType.APPLICATION_JSON_VALUE;
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }

        @Override
        public String getHeader(String name) {
            if (name == null) return null;
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return (values == null || values.isEmpty()) ? null : values.get(0);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (name == null) return Collections.emptyEnumeration();
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? Collections.emptyEnumeration() : Collections.enumeration(values);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(new ArrayList<>(headers.keySet()));
        }

        @Override public String getParameter(String name) {
            String[] values = parameters.get(name);
            return (values == null || values.length == 0) ? null : values[0];
        }

        @Override public Map<String, String[]> getParameterMap() { return parameters; }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
        @Override public String[] getParameterValues(String name) { return parameters.get(name); }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
                @Override public int read() { return in.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
