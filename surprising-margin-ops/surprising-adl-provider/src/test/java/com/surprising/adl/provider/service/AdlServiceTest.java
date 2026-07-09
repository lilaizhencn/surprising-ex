package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.adl.api.model.AdminCursorPage;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.repository.AdlRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdlServiceTest {

    @Test
    void processResidualDeficitsDoesNothingWhenScannerIsDisabled() {
        AdlProperties properties = new AdlProperties();
        properties.getScanner().setEnabled(false);
        FakeAdlRepository repository = new FakeAdlRepository();
        AdlService service = new AdlService(properties, repository);

        service.processResidualDeficits();

        assertThat(repository.claimCalls).isZero();
        assertThat(repository.executions).isZero();
    }

    @Test
    void processResidualDeficitsClaimsWithConfiguredScannerLimitsWhenEnabled() {
        AdlProperties properties = new AdlProperties();
        properties.getScanner().setBatchSize(3);
        properties.getScanner().setMinDeficitAgeMs(2500L);
        FakeAdlRepository repository = new FakeAdlRepository();
        AdlService service = new AdlService(properties, repository);

        service.processResidualDeficits();

        assertThat(repository.claimCalls).isEqualTo(1);
        assertThat(repository.lastBatchSize).isEqualTo(3);
        assertThat(repository.lastMinAge).isEqualTo(Duration.ofMillis(2500L));
    }

    @Test
    void adminQueueExposesRankingCursorMetadata() {
        FakeAdlRepository repository = new FakeAdlRepository();
        repository.queueRows.add(new AdlCandidate(1001L, "USDT", "BTC-USDT", AdlSide.LONG,
                10L, 10L, 50_000L, 55_000L, 5_000L, 550_000L,
                50_000L, 100_000L, 500_000L, 1_000_000L, 900_000L));
        repository.queueRows.add(new AdlCandidate(1002L, "USDT", "ETH-USDT", AdlSide.LONG,
                10L, 10L, 3_000L, 3_500L, 500L, 35_000L,
                5_000L, 10_000L, 500_000L, 1_000_000L, 800_000L));
        AdlService service = new AdlService(new AdlProperties(), repository);

        var firstPage = service.queue("usdt", 1, null, null);
        var secondPage = service.queue("USDT", 1, firstPage.nextCursor(), null);

        assertThat(repository.lastAsset).isEqualTo("USDT");
        assertThat(repository.lastLimit).isEqualTo(5000);
        assertThat(firstPage.positions()).singleElement().satisfies(position -> {
            assertThat(position.userId()).isEqualTo(1001L);
            assertThat(position.priorityScorePpm()).isEqualTo(900_000L);
        });
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.sort()).isEqualTo("priorityScorePpm.desc");
        assertThat(secondPage.positions()).singleElement()
                .satisfies(position -> assertThat(position.userId()).isEqualTo(1002L));
    }

    @Test
    void adminEventsExposeCursorMetadata() {
        FakeAdlRepository repository = new FakeAdlRepository();
        AdlEventResponse event = new AdlEventResponse(7001L, 1001L, 1002L, "USDT",
                "BTC-USDT", AdlSide.LONG, 10L, 50_000L, 55_000L,
                1_000L, 500L, 500L, 500L, 900_000L, "ADL_DEFICIT_COVERAGE",
                Instant.parse("2026-07-01T00:00:00Z"));
        repository.eventPage = new AdminCursorPage.CursorPage<>(List.of(event), "next-events",
                true, "createdAt.desc", 50);
        AdlService service = new AdlService(new AdlProperties(), repository);

        var response = service.events(1001L, "usdt", "btc-usdt", 50, "cursor-events", "createdAt.desc");

        assertThat(repository.lastEventsUserId).isEqualTo(1001L);
        assertThat(repository.lastEventsAsset).isEqualTo("USDT");
        assertThat(repository.lastEventsSymbol).isEqualTo("BTC-USDT");
        assertThat(repository.lastEventsCursor).isEqualTo("cursor-events");
        assertThat(response.events()).containsExactly(event);
        assertThat(response.nextCursor()).isEqualTo("next-events");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.limit()).isEqualTo(50);
    }

    private static final class FakeAdlRepository extends AdlRepository {
        private int claimCalls;
        private int executions;
        private int lastBatchSize;
        private Duration lastMinAge;
        private final List<AdlCandidate> queueRows = new ArrayList<>();
        private String lastAsset;
        private int lastLimit;
        private Long lastEventsUserId;
        private String lastEventsAsset;
        private String lastEventsSymbol;
        private String lastEventsCursor;
        private String lastEventsSort;
        private AdminCursorPage.CursorPage<AdlEventResponse> eventPage =
                new AdminCursorPage.CursorPage<>(List.of(), null, false, "createdAt.desc", 0);

        private FakeAdlRepository() {
            super(null);
        }

        @Override
        public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
            claimCalls++;
            lastBatchSize = batchSize;
            lastMinAge = minAge;
            return List.of();
        }

        @Override
        public List<AdlCandidate> queue(String asset, long excludedUserId, int limit, Duration maxMarkAge) {
            lastAsset = asset;
            lastLimit = limit;
            return queueRows.stream().limit(limit).toList();
        }

        @Override
        public Optional<AdlCandidate> lockCandidate(long userId, String symbol, String asset, Duration maxMarkAge) {
            return Optional.empty();
        }

        @Override
        public long executeAdl(DeficitRow deficit, AdlCandidate candidate, long remainingDeficitUnits) {
            executions++;
            return remainingDeficitUnits;
        }

        @Override
        public List<AdlEventResponse> events(Long userId, String asset, String symbol, int limit) {
            return List.of();
        }

        @Override
        public AdminCursorPage.CursorPage<AdlEventResponse> eventsPage(Long userId,
                                                                       String asset,
                                                                       String symbol,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
            lastEventsUserId = userId;
            lastEventsAsset = asset;
            lastEventsSymbol = symbol;
            lastEventsCursor = cursor;
            lastEventsSort = sort;
            return eventPage;
        }
    }
}
