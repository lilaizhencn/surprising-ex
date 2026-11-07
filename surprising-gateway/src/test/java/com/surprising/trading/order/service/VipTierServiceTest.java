package com.surprising.trading.order.service;

import com.surprising.trading.order.model.VipTierModels;
import com.surprising.trading.order.model.VipTierModels.UserVipStatusResponse;
import com.surprising.trading.order.model.VipTierModels.VipTierDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VipTierServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TradingFeeService tradingFeeService = mock(TradingFeeService.class);
    private final VipTierService service = new VipTierService(jdbc, tradingFeeService);

    @Test
    @DisplayName("测试不同交易量正确匹配 VIP 梯级与费率标准")
    void shouldResolveCorrectVipTierByVolume() {
        // 0 成交量 -> VIP0
        VipTierDefinition tier0 = service.resolveTier(0L);
        assertEquals("VIP0", tier0.tierCode());
        assertEquals(200L, tier0.makerFeeRatePpm());
        assertEquals(500L, tier0.takerFeeRatePpm());

        // 150,000 USD -> VIP1
        VipTierDefinition tier1 = service.resolveTier(150_000L);
        assertEquals("VIP1", tier1.tierCode());

        // 15,000,000 USD -> VIP4
        VipTierDefinition tier4 = service.resolveTier(15_000_000L);
        assertEquals("VIP4", tier4.tierCode());
        assertEquals(100L, tier4.makerFeeRatePpm());
        assertEquals(300L, tier4.takerFeeRatePpm());

        // 600,000,000 USD -> VIP9 做市商返佣级别
        VipTierDefinition tier9 = service.resolveTier(600_000_000L);
        assertEquals("VIP9_MM2", tier9.tierCode());
        assertEquals(-50L, tier9.makerFeeRatePpm()); // 做市商负费率返佣
        assertEquals(120L, tier9.takerFeeRatePpm());
    }

    @Test
    @DisplayName("测试计算用户 VIP 状态并计算晋级所需差额")
    void shouldCalculateUserVipStatusWithProgression() {
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(1001L), eq(1001L), anyLong()))
                .thenReturn(700_000L); // 70 万 USD，达到 VIP1 (10万)，距 VIP2 (100万) 还差 30 万

        UserVipStatusResponse status = service.getUserVipStatus(1001L);
        assertNotNull(status);
        assertEquals(1, status.currentTierLevel());
        assertEquals("VIP1", status.currentTierCode());
        assertEquals(700_000L, status.rolling30DayVolumeUsd());
        assertEquals(1_000_000L, status.nextTierMinVolumeUsd());
        assertEquals(300_000L, status.volumeNeededForNextTier());
    }
}
