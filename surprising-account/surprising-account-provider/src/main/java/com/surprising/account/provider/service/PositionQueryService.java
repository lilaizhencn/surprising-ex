package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.repository.AccountInstrumentRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PositionQueryService {

    private final PositionRepository positionRepository;
    private final PositionMarginRepository positionMarginRepository;
    private final AccountInstrumentRepository instrumentRepository;

    public PositionQueryService(PositionRepository positionRepository,
                                PositionMarginRepository positionMarginRepository,
                                AccountInstrumentRepository instrumentRepository) {
        this.positionRepository = positionRepository;
        this.positionMarginRepository = positionMarginRepository;
        this.instrumentRepository = instrumentRepository;
    }

    public Optional<PositionMarginResponse> positionMargin(long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide) {
        Optional<PositionResponse> position = positionRepository.find(
                userId, symbol, marginMode, positionSide);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        return toMarginResponse(null, position.get());
    }

    public Optional<PositionMarginResponse> positionMargin(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide) {
        Optional<PositionResponse> position = positionRepository.find(
                productLine, userId, symbol, marginMode, positionSide);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        return toMarginResponse(productLine, position.get());
    }

    private Optional<PositionMarginResponse> toMarginResponse(ProductLine productLine,
                                                              PositionResponse position) {
        Optional<String> asset = instrumentRepository.findSettleAsset(
                position.symbol(), position.instrumentVersion());
        if (asset.isEmpty()) {
            return Optional.empty();
        }
        Optional<PositionMarginRepository.PositionMarginRow> margin = productLine == null
                ? positionMarginRepository.findLegacy(
                        position.userId(), position.symbol(), asset.get(),
                        position.marginMode(), position.positionSide())
                : positionMarginRepository.find(
                        productLine, position.userId(), position.symbol(), asset.get(),
                        position.marginMode(), position.positionSide());
        long marginUnits = margin.map(PositionMarginRepository.PositionMarginRow::marginUnits).orElse(0L);
        Instant updatedAt = margin.map(PositionMarginRepository.PositionMarginRow::updatedAt)
                .orElse(position.updatedAt());
        return Optional.of(new PositionMarginResponse(
                position.userId(),
                position.symbol(),
                asset.get(),
                position.marginMode(),
                position.positionSide(),
                marginUnits,
                updatedAt));
    }
}
