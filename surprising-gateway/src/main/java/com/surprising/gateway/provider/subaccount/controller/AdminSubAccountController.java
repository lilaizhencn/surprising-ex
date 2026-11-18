package com.surprising.gateway.provider.subaccount.controller;

import com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.subaccount.model.SubAccountModels.SubAccountRecord;
import com.surprising.gateway.provider.subaccount.service.SubAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 后台主子账户关系查询与状态治理接口。
 */
@RestController
@RequestMapping("/api/v1/admin/sub-accounts")
public class AdminSubAccountController {

    private final AuthService authService;
    private final AdminApprovalService approvalService;
    private final SubAccountService subAccountService;
    private final ObjectMapper objectMapper;

    public AdminSubAccountController(AuthService authService,
                                     AdminApprovalService approvalService,
                                     SubAccountService subAccountService,
                                     ObjectMapper objectMapper) {
        this.authService = authService;
        this.approvalService = approvalService;
        this.subAccountService = subAccountService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "masterUserId", required = false) Long masterUserId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            authService.requireAdminPermission(authorization, "admin.users.read");
            return subAccountService.adminListSubAccounts(masterUserId, status, limit).stream()
                    .map(AdminSubAccountController::mapRecord)
                    .toList();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{subUserId}/status")
    public Map<String, Object> updateStatus(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("subUserId") long subUserId,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        try {
            approvalService.requireWrite(
                    authorization,
                    "admin.users.write",
                    "gateway-admin",
                    new AdminRequestMetadata(
                            AdminApprovalRequestBinding.reference(request.getHeader(approvalService.approvalHeaderName())),
                            request.getMethod(),
                            request.getRequestURI(),
                            request.getQueryString()),
                    body == null ? new byte[0] : body);
            StatusUpdateRequest payload = objectMapper.readValue(body == null ? new byte[0] : body, StatusUpdateRequest.class);
            if (payload == null || payload.status() == null || payload.status().isBlank()) {
                throw new IllegalArgumentException("status is required");
            }
            SubAccountRecord updated = subAccountService.adminUpdateStatus(subUserId, payload.status());
            return Map.of("ok", true, "subAccount", mapRecord(updated));
        } catch (AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> mapRecord(SubAccountRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.id());
        row.put("subUserId", r.subUserId());
        row.put("masterUserId", r.parentUserId());
        row.put("subAccountName", r.subAccountName());
        row.put("permission", r.permissions());
        row.put("status", r.status());
        row.put("createdAt", r.createdAt());
        return row;
    }

    public record StatusUpdateRequest(String status) {}
}
