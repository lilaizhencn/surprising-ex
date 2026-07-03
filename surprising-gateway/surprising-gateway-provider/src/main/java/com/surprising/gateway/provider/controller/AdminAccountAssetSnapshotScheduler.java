package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AdminAlertRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountAssetSnapshotScheduler {

    static final String RULE_CODE = "ACCOUNT_ASSET_SNAPSHOT_DIFF";
    static final String METRIC_KEY = "ACCOUNT_ASSET_SNAPSHOT_DIFF_PPM";
    private static final String SYSTEM_USERNAME = "system:account-asset-snapshot-scheduler";

    private static final Logger log = LoggerFactory.getLogger(AdminAccountAssetSnapshotScheduler.class);

    private final AdminAccountAssetSnapshotService snapshotService;
    private final AdminAlertRepository alertRepository;
    private final GatewayProperties properties;

    public AdminAccountAssetSnapshotScheduler(AdminAccountAssetSnapshotService snapshotService,
                                              AdminAlertRepository alertRepository,
                                              GatewayProperties properties) {
        this.snapshotService = snapshotService;
        this.alertRepository = alertRepository;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${surprising.gateway.reports.account-asset-snapshots.initial-delay-ms:15000}",
            fixedDelayString = "${surprising.gateway.reports.account-asset-snapshots.poll-delay-ms:60000}")
    public void scheduledRun() {
        run(Instant.now());
    }

    void run(Instant now) {
        GatewayProperties.AccountAssetSnapshots config = properties.getReports().getAccountAssetSnapshots();
        if (!config.isSchedulerEnabled()) {
            return;
        }
        LocalTime currentUtcTime = now.atOffset(ZoneOffset.UTC).toLocalTime();
        LocalTime scheduledTime = LocalTime.of(config.getSnapshotHourUtc(), config.getSnapshotMinuteUtc());
        if (currentUtcTime.isBefore(scheduledTime)) {
            return;
        }
        String valuationAsset = config.getValuationAsset();
        LocalDate snapshotDate = now.atOffset(ZoneOffset.UTC).toLocalDate()
                .minusDays(config.getSnapshotDateOffsetDays());
        if (snapshotService.snapshotExists(snapshotDate, valuationAsset)) {
            return;
        }
        LocalDate previousDate = snapshotDate.minusDays(1);
        boolean canCompare = snapshotService.snapshotExists(previousDate, valuationAsset);
        try {
            int writtenRows = snapshotService.writeSnapshot(snapshotDate, valuationAsset, null, SYSTEM_USERNAME);
            if (canCompare) {
                evaluateDiscrepancies(snapshotDate, previousDate, valuationAsset, config, now);
            }
            log.info("Generated scheduled account asset snapshot date={} valuationAsset={} rows={} compared={}",
                    snapshotDate, valuationAsset, writtenRows, canCompare);
        } catch (Exception ex) {
            log.warn("Failed to generate scheduled account asset snapshot date={} valuationAsset={} error={}",
                    snapshotDate, valuationAsset, ex.getMessage());
        }
    }

    private void evaluateDiscrepancies(LocalDate snapshotDate,
                                       LocalDate previousDate,
                                       String valuationAsset,
                                       GatewayProperties.AccountAssetSnapshots config,
                                       Instant now) {
        BigDecimal minAbsoluteValue = parseDecimal(config.getDiscrepancyMinAbsoluteValue());
        var discrepancies = snapshotService.discrepancies(snapshotDate, previousDate, valuationAsset,
                config.getDiscrepancyThresholdPpm(), minAbsoluteValue, config.getDiscrepancyLimit());
        Set<String> activeTargets = new LinkedHashSet<>();
        for (var discrepancy : discrepancies) {
            String target = discrepancy.accountType() + ":" + discrepancy.asset();
            activeTargets.add(target);
            alertRepository.upsertSystemAlert(
                    RULE_CODE,
                    METRIC_KEY,
                    target,
                    config.getDiscrepancySeverity(),
                    BigDecimal.valueOf(config.getDiscrepancyThresholdPpm()),
                    BigDecimal.valueOf(discrepancy.diffPpm()),
                    message(snapshotDate, previousDate, valuationAsset, discrepancy),
                    now);
        }
        alertRepository.resolveSystemAlerts(RULE_CODE, activeTargets, now);
    }

    private String message(LocalDate snapshotDate,
                           LocalDate previousDate,
                           String valuationAsset,
                           AdminAccountAssetSnapshotService.AccountAssetSnapshotDiscrepancy discrepancy) {
        return "Account asset snapshot discrepancy %s/%s %s %s vs %s: current=%s previous=%s diff=%s diffPpm=%d"
                .formatted(discrepancy.accountType(), discrepancy.asset(), valuationAsset,
                        snapshotDate, previousDate, discrepancy.currentValue(), discrepancy.previousValue(),
                        discrepancy.diffValue(), discrepancy.diffPpm());
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim()).abs();
        } catch (RuntimeException ex) {
            log.warn("Invalid account asset snapshot discrepancyMinAbsoluteValue={}, using 0", value);
            return BigDecimal.ZERO;
        }
    }
}
