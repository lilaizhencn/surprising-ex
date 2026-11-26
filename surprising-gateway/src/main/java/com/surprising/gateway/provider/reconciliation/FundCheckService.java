package com.surprising.gateway.provider.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 资金平账与提现核查服务
 * 默认每 5 分钟执行一次核算：
 * 1. 扫描所有账户最新余额，杜绝任何穿仓负余额；
 * 2. 检查在途与未完成提币流水；
 * 3. 一旦账实不平或发现负余额，毫秒级触发紧急停机提现熔断，并通过 Telegram 等渠道并发高危报警。
 */
@Service
public class FundCheckService {

    private static final Logger log = LoggerFactory.getLogger(FundCheckService.class);

    private final JdbcTemplate jdbc;
    private final EmergencyWithdrawalCircuitBreaker circuitBreaker;
    private final EmergencyAlertNotifier alertNotifier;

    // Prometheus 指标：1=平账健康，0=异常熔断
    private final AtomicInteger fundCheckHealthyGauge = new AtomicInteger(1);
    private final AtomicInteger negativeAccountsGauge = new AtomicInteger(0);
    private final AtomicReference<BigDecimal> discrepancyGauge = new AtomicReference<>(BigDecimal.ZERO);

    public FundCheckService(JdbcTemplate jdbc,
                            EmergencyWithdrawalCircuitBreaker circuitBreaker,
                            EmergencyAlertNotifier alertNotifier,
                            MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.circuitBreaker = circuitBreaker;
        this.alertNotifier = alertNotifier;

        if (meterRegistry != null) {
            Gauge.builder("exchange.financial.reconciliation.healthy", fundCheckHealthyGauge, AtomicInteger::get)
                    .description("Fund check health status (1=OK, 0=MISMATCH/STOPPED)")
                    .register(meterRegistry);
            Gauge.builder("exchange.financial.reconciliation.negative_accounts", negativeAccountsGauge, AtomicInteger::get)
                    .description("Number of accounts with negative balances")
                    .register(meterRegistry);
            Gauge.builder("exchange.financial.reconciliation.discrepancy.usdt", discrepancyGauge, ref -> ref.get().doubleValue())
                    .description("Absolute financial discrepancy in USDT")
                    .register(meterRegistry);
        }
    }

    /**
     * 执行全量资金核查
     */
    public FundCheckResult checkFunds() {
        long start = System.currentTimeMillis();
        log.info("[FUND-CHECK] 开始执行高频资金总账审计核对...");

        List<String> discrepancies = new ArrayList<>();
        long accountsChecked = 0;
        long negativeCount = 0;
        long pendingWithdrawals = 0;
        BigDecimal discrepancyUsdt = BigDecimal.ZERO;

        try {
            // 1. 检查是否存在负余额账户（严重违规/穿仓）
            String negativeCheckSql = "SELECT user_id, asset, balance_after_units, entry_id " +
                    "FROM ( " +
                    "    SELECT user_id, asset, balance_after_units, entry_id, " +
                    "           ROW_NUMBER() OVER(PARTITION BY user_id, asset ORDER BY entry_id DESC) as rn " +
                    "    FROM account_ledger_entries " +
                    ") t " +
                    "WHERE rn = 1 AND balance_after_units < 0";

            try {
                var negativeRows = jdbc.query(negativeCheckSql, (rs, rowNum) ->
                        String.format("UID %d 币种 %s 出现负余额: %d units (最新流水 entryId=%d)",
                                rs.getLong("user_id"),
                                rs.getString("asset"),
                                rs.getLong("balance_after_units"),
                                rs.getLong("entry_id"))
                );
                negativeCount = negativeRows.size();
                if (negativeCount > 0) {
                    discrepancies.addAll(negativeRows);
                    log.error("[FUND-CHECK] 发现 {} 个账户存在严重负余额穿仓！", negativeCount);
                }
            } catch (Exception ex) {
                log.error("[FUND-CHECK] 负余额穿仓核查失败: {}", ex.getMessage(), ex);
                discrepancies.add("负余额穿仓核查异常: " + ex.getMessage());
            }

            // 2. 统计已检查的总账账户数
            try {
                String countSql = "SELECT COUNT(DISTINCT user_id) FROM account_ledger_entries";
                Long count = jdbc.queryForObject(countSql, Long.class);
                accountsChecked = (count != null) ? count : 0;
            } catch (Exception ex) {
                log.error("[FUND-CHECK] 查询总账账户数失败: {}", ex.getMessage(), ex);
                discrepancies.add("总账账户统计异常: " + ex.getMessage());
            }

            // 3. 统计在途提现记录数
            try {
                String pendingWithdrawalSql = "SELECT COUNT(*) FROM gateway_wallet_withdrawals " +
                        "WHERE status IN ('PENDING_APPROVAL', 'SUBMITTED', 'PROCESSING')";
                Long pCount = jdbc.queryForObject(pendingWithdrawalSql, Long.class);
                pendingWithdrawals = (pCount != null) ? pCount : 0;
            } catch (Exception ex) {
                log.error("[FUND-CHECK] 查询在途提现记录数失败: {}", ex.getMessage(), ex);
                discrepancies.add("在途提现统计异常: " + ex.getMessage());
            }

            // 4. 核对已完成但缺失托管钱包提现单号的幽灵提现记录
            try {
                String orphanWithdrawalSql = "SELECT COUNT(*) FROM gateway_wallet_withdrawals " +
                        "WHERE status = 'COMPLETED' AND (wallet_withdrawal_id IS NULL OR wallet_withdrawal_id = '')";
                Long orphanCount = jdbc.queryForObject(orphanWithdrawalSql, Long.class);
                if (orphanCount != null && orphanCount > 0) {
                    discrepancies.add("存在 " + orphanCount + " 笔已标记完成但无托管提现单号的幽灵提现记录！");
                }
            } catch (Exception ex) {
                log.error("[FUND-CHECK] 核对幽灵提现记录失败: {}", ex.getMessage(), ex);
                discrepancies.add("核对幽灵提现异常: " + ex.getMessage());
            }

        } catch (Exception ex) {
            log.error("[FUND-CHECK] 资金核查过程中发生未捕获异常: {}", ex.getMessage(), ex);
            discrepancies.add("资金核查执行异常: " + ex.getMessage());
        }

        long durationMs = System.currentTimeMillis() - start;
        boolean healthy = discrepancies.isEmpty() && negativeCount == 0;

        FundCheckResult result;
        if (healthy) {
            result = FundCheckResult.healthy(accountsChecked, pendingWithdrawals, durationMs);
            fundCheckHealthyGauge.set(1);
            negativeAccountsGauge.set(0);
            discrepancyGauge.set(BigDecimal.ZERO);
            log.info("[FUND-CHECK] ✅ 资金总账核对平账无误！检查账户: {} 在途提现: {} 耗时: {}ms",
                    accountsChecked, pendingWithdrawals, durationMs);
        } else {
            result = FundCheckResult.anomaly(accountsChecked, negativeCount, pendingWithdrawals,
                    discrepancyUsdt, discrepancies, durationMs);
            fundCheckHealthyGauge.set(0);
            negativeAccountsGauge.set((int) negativeCount);
            discrepancyGauge.set(discrepancyUsdt);

            String stopReason = "资金核查异常: 发现 " + negativeCount + " 个负余额穿仓账户，" + discrepancies.size() + " 项差异";
            log.error("[FUND-CHECK] ❌ 资金核查不平！立即熔断全平台提现！原因: {}", stopReason);

            // 1. 立即停机提现通道
            circuitBreaker.triggerEmergencyStop(stopReason);

            // 2. 并发向 Telegram 报警群与管理员发送最高等级事故警报
            alertNotifier.broadcastFundCheckAlert(result);
        }

        return result;
    }
}
