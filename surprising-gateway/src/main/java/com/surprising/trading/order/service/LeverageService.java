package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.repository.OrderLeverageMath;
import java.time.Instant;
import java.util.UUID;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import org.springframework.stereotype.Service;

@Service
public class LeverageService {

    private static final long MIN_LEVERAGE_PPM = 1_000_000L;

    private final InstrumentRuleLookup instrumentRuleLookup;
    private final OrderAeronGateway aeron;

    @org.springframework.beans.factory.annotation.Autowired
    public LeverageService(InstrumentRuleLookup instrumentRuleLookup,
                           OrderAeronGateway aeron) {
        this.instrumentRuleLookup = instrumentRuleLookup;
        this.aeron = aeron;
    }

    public LeverageSettingResponse set(LeverageSettingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("leverage setting request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String instrumentId = normalizeSymbol(request.instrumentId());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        InstrumentRule rule = tradingRule(instrumentId);
        ProductLine productLine = productLine(rule, request.productLine());
        if (request.leveragePpm() < MIN_LEVERAGE_PPM) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        if (request.leveragePpm() > rule.maxLeveragePpm()) {
            throw new IllegalArgumentException("leveragePpm exceeds instrument max leverage");
        }
        Instant updatedAt = Instant.now();
        // A rejected attempt must not poison a later retry after open exposure has been cleared.
        UUID commandId = UUID.randomUUID();
        aeron.command(CoreMessageType.UPDATE_LEVERAGE, commandId, request.userId(),
                TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand(instrumentId,
                        CoreMarginMode.valueOf(marginMode.name()), request.leveragePpm())));
        return new LeverageSettingResponse(request.userId(), productLine, instrumentId, marginMode,
                request.leveragePpm(), rule.maxLeveragePpm(),
                OrderLeverageMath.initialMarginRateFromLeveragePpm(request.leveragePpm()),
                "USER", updatedAt);
    }

    public LeverageSettingResponse get(long userId, String instrumentId, MarginMode marginMode) {
        return get(userId, instrumentId, marginMode, null);
    }

    public LeverageSettingResponse get(long userId, String instrumentId, MarginMode marginMode, ProductLine productLine) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = normalizeSymbol(instrumentId);
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        InstrumentRule rule = tradingRule(normalizedSymbol);
        ProductLine resolvedProductLine = productLine(rule, productLine);
        var configured = aeron.leverage(userId, normalizedSymbol,
                CoreMarginMode.valueOf(normalizedMarginMode.name()));
        return configured == null ? instrumentDefault(userId, resolvedProductLine, normalizedSymbol,
                normalizedMarginMode, rule) : new LeverageSettingResponse(userId, resolvedProductLine,
                normalizedSymbol, normalizedMarginMode, configured.leveragePpm(), rule.maxLeveragePpm(),
                OrderLeverageMath.initialMarginRateFromLeveragePpm(configured.leveragePpm()), "USER", Instant.EPOCH);
    }

    private InstrumentRule tradingRule(String instrumentId) {
        InstrumentRule rule = instrumentRuleLookup.currentRule(instrumentId)
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + instrumentId));
        if (!"TRADING".equals(rule.status())) {
            throw new IllegalStateException("instrument is not trading: " + instrumentId);
        }
        return rule;
    }

    private ProductLine productLine(InstrumentRule rule, ProductLine requestedProductLine) {
        ProductLine instrumentProductLine = ProductLine.requireContractTypeCode(rule.contractType().name());
        if (requestedProductLine != null && requestedProductLine != instrumentProductLine) {
            throw new IllegalArgumentException("productLine does not match instrument contractType");
        }
        return instrumentProductLine;
    }

    /** 快照中没有用户覆盖时使用当前 Instrument 规则计算默认杠杆。 */
    private LeverageSettingResponse instrumentDefault(long userId,
                                                       ProductLine productLine,
                                                       String instrumentId,
                                                       MarginMode marginMode,
                                                       InstrumentRule rule) {
        long leveragePpm = Math.min(OrderLeverageMath.leveragePpmFromInitialMarginRate(
                rule.initialMarginRatePpm()), rule.maxLeveragePpm());
        long effectiveRate = Math.max(rule.initialMarginRatePpm(),
                OrderLeverageMath.initialMarginRateFromLeveragePpm(leveragePpm));
        return new LeverageSettingResponse(userId, productLine, instrumentId, marginMode, leveragePpm,
                rule.maxLeveragePpm(), effectiveRate, "INSTRUMENT_DEFAULT", Instant.EPOCH);
    }

    private String normalizeSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase();
        if (!com.surprising.product.api.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }
}
