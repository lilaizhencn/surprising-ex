package com.surprising.aeron.service.instrument;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreOrderProtection;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 单个标的的身份与配置外壳。整数身份固定，配置和维护状态以两个 volatile 引用分别发布。业务转换保留外壳供已有订单引用，更新时换不可变配置。volatile 只保证各引用可见，不把两项变更合成原子事务。 */
public final class CoreInstrument {
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String instrumentId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int instrumentIdValue;
    /** 当前不可变配置版本，通过替换引用生效。借出当前不可变配置版本。多个独立 getter 不构成跨版本的原子快照。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile Configuration configuration;
    /** 独立发布的不可变维护状态，配置更新不会替换它。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile CoreInstrumentMaintenance maintenance;

    /** 指定维护状态创建标的。 */
    public CoreInstrument(
            String instrumentId, ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks, CoreInstrumentMaintenance maintenance) {
        this(
                instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType,
                strikePriceTicks, maintenance, InstrumentStatus.TRADING, true, true, true, 0b11, 0b1111);
    }

    /** 指定交易状态与功能开关创建标的，数量步长默认一，继续交给完整构造校验。 */
    public CoreInstrument(
            String instrumentId, ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask) {
        this(
                instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType,
                strikePriceTicks, maintenance, instrumentStatus, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled,
                supportedOrderTypeMask, supportedTimeInForceMask, 1L);
    }

    /** 接收显式数量步长，衍生品订单保护使用初始规则，继续交给完整构造校验。 */
    public CoreInstrument(
            String instrumentId, ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask, long quantityStepUnits) {
        this(
                instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType,
                strikePriceTicks, maintenance, instrumentStatus, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled,
                supportedOrderTypeMask, supportedTimeInForceMask, quantityStepUnits,
                contractType == ContractType.SPOT ? null : CoreOrderProtection.initial());
    }

    /** 解析整数身份，规范化资产并复制风险档位，建立配置及维护状态后校验单位、档位覆盖、到期和期权字段。 */
    public CoreInstrument(
            String instrumentId, ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask, long quantityStepUnits,
            CoreOrderProtection orderProtection) {
        this.instrumentIdValue = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.instrumentId = instrumentId;
        this.configuration = new Configuration(
                Objects.requireNonNull(contractType, "contractType"), AssetBalance.normalizeAsset(baseAsset),
                AssetBalance.normalizeAsset(quoteAsset), AssetBalance.normalizeAsset(settleAsset),
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm,
                expiryEpochMillis, optionType, strikePriceTicks,
                Objects.requireNonNull(instrumentStatus, "instrumentStatus"), marketOrderEnabled, postOnlyEnabled,
                reduceOnlyEnabled, supportedOrderTypeMask, supportedTimeInForceMask, quantityStepUnits, orderProtection);
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        validate();
    }

    /** 省略维护状态时以正常交易维护模式创建标的，其他默认项沿用构造链。 */
    public CoreInstrument(
            String instrumentId, ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks) {
        this(
                instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, riskLimits, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType,
                strikePriceTicks, CoreInstrumentMaintenance.TRADING);
    }

    /** 创建查询专用外壳并固定当时读到的配置与维护引用，后续原对象替换引用不会改变这个外壳。 */
    public CoreInstrument captureForQuery() {
        return new CoreInstrument(
                instrumentId, configuration, maintenance);
    }

    /** 为查询固定当前配置与维护引用，复用已校验的不可变值。 */
    private CoreInstrument(String instrumentId, Configuration configuration, CoreInstrumentMaintenance maintenance) {
        this.instrumentId = instrumentId;
        this.instrumentIdValue = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.configuration = configuration;
        this.maintenance = maintenance;
    }

    /** 只核对候选非空且整数标的身份相同，然后替换配置引用。不修改维护状态，合约身份不可变等业务检查由外层转换负责。 */
    public void updateConfiguration(CoreInstrument updated) {
        if (updated == null || instrumentIdValue != updated.instrumentIdValue) throw new IllegalArgumentException("instrument instrumentId mismatch");
        this.configuration = updated.configuration;
    }
    /** 返回当前配置的合约类型，决定产品线和金额公式。 */
    public ContractType contractType() { return configuration.contractType(); }
    /** 返回规范化的基础资产代码。 */
    public String baseAsset() { return configuration.baseAsset(); }
    /** 返回规范化的报价资产代码。 */
    public String quoteAsset() { return configuration.quoteAsset(); }
    /** 返回规范化的结算资产代码。 */
    public String settleAsset() { return configuration.settleAsset(); }
    /** 返回合约名义金额换算乘数。 */
    public long notionalMultiplierUnits() { return configuration.notionalMultiplierUnits(); }
    /** 返回反向换算使用的真实价格单位。其他产品使用固定单位一。 */
    public long priceTickUnits() { return configuration.priceTickUnits(); }
    /** 返回衍生品不可变订单保护规则，现货为空。 */
    public CoreOrderProtection orderProtection() { return configuration.orderProtection(); }
    /** 返回每个数量步对应的资产整数数量。 */
    public long quantityStepUnits() { return configuration.quantityStepUnits(); }
    /** 返回合约金额换算使用的结算精度比例。 */
    public long settleScaleUnits() { return configuration.settleScaleUnits(); }
    /** 衍生品的保证金和限额配置，现货返回空。配置更新时随同原不可变配置发布。 */
    public CoreInstrumentRiskLimits riskLimits() { return configuration.riskLimits(); }

    /** 返回百万分之一口径的默认初始保证金率，具体档位可有更高要求。 */
    public long initialMarginRatePpm() { return configuration.riskLimits().initialMarginRatePpm(); }
    /** 返回百万分之一口径的标的挂单成交费率。 */
    public long makerFeeRatePpm() { return configuration.makerFeeRatePpm(); }
    /** 返回百万分之一口径的标的吃单成交费率。 */
    public long takerFeeRatePpm() { return configuration.takerFeeRatePpm(); }
    /** 返回交割或期权的到期毫秒时间戳。 */
    public long expiryEpochMillis() { return configuration.expiryEpochMillis(); }
    /** 返回期权类型，非期权为空。 */
    public OptionType optionType() { return configuration.optionType(); }
    /** 返回按价格 tick 保存的期权行权价。 */
    public long strikePriceTicks() { return configuration.strikePriceTicks(); }
    /** 返回一百万倍缩放的最大杠杆。 */
    public long maxLeveragePpm() { return configuration.riskLimits().maxLeveragePpm(); }
    /** 返回用户持仓名义金额的固定上限。 */
    public long maxPositionNotionalUnits() { return configuration.riskLimits().maxPositionNotionalUnits(); }
    /** 返回百万分之一口径的用户未平仓规模比例上限。 */
    public long userOpenInterestLimitRatePpm() { return configuration.riskLimits().userOpenInterestLimitRatePpm(); }
    /** 返回未平仓规模比例限额的最低名义金额。 */
    public long userOpenInterestLimitFloorUnits() { return configuration.riskLimits().userOpenInterestLimitFloorUnits(); }
    /** 只有衍生品启用了未平仓比例且其最低额度小于固定上限时，才需要市场未平仓总额参与用户额度计算。 */
    public boolean usesOpenInterestForUserLimit() {
        Configuration current = configuration;
        return current.contractType() != ContractType.SPOT && current.riskLimits().userOpenInterestLimitRatePpm() != 0
                && current.riskLimits().userOpenInterestLimitFloorUnits() < current.riskLimits().maxPositionNotionalUnits();
    }
    /** 返回已经复制为只读列表的连续风险限额档位。 */
    public List<CoreRiskLimitBracket> riskLimitBrackets() { return configuration.riskLimits().riskLimitBrackets(); }
    /** 返回标的业务状态，维护模式另行保存。 */
    public InstrumentStatus instrumentStatus() { return configuration.instrumentStatus(); }
    /** 返回市价单功能是否开启，订单类型位集仍需另行检查。 */
    public boolean marketOrderEnabled() { return configuration.marketOrderEnabled(); }
    /** 返回只挂单功能是否开启。 */
    public boolean postOnlyEnabled() { return configuration.postOnlyEnabled(); }
    /** 返回只减仓功能是否开启，其他准入条件仍需满足。 */
    public boolean reduceOnlyEnabled() { return configuration.reduceOnlyEnabled(); }
    /** 返回允许的订单类型位集，位号按 wireCode 减一。 */
    public int supportedOrderTypeMask() { return configuration.supportedOrderTypeMask(); }
    /** 返回允许的有效期位集，位号按 wireCode 减一。 */
    public int supportedTimeInForceMask() { return configuration.supportedTimeInForceMask(); }

    /** 替换为非空维护状态。 */
    public void updateMaintenance(CoreInstrumentMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    /** 依次检查标的业务状态、现货只减仓限制、维护模式和只减仓开关。 */
    public void requireTrading(boolean reduceOnly) {
        InstrumentStatus status = instrumentStatus();
        if (status != InstrumentStatus.TRADING
                && !(status == InstrumentStatus.SETTLING && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING",
                    "instrument status does not allow this order");
        }
        if (reduceOnly && contractType().productLine() == ProductLine.SPOT) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "spot assets do not support reduce-only orders");
        }
        var mode = maintenance().mode();
        if (mode != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.TRADING
                && !(mode == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING", "instrument is under maintenance");
        }
        if (reduceOnly && !reduceOnlyEnabled()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_DISABLED", "reduce-only orders are disabled");
        }
    }

    /** 从下单意图读取类型、有效期和标志，交给统一准入开关检查。 */
    public void requireOrderEnabled(com.surprising.aeron.protocol.PlaceOrderCommand command) {
        requireOrderEnabled(command.orderType(), command.timeInForce(), command.postOnly(), command.reduceOnly());
    }

    /** 先检查可交易条件，再按协议位号检查订单类型和有效期，最后检查市价与只挂单开关。 */
    public void requireOrderEnabled(com.surprising.aeron.protocol.CoreOrderType orderType,
                                    com.surprising.aeron.protocol.CoreTimeInForce timeInForce,
                                    boolean postOnly, boolean reduceOnly) {
        requireTrading(reduceOnly);
        int orderTypeBit = 1 << (orderType.wireCode() - 1);
        int timeInForceBit = 1 << (timeInForce.wireCode() - 1);
        if ((supportedOrderTypeMask() & orderTypeBit) == 0) {
            throw new CoreStateRejectedException("ORDER_TYPE_DISABLED", "order type is not enabled");
        }
        if ((supportedTimeInForceMask() & timeInForceBit) == 0) {
            throw new CoreStateRejectedException("TIME_IN_FORCE_DISABLED", "time in force is not enabled");
        }
        if (orderType == com.surprising.aeron.protocol.CoreOrderType.MARKET && !marketOrderEnabled()) {
            throw new CoreStateRejectedException("MARKET_ORDER_DISABLED", "market orders are disabled");
        }
        if (postOnly && !postOnlyEnabled()) {
            throw new CoreStateRejectedException("POST_ONLY_DISABLED", "post-only orders are disabled");
        }
    }

    /** 判断是否处于固定价格清仓模式，且任务 ID 与清仓价都和本命令相同。 */
    public boolean administrativeSettlement(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var maintenance = maintenance();
        return maintenance.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && maintenance.taskId() == command.settlementId()
                && maintenance.settlementPriceTicks() == command.settlementPriceTicks();
    }

    /** 检查资产不同、正数单位及限额、合法位集、衍生品风险配置在构造时校验，再核对交割或期权到期字段。 */
    private void validate() {
        Configuration configuration = this.configuration;
        if (configuration.baseAsset().equals(configuration.quoteAsset())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "base and quote assets must differ");
        }
        if (configuration.notionalMultiplierUnits() <= 0 || configuration.priceTickUnits() <= 0 || configuration.quantityStepUnits() <= 0
                || configuration.settleScaleUnits() <= 0
                || configuration.instrumentStatus() == null
                || configuration.supportedOrderTypeMask() <= 0
                || (configuration.supportedOrderTypeMask() & ~0b11) != 0
                || configuration.supportedTimeInForceMask() <= 0
                || (configuration.supportedTimeInForceMask() & ~0b1111) != 0) {
            throw new IllegalArgumentException("invalid instrument");
        }
        if (configuration.contractType().isDelivery() && configuration.expiryEpochMillis() <= 0) {
            throw new IllegalArgumentException("delivery instrument requires expiry time");
        }
        if (configuration.contractType().isOption()) {
            if (configuration.expiryEpochMillis() <= 0 || configuration.optionType() == null
                    || configuration.strikePriceTicks() <= 0) {
                throw new IllegalArgumentException("option instrument requires expiry, type, and strike");
            }
        }
    }

    /** 比较标的文本、完整配置和维护状态，三者都相同才按值相等。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CoreInstrument that)) return false;
        return instrumentId.equals(that.instrumentId) && configuration.equals(that.configuration)
                && maintenance.equals(that.maintenance);
    }

    /** 按标的文本和配置计算哈希，当前实现不纳入维护状态。 */
    @Override
    public int hashCode() {
        return Objects.hash(instrumentId, configuration);
    }

    /** 读取一个配置版本，输出标的、合约类型、资产和维护状态，供诊断使用。 */
    @Override
    public String toString() {
        Configuration current = configuration;
        return "CoreInstrument[instrumentId=" + instrumentId + ", contractType=" + current.contractType()
                + ", baseAsset=" + current.baseAsset() + ", quoteAsset=" + current.quoteAsset()
                + ", settleAsset=" + current.settleAsset() + ", maintenance=" + maintenance + ']';
    }

    /** 把已构造的注册命令转换成标的，确认合约类型属于当前产品线并解释期权类型。 */
    public static CoreInstrument from(ProductLine productLine, RegisterInstrumentCommand command) {
        ContractType contractType = ContractType.valueOf(productLine.contractTypeCode());
        OptionType optionType = null;
        if (contractType.isOption()) {
            if (command.optionTypeCode() < 0 || command.optionTypeCode() >= OptionType.values().length) {
                throw new CoreStateRejectedException("INVALID_OPTION_TYPE", "option type code is unknown");
            }
            optionType = OptionType.values()[command.optionTypeCode()];
        }
        return new CoreInstrument(
                command.instrumentId(), contractType, command.baseAsset(), command.quoteAsset(), command.settleAsset(),
                command.notionalMultiplierUnits(), command.priceTickUnits(), command.settleScaleUnits(),
                command.riskLimits(), command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.expiryEpochMillis(),
                optionType, command.strikePriceTicks(), CoreInstrumentMaintenance.TRADING,
                InstrumentStatus.values()[command.instrumentStatusCode()], command.marketOrderEnabled(),
                command.postOnlyEnabled(), command.reduceOnlyEnabled(), command.supportedOrderTypeMask(),
                command.supportedTimeInForceMask(), command.quantityStepUnits(), command.orderProtection());
    }

    /** 一整版不可变的合约、费率、风险及订单开关配置。 */
    public record Configuration(
            ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
            long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            CoreInstrumentRiskLimits riskLimits, long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
            OptionType optionType, long strikePriceTicks, InstrumentStatus instrumentStatus, boolean marketOrderEnabled,
            boolean postOnlyEnabled, boolean reduceOnlyEnabled, int supportedOrderTypeMask,
            int supportedTimeInForceMask, long quantityStepUnits, CoreOrderProtection orderProtection) {
        public Configuration {
            Objects.requireNonNull(contractType, "contractType");
            orderProtection = contractType == ContractType.SPOT ? null
                    : Objects.requireNonNull(orderProtection, "orderProtection");
            if ((contractType == ContractType.SPOT) != (riskLimits == null))
                throw new IllegalArgumentException("risk limits are required only for derivative instruments");
            if (riskLimits != null) riskLimits = riskLimits.forProduct(contractType.productLine());
            if (contractType != ContractType.SPOT) quantityStepUnits = 1;
            if (!contractType.isInverse()) {
                priceTickUnits = 1;
                settleScaleUnits = 1;
            }
            if (!contractType.isDelivery() && !contractType.isOption()) expiryEpochMillis = 0;
            if (!contractType.isOption()) {
                optionType = null;
                strikePriceTicks = 0;
            }
            Objects.requireNonNull(instrumentStatus, "instrumentStatus");
        }
    }
}
