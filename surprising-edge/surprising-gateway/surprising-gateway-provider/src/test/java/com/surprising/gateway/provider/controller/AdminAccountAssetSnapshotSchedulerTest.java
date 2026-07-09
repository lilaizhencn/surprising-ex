package com.surprising.gateway.provider.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AdminAlertRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAccountAssetSnapshotSchedulerTest {

    @Test
    void skipsBeforeConfiguredUtcTime() {
        AdminAccountAssetSnapshotService snapshotService = mock(AdminAccountAssetSnapshotService.class);
        AdminAlertRepository alertRepository = mock(AdminAlertRepository.class);
        GatewayProperties properties = new GatewayProperties();
        properties.getReports().getAccountAssetSnapshots().setSnapshotHourUtc(1);
        properties.getReports().getAccountAssetSnapshots().setSnapshotMinuteUtc(0);
        AdminAccountAssetSnapshotScheduler scheduler = new AdminAccountAssetSnapshotScheduler(
                snapshotService, alertRepository, properties);

        scheduler.run(Instant.parse("2026-07-03T00:59:00Z"));

        verifyNoInteractions(snapshotService, alertRepository);
    }

    @Test
    void writesPriorDaySnapshotAndRaisesDiscrepancyAlert() {
        AdminAccountAssetSnapshotService snapshotService = mock(AdminAccountAssetSnapshotService.class);
        AdminAlertRepository alertRepository = mock(AdminAlertRepository.class);
        GatewayProperties properties = new GatewayProperties();
        var config = properties.getReports().getAccountAssetSnapshots();
        config.setSnapshotHourUtc(0);
        config.setSnapshotMinuteUtc(5);
        config.setSnapshotDateOffsetDays(1);
        config.setDiscrepancyThresholdPpm(100_000L);
        config.setDiscrepancyMinAbsoluteValue("100");
        config.setDiscrepancyLimit(10);
        LocalDate snapshotDate = LocalDate.parse("2026-07-02");
        LocalDate previousDate = LocalDate.parse("2026-07-01");
        when(snapshotService.snapshotExists(snapshotDate, "USDT")).thenReturn(false);
        when(snapshotService.snapshotExists(previousDate, "USDT")).thenReturn(true);
        when(snapshotService.writeSnapshot(snapshotDate, "USDT", null,
                "system:account-asset-snapshot-scheduler")).thenReturn(3);
        when(snapshotService.discrepancies(snapshotDate, previousDate, "USDT",
                100_000L, new BigDecimal("100"), 10)).thenReturn(List.of(
                new AdminAccountAssetSnapshotService.AccountAssetSnapshotDiscrepancy(
                        "BASIC", "USDT", new BigDecimal("1200"), new BigDecimal("1000"),
                        new BigDecimal("200"), 200_000L)));
        AdminAccountAssetSnapshotScheduler scheduler = new AdminAccountAssetSnapshotScheduler(
                snapshotService, alertRepository, properties);

        scheduler.run(Instant.parse("2026-07-03T00:06:00Z"));

        verify(snapshotService).writeSnapshot(snapshotDate, "USDT", null,
                "system:account-asset-snapshot-scheduler");
        verify(alertRepository).upsertSystemAlert(
                eq(AdminAccountAssetSnapshotScheduler.RULE_CODE),
                eq(AdminAccountAssetSnapshotScheduler.METRIC_KEY),
                eq("BASIC:USDT"),
                eq("WARN"),
                eq(BigDecimal.valueOf(100_000L)),
                eq(BigDecimal.valueOf(200_000L)),
                any(),
                eq(Instant.parse("2026-07-03T00:06:00Z")));
        verify(alertRepository).resolveSystemAlerts(
                eq(AdminAccountAssetSnapshotScheduler.RULE_CODE),
                eq(Set.of("BASIC:USDT")),
                eq(Instant.parse("2026-07-03T00:06:00Z")));
    }

    @Test
    void skipsWhenSnapshotAlreadyExists() {
        AdminAccountAssetSnapshotService snapshotService = mock(AdminAccountAssetSnapshotService.class);
        AdminAlertRepository alertRepository = mock(AdminAlertRepository.class);
        GatewayProperties properties = new GatewayProperties();
        LocalDate snapshotDate = LocalDate.parse("2026-07-02");
        when(snapshotService.snapshotExists(snapshotDate, "USDT")).thenReturn(true);
        AdminAccountAssetSnapshotScheduler scheduler = new AdminAccountAssetSnapshotScheduler(
                snapshotService, alertRepository, properties);

        scheduler.run(Instant.parse("2026-07-03T00:06:00Z"));

        verify(snapshotService, never()).writeSnapshot(any(), any(), any(), any());
        verifyNoInteractions(alertRepository);
    }
}
