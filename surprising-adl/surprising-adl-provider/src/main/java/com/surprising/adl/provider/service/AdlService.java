package com.surprising.adl.provider.service;

import com.surprising.adl.api.model.AdminCursorPage;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueuePositionResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.repository.AdlRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
        int capped = normalizeLimit(limit);
        var positions = adlRepository.queue(normalizeAsset(asset), capped, maxMarkAge()).stream()
                .map(this::toQueuePosition)
                .toList();
        return new AdlQueueQueryResponse(positions.size(), positions);
    }

    public AdlQueueQueryResponse queue(String asset, int limit, String cursor, String sort) {
        int capped = normalizeLimit(limit);
        QueueSortSpec sortSpec = parseQueueSort(sort);
        QueueCursor decodedCursor = decodeQueueCursor(cursor);
        List<AdlQueuePositionResponse> fetched = adlRepository.queue(normalizeAsset(asset), 5000, maxMarkAge())
                .stream()
                .map(this::toQueuePosition)
                .sorted(queueComparator(sortSpec))
                .filter(position -> decodedCursor == null || afterQueueCursor(position, decodedCursor, sortSpec))
                .limit(capped + 1L)
                .toList();
        boolean hasMore = fetched.size() > capped;
        List<AdlQueuePositionResponse> positions = hasMore
                ? List.copyOf(fetched.subList(0, capped))
                : List.copyOf(fetched);
        String nextCursor = hasMore && !positions.isEmpty()
                ? encodeQueueCursor(positions.get(positions.size() - 1))
                : null;
        return new AdlQueueQueryResponse(positions.size(), positions, nextCursor, hasMore, sortSpec.token(), capped);
    }

    public AdlEventQueryResponse events(Long userId, String asset, String symbol, int limit) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = normalizeLimit(limit);
        var rows = adlRepository.events(userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset),
                symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol),
                capped);
        return new AdlEventQueryResponse(rows.size(), rows);
    }

    public AdlEventQueryResponse events(Long userId,
                                        String asset,
                                        String symbol,
                                        int limit,
                                        String cursor,
                                        String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        AdminCursorPage.CursorPage<AdlEventResponse> page = adlRepository.eventsPage(userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset),
                symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol),
                normalizeLimit(limit), cursor, sort);
        return new AdlEventQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
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
            var locked = adlRepository.lockCandidate(candidate.userId(), candidate.symbol(), candidate.marginMode(),
                    candidate.positionSide(), deficit.asset(), maxMarkAge());
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

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return limit;
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

    private AdlQueuePositionResponse toQueuePosition(AdlCandidate candidate) {
        return new AdlQueuePositionResponse(candidate.userId(), candidate.asset(),
                candidate.symbol(), candidate.positionSide(), candidate.side(), candidate.signedQuantitySteps(),
                candidate.entryPriceTicks(), candidate.markPriceTicks(), candidate.notionalUnits(),
                candidate.unrealizedProfitUnits(), candidate.marginUnits(), candidate.profitRatePpm(),
                candidate.effectiveLeveragePpm(), candidate.priorityScorePpm());
    }

    private QueueSortSpec parseQueueSort(String value) {
        if (value == null || value.isBlank() || "priorityScorePpm.desc".equals(value.trim())) {
            return new QueueSortSpec("priorityScorePpm.desc");
        }
        throw new IllegalArgumentException("unsupported sort: " + value);
    }

    private Comparator<AdlQueuePositionResponse> queueComparator(QueueSortSpec sortSpec) {
        return Comparator.comparingLong(AdlQueuePositionResponse::priorityScorePpm).reversed()
                .thenComparing(Comparator.comparingLong(AdlQueuePositionResponse::unrealizedProfitUnits).reversed())
                .thenComparingLong(AdlQueuePositionResponse::userId)
                .thenComparing(AdlQueuePositionResponse::symbol)
                .thenComparing(position -> position.positionSide().name());
    }

    private boolean afterQueueCursor(AdlQueuePositionResponse position,
                                     QueueCursor cursor,
                                     QueueSortSpec sortSpec) {
        int priority = Long.compare(position.priorityScorePpm(), cursor.priorityScorePpm());
        if (priority != 0) {
            return priority < 0;
        }
        int profit = Long.compare(position.unrealizedProfitUnits(), cursor.unrealizedProfitUnits());
        if (profit != 0) {
            return profit < 0;
        }
        int user = Long.compare(position.userId(), cursor.userId());
        if (user != 0) {
            return user > 0;
        }
        int symbol = position.symbol().compareTo(cursor.symbol());
        if (symbol != 0) {
            return symbol > 0;
        }
        return position.positionSide().name().compareTo(cursor.positionSide()) > 0;
    }

    private QueueCursor decodeQueueCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 5);
            if (parts.length != 4 && parts.length != 5) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new QueueCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), parts[3], parts.length == 5 ? parts[4] : "NET");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cursor", ex);
        }
    }

    private String encodeQueueCursor(AdlQueuePositionResponse position) {
        String raw = position.priorityScorePpm() + ":" + position.unrealizedProfitUnits()
                + ":" + position.userId() + ":" + position.symbol() + ":" + position.positionSide().name();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record QueueSortSpec(String token) {
    }

    private record QueueCursor(long priorityScorePpm,
                               long unrealizedProfitUnits,
                               long userId,
                               String symbol,
                               String positionSide) {
    }
}
