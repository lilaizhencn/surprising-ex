package com.surprising.gateway.provider.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialReconciliationTest {

    private JdbcTemplate jdbc;
    private EmergencyWithdrawalCircuitBreaker circuitBreaker;
    private EmergencyAlertNotifier alertNotifier;
    private FinancialReconciliationEngine engine;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        circuitBreaker = new EmergencyWithdrawalCircuitBreaker(null);
        alertNotifier = mock(EmergencyAlertNotifier.class);
        engine = new FinancialReconciliationEngine(jdbc, circuitBreaker, alertNotifier, new SimpleMeterRegistry());
    }

    @Test
    void circuitBreakerNormalFlowAndTrigger() {
        assertTrue(circuitBreaker.isWithdrawalAllowed());
        assertFalse(circuitBreaker.isStopped());

        // 触发停机
        circuitBreaker.triggerEmergencyStop("对账异常");
        assertFalse(circuitBreaker.isWithdrawalAllowed());
        assertTrue(circuitBreaker.isStopped());
        assertEquals("对账异常", circuitBreaker.getStopReason());

        // 恢复提现
        circuitBreaker.resumeWithdrawal("admin-tester");
        assertTrue(circuitBreaker.isWithdrawalAllowed());
        assertFalse(circuitBreaker.isStopped());
    }

    @Test
    void runsHealthyReconciliationWhenNoNegativeBalances() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("COUNT(DISTINCT user_id)"), eq(Long.class))).thenReturn(150L);
        when(jdbc.queryForObject(contains("status IN ('PENDING_APPROVAL'"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(contains("tx_hash IS NULL"), eq(Long.class))).thenReturn(0L);

        ReconciliationResult result = engine.runReconciliation();

        assertTrue(result.healthy());
        assertEquals(0, result.negativeBalanceCount());
        assertTrue(circuitBreaker.isWithdrawalAllowed());
        verify(alertNotifier, never()).broadcastReconciliationAlert(any());
    }

    @Test
    void triggersEmergencyStopAndAlertWhenNegativeBalancesFound() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
                List.of("UID 10086 币种 USDT 出现负余额: -500 units (最新流水 entryId=99)")
        );

        ReconciliationResult result = engine.runReconciliation();

        assertFalse(result.healthy());
        assertEquals(1, result.negativeBalanceCount());
        // 验证提现熔断器已被自动锁死
        assertFalse(circuitBreaker.isWithdrawalAllowed());
        assertTrue(circuitBreaker.isStopped());
        assertTrue(circuitBreaker.getStopReason().contains("负余额"));

        // 验证并发触发了紧急通知
        verify(alertNotifier).broadcastReconciliationAlert(eq(result));
    }
}
