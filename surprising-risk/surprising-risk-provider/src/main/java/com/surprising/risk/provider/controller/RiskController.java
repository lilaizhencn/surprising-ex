package com.surprising.risk.provider.controller;

import com.surprising.risk.api.RiskApiPaths;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.provider.service.RiskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/account/latest")
    public RiskAccountSnapshotResponse latestAccountRisk(@RequestParam("userId") long userId,
                                                         @RequestParam("settleAsset") String settleAsset) {
        try {
            return riskService.latestAccount(userId, settleAsset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/positions/latest")
    public RiskPositionQueryResponse latestPositionRisk(@RequestParam("userId") long userId) {
        return riskService.latestPositions(userId);
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/liquidation-candidates")
    public LiquidationCandidateQueryResponse liquidationCandidates(
            @RequestParam(value = "status", defaultValue = "NEW") String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return riskService.liquidationCandidates(status, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
