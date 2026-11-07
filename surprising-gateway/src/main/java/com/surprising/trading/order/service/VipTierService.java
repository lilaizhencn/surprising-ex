package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.order.model.VipTierModels;
import com.surprising.trading.order.model.VipTierModels.UserVipStatusResponse;
import com.surprising.trading.order.model.VipTierModels.VipTierDefinition;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VipTierService {
    private static final Logger log = LoggerFactory.getLogger(VipTierService.class);

    private final JdbcTemplate jdbc;
    private final TradingFeeService tradingFeeService;

    public VipTierService(JdbcTemplate jdbc, TradingFeeService tradingFeeService) {
        this.jdbc = jdbc;
        this.tradingFeeService = tradingFeeService;
    }

    public List<VipTierDefinition> getVipTiers() {
        return VipTierModels.VIP_TIERS;
    }

    /**
     * 计算并查询用户的 VIP 等级状态。
     */
    public UserVipStatusResponse getUserVipStatus(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }

        long volume30d = query30DayVolumeUsd(userId);
        VipTierDefinition currentTier = resolveTier(volume30d);

        VipTierDefinition nextTier = currentTier.tierLevel() < VipTierModels.VIP_TIERS.size() - 1
                ? VipTierModels.VIP_TIERS.get(currentTier.tierLevel() + 1)
                : null;

        long nextTierVolume = nextTier != null ? nextTier.min30DayVolumeUsd() : currentTier.min30DayVolumeUsd();
        long needed = nextTier != null ? Math.max(0, nextTier.min30DayVolumeUsd() - volume30d) : 0L;

        return new UserVipStatusResponse(
                userId,
                currentTier.tierLevel(),
                currentTier.tierCode(),
                currentTier.makerFeeRatePpm(),
                currentTier.takerFeeRatePpm(),
                volume30d,
                nextTierVolume,
                needed
        );
    }

    /**
     * 评定并下发该用户的 VIP 费率到撮合核心。
     */
    public UserVipStatusResponse evaluateAndApplyUserVip(long userId, ProductLine productLine) {
        UserVipStatusResponse status = getUserVipStatus(userId);
        ProductLine targetProduct = productLine != null ? productLine : ProductLine.LINEAR_PERPETUAL;

        try {
            long scheduleId = userId * 1000 + status.currentTierLevel();
            FeeScheduleUpsertRequest request = new FeeScheduleUpsertRequest(
                    scheduleId,
                    targetProduct,
                    userId,
                    null,
                    status.makerFeeRatePpm(),
                    status.takerFeeRatePpm(),
                    FeeScheduleSourceType.VIP,
                    status.currentTierCode(),
                    "VIP_AUTO_EVALUATION",
                    FeeScheduleStatus.ACTIVE,
                    Instant.now(),
                    null
            );
            tradingFeeService.upsertSchedule(request);
            log.info("VIP fee applied: userId={}, tier={}, maker={}, taker={}",
                    userId, status.currentTierCode(), status.makerFeeRatePpm(), status.takerFeeRatePpm());
        } catch (Exception ex) {
            log.warn("Failed to apply VIP fee schedule for userId {}: {}", userId, ex.getMessage());
        }

        return status;
    }

    VipTierDefinition resolveTier(long volumeUsd) {
        VipTierDefinition best = VipTierModels.VIP_TIERS.getFirst();
        for (VipTierDefinition tier : VipTierModels.VIP_TIERS) {
            if (volumeUsd >= tier.min30DayVolumeUsd()) {
                best = tier;
            }
        }
        return best;
    }

    private long query30DayVolumeUsd(long userId) {
        try {
            long sinceEpochMs = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            // 从核心已提交成交事实中聚合过去 30 天名义成交量 (折算 USD)
            String sql = """
                    SELECT COALESCE(SUM((price_ticks::numeric * quantity_steps::numeric) / 10000.0), 0)
                    FROM core_execution_projection
                    WHERE (taker_user_id = ? OR maker_user_id = ?)
                      AND occurred_at_epoch_ms >= ?
                    """;
            Number sum = jdbc.queryForObject(sql, Number.class, userId, userId, sinceEpochMs);
            return sum != null ? sum.longValue() : 0L;
        } catch (Exception ex) {
            log.debug("Volume query fallback for userId {}: {}", userId, ex.getMessage());
            return 0L;
        }
    }
}
