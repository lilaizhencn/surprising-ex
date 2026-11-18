package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.order.model.FeeSchedulePageQuery;
import com.surprising.trading.order.model.VipTierModels;
import com.surprising.trading.order.model.VipTierModels.UserVipStatusResponse;
import com.surprising.trading.order.model.VipTierModels.VipTierDefinition;
import com.surprising.trading.order.repository.TradeProjectionRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VipTierService {
    private static final Logger log = LoggerFactory.getLogger(VipTierService.class);

    public record AdminUserVipStatus(
            long userId,
            int currentLevel,
            String tierName,
            long volume30d,
            long makerFeeRatePpm,
            long takerFeeRatePpm,
            long makerFeeDiscountPpm,
            long takerFeeDiscountPpm,
            boolean manualOverride,
            Instant updatedAt
    ) {}

    private final TradeProjectionRepository tradeProjectionRepository;
    private final TradingFeeService tradingFeeService;

    public VipTierService(TradeProjectionRepository tradeProjectionRepository, TradingFeeService tradingFeeService) {
        this.tradeProjectionRepository = tradeProjectionRepository;
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

    public AdminUserVipStatus getAdminUserVipStatus(long userId, ProductLine productLine) {
        UserVipStatusResponse base = getUserVipStatus(userId);
        ProductLine targetProduct = productLine != null ? productLine : ProductLine.LINEAR_PERPETUAL;
        FeeScheduleResponse activeVip = findActiveVipSchedule(userId, targetProduct);
        VipTierDefinition vip0 = VipTierModels.VIP_TIERS.getFirst();
        if (activeVip != null) {
            VipTierDefinition matched = VipTierModels.VIP_TIERS.stream()
                    .filter(t -> t.tierCode().equalsIgnoreCase(String.valueOf(activeVip.tierCode())))
                    .findFirst().orElse(null);
            int level = matched != null ? matched.tierLevel() : base.currentTierLevel();
            String tierName = matched != null ? matched.tierCode() : (activeVip.tierCode() != null ? activeVip.tierCode() : base.currentTierCode());
            boolean manualOverride = !"VIP_AUTO_EVALUATION".equals(activeVip.reason());
            long makerRate = activeVip.makerFeeRatePpm();
            long takerRate = activeVip.takerFeeRatePpm();
            return new AdminUserVipStatus(
                    userId,
                    level,
                    tierName,
                    base.rolling30DayVolumeUsd(),
                    makerRate,
                    takerRate,
                    Math.max(0L, vip0.makerFeeRatePpm() - makerRate),
                    Math.max(0L, vip0.takerFeeRatePpm() - takerRate),
                    manualOverride,
                    activeVip.updatedAt() != null ? activeVip.updatedAt() : Instant.now()
            );
        }
        return new AdminUserVipStatus(
                userId,
                base.currentTierLevel(),
                base.currentTierCode(),
                base.rolling30DayVolumeUsd(),
                base.makerFeeRatePpm(),
                base.takerFeeRatePpm(),
                Math.max(0L, vip0.makerFeeRatePpm() - base.makerFeeRatePpm()),
                Math.max(0L, vip0.takerFeeRatePpm() - base.takerFeeRatePpm()),
                false,
                Instant.now()
        );
    }

    public AdminUserVipStatus overrideUserVip(long userId,
                                              int vipLevel,
                                              Long makerFeeRatePpm,
                                              Long takerFeeRatePpm,
                                              Long makerFeeDiscountPpm,
                                              Long takerFeeDiscountPpm,
                                              String reason,
                                              ProductLine productLine) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        VipTierDefinition tier = VipTierModels.VIP_TIERS.stream()
                .filter(t -> t.tierLevel() == vipLevel)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported vipLevel: " + vipLevel));
        VipTierDefinition vip0 = VipTierModels.VIP_TIERS.getFirst();
        long targetMaker = makerFeeRatePpm != null
                ? makerFeeRatePpm
                : (makerFeeDiscountPpm != null && makerFeeDiscountPpm > 0
                        ? vip0.makerFeeRatePpm() - makerFeeDiscountPpm
                        : tier.makerFeeRatePpm());
        long targetTaker = takerFeeRatePpm != null
                ? takerFeeRatePpm
                : (takerFeeDiscountPpm != null && takerFeeDiscountPpm > 0
                        ? vip0.takerFeeRatePpm() - takerFeeDiscountPpm
                        : tier.takerFeeRatePpm());
        if (targetMaker < -1_000_000L || targetMaker > 1_000_000L
                || targetTaker < -1_000_000L || targetTaker > 1_000_000L) {
            throw new IllegalArgumentException("fee rate must be within +/- 100%");
        }
        if (targetMaker > targetTaker) {
            throw new IllegalArgumentException("makerFeeRatePpm cannot exceed takerFeeRatePpm");
        }
        String normalizedReason = reason == null || reason.isBlank()
                ? "Admin manual VIP override to " + tier.tierCode()
                : reason.trim();
        ProductLine targetProduct = productLine != null ? productLine : ProductLine.LINEAR_PERPETUAL;
        long scheduleId = userId * 1000L + 900L + vipLevel;

        // Disable prior active VIP overrides on this product line so only one VIP tier applies.
        try {
            FeeScheduleQueryResponse existing = tradingFeeService.querySchedules(
                    targetProduct, userId, null, FeeScheduleStatus.ACTIVE,
                    new FeeSchedulePageQuery(50, null, FeeSchedulePageQuery.Order.UPDATED_DESC));
            if (existing != null && existing.schedules() != null) {
                for (FeeScheduleResponse item : existing.schedules()) {
                    if (item.sourceType() == FeeScheduleSourceType.VIP && item.feeScheduleId() != scheduleId) {
                        tradingFeeService.disableSchedule(item.feeScheduleId(), targetProduct);
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Prior VIP schedule cleanup skipped for userId={}: {}", userId, ex.getMessage());
        }

        Instant now = Instant.now();
        FeeScheduleResponse saved = tradingFeeService.upsertSchedule(new FeeScheduleUpsertRequest(
                scheduleId,
                targetProduct,
                userId,
                null,
                targetMaker,
                targetTaker,
                FeeScheduleSourceType.VIP,
                tier.tierCode(),
                normalizedReason,
                FeeScheduleStatus.ACTIVE,
                now,
                null
        ));
        long volume30d = query30DayVolumeUsd(userId);
        return new AdminUserVipStatus(
                userId,
                tier.tierLevel(),
                tier.tierCode(),
                volume30d,
                saved.makerFeeRatePpm(),
                saved.takerFeeRatePpm(),
                Math.max(0L, vip0.makerFeeRatePpm() - saved.makerFeeRatePpm()),
                Math.max(0L, vip0.takerFeeRatePpm() - saved.takerFeeRatePpm()),
                true,
                saved.updatedAt() != null ? saved.updatedAt() : now
        );
    }

    private FeeScheduleResponse findActiveVipSchedule(long userId, ProductLine productLine) {
        try {
            FeeScheduleQueryResponse query = tradingFeeService.querySchedules(
                    productLine, userId, null, FeeScheduleStatus.ACTIVE,
                    new FeeSchedulePageQuery(20, null, FeeSchedulePageQuery.Order.UPDATED_DESC));
            if (query == null || query.schedules() == null) {
                return null;
            }
            Instant now = Instant.now();
            return query.schedules().stream()
                    .filter(s -> s.sourceType() == FeeScheduleSourceType.VIP
                            && s.status() == FeeScheduleStatus.ACTIVE
                            && (s.expireTime() == null || s.expireTime().isAfter(now)))
                    .findFirst().orElse(null);
        } catch (RuntimeException ex) {
            log.debug("VIP schedule query fallback for userId={}: {}", userId, ex.getMessage());
            return null;
        }
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
            return tradeProjectionRepository.sumUserVolumeUsdSince(userId, sinceEpochMs);
        } catch (Exception ex) {
            log.debug("Volume query fallback for userId {}: {}", userId, ex.getMessage());
            return 0L;
        }
    }
}
