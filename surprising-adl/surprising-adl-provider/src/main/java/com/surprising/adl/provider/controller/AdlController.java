package com.surprising.adl.provider.controller;

import com.surprising.adl.api.AdlApiPaths;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.service.AdlService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(AdlApiPaths.API_V1)
public class AdlController {

    private final AdlService adlService;
    private final AdlProperties properties;

    public AdlController(AdlService adlService, AdlProperties properties) {
        this.adlService = adlService;
        this.properties = properties;
    }

    @GetMapping("/queue")
    public AdlQueueQueryResponse queue(@RequestParam String asset,
                                       @RequestParam(defaultValue = "100") int limit) {
        try {
            return adlService.queue(asset, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/events")
    public AdlEventQueryResponse events(@RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String asset,
                                        @RequestParam(required = false) String symbol,
                                        @RequestParam(defaultValue = "100") int limit) {
        try {
            return adlService.events(userId, asset, symbol, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/queue")
    public AdlQueueQueryResponse adminQueue(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                            @RequestParam String asset,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return adlService.queue(asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/events")
    public AdlEventQueryResponse adminEvents(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                             @RequestParam(required = false) Long userId,
                                             @RequestParam(required = false) String asset,
                                             @RequestParam(required = false) String symbol,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return adlService.events(userId, asset, symbol, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfig();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        if (request.scannerEnabled() != null) {
            properties.getScanner().setEnabled(request.scannerEnabled());
        }
        if (request.scanDelayMs() != null) {
            properties.getScanner().setScanDelayMs(nonNegative(request.scanDelayMs(), "scanDelayMs"));
        }
        if (request.minDeficitAgeMs() != null) {
            properties.getScanner().setMinDeficitAgeMs(nonNegative(request.minDeficitAgeMs(), "minDeficitAgeMs"));
        }
        if (request.maxMarkAgeMs() != null) {
            properties.getScanner().setMaxMarkAgeMs(nonNegative(request.maxMarkAgeMs(), "maxMarkAgeMs"));
        }
        if (request.batchSize() != null) {
            properties.getScanner().setBatchSize(bounded(request.batchSize(), 1, 10_000, "batchSize"));
        }
        if (request.maxDeleveragesPerDeficit() != null) {
            properties.getScanner().setMaxDeleveragesPerDeficit(bounded(request.maxDeleveragesPerDeficit(), 1, 1_000, "maxDeleveragesPerDeficit"));
        }
        if (request.candidateMultiplier() != null) {
            properties.getScanner().setCandidateMultiplier(bounded(request.candidateMultiplier(), 1, 1_000, "candidateMultiplier"));
        }
        return runtimeConfig();
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> scanner = new LinkedHashMap<>();
        scanner.put("enabled", properties.getScanner().isEnabled());
        scanner.put("scanDelayMs", properties.getScanner().getScanDelayMs());
        scanner.put("minDeficitAgeMs", properties.getScanner().getMinDeficitAgeMs());
        scanner.put("maxMarkAgeMs", properties.getScanner().getMaxMarkAgeMs());
        scanner.put("batchSize", properties.getScanner().getBatchSize());
        scanner.put("maxDeleveragesPerDeficit", properties.getScanner().getMaxDeleveragesPerDeficit());
        scanner.put("candidateMultiplier", properties.getScanner().getCandidateMultiplier());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("scanner", scanner);
        return response;
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be non-negative");
        }
        return value;
    }

    private int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin user header is required");
        }
    }

    public record RuntimeConfigUpdate(
            Boolean scannerEnabled,
            Long scanDelayMs,
            Long minDeficitAgeMs,
            Long maxMarkAgeMs,
            Integer batchSize,
            Integer maxDeleveragesPerDeficit,
            Integer candidateMultiplier) {
    }
}
