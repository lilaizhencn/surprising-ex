package com.surprising.adl.provider.service;

import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueuePositionResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.repository.AdlRepository;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdlService {

    private final AdlProperties properties;
    private final AdlRepository adlRepository;

    public AdlService(AdlProperties properties, AdlRepository adlRepository) {
        this.properties = properties;
        this.adlRepository = adlRepository;
    }

    /**
     * ADL is intentionally delayed behind insurance coverage. If insurance has
     * balance for an asset, the scanner leaves the deficit to insurance first.
     */
    @Transactional
    @Scheduled(fixedDelayString = "${surprising.adl.scanner.scan-delay-ms:1000}")
    public void processResidualDeficits() {
        var scanner = properties.getScanner();
        if (!scanner.isEnabled()) {
            return;
        }
        List<DeficitRow> deficits = adlRepository.claimResidualDeficits(
                Math.max(1, scanner.getBatchSize()),
                Duration.ofMillis(Math.max(0L, scanner.getMinDeficitAgeMs())));
        for (DeficitRow deficit : deficits) {
            processDeficit(deficit);
        }
    }

    public AdlQueueQueryResponse queue(String asset, int limit) {
        int capped = Math.max(1, Math.min(1000, limit));
        var positions = adlRepository.queue(normalizeAsset(asset), capped, maxMarkAge()).stream()
                .map(candidate -> new AdlQueuePositionResponse(candidate.userId(), candidate.asset(),
                        candidate.symbol(), candidate.side(), candidate.signedQuantitySteps(),
                        candidate.entryPriceTicks(), candidate.markPriceTicks(), candidate.notionalUnits(),
                        candidate.unrealizedProfitUnits(), candidate.marginUnits(), candidate.profitRatePpm(),
                        candidate.effectiveLeveragePpm(), candidate.priorityScorePpm()))
                .toList();
        return new AdlQueueQueryResponse(positions.size(), positions);
    }

    public AdlEventQueryResponse events(Long userId, String asset, String symbol, int limit) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = Math.max(1, Math.min(1000, limit));
        var rows = adlRepository.events(userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset),
                symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol),
                capped);
        return new AdlEventQueryResponse(rows.size(), rows);
    }

    private void processDeficit(DeficitRow deficit) {
        var scanner = properties.getScanner();
        int maxDeleverages = Math.max(1, scanner.getMaxDeleveragesPerDeficit());
        int candidateLimit = Math.max(maxDeleverages,
                maxDeleverages * Math.max(1, scanner.getCandidateMultiplier()));
        long remaining = deficit.deficitUnits();
        int executions = 0;
        var candidates = adlRepository.queue(deficit.asset(), deficit.userId(), candidateLimit, maxMarkAge()).stream()
                .sorted(Comparator.comparingLong((com.surprising.adl.provider.model.AdlCandidate c) ->
                                c.priorityScorePpm()).reversed()
                        .thenComparing(Comparator.comparingLong(
                                com.surprising.adl.provider.model.AdlCandidate::unrealizedProfitUnits).reversed()))
                .toList();
        for (var candidate : candidates) {
            if (remaining <= 0 || executions >= maxDeleverages) {
                break;
            }
            var locked = adlRepository.lockCandidate(candidate.userId(), candidate.symbol(), deficit.asset(),
                    maxMarkAge());
            if (locked.isEmpty()) {
                continue;
            }
            remaining = adlRepository.executeAdl(deficit, locked.get(), remaining);
            executions++;
        }
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private Duration maxMarkAge() {
        return Duration.ofMillis(Math.max(1L, properties.getScanner().getMaxMarkAgeMs()));
    }

    private String normalizeSymbol(String symbol) {
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }
}
