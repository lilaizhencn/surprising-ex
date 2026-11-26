package com.surprising.gateway.provider.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundCheckTest {

    private JdbcTemplate jdbc;
    private EmergencyWithdrawalCircuitBreaker circuitBreaker;
    private EmergencyAlertNotifier alertNotifier;
    private FundCheckService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        circuitBreaker = new EmergencyWithdrawalCircuitBreaker(null);
        alertNotifier = mock(EmergencyAlertNotifier.class);
        service = new FundCheckService(jdbc, circuitBreaker, alertNotifier, new SimpleMeterRegistry());
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
    void runsHealthyFundCheckWhenNoNegativeBalances() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("COUNT(DISTINCT user_id)"), eq(Long.class))).thenReturn(150L);
        when(jdbc.queryForObject(contains("status IN ('PENDING_APPROVAL'"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(contains("wallet_withdrawal_id IS NULL"), eq(Long.class))).thenReturn(0L);

        FundCheckResult result = service.checkFunds();

        assertTrue(result.healthy());
        assertEquals(0, result.negativeBalanceCount());
        assertTrue(circuitBreaker.isWithdrawalAllowed());
        verify(alertNotifier, never()).broadcastFundCheckAlert(any());
    }

    @Test
    void triggersEmergencyStopAndAlertWhenNegativeBalancesFound() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
                List.of("UID 10086 币种 USDT 出现负余额: -500 units (最新流水 entryId=99)")
        );

        FundCheckResult result = service.checkFunds();

        assertFalse(result.healthy());
        assertEquals(1, result.negativeBalanceCount());
        // 验证提现熔断器已被自动锁死
        assertFalse(circuitBreaker.isWithdrawalAllowed());
        assertTrue(circuitBreaker.isStopped());
        assertTrue(circuitBreaker.getStopReason().contains("负余额"));

        // 验证并发触发了紧急通知
        verify(alertNotifier).broadcastFundCheckAlert(eq(result));
    }

    @Test
    void failsClosedWhenNegativeBalanceQueryThrows() {
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenThrow(new RuntimeException("database timeout"));
        when(jdbc.queryForObject(contains("COUNT(DISTINCT user_id)"), eq(Long.class))).thenReturn(150L);
        when(jdbc.queryForObject(contains("status IN ('PENDING_APPROVAL'"), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("wallet_withdrawal_id IS NULL"), eq(Long.class))).thenReturn(0L);

        FundCheckResult result = service.checkFunds();

        assertFalse(result.healthy());
        assertFalse(result.discrepancies().isEmpty());
        assertTrue(circuitBreaker.isStopped());
        verify(alertNotifier).broadcastFundCheckAlert(eq(result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void circuitBreakerFailsClosedWhenRedisReadFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis unavailable"));

        EmergencyWithdrawalCircuitBreaker distributedBreaker = new EmergencyWithdrawalCircuitBreaker(redis);

        assertFalse(distributedBreaker.isWithdrawalAllowed());
        assertTrue(distributedBreaker.isStopped());
    }
}
