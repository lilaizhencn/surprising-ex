package com.surprising.gateway.provider.local;

import com.surprising.account.provider.service.AccountCommandRejectedException;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 保留公共 API 的响应契约，将已鉴权的请求直接交给进程内业务入口。 */
@Component
public final class LocalBusinessApi {
    private final TradingLocalRoutes trading;
    private final AccountLocalRoutes account;
    private final InstrumentLocalRoutes instrument;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final com.surprising.account.provider.config.AccountProperties accountProperties;
    private final com.surprising.trading.order.config.TradingOrderProperties tradingProperties;

    public LocalBusinessApi(TradingLocalRoutes trading, AccountLocalRoutes account,
                            InstrumentLocalRoutes instrument, ObjectMapper mapper, Validator validator,
                            com.surprising.account.provider.config.AccountProperties accountProperties,
                            com.surprising.trading.order.config.TradingOrderProperties tradingProperties) {
        this.trading = trading;
        this.account = account;
        this.instrument = instrument;
        this.mapper = mapper;
        this.validator = validator;
        this.accountProperties = accountProperties;
        this.tradingProperties = tradingProperties;
    }

    @jakarta.annotation.PostConstruct
    public void validateConfiguration() {
        if (productLine() == null || productLine() != tradingProperties.getKafka().getProductLine()) {
            throw new IllegalStateException("account and trading must use the same product line");
        }
    }

    public com.surprising.product.api.ProductLine productLine() {
        return accountProperties.getKafka().getProductLine();
    }

    public void validateProductSelectors(jakarta.servlet.http.HttpServletRequest request, byte[] body) {
        String path = request.getRequestURI();
        if (path != null) {
            if (path.startsWith("/fapi/")) validateProduct("LINEAR_PERPETUAL");
            if (path.startsWith("/dapi/")) validateProduct("INVERSE_PERPETUAL");
            if (path.startsWith("/eapi/")) validateProduct("OPTION");
        }
        for (String name : java.util.List.of("X-Product-Line", "X-Account-Type", "X-Contract-Type")) {
            validateProduct(request.getHeader(name));
        }
        for (String name : java.util.List.of("productLine", "product-line", "product_line", "accountType",
                "account-type", "account_type", "contractType", "contract-type", "contract_type")) {
            String[] values = request.getParameterValues(name);
            if (values != null) {
                for (String value : values) {
                    validateProduct(value);
                }
            }
        }
        if (body != null && body.length > 0) {
            try {
                validateBodyProduct(mapper.readTree(body));
            } catch (tools.jackson.core.JacksonException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid JSON body", exception);
            }
        }
    }

    private void validateBodyProduct(tools.jackson.databind.JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String name : java.util.List.of("productLine", "product-line", "product_line", "accountType",
                    "account-type", "account_type", "contractType", "contract-type", "contract_type")) {
                var value = node.get(name);
                if (value != null && !value.isNull()) {
                    validateProduct(value.asText());
                }
            }
        }
        if ((node.isObject() || node.isArray())) {
            for (var child : node) {
                validateBodyProduct(child);
            }
        }
    }

    private void validateProduct(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        var current = productLine();
        if (!current.name().equalsIgnoreCase(value.replace('-', '_'))
                && !current.topicSegment().equalsIgnoreCase(value)
                && !current.accountTypeCode().equalsIgnoreCase(value)
                && !current.contractTypeCode().equalsIgnoreCase(value)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product is not enabled in this application");
        }
    }

    public static boolean isLocalService(String service) {
        return switch (service.toLowerCase(java.util.Locale.ROOT)) {
            case "trading", "trading-orders", "trading-fees", "trading-leverage", "trading-trigger",
                 "account", "account-public", "instrument", "instrument-admin" -> true;
            default -> false;
        };
    }

    public ResponseEntity<byte[]> invoke(String service, URI target, HttpMethod method,
                                          HttpHeaders headers, byte[] body, Duration timeout) {
        LocalApiRequest request = new LocalApiRequest(target, method, headers, body, mapper, validator);
        try {
            if (request.hasAdminTarget() && headers.getFirst("X-Admin-User-Id") == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin identity is required");
            }
            Object result = switch (service.toLowerCase(java.util.Locale.ROOT)) {
                case "account", "account-public" -> account.invoke(request);
                case "instrument", "instrument-admin" -> instrument.invoke(request);
                default -> trading.invoke(request);
            };
            if (result instanceof CompletionStage<?> stage) {
                // 同步网关契约保持有界等待；超时不撤销已提交的 Core 命令。
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
            return error(HttpStatus.GATEWAY_TIMEOUT, "business response timed out; command outcome may be pending");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE, "business response interrupted");
        } catch (RuntimeException exception) {
            return failure(exception);
        }
    }

    private byte[] bytes(Object value) {
        return value == null ? null : mapper.writeValueAsBytes(value);
    }

    private ResponseEntity<byte[]> failure(Throwable exception) {
        while (exception instanceof java.util.concurrent.CompletionException && exception.getCause() != null) {
            exception = exception.getCause();
        }
        if (exception instanceof ResponseStatusException status) {
            return error(status.getStatusCode(), status.getReason());
        }
        if (exception instanceof AccountCommandRejectedException rejected) {
            return error(HttpStatus.CONFLICT, rejected.errorCode());
        }
        if (exception instanceof IllegalArgumentException) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (exception instanceof org.springframework.dao.DuplicateKeyException) {
            return error(HttpStatus.CONFLICT, "conflicting business request");
        }
        throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
    }

    private ResponseEntity<byte[]> error(org.springframework.http.HttpStatusCode status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsBytes(Map.of("status", status.value(), "message", message == null ? "" : message)));
    }
}
