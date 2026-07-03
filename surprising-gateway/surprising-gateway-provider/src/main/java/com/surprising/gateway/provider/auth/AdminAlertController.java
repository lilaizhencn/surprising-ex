package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertEvaluationResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertChannel;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertChannelQueryResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertChannelRequest;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertDelivery;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertDeliveryQueryResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertEvent;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertEventQueryResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertRule;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertRuleQueryResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertRuleRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/alerts")
public class AdminAlertController {

    private final AuthService authService;
    private final AdminAlertRepository alertRepository;
    private final AdminApprovalRepository approvalRepository;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AdminAlertController(AuthService authService,
                                AdminAlertRepository alertRepository,
                                AdminApprovalRepository approvalRepository,
                                GatewayProperties properties,
                                ObjectMapper objectMapper) {
        this.authService = authService;
        this.alertRepository = alertRepository;
        this.approvalRepository = approvalRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/rules")
    public AlertRuleQueryResponse rules(@RequestHeader("Authorization") String authorization,
                                        @RequestParam(value = "domain", required = false) String domain,
                                        @RequestParam(value = "enabled", required = false) Boolean enabled,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit,
                                        @RequestParam(value = "cursor", required = false) String cursor,
                                        @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.read");
            var page = alertRepository.rulesPage(domain, enabled, limit, cursor, sort);
            return new AlertRuleQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/rules")
    public AlertRule createRule(@RequestHeader("Authorization") String authorization,
                                @RequestBody byte[] body,
                                HttpServletRequest httpRequest) {
        try {
            AlertRuleRequest request = readBody(body, AlertRuleRequest.class);
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.createRule(principal, request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/rules/{ruleId}")
    public AlertRule updateRule(@RequestHeader("Authorization") String authorization,
                                @PathVariable("ruleId") long ruleId,
                                @RequestBody byte[] body,
                                HttpServletRequest httpRequest) {
        try {
            AlertRuleRequest request = readBody(body, AlertRuleRequest.class);
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.updateRule(ruleId, principal, request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/rules/{ruleId}/enable")
    public AlertRule enableRule(@RequestHeader("Authorization") String authorization,
                                @PathVariable("ruleId") long ruleId,
                                @RequestBody(required = false) byte[] body,
                                HttpServletRequest httpRequest) {
        return setRuleEnabled(authorization, ruleId, true, body, httpRequest);
    }

    @PostMapping("/rules/{ruleId}/disable")
    public AlertRule disableRule(@RequestHeader("Authorization") String authorization,
                                 @PathVariable("ruleId") long ruleId,
                                 @RequestBody(required = false) byte[] body,
                                 HttpServletRequest httpRequest) {
        return setRuleEnabled(authorization, ruleId, false, body, httpRequest);
    }

    @GetMapping("/events")
    public AlertEventQueryResponse events(@RequestHeader("Authorization") String authorization,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "severity", required = false) String severity,
                                          @RequestParam(value = "domain", required = false) String domain,
                                          @RequestParam(value = "limit", defaultValue = "100") int limit,
                                          @RequestParam(value = "cursor", required = false) String cursor,
                                          @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.read");
            var page = alertRepository.eventsPage(status, severity, domain, limit, cursor, sort);
            return new AlertEventQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/channels")
    public AlertChannelQueryResponse channels(@RequestHeader("Authorization") String authorization,
                                              @RequestParam(value = "domain", required = false) String domain,
                                              @RequestParam(value = "enabled", required = false) Boolean enabled,
                                              @RequestParam(value = "limit", defaultValue = "100") int limit,
                                              @RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.read");
            var page = alertRepository.channelsPage(domain, enabled, limit, cursor, sort);
            return new AlertChannelQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/channels")
    public AlertChannel createChannel(@RequestHeader("Authorization") String authorization,
                                      @RequestBody byte[] body,
                                      HttpServletRequest httpRequest) {
        try {
            AlertChannelRequest request = readBody(body, AlertChannelRequest.class);
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.createChannel(principal, request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/channels/{channelId}")
    public AlertChannel updateChannel(@RequestHeader("Authorization") String authorization,
                                      @PathVariable("channelId") long channelId,
                                      @RequestBody byte[] body,
                                      HttpServletRequest httpRequest) {
        try {
            AlertChannelRequest request = readBody(body, AlertChannelRequest.class);
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.updateChannel(channelId, principal, request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/channels/{channelId}/enable")
    public AlertChannel enableChannel(@RequestHeader("Authorization") String authorization,
                                      @PathVariable("channelId") long channelId,
                                      @RequestBody(required = false) byte[] body,
                                      HttpServletRequest httpRequest) {
        return setChannelEnabled(authorization, channelId, true, body, httpRequest);
    }

    @PostMapping("/channels/{channelId}/disable")
    public AlertChannel disableChannel(@RequestHeader("Authorization") String authorization,
                                       @PathVariable("channelId") long channelId,
                                       @RequestBody(required = false) byte[] body,
                                       HttpServletRequest httpRequest) {
        return setChannelEnabled(authorization, channelId, false, body, httpRequest);
    }

    @GetMapping("/deliveries")
    public AlertDeliveryQueryResponse deliveries(@RequestHeader("Authorization") String authorization,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "channelId", required = false) Long channelId,
                                                 @RequestParam(value = "eventId", required = false) Long eventId,
                                                 @RequestParam(value = "limit", defaultValue = "100") int limit,
                                                 @RequestParam(value = "cursor", required = false) String cursor,
                                                 @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.read");
            var page = alertRepository.deliveriesPage(status, channelId, eventId, limit, cursor, sort);
            return new AlertDeliveryQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/events/{eventId}/acknowledge")
    public AlertEvent acknowledge(@RequestHeader("Authorization") String authorization,
                                  @PathVariable("eventId") long eventId) {
        try {
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            return alertRepository.acknowledge(eventId, principal, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public AlertDelivery retryDelivery(@RequestHeader("Authorization") String authorization,
                                       @PathVariable("deliveryId") long deliveryId) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.write");
            return alertRepository.retryDelivery(deliveryId, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/evaluate")
    public AlertEvaluationResponse evaluate(@RequestHeader("Authorization") String authorization) {
        try {
            authService.requireAdminPermission(authorization, "admin.alerts.write");
            return alertRepository.evaluate(Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private AlertRule setRuleEnabled(String authorization,
                                     long ruleId,
                                     boolean enabled,
                                     byte[] body,
                                     HttpServletRequest httpRequest) {
        try {
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.setEnabled(ruleId, principal, enabled, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private AlertChannel setChannelEnabled(String authorization,
                                           long channelId,
                                           boolean enabled,
                                           byte[] body,
                                           HttpServletRequest httpRequest) {
        try {
            var principal = authService.requireAdminPermission(authorization, "admin.alerts.write");
            requireLocalAdminApproval(principal, httpRequest, body);
            return alertRepository.setChannelEnabled(channelId, principal, enabled, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid request body", ex);
        }
    }

    private void requireLocalAdminApproval(AuthModels.JwtPrincipal principal,
                                           HttpServletRequest request,
                                           byte[] body) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        String approvalIdHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalIdHeader == null || approvalIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            approvalRepository.consumeApproved(
                    approvalId,
                    principal.userId(),
                    "gateway-admin",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodySha256(body),
                    traceId(request),
                    Instant.now());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private String bodySha256(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return request.getHeader(GatewayTraceFilter.TRACE_ID_HEADER);
    }
}
