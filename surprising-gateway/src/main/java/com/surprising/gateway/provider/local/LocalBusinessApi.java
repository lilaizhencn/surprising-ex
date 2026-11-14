package com.surprising.gateway.provider.local;

import com.surprising.account.provider.exception.AccountCommandRejectedException;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 保留公共 API 的响应契约，将已鉴权的请求直接交给进程内业务入口。 */
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
        if (accountProperties == null) return; // 公共币种/合约目录没有产品账户容器。
        if (productLine() == null || productLine() != tradingProperties.getProductLine()) {
            throw new IllegalStateException("account and trading must use the same product line: account="
                    + productLine() + ", trading=" + tradingProperties.getProductLine());
        }
    }

    public com.surprising.product.api.ProductLine productLine() {
        return accountProperties.getProductLine();
    }

    public static LocalBusinessApi shared(com.surprising.instrument.provider.controller.InstrumentRequestHandler instruments,
                                         ObjectMapper mapper, Validator validator) {
        return new LocalBusinessApi(null, null, new InstrumentLocalRoutes(instruments, null), mapper, validator,
                null, null);
    }

    public static boolean isLocalService(String service) {
        return switch (service.toLowerCase(java.util.Locale.ROOT)) {
            case "trading-market", "trading", "trading-orders", "trading-fees", "trading-leverage", "trading-trigger",
                 "account", "account-public", "instrument", "instrument-admin" -> true;
            default -> false;
        };
    }

    public ResponseEntity<byte[]> invoke(String service, URI target, HttpMethod method,
                                          HttpHeaders headers, byte[] body, Duration timeout) {
        LocalApiRequest request = new LocalApiRequest(target, method, headers, body, mapper, validator);
        try {
            if (request.hasAdminTarget() && (headers.getFirst("X-Admin-User-Id") == null
                    || headers.getFirst("X-Admin-User-Id").isBlank())) {
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
            return error(HttpStatus.GATEWAY_TIMEOUT,
                    "The result is not yet confirmed. Check your orders or settings before submitting again.", "RESULT_UNKNOWN");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "The result is not yet confirmed. Check your orders or settings before submitting again.", "RESULT_UNKNOWN");
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
        if (exception instanceof com.surprising.realtime.api.exception.ReadViewUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
        if (exception instanceof com.surprising.trading.order.exception.OrderCommandRejectedException rejected) {
            return error(HttpStatus.CONFLICT,rejected.getMessage(),rejected.code().name());
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
                    "The result is not yet confirmed. Check your orders or settings before submitting again.", "RESULT_UNKNOWN");
        }
        if (exception instanceof com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedException) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "The request was not accepted. Please try again shortly.", "REQUEST_NOT_ACCEPTED");
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

    private ResponseEntity<byte[]> error(org.springframework.http.HttpStatusCode status, String message, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsBytes(Map.of("status", status.value(), "message", message, "code", code)));
    }
}
