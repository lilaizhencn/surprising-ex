package com.surprising.price.probe;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotWebSocketManager;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.service.LatestIndexPriceCache;
import com.surprising.price.mark.service.MarkPriceQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class MarketProbeService {

    private static final int CADENCE_WINDOW_CAPACITY = 64;
    private final LatestIndexPriceCache indexPriceCache;
    private final MarkPriceQueryService markPriceQueryService;
    private final ExternalSpotWebSocketManager webSocketManager;
    private final IndexPriceProperties indexPriceProperties;
    private final ConcurrentMap<String, CadenceWindows> cadenceBySymbol = new ConcurrentHashMap<>();

    public MarketProbeService(LatestIndexPriceCache indexPriceCache,
                              MarkPriceQueryService markPriceQueryService,
                              ExternalSpotWebSocketManager webSocketManager,
                              IndexPriceProperties indexPriceProperties) {
        this.indexPriceCache = indexPriceCache;
        this.markPriceQueryService = markPriceQueryService;
        this.webSocketManager = webSocketManager;
        this.indexPriceProperties = indexPriceProperties;
    }

    public MarketProbeSnapshot snapshot(String symbol) {
        return snapshot(symbol, SourceMode.OKX_PUBLIC_WEBSOCKET_ONLY);
    }

    public MarketProbeSnapshot snapshot(String symbol, SourceMode sourceMode) {
        Instant observedAt = Instant.now();
        IndexPriceResponse index = indexPriceCache.requireFresh(symbol);
        MarkPriceResponse mark = markPriceQueryService.latest(symbol);
        List<ExternalSpotWebSocketManager.WebSocketHealth> webSockets = webSocketManager.health();
        List<SourceHealth> sourceHealth = sourceHealth(index.components(), webSockets);
        int freshSourceCount = freshSourceCount(index.components(), sourceHealth, sourceMode, observedAt);
        CadenceWindows cadence = cadenceBySymbol.computeIfAbsent(index.symbol(), ignored -> new CadenceWindows());
        CadenceSummary indexCadence = cadence.index().observe(index.eventTime(), observedAt,
                indexPriceProperties.getCalculation().getMaxSourceAge());
        CadenceSummary markCadence = cadence.mark().observe(mark.eventTime(), observedAt,
                indexPriceProperties.getCalculation().getMaxSourceAge());
        return new MarketProbeSnapshot(observedAt, index, mark, sourceMode, freshSourceCount,
                freshSourceCount >= 3, indexCadence.timestampRegressed() || markCadence.timestampRegressed(),
                sourceHealth, indexCadence, markCadence, webSockets);
    }

    private int freshSourceCount(List<IndexComponentSnapshot> components, List<SourceHealth> sourceHealth,
                                 SourceMode sourceMode, Instant observedAt) {
        Duration maxAge = indexPriceProperties.getCalculation().getMaxSourceAge();
        return (int) components.stream()
                .filter(component -> component.status() == SourceStatus.HEALTHY)
                .filter(component -> component.receivedAt() != null)
                .filter(component -> !component.receivedAt().isAfter(observedAt.plusSeconds(1)))
                .filter(component -> !component.receivedAt().isBefore(observedAt.minus(maxAge)))
                .filter(sourceMode::matches)
                .filter(component -> component.transport() != QuoteTransport.PUBLIC_WEBSOCKET
                        || sourceHealth.stream().anyMatch(health -> health.exchange().equalsIgnoreCase(component.source())
                        && health.connected() && health.frameAgeMillis() <= maxAge.toMillis()))
                .count();
    }

    private List<SourceHealth> sourceHealth(List<IndexComponentSnapshot> components,
                                             List<ExternalSpotWebSocketManager.WebSocketHealth> webSockets) {
        return components.stream().map(component -> webSockets.stream()
                        .filter(socket -> socket.sources().stream().anyMatch(
                                source -> source.exchange().equalsIgnoreCase(component.source())))
                        .findFirst()
                        .map(socket -> new SourceHealth(component.source(), transportName(component.transport()),
                                socket.connected(), socket.frameAgeMillis()))
                        .orElseGet(() -> new SourceHealth(component.source(), transportName(component.transport()),
                                false, Long.MAX_VALUE)))
                .toList();
    }

    private String transportName(QuoteTransport transport) {
        return transport == null ? "MISSING" : transport.name();
    }

    public record MarketProbeSnapshot(Instant observedAt, IndexPriceResponse index, MarkPriceResponse mark,
                                      SourceMode sourceMode, int freshSourceCount, boolean sourceQuorumHealthy,
                                      boolean timestampRegressed,
                                      List<SourceHealth> sourceHealth, CadenceSummary indexCadence,
                                      CadenceSummary markCadence,
                                      List<ExternalSpotWebSocketManager.WebSocketHealth> webSockets) {
    }

    public enum SourceMode {
        OKX_PUBLIC_WEBSOCKET_ONLY {
            @Override
            boolean matches(IndexComponentSnapshot component) {
                return "OKX".equalsIgnoreCase(component.source())
                        && component.transport() == QuoteTransport.PUBLIC_WEBSOCKET;
            }
        },
        PUBLIC_WEBSOCKET_ONLY {
            @Override
            boolean matches(IndexComponentSnapshot component) {
                return component.transport() == QuoteTransport.PUBLIC_WEBSOCKET;
            }
        };

        abstract boolean matches(IndexComponentSnapshot component);
    }

    public record SourceHealth(String exchange, String transport, boolean connected, long frameAgeMillis) {
    }

    public record CadenceSummary(int sampleCount, Long intervalP50Millis, Long intervalP99Millis,
                                 Long jitterMillis, boolean stale, boolean timestampRegressed) {
    }

    private static class CadenceWindows {
        private final CadenceWindow index = new CadenceWindow();
        private final CadenceWindow mark = new CadenceWindow();

        private CadenceWindow index() {
            return index;
        }

        private CadenceWindow mark() {
            return mark;
        }
    }

    private static class CadenceWindow {
        private final ArrayDeque<Long> intervals = new ArrayDeque<>();
        private Instant lastEventTime;

        private synchronized CadenceSummary observe(Instant eventTime, Instant observedAt, Duration maxAge) {
            boolean stale = eventTime == null || eventTime.isBefore(observedAt.minus(maxAge))
                    || eventTime.isAfter(observedAt.plusSeconds(1));
            boolean regressed = lastEventTime != null && eventTime != null && eventTime.isBefore(lastEventTime);
            if (eventTime != null && (lastEventTime == null || eventTime.isAfter(lastEventTime))) {
                if (lastEventTime != null) {
                    addInterval(Duration.between(lastEventTime, eventTime).toMillis());
                }
                lastEventTime = eventTime;
            }
            return summary(stale, regressed);
        }

        private void addInterval(long intervalMillis) {
            if (intervals.size() == CADENCE_WINDOW_CAPACITY) {
                intervals.removeFirst();
            }
            intervals.addLast(Math.max(0L, intervalMillis));
        }

        private CadenceSummary summary(boolean stale, boolean regressed) {
            if (intervals.isEmpty()) {
                return new CadenceSummary(0, null, null, null, stale, regressed);
            }
            List<Long> sorted = new ArrayList<>(intervals);
            sorted.sort(Comparator.naturalOrder());
            long p50 = percentile(sorted, 0.50d);
            long p99 = percentile(sorted, 0.99d);
            return new CadenceSummary(sorted.size(), p50, p99, p99 - p50, stale, regressed);
        }

        private long percentile(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }
}
