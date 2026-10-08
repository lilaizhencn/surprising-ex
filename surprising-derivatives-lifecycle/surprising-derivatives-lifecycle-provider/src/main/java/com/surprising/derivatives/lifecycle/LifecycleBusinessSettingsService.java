package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 数据库是配置权威；完整校验、原子版本保存成功后才安装新的进程快照。 */
@Service
public class LifecycleBusinessSettingsService {
    public record Settings(long version, LifecycleBusinessSettings settings, String updatedBy, String reason, Instant updatedAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String line;
    private final FundingProperties funding;
    private final LiquidationProperties liquidation;
    private final InsuranceProperties insurance;
    private final AdlProperties adl;
    private volatile Settings current;
    private final ApplicationEventPublisher events;

    public LifecycleBusinessSettingsService(JdbcTemplate jdbc, ObjectMapper json, RiskProperties risk,
            ObjectProvider<FundingProperties> funding, LiquidationProperties liquidation,
            InsuranceProperties insurance, AdlProperties adl, ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc; this.json = json; this.line = risk.getProductLine().name();
        this.funding = funding.getIfAvailable(); this.liquidation = liquidation;
        this.insurance = insurance; this.adl = adl;
    }

    @PostConstruct
    public void initialize() {
        jdbc.update("""
                INSERT INTO lifecycle_business_settings(product_line, settings, version, updated_by, reason)
                VALUES (?,?::jsonb,1,'SYSTEM','initial lifecycle business settings') ON CONFLICT DO NOTHING
                """, line, json.writeValueAsString(LifecycleBusinessSettings.initial()));
        reload();
    }

    public Settings current() {
        var value = current;
        if (value == null) throw new IllegalStateException("lifecycle business settings are not initialized");
        return value;
    }

    public synchronized Settings save(LifecycleBusinessSettings settings, Long expectedVersion, String admin, String reason) {
        if (settings == null || expectedVersion == null || expectedVersion < 1)
            throw new IllegalArgumentException("complete settings and expectedVersion are required");
        if (admin == null || !admin.matches("[1-9][0-9]*") || reason == null || reason.isBlank() || reason.length() > 1_000)
            throw new IllegalArgumentException("administrator and reason (1-1000 characters) are required");
        if (current().version() != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "lifecycle settings changed; reload before saving");
        var rows = jdbc.query("""
                UPDATE lifecycle_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING version,settings,updated_by,reason,updated_at
                """, (rs, row) -> new Settings(rs.getLong("version"), json.readValue(rs.getString("settings"), LifecycleBusinessSettings.class),
                        rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant()),
                json.writeValueAsString(settings), admin, reason.trim(), line, expectedVersion);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "lifecycle settings changed; reload before saving");
        install(rows.getFirst());
        return current;
    }

    @Scheduled(fixedDelay = 1_000)
    public synchronized void reload() {
        var value = jdbc.queryForObject("SELECT version,settings,updated_by,reason,updated_at FROM lifecycle_business_settings WHERE product_line=?",
                (rs, row) -> new Settings(rs.getLong("version"), json.readValue(rs.getString("settings"), LifecycleBusinessSettings.class),
                        rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant()), line);
        if (value == null) throw new IllegalStateException("lifecycle business settings are missing");
        if (current == null || value.version() > current.version()) install(value);
    }

    private void install(Settings value) {
        var settings = value.settings();
        var calculation = new FundingProperties.Calculation();
        var settlement = new FundingProperties.Settlement();
        var coordination = new FundingProperties.Coordination();
        var f = settings.funding();
        calculation.setEnabled(f.calculationEnabled()); calculation.setPublishDelayMs(f.publishDelayMs());
        calculation.setMaxMarkAge(Duration.ofMillis(f.maxMarkAgeMs()));
        calculation.setMaxRateAge(Duration.ofMillis(f.maxRateAgeMs()));
        settlement.setEnabled(f.settlementEnabled()); settlement.setSettleDelayMs(f.settleDelayMs());
        settlement.setBatchSize(f.batchSize()); settlement.setMaxPagesPerRun(f.maxPagesPerRun());
        coordination.setEnabled(f.coordinationEnabled()); coordination.setLeaseDuration(Duration.ofMillis(f.leaseDurationMs()));
        if (funding != null) coordination.setNodeId(funding.getCoordination().getNodeId());
        var execution = new LiquidationProperties.Execution();
        var coordinator = new LiquidationProperties.Coordinator();
        var l = settings.liquidation();
        execution.setEnabled(l.enabled()); execution.setLiquidationFeeRatePpm(l.feeRatePpm());
        coordinator.setDelayMs(l.delayMs()); coordinator.setWorkBatchSize(l.workBatchSize());
        coordinator.setMaxPagesPerRun(l.maxPagesPerRun()); coordinator.setMaxWorkBytes(l.maxWorkBytes());
        var coverage = new InsuranceProperties.Coverage();
        coverage.setEnabled(settings.insurance().enabled()); coverage.setScanDelayMs(settings.insurance().scanDelayMs());
        coverage.setBatchSize(settings.insurance().batchSize());
        var scanner = new AdlProperties.Scanner();
        var a = settings.adl();
        scanner.setEnabled(a.enabled()); scanner.setScanDelayMs(a.scanDelayMs()); scanner.setBatchSize(a.batchSize());
        scanner.setMaxDeleveragesPerDeficit(a.maxDeleveragesPerDeficit()); scanner.setCandidateMultiplier(a.candidateMultiplier());
        if (funding != null) {
            funding.setCalculation(calculation); funding.setSettlement(settlement); funding.setCoordination(coordination);
        }
        liquidation.setExecution(execution); liquidation.setCoordinator(coordinator);
        insurance.setCoverage(coverage); adl.setScanner(scanner);
        current = value;
        events.publishEvent(value);
    }
}
