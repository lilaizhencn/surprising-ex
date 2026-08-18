package com.surprising.adl.provider.service;

import com.surprising.adl.api.model.AdminCursorPage;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlQueuePositionResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.CoreAdlLiquidationProjection;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.repository.AdlSequenceRepository;
import com.surprising.adl.provider.repository.CoreAdlProjectionRepository;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.client.AeronLifecycleCoordinator;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.trading.api.model.PositionSide;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdlService {

    private final AdlProperties properties;
    private final CoreAdlProjectionRepository projections;
    private final AdlAeronGateway aeron;
    private final AdlEventRepository events;
    private final AdlSequenceRepository sequences;
    private final AeronLifecycleCoordinator lifecycleCoordinator = AeronLifecycleCoordinator.shared();
    public AdlService(AdlProperties properties, CoreAdlProjectionRepository projections,
                      AdlAeronGateway aeron, AdlEventRepository events, AdlSequenceRepository sequences) {
        this.properties = properties;
        this.projections = projections;
        this.aeron = aeron;
        this.events = events;
        this.sequences = sequences;
    }

    public synchronized AdlCycle processResidualDeficits() {
        return lifecycleCoordinator.execute(this::processResidualDeficitsInternal);
    }

    private AdlCycle processResidualDeficitsInternal() {
        if (!properties.getScanner().isEnabled()) return AdlCycle.disabled();
        CoreLiquidationWorkView work = aeron.resolutionWork(CoreLiquidationWorkView.Purpose.ADL, 0,
                properties.getScanner().getBatchSize(), 1_048_576);
        requireOwnedWork(work);
        int executed = 0;
        for (CoreLiquidationWorkView.Resolution resolution : work.resolutions()) {
            executed += process(new CoreAdlLiquidationProjection(resolution.liquidationId(), resolution.userId(),
                    resolution.symbol(), resolution.asset(), resolution.signedQuantitySteps(),
                    resolution.deficitUnits()));
        }
        return new AdlCycle(true, work.resolutions().size(), executed);
    }

    private void requireOwnedWork(CoreLiquidationWorkView work) {
        if (work.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("Core ADL work ProductLine mismatch");
        }
        if (!work.actions().isEmpty() || work.resolutions().stream()
                .anyMatch(value -> value.purpose() != CoreLiquidationWorkView.Purpose.ADL)) {
            throw new IllegalStateException("Core ADL authority mismatch");
        }
    }

    private int process(CoreAdlLiquidationProjection liquidation) {
        long remaining = liquidation.deficitUnits();
        int max = Math.max(1, properties.getScanner().getMaxDeleveragesPerDeficit());
        int candidateLimit = Math.min(1000, max * Math.max(1, properties.getScanner().getCandidateMultiplier()));
        int executed = 0;
        for (CoreAdlCandidateView candidate : aeron.candidates(liquidation.asset(), candidateLimit)) {
            if (remaining <= 0 || executed >= max) break;
            if (candidate.userId() == liquidation.userId()
                    || Long.signum(candidate.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
                continue;
            }
            long closeSteps = AdlMath.closeStepsForCover(remaining,
                    Math.absExact(candidate.signedQuantitySteps()), candidate.unrealizedProfitUnits());
            long realized = AdlMath.proportionalUnits(candidate.unrealizedProfitUnits(), closeSteps,
                    Math.absExact(candidate.signedQuantitySteps()));
            long covered = Math.min(remaining, realized);
            if (closeSteps <= 0 || covered <= 0) continue;
            UUID commandId = UUID.nameUUIDFromBytes((properties.getKafka().getProductLine() + ":ADL:"
                    + liquidation.liquidationId() + ':' + candidate.userId() + ':' + candidate.symbol() + ':'
                    + candidate.markPriceSequence() + ':' + closeSteps).getBytes(StandardCharsets.UTF_8));
            aeron.execute(commandId, TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                    liquidation.liquidationId(), candidate.userId(), candidate.symbol(), candidate.marginMode(),
                    candidate.positionSide(), candidate.signedQuantitySteps(), candidate.entryPriceTicks(),
                    candidate.markPriceSequence(), closeSteps, covered)));
            remaining = Math.subtractExact(remaining, covered);
            executed++;
        }
        return executed;
    }

    public AdlQueueQueryResponse queue(String asset, int limit) {
        return queue(asset, limit, null, null);
    }

    public AdlQueueQueryResponse queue(String asset, int limit, String cursor, String sort) {
        if (sort != null && !sort.isBlank() && !"priorityScorePpm.desc".equals(sort.trim())) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        int safeLimit = normalizeLimit(limit);
        CoreAdlCandidateView after = decodeCursor(cursor);
        List<CoreAdlCandidateView> fetched = aeron.candidates(normalizeAsset(asset), 1000).stream()
                .filter(value -> after == null || isAfter(value, after)).limit(safeLimit + 1L).toList();
        boolean hasMore = fetched.size() > safeLimit;
        List<CoreAdlCandidateView> page = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<AdlQueuePositionResponse> positions = page.stream().map(this::response).toList();
        String next = hasMore ? encodeCursor(page.getLast()) : null;
        return new AdlQueueQueryResponse(positions.size(), positions, next, hasMore,
                "priorityScorePpm.desc", safeLimit);
    }

    public AdlEventQueryResponse events(Long userId, String asset, String symbol, int limit) {
        return events(userId, asset, symbol, limit, null, null);
    }

    public AdlEventQueryResponse events(Long userId, String asset, String symbol, int limit,
                                        String cursor, String sort) {
        AdminCursorPage.CursorPage<AdlEventResponse> page = events.page(accountType(), userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset),
                symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(),
                normalizeLimit(limit), cursor, sort);
        return new AdlEventQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    private AdlQueuePositionResponse response(CoreAdlCandidateView value) {
        return new AdlQueuePositionResponse(value.userId(), value.asset(), value.symbol(),
                positionSide(value.positionSide()), value.signedQuantitySteps() > 0 ? AdlSide.LONG : AdlSide.SHORT,
                value.signedQuantitySteps(), value.entryPriceTicks(), value.markPriceTicks(), value.notionalUnits(),
                value.unrealizedProfitUnits(), value.marginUnits(), value.profitRatePpm(),
                value.effectiveLeveragePpm(), value.priorityScorePpm());
    }

    private static boolean isAfter(CoreAdlCandidateView value, CoreAdlCandidateView cursor) {
        return value.priorityScorePpm() < cursor.priorityScorePpm()
                || value.priorityScorePpm() == cursor.priorityScorePpm()
                && (value.unrealizedProfitUnits() < cursor.unrealizedProfitUnits()
                || value.unrealizedProfitUnits() == cursor.unrealizedProfitUnits()
                && (value.userId() > cursor.userId()
                || value.userId() == cursor.userId() && value.symbol().compareTo(cursor.symbol()) > 0));
    }

    private static String encodeCursor(CoreAdlCandidateView value) {
        String raw = value.priorityScorePpm() + ":" + value.unrealizedProfitUnits() + ":"
                + value.userId() + ":" + value.symbol();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static CoreAdlCandidateView decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 4);
            if (parts.length != 4) throw new IllegalArgumentException("invalid cursor");
            return new CoreAdlCandidateView(Long.parseLong(parts[2]), parts[3], "USDT", CoreMarginMode.CROSS,
                    CorePositionSide.NET, 1, 1, 1, 1, 1, Long.parseLong(parts[1]), 1, 1, 1,
                    Long.parseLong(parts[0]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid cursor", exception);
        }
    }

    private static PositionSide positionSide(CorePositionSide side) {
        return switch (side) { case NET -> PositionSide.NET; case LONG -> PositionSide.LONG; case SHORT -> PositionSide.SHORT; };
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1,1000]");
        return limit;
    }

    private String normalizeAsset(String asset) {
        if (asset == null || !asset.trim().toUpperCase().matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset");
        }
        return asset.trim().toUpperCase();
    }

    private String accountType() {
        return properties.getKafka().getAccountType();
    }

    public record AdlCycle(boolean enabled, int resolutions, int executed) {
        static AdlCycle disabled() {
            return new AdlCycle(false, 0, 0);
        }
    }
}
