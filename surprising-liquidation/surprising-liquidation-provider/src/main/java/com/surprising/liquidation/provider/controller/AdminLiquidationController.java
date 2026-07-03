package com.surprising.liquidation.provider.controller;

import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.provider.service.LiquidationService;
import com.surprising.liquidation.provider.service.LiquidationService.LiquidationAdminActionResponse;
import com.surprising.liquidation.provider.service.LiquidationService.LiquidationTimelineResponse;
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
@RequestMapping("/api/v1/admin/liquidations")
public class AdminLiquidationController {

    private final LiquidationService liquidationService;

    public AdminLiquidationController(LiquidationService liquidationService) {
        this.liquidationService = liquidationService;
    }

    @GetMapping("/orders")
    public LiquidationOrderQueryResponse orders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return liquidationService.orders(userId, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/candidates/{candidateId}/timeline")
    public LiquidationTimelineResponse timeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("candidateId") long candidateId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        requireAdmin(adminUserId);
        try {
            return liquidationService.timeline(candidateId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/candidates/{candidateId}/cancel")
    public LiquidationAdminActionResponse cancelCandidate(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("candidateId") long candidateId,
            @RequestBody CancelCandidateRequest request) {
        requireAdmin(adminUserId);
        try {
            return liquidationService.cancelCandidate(candidateId, adminUserId, request == null ? null : request.reason());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }

    public record CancelCandidateRequest(String reason) {
    }
}
