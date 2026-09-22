package com.surprising.gateway.provider.local;

import jakarta.validation.Validator;
import java.net.URI;
import java.util.Map;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.pattern.PathPattern;
import tools.jackson.databind.ObjectMapper;

/** 单次网关请求的协议绑定。身份头只接受网关校验后的值。 */
public final class LocalApiRequest {
    private static final ConversionService CONVERSION = DefaultConversionService.getSharedInstance();
    private final HttpMethod method;
    private final PathContainer path;
    private final String decodedPath;
    private final MultiValueMap<String, String> query;
    private final HttpHeaders headers;
    private final byte[] body;
    private final ObjectMapper mapper;
    private final Validator validator;
    private Map<String, String> variables = Map.of();

    public LocalApiRequest(URI target, HttpMethod method, HttpHeaders headers, byte[] body,
                           ObjectMapper mapper, Validator validator) {
        this.method = method;
        this.path = PathContainer.parsePath(target.getRawPath());
        this.decodedPath = UriUtils.decode(target.getRawPath(), java.nio.charset.StandardCharsets.UTF_8);
        this.query = UriComponentsBuilder.fromUri(target).build(true).getQueryParams();
        this.headers = headers;
        this.body = body;
        this.mapper = mapper;
        this.validator = validator;
    }

    public boolean isMaintenanceRequest() {
        String value = decodedPath;
        return value.equals("/api/v1/admin/trading/orders/maintenance")
                || value.startsWith("/api/v1/admin/trading/orders/maintenance/");
    }

    public boolean hasAdminTarget() {
        String value = decodedPath;
        return value.startsWith("/api/v1/admin/") || value.startsWith("/api/v1/instruments/admin")
                || value.startsWith("/api/v1/accounts/admin");
    }

    public boolean matches(HttpMethod method, PathPattern pattern) {
        if (!this.method.equals(method)) {
            return false;
        }
        var match = pattern.matchAndExtract(path);
        if (match == null) {
            return false;
        }
        variables = match.getUriVariables();
        return true;
    }

    public <T> T path(String name, Class<T> type) {
        return convert(name, variables.get(name), type, null, true);
    }

    public <T> T query(String name, Class<T> type, String defaultValue, boolean required) {
        String value = query.getFirst(name);
        if (value != null) {
            value = UriUtils.decode(value.replace("+", " "), java.nio.charset.StandardCharsets.UTF_8);
        }
        // 用户查询不能通过 query userId 访问他人账户；管理员仍可选择目标用户。
        if ("userId".equals(name) && headers.getFirst("X-Admin-User-Id") == null) {
            String userId = headers.getFirst("X-User-Id");
            if (userId != null) {
                if (value != null && !value.equals(userId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
                }
                value = userId;
            }
        }
        return convert(name, value, type, defaultValue, required);
    }

    public <T> T header(String name, Class<T> type, String defaultValue, boolean required) {
        return convert(name, headers.getFirst(name), type, defaultValue, required);
    }

    public <T> T body(Class<T> type, boolean required, boolean validate) {
        if (body == null || body.length == 0) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
            }
            return null;
        }
        try {
            var tree = mapper.readTree(body);
            String userId = headers.getFirst("X-User-Id");
            if (userId != null && headers.getFirst("X-Admin-User-Id") == null) {
                verifyUserId(tree, userId);
            }
            T value = mapper.treeToValue(tree, type);
            if (value == null || (validate && !validator.validate(value).isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body");
            }
            return value;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body", exception);
        }
    }

    private void verifyUserId(tools.jackson.databind.JsonNode node, String userId) {
        if (node.isObject()) {
            var id = node.get("userId");
            if (id != null && !userId.equals(id.asText())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
            }
        }
        if ((node.isObject() || node.isArray())) {
            for (var child : node) {
                verifyUserId(child, userId);
            }
        }
    }

    private <T> T convert(String name, String value, Class<T> type, String defaultValue, boolean required) {
        if (value == null || value.isEmpty()) {
            value = defaultValue;
        }
        if (value == null && required) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        try {
            return CONVERSION.convert(value, type);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name, exception);
        }
    }
}
