package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.provider.repository.PositionModeRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionModeCommandService {

    private final PositionModeRepository positionModeRepository;
    private final PositionModeSwitchGuard switchGuard;

    public PositionModeCommandService(PositionModeRepository positionModeRepository,
                                      PositionModeSwitchGuard switchGuard) {
        this.positionModeRepository = positionModeRepository;
        this.switchGuard = switchGuard;
    }

    @Transactional
    public PositionModeResponse update(ProductLine productLine,
                                       long userId,
                                       PositionMode positionMode,
                                       Instant now) {
        ProductLine resolvedProductLine = productLine == null
                ? ProductLine.LINEAR_PERPETUAL
                : productLine;
        PositionMode normalizedMode = PositionMode.defaultIfNull(positionMode);
        switchGuard.lock(resolvedProductLine, userId);
        PositionMode current = positionModeRepository.find(resolvedProductLine, userId)
                .map(PositionModeRepository.PositionModeRow::positionMode)
                .orElse(PositionMode.ONE_WAY);
        if (current == normalizedMode) {
            return new PositionModeResponse(resolvedProductLine, userId, current, now);
        }
        switchGuard.requireSwitchable(resolvedProductLine, userId);
        int rows = positionModeRepository.upsert(resolvedProductLine, userId, normalizedMode, now);
        if (rows != 1) {
            throw new IllegalStateException("position mode upsert affected " + rows + " rows");
        }
        return new PositionModeResponse(resolvedProductLine, userId, normalizedMode, now);
    }
}
