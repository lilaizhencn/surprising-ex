package com.surprising.trading.order.service;

import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierRefreshResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.FeeTierRepository;
import com.surprising.trading.order.repository.FeeTierRepository.FeeTierAssignmentRecord;
import com.surprising.trading.order.repository.FeeTierRepository.FeeTierMetrics;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeeTierService {

    private final FeeTierRepository feeTierRepository;
    private final OrderFeeRepository orderFeeRepository;
    private final OrderRepository orderRepository;
    private final TradingOrderProperties properties;

    public FeeTierService(FeeTierRepository feeTierRepository,
                          OrderFeeRepository orderFeeRepository,
                          OrderRepository orderRepository,
                          TradingOrderProperties properties) {
        this.feeTierRepository = feeTierRepository;
        this.orderFeeRepository = orderFeeRepository;
        this.orderRepository = orderRepository;
        this.properties = properties;
    }

    @Transactional
    public FeeTierResponse upsertTier(FeeTierUpsertRequest request) {
        Instant now = Instant.now();
        feeTierRepository.upsertTier(request, now);
        return feeTierRepository.findTier(request.tierCode())
                .orElseThrow(() -> new IllegalStateException("fee tier upsert failed: " + request.tierCode()));
    }

    public FeeTierQueryResponse queryTiers(FeeScheduleStatus status, int limit) {
        return feeTierRepository.queryTiers(status, limit);
    }

    @Transactional
    public FeeTierAssignmentResponse refreshUserTier(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Instant now = Instant.now();
        Instant since = lookbackStart(now);
        FeeTierMetrics metrics = feeTierRepository.metrics(userId, since);
        long proposedFeeScheduleId = orderRepository.nextSequence("fee-schedule");
        FeeTierAssignmentRecord assignment = feeTierRepository.lockAssignment(userId, proposedFeeScheduleId, now);
        Optional<FeeTierResponse> eligibleTier = feeTierRepository.eligibleTier(
                metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits());
        if (eligibleTier.isEmpty()) {
            if (assignment.status() == FeeScheduleStatus.ACTIVE) {
                orderFeeRepository.disableSchedule(assignment.feeScheduleId(), now);
            }
            feeTierRepository.disableAssignment(userId, metrics, now);
            return feeTierRepository.currentAssignment(userId)
                    .orElseThrow(() -> new IllegalStateException("fee tier assignment missing after disable"));
        }

        FeeTierResponse tier = eligibleTier.orElseThrow();
        boolean sameTier = sameActiveTier(assignment, tier);
        Instant effectiveTime = sameTier ? assignment.effectiveTime() : now;
        if (!sameTier) {
            upsertVipSchedule(userId, assignment.feeScheduleId(), tier, effectiveTime, now);
        }
        feeTierRepository.activateAssignment(userId, tier, assignment.feeScheduleId(), metrics, effectiveTime, now);
        return feeTierRepository.currentAssignment(userId)
                .orElseThrow(() -> new IllegalStateException("fee tier assignment missing after refresh"));
    }

    public FeeTierAssignmentResponse currentUserTier(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return feeTierRepository.currentAssignment(userId)
                .orElseGet(() -> new FeeTierAssignmentResponse(userId, null, null, 0L, 0L, 0L,
                        0L, 0L, FeeScheduleStatus.DISABLED, Instant.EPOCH, Instant.EPOCH));
    }

    @Transactional
    public FeeTierRefreshResponse refreshActiveUserTiers(int limit) {
        Instant now = Instant.now();
        Instant since = lookbackStart(now);
        List<Long> users = feeTierRepository.candidateUsers(since, limit);
        List<FeeTierAssignmentResponse> assignments = new ArrayList<>();
        int changed = 0;
        for (Long userId : users) {
            FeeTierAssignmentResponse before = currentUserTier(userId);
            FeeTierAssignmentResponse after = refreshUserTier(userId);
            assignments.add(after);
            if (!sameAssignment(before, after)) {
                changed++;
            }
        }
        return new FeeTierRefreshResponse(users.size(), changed, now, assignments);
    }

    @Scheduled(
            initialDelayString = "${surprising.trading.order.fee-tier.refresh-initial-delay-ms:60000}",
            fixedDelayString = "${surprising.trading.order.fee-tier.refresh-delay-ms:3600000}")
    @Transactional
    public void refreshActiveUserTiersScheduled() {
        if (!properties.getFeeTier().isEnabled()) {
            return;
        }
        refreshActiveUserTiers(properties.getFeeTier().getBatchSize());
    }

    private void upsertVipSchedule(long userId,
                                   long feeScheduleId,
                                   FeeTierResponse tier,
                                   Instant effectiveTime,
                                   Instant now) {
        FeeScheduleUpsertRequest request = new FeeScheduleUpsertRequest(feeScheduleId, userId, null,
                tier.makerFeeRatePpm(), tier.takerFeeRatePpm(), tier.sourceType(), tier.tierCode(),
                "automatic " + tier.sourceType().name().toLowerCase() + " fee tier " + tier.tierCode(),
                FeeScheduleStatus.ACTIVE, effectiveTime, null);
        orderFeeRepository.upsertSchedule(request, feeScheduleId, now);
        FeeScheduleResponse persisted = orderFeeRepository.findSchedule(feeScheduleId)
                .orElseThrow(() -> new IllegalStateException("fee schedule upsert failed: " + feeScheduleId));
        if (persisted.status() != FeeScheduleStatus.ACTIVE) {
            throw new IllegalStateException("fee schedule did not become active: " + feeScheduleId);
        }
    }

    private boolean sameActiveTier(FeeTierAssignmentRecord assignment, FeeTierResponse tier) {
        return assignment.status() == FeeScheduleStatus.ACTIVE
                && tier.tierCode().equals(assignment.tierCode())
                && tier.sourceType() == assignment.sourceType()
                && tier.makerFeeRatePpm() == assignment.makerFeeRatePpm()
                && tier.takerFeeRatePpm() == assignment.takerFeeRatePpm();
    }

    private boolean sameAssignment(FeeTierAssignmentResponse before, FeeTierAssignmentResponse after) {
        return before.status() == after.status()
                && stringEquals(before.tierCode(), after.tierCode())
                && before.makerFeeRatePpm() == after.makerFeeRatePpm()
                && before.takerFeeRatePpm() == after.takerFeeRatePpm();
    }

    private Instant lookbackStart(Instant now) {
        long lookbackDays = Math.max(1L, properties.getFeeTier().getLookbackDays());
        return now.minus(lookbackDays, ChronoUnit.DAYS);
    }

    private boolean stringEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
