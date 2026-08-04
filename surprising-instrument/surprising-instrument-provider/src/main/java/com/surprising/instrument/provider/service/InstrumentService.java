package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductTopicNames;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentService {

    private final InstrumentStorageService storageService;
    private final InstrumentValidator instrumentValidator;
    private final InstrumentProperties properties;
    private final InstrumentOutboxService outboxService;

    public InstrumentService(InstrumentStorageService storageService,
                             InstrumentValidator instrumentValidator,
                             InstrumentProperties properties,
                             InstrumentOutboxService outboxService) {
        this.storageService = storageService;
        this.instrumentValidator = instrumentValidator;
        this.properties = properties;
        this.outboxService = outboxService;
    }

    public InstrumentResponse latest(String symbol) {
        return storageService.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("instrument not found: " + symbol));
    }

    public InstrumentResponse latest(String symbol, ProductLine productLine) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (productLine == null) {
            return latest(normalizedSymbol);
        }
        Optional<InstrumentResponse> productCurrent = storageService.latest(normalizedSymbol, productLine);
        if (productCurrent.isPresent()) {
            InstrumentResponse response = productCurrent.get();
            if (response.contractType().productLine() == productLine) {
                return response;
            }
            throw new IllegalStateException("instrument product current mismatch: "
                    + symbol + ":" + productLine.name() + ":" + response.contractType().name());
        }
        InstrumentResponse response = latest(normalizedSymbol);
        if (response.contractType().productLine() == productLine) {
            return response;
        }
        throw new IllegalStateException("instrument not found for productLine: " + symbol + ":" + productLine.name());
    }

    public InstrumentResponse version(String symbol, long version) {
        return storageService.version(normalizeSymbol(symbol), version)
                .orElseThrow(() -> new IllegalStateException("instrument version not found: " + symbol + ":" + version));
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status) {
        var rows = storageService.list(type, status);
        return new InstrumentQueryResponse(rows.size(), rows);
    }

    public InstrumentQueryResponse list(ProductLine productLine, InstrumentType type, InstrumentStatus status) {
        var rows = storageService.list(productLine, type, status);
        return new InstrumentQueryResponse(rows.size(), rows);
    }

    /**
     * 返回指定产品线的完整合约快照，供其他服务启动初始化和缓存修复使用。
     */
    @Transactional(readOnly = true)
    public InstrumentSnapshotResponse snapshot(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        var rows = storageService.listAllVersions(productLine);
        var assetScales = storageService.assetScales();
        long sequence = rows.stream().mapToLong(InstrumentResponse::version).max().orElse(0L);
        return new InstrumentSnapshotResponse(productLine, sequence,
                snapshotChecksum(productLine, rows, assetScales), rows, assetScales);
    }

    public InstrumentQueryResponse list(InstrumentType type, InstrumentStatus status, int limit, String cursor, String sort) {
        var page = storageService.listPage(type, status, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public InstrumentQueryResponse list(ProductLine productLine,
                                        InstrumentType type,
                                        InstrumentStatus status,
                                        int limit,
                                        String cursor,
                                        String sort) {
        var page = storageService.listPage(productLine, type, status, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public InstrumentQueryResponse versions(String symbol, int limit, String cursor, String sort) {
        return versions(symbol, null, limit, cursor, sort);
    }

    public InstrumentQueryResponse versions(String symbol, ProductLine productLine, int limit, String cursor, String sort) {
        var page = storageService.versionsPage(normalizeSymbol(symbol), productLine, limit, cursor, sort);
        return new InstrumentQueryResponse(page.instruments().size(), page.instruments(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @Transactional
    public InstrumentResponse upsert(InstrumentUpsertRequest request) {
        return upsert(request, InstrumentEventType.UPSERTED);
    }

    private InstrumentResponse upsert(InstrumentUpsertRequest request, InstrumentEventType eventType) {
        instrumentValidator.validate(request);
        String symbol = normalizeSymbol(request.symbol());
        Instant now = Instant.now();
        long version = storageService.nextVersion(symbol);
        storageService.insert(symbol, version, request, now);
        storageService.setCurrentVersion(request.contractType().productLine(), symbol, version, now);
        storageService.setCurrentVersion(symbol, version, now);
        InstrumentResponse response = storageService.version(symbol, version)
                .orElseThrow(() -> new IllegalStateException("instrument insert failed: " + symbol));
        publish(response, eventType);
        return response;
    }

    @Transactional
    public InstrumentResponse updateStatus(String symbol, InstrumentStatus status) {
        return updateStatus(symbol, null, status);
    }

    @Transactional
    public InstrumentResponse updateStatus(String symbol, ProductLine productLine, InstrumentStatus status) {
        InstrumentResponse current = latest(symbol, productLine);
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                current.symbol(), current.instrumentType(), current.contractType(), current.baseAsset(),
                current.quoteAsset(), current.settleAsset(), current.contractMultiplierPpm(), current.contractValueAsset(),
                current.priceTickUnits(), current.quantityStepUnits(), current.minQuantitySteps(), current.maxQuantitySteps(),
                current.minNotionalUnits(), current.maxNotionalUnits(), current.notionalMultiplierUnits(),
                current.pricePrecision(), current.quantityPrecision(),
                current.supportedOrderTypes(), current.supportedTimeInForce(), current.postOnlyEnabled(),
                current.reduceOnlyEnabled(), current.marketOrderEnabled(), current.maxLeveragePpm(),
                current.initialMarginRatePpm(), current.maintenanceMarginRatePpm(), current.makerFeeRatePpm(),
                current.takerFeeRatePpm(), current.maxPositionNotionalUnits(),
                current.userOpenInterestLimitRatePpm(), current.userOpenInterestLimitFloorUnits(),
                current.fundingIntervalHours(),
                current.interestRatePpm(), current.fundingRateCapPpm(), current.fundingRateFloorPpm(),
                current.impactNotionalUnits(), current.minValidIndexSources(), current.expiryTime(),
                current.deliveryTime(), current.underlyingSymbol(), current.strikePriceUnits(),
                current.optionType(), current.optionExerciseStyle(), current.settlementMethod(), status, Instant.now(),
                current.riskLimitBrackets(), current.indexSources());
        return upsert(request, InstrumentEventType.STATUS_CHANGED);
    }

    private void publish(InstrumentResponse response, InstrumentEventType eventType) {
        Instant eventTime = Instant.now();
        InstrumentEvent event = new InstrumentEvent(response.symbol(), response.version(), response.status(),
                eventType, eventTime, response, response.contractType().productLine(), response.version());
        outboxService.enqueue("INSTRUMENT", response.version(),
                ProductTopicNames.INSTRUMENT_EVENTS_TOPIC, InstrumentEventKeys.key(event),
                eventType.name(), event, eventTime);
    }

    @Transactional
    public void publishProductLifecycleEvent(InstrumentResponse response) {
        throw new IllegalStateException("生命周期事件必须由结算价确认入口发布");
    }

    /** 只有带不可变结算价的入口才能发布到期结算事件。 */
    @Transactional
    public void publishProductLifecycleEvent(InstrumentResponse response,
                                             long settlementPriceTicks,
                                             long underlyingSettlementPriceUnits) {
        Instant eventTime = Instant.now();
        if (response.instrumentType() == InstrumentType.DELIVERY) {
            outboxService.enqueue("INSTRUMENT", response.version(), deliverySettlementsTopic(response),
                    response.symbol(), "DELIVERY_SETTLEMENT", new DeliverySettlementEvent(
                    response.symbol(),
                    response.version(),
                    response.contractType(),
                    settlementPriceTicks,
                    response.expiryTime(),
                    response.deliveryTime(),
                    response.settlementMethod(),
                    response.status(),
                    eventTime,
                    response), eventTime);
            return;
        }
        if (response.instrumentType() == InstrumentType.OPTION) {
            outboxService.enqueue("INSTRUMENT", response.version(), optionExercisesTopic(response),
                    response.symbol(), "OPTION_EXERCISE", new OptionExerciseEvent(
                    response.symbol(),
                    response.version(),
                    response.underlyingSymbol(),
                    response.strikePriceUnits(),
                    underlyingSettlementPriceUnits,
                    optionCashSettlementUnitsPerContract(response, underlyingSettlementPriceUnits),
                    response.optionType(),
                    response.optionExerciseStyle(),
                    response.expiryTime(),
                    response.deliveryTime(),
                    response.settlementMethod(),
                    response.status(),
                    eventTime,
                    response), eventTime);
        }
    }

    /**
     * 关闭到期品种时，把状态变更和产品结算事件写入同一个数据库事务。
     */
    @Transactional
    public InstrumentResponse closeForSettlement(String symbol) {
        throw new IllegalStateException("关闭到期合约前必须确认结算价");
    }

    @Transactional
    public InstrumentResponse closeForSettlement(String symbol,
                                                 ProductLine productLine,
                                                 long settlementPriceTicks,
                                                 long underlyingSettlementPriceUnits) {
        if (productLine == null || (!productLine.isDeliveryProduct() && productLine != ProductLine.OPTION)) {
            throw new IllegalArgumentException("交割或行权必须指定到期产品线");
        }
        InstrumentResponse current = latest(symbol, productLine);
        // 已关闭合约的重复请求必须幂等返回，不能再次创建版本或重复发布资金事件。
        if (current.status() == InstrumentStatus.CLOSED) {
            return current;
        }
        if (current.status() != InstrumentStatus.SETTLING) {
            throw new IllegalStateException("合约必须先进入 SETTLING 才能确认结算: " + current.symbol());
        }
        InstrumentResponse closed = updateStatus(symbol, productLine, InstrumentStatus.CLOSED);
        publishProductLifecycleEvent(closed, settlementPriceTicks, underlyingSettlementPriceUnits);
        return closed;
    }

    /**
     * 在 Instrument 唯一入口冻结每份期权的现金收益，避免账户模块按自身价格精度重复换算。
     * 标的价格必须落在标的最小价格档位上；否则拒绝发布不可审计的舍入结果。
     */
    private long optionCashSettlementUnitsPerContract(InstrumentResponse option,
                                                       long underlyingSettlementPriceUnits) {
        if (underlyingSettlementPriceUnits <= 0L || option.strikePriceUnits() == null
                || option.underlyingSymbol() == null || option.underlyingSymbol().isBlank()
                || option.optionType() == null) {
            throw new IllegalArgumentException("期权行权缺少完整标的结算信息");
        }
        InstrumentResponse underlying = latest(option.underlyingSymbol());
        if (underlying.instrumentType() == InstrumentType.OPTION
                || underlying.priceTickUnits() <= 0L
                || !underlying.settleAsset().equalsIgnoreCase(option.settleAsset())) {
            throw new IllegalStateException("期权标的合约规格不可用于现金结算: " + option.underlyingSymbol());
        }
        BigInteger underlyingPrice = BigInteger.valueOf(underlyingSettlementPriceUnits);
        BigInteger strike = BigInteger.valueOf(option.strikePriceUnits());
        BigInteger intrinsic = option.optionType() == com.surprising.instrument.api.model.OptionType.CALL
                ? underlyingPrice.subtract(strike).max(BigInteger.ZERO)
                : strike.subtract(underlyingPrice).max(BigInteger.ZERO);
        BigInteger tick = BigInteger.valueOf(underlying.priceTickUnits());
        BigInteger[] quotientAndRemainder = intrinsic.divideAndRemainder(tick);
        if (quotientAndRemainder[1].signum() != 0) {
            throw new IllegalArgumentException("标的结算价未落在标的价格档位");
        }
        return quotientAndRemainder[0]
                .multiply(BigInteger.valueOf(option.notionalMultiplierUnits()))
                .longValueExact();
    }

    private String deliverySettlementsTopic(InstrumentResponse response) {
        String override = properties.getKafka().getDeliverySettlementsTopic();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return ProductTopicNames.of(response.contractType().productLine()).deliverySettlementsTopic();
    }

    private String optionExercisesTopic(InstrumentResponse response) {
        String override = properties.getKafka().getOptionExercisesTopic();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return ProductTopicNames.of(response.contractType().productLine()).optionExercisesTopic();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return symbol.trim().toUpperCase();
    }

    private String snapshotChecksum(ProductLine productLine,
                                    java.util.List<InstrumentResponse> rows,
                                    java.util.Map<String, Long> assetScales) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(productLine.name().getBytes(StandardCharsets.UTF_8));
            rows.stream()
                    .sorted(Comparator.comparing(InstrumentResponse::symbol)
                            .thenComparingLong(InstrumentResponse::version))
                    .forEach(row -> digest.update((row.symbol() + "|" + row.version() + "|"
                            + row.status() + "|" + row.updatedAt() + "\n").getBytes(StandardCharsets.UTF_8)));
            assetScales.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> digest.update((entry.getKey() + "=" + entry.getValue() + "\n")
                            .getBytes(StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
