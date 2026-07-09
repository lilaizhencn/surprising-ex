package com.surprising.risk.provider.controller;

import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.provider.service.RiskService;
import com.surprising.risk.provider.service.RiskService.HighRiskAccountsResponse;
import com.surprising.risk.provider.service.RiskService.RiskRuleResponse;
import com.surprising.risk.provider.service.RiskService.RiskRuleUpdateCommand;
import com.surprising.risk.provider.service.RiskService.RiskRulesResponse;
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

@RestController
@RequestMapping("/api/v1/admin/risk")
public class AdminRiskController {

    private final RiskService riskService;

    public AdminRiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/rules")
    public RiskRulesResponse rules(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        requireAdmin(adminUserId);
        return riskService.riskRules();
    }

    @PostMapping("/rules/{ruleCode}")
    public RiskRuleResponse updateRule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable String ruleCode,
            @RequestBody RiskRuleUpdateCommand request) {
        try {
            return riskService.updateRiskRule(ruleCode, requireAdmin(adminUserId), request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/high-risk-accounts")
    public HighRiskAccountsResponse highRiskAccounts(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "minMarginRatioPpm", required = false) Long minMarginRatioPpm,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return riskService.highRiskAccounts(minMarginRatioPpm, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/liquidation-candidates")
    public LiquidationCandidateQueryResponse liquidationCandidates(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "status", defaultValue = "NEW") String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return riskService.liquidationCandidates(status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private String requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin user header is required");
        }
        return adminUserId.trim();
    }
}
