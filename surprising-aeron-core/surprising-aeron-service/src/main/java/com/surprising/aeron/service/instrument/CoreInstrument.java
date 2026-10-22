package com.surprising.aeron.service.instrument;

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

/**
 * 单个标的的身份与配置外壳。整数身份固定，配置和维护状态以两个 volatile 引用分别发布；业务转换保留外壳供已有订单引用，更新时换不可变配置。volatile 只保证各引用可见，不把两项变更合成原子事务。
 */
public final class CoreInstrument {
    /**  只保留一份的规范协议标的文本，整数身份另用于核心索引。 返回保留的规范协议文本，供外部查询和协议输出使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String instrumentId;
    /**  从协议标的文本解析出的固定整数身份。 返回解析后的整数标的身份，供核心索引使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int instrumentIdValue;
    /**  当前不可变配置版本，通过替换引用生效；读者需要同版字段时先固定该引用。 借出当前不可变配置版本；多个独立 getter 不构成跨版本的原子快照。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile Configuration configuration;
    /**  独立发布的不可变维护状态，配置更新不会替换它。 返回当前独立维护状态，包含任务归属和清仓价。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile CoreInstrumentMaintenance maintenance;

    /**
     * 指定维护状态创建标的；默认处于可交易状态，开启市价、只挂单、只减仓和全部已知订单类型及有效期，数量步长使用一。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码，完整构造时规范为大写资产名
     * @param quoteAsset 报价资产代码，完整构造时规范化且要求与基础资产不同
     * @param settleAsset 结算资产代码，完整构造时规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 从第一档开始连续覆盖的风险限额档位，构造时复制为只读列表
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     */
    public CoreInstrument(String instrumentId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets,
            CoreInstrumentMaintenance maintenance) {
        this(instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, InstrumentStatus.TRADING,
                true, true, true, 0b11, 0b1111);
    }

    /**
     * 指定交易状态与功能开关创建标的，数量步长默认一，继续交给完整构造校验。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码，完整构造时规范为大写资产名
     * @param quoteAsset 报价资产代码，完整构造时规范化且要求与基础资产不同
     * @param settleAsset 结算资产代码，完整构造时规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 从第一档开始连续覆盖的风险限额档位，构造时复制为只读列表
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     * @param instrumentStatus 标的业务状态，与维护任务模式分别判断
     * @param marketOrderEnabled 是否额外允许市价单，仍需通过订单类型掩码检查
     * @param postOnlyEnabled 是否允许只挂单委托
     * @param reduceOnlyEnabled 是否允许只减仓委托，仍需满足产品线、标的状态和维护模式
     * @param supportedOrderTypeMask 允许的订单类型位集，第 wireCode 减一位表示该类型
     * @param supportedTimeInForceMask 允许的有效期位集，第 wireCode 减一位表示该有效期
     */
    public CoreInstrument(String instrumentId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask) {
        this(instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, instrumentStatus,
                marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled, supportedOrderTypeMask,
                supportedTimeInForceMask, 1L);
    }

    /**
     * 接收显式数量步长，订单保护使用初始规则，继续交给完整构造校验。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码，完整构造时规范为大写资产名
     * @param quoteAsset 报价资产代码，完整构造时规范化且要求与基础资产不同
     * @param settleAsset 结算资产代码，完整构造时规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 从第一档开始连续覆盖的风险限额档位，构造时复制为只读列表
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     * @param instrumentStatus 标的业务状态，与维护任务模式分别判断
     * @param marketOrderEnabled 是否额外允许市价单，仍需通过订单类型掩码检查
     * @param postOnlyEnabled 是否允许只挂单委托
     * @param reduceOnlyEnabled 是否允许只减仓委托，仍需满足产品线、标的状态和维护模式
     * @param supportedOrderTypeMask 允许的订单类型位集，第 wireCode 减一位表示该类型
     * @param supportedTimeInForceMask 允许的有效期位集，第 wireCode 减一位表示该有效期
     * @param quantityStepUnits 每个数量步对应的资产整数数量，必须为正
     */
    public CoreInstrument(String instrumentId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask,
            long quantityStepUnits) {
        this(instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks, maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits, riskLimitBrackets, maintenance, instrumentStatus, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled, supportedOrderTypeMask, supportedTimeInForceMask, quantityStepUnits, CoreOrderProtection.initial());
    }

    /**
     * 解析整数身份，规范化资产并复制风险档位，建立配置及维护状态后校验单位、档位覆盖、到期和期权字段；构造本身不核对所属运行时产品线。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码，完整构造时规范为大写资产名
     * @param quoteAsset 报价资产代码，完整构造时规范化且要求与基础资产不同
     * @param settleAsset 结算资产代码，完整构造时规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 从第一档开始连续覆盖的风险限额档位，构造时复制为只读列表
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     * @param instrumentStatus 标的业务状态，与维护任务模式分别判断
     * @param marketOrderEnabled 是否额外允许市价单，仍需通过订单类型掩码检查
     * @param postOnlyEnabled 是否允许只挂单委托
     * @param reduceOnlyEnabled 是否允许只减仓委托，仍需满足产品线、标的状态和维护模式
     * @param supportedOrderTypeMask 允许的订单类型位集，第 wireCode 减一位表示该类型
     * @param supportedTimeInForceMask 允许的有效期位集，第 wireCode 减一位表示该有效期
     * @param quantityStepUnits 每个数量步对应的资产整数数量，必须为正
     * @param orderProtection 订单价格和数量保护规则，必须非空
     */
    public CoreInstrument(String instrumentId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets, CoreInstrumentMaintenance maintenance,
            InstrumentStatus instrumentStatus, boolean marketOrderEnabled, boolean postOnlyEnabled,
            boolean reduceOnlyEnabled, int supportedOrderTypeMask, int supportedTimeInForceMask,
            long quantityStepUnits, CoreOrderProtection orderProtection) {
        this.instrumentIdValue = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.instrumentId = instrumentId;
        this.configuration = new Configuration(Objects.requireNonNull(contractType, "contractType"),
                AssetBalance.normalizeAsset(baseAsset), AssetBalance.normalizeAsset(quoteAsset),
                AssetBalance.normalizeAsset(settleAsset), notionalMultiplierUnits, priceTickUnits,
                settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm, makerFeeRatePpm,
                takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks, maxLeveragePpm,
                maxPositionNotionalUnits, userOpenInterestLimitRatePpm, userOpenInterestLimitFloorUnits,
                List.copyOf(Objects.requireNonNull(riskLimitBrackets, "riskLimitBrackets")),
                Objects.requireNonNull(instrumentStatus, "instrumentStatus"), marketOrderEnabled,
                postOnlyEnabled, reduceOnlyEnabled, supportedOrderTypeMask, supportedTimeInForceMask, quantityStepUnits, orderProtection);
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        validate();
    }

    /**
     * 省略维护状态时以正常交易维护模式创建标的，其他默认项沿用构造链。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码，完整构造时规范为大写资产名
     * @param quoteAsset 报价资产代码，完整构造时规范化且要求与基础资产不同
     * @param settleAsset 结算资产代码，完整构造时规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 从第一档开始连续覆盖的风险限额档位，构造时复制为只读列表
     */
    public CoreInstrument(String instrumentId, ContractType contractType, String baseAsset,
            String quoteAsset, String settleAsset, long notionalMultiplierUnits, long priceTickUnits,
            long settleScaleUnits, long initialMarginRatePpm, long maintenanceMarginRatePpm,
            long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis, OptionType optionType,
            long strikePriceTicks, long maxLeveragePpm, long maxPositionNotionalUnits,
            long userOpenInterestLimitRatePpm, long userOpenInterestLimitFloorUnits,
            List<CoreRiskLimitBracket> riskLimitBrackets) {
        this(instrumentId, contractType, baseAsset, quoteAsset, settleAsset, notionalMultiplierUnits,
                priceTickUnits, settleScaleUnits, initialMarginRatePpm, maintenanceMarginRatePpm,
                makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionType, strikePriceTicks,
                maxLeveragePpm, maxPositionNotionalUnits, userOpenInterestLimitRatePpm,
                userOpenInterestLimitFloorUnits, riskLimitBrackets,
                CoreInstrumentMaintenance.TRADING);
    }

    /**
     * 创建查询专用外壳并固定当时读到的配置与维护引用，后续原对象替换引用不会改变这个外壳；固定动作仍依赖调用方的查询边界。
     */
    public CoreInstrument captureForQuery() {
        return new CoreInstrument(instrumentId, configuration, maintenance);
    }

    /**
     * 为查询固定当前配置与维护引用，复用已校验的不可变值；仍创建独立外壳，不重新执行完整配置校验。
     * @param instrumentId 协议使用的规范数字标的 ID 文本，内部另保存解析后的整数身份
     * @param configuration 需要接入的非空配置版本；此接入方法不重新执行完整业务校验
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     */
    private CoreInstrument(String instrumentId, Configuration configuration, CoreInstrumentMaintenance maintenance) {
        this.instrumentId = instrumentId;
        this.instrumentIdValue = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        this.configuration = configuration;
        this.maintenance = maintenance;
    }

    /**
     * 只核对候选非空且整数标的身份相同，然后替换配置引用；不修改维护状态，合约身份不可变等业务检查由外层转换负责。
     * @param updated 同一整数标的身份的候选对象，借用其配置，保留本对象的维护状态
     */
    public void updateConfiguration(CoreInstrument updated) {
        if (updated == null || instrumentIdValue != updated.instrumentIdValue) throw new IllegalArgumentException("instrument instrumentId mismatch");
        this.configuration = updated.configuration;
    }
    /**
     * 接回非空配置引用，供恢复或回滚使用，不在这里重新验证所有配置字段。
     * @param configuration 需要接入的非空配置版本；此接入方法不重新执行完整业务校验
     */
    public void restoreConfiguration(Configuration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    /**
     * 返回当前配置的合约类型，决定产品线和金额公式。
     */
    public ContractType contractType() { return configuration.contractType(); }
    /**
     * 返回规范化的基础资产代码。
     */
    public String baseAsset() { return configuration.baseAsset(); }
    /**
     * 返回规范化的报价资产代码。
     */
    public String quoteAsset() { return configuration.quoteAsset(); }
    /**
     * 返回规范化的结算资产代码。
     */
    public String settleAsset() { return configuration.settleAsset(); }
    /**
     * 返回合约名义金额换算乘数。
     */
    public long notionalMultiplierUnits() { return configuration.notionalMultiplierUnits(); }
    /**
     * 返回每个价格 tick 对应的整数价格单位。
     */
    public long priceTickUnits() { return configuration.priceTickUnits(); }
    /**
     * 返回当前不可变订单保护规则。
     */
    public CoreOrderProtection orderProtection() { return configuration.orderProtection(); }
    /**
     * 返回每个数量步对应的资产整数数量。
     */
    public long quantityStepUnits() { return configuration.quantityStepUnits(); }
    /**
     * 返回合约金额换算使用的结算精度比例。
     */
    public long settleScaleUnits() { return configuration.settleScaleUnits(); }
    /**
     * 返回百万分之一口径的默认初始保证金率，具体档位可有更高要求。
     */
    public long initialMarginRatePpm() { return configuration.initialMarginRatePpm(); }
    /**
     * 返回百万分之一口径的默认维持保证金率。
     */
    public long maintenanceMarginRatePpm() { return configuration.maintenanceMarginRatePpm(); }
    /**
     * 返回百万分之一口径的标的挂单成交费率。
     */
    public long makerFeeRatePpm() { return configuration.makerFeeRatePpm(); }
    /**
     * 返回百万分之一口径的标的吃单成交费率。
     */
    public long takerFeeRatePpm() { return configuration.takerFeeRatePpm(); }
    /**
     * 返回交割或期权的到期毫秒时间戳。
     */
    public long expiryEpochMillis() { return configuration.expiryEpochMillis(); }
    /**
     * 返回期权类型，非期权为空。
     */
    public OptionType optionType() { return configuration.optionType(); }
    /**
     * 返回按价格 tick 保存的期权行权价。
     */
    public long strikePriceTicks() { return configuration.strikePriceTicks(); }
    /**
     * 返回一百万倍缩放的最大杠杆。
     */
    public long maxLeveragePpm() { return configuration.maxLeveragePpm(); }
    /**
     * 返回用户持仓名义金额的固定上限。
     */
    public long maxPositionNotionalUnits() { return configuration.maxPositionNotionalUnits(); }
    /**
     * 返回百万分之一口径的用户未平仓规模比例上限。
     */
    public long userOpenInterestLimitRatePpm() { return configuration.userOpenInterestLimitRatePpm(); }
    /**
     * 返回未平仓规模比例限额的最低名义金额。
     */
    public long userOpenInterestLimitFloorUnits() { return configuration.userOpenInterestLimitFloorUnits(); }
    /**
     * 只有衍生品启用了未平仓比例且其最低额度小于固定上限时，才需要市场未平仓总额参与用户额度计算。
     */
    public boolean usesOpenInterestForUserLimit() {
        // 本次读取固定下来的配置版本，防止同次计算混用前后配置
        Configuration current = configuration;
        return current.contractType() != ContractType.SPOT && current.userOpenInterestLimitRatePpm() != 0
                && current.userOpenInterestLimitFloorUnits() < current.maxPositionNotionalUnits();
    }
    /**
     * 返回已经复制为只读列表的连续风险限额档位。
     */
    public List<CoreRiskLimitBracket> riskLimitBrackets() { return configuration.riskLimitBrackets(); }
    /**
     * 返回标的业务状态，维护模式另行保存。
     */
    public InstrumentStatus instrumentStatus() { return configuration.instrumentStatus(); }
    /**
     * 返回市价单功能是否开启，订单类型位集仍需另行检查。
     */
    public boolean marketOrderEnabled() { return configuration.marketOrderEnabled(); }
    /**
     * 返回只挂单功能是否开启。
     */
    public boolean postOnlyEnabled() { return configuration.postOnlyEnabled(); }
    /**
     * 返回只减仓功能是否开启，其他准入条件仍需满足。
     */
    public boolean reduceOnlyEnabled() { return configuration.reduceOnlyEnabled(); }
    /**
     * 返回允许的订单类型位集，位号按 wireCode 减一。
     */
    public int supportedOrderTypeMask() { return configuration.supportedOrderTypeMask(); }
    /**
     * 返回允许的有效期位集，位号按 wireCode 减一。
     */
    public int supportedTimeInForceMask() { return configuration.supportedTimeInForceMask(); }

    /**
     * 替换为非空维护状态；任务归属、清仓完成等转换条件由外层检查。
     * @param maintenance 独立保存的维护状态，包含任务 ID、运行模式和固定清仓价格
     */
    public void updateMaintenance(CoreInstrumentMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    /**
     * 依次检查标的业务状态、现货只减仓限制、维护模式和只减仓开关；标的业务状态为 SETTLING 时仅容许只减仓，其他不满足条件的请求抛出业务拒绝。
     * @param reduceOnly 本次委托是否只减仓，用于判断状态、维护模式及功能开关
     */
    public void requireTrading(boolean reduceOnly) {
        // 当前标的业务状态，先于维护任务模式判断
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
        // 当前维护任务模式，用于区分交易、只减仓与停用
        var mode = maintenance().mode();
        if (mode != com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.TRADING
                && !(mode == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.REDUCE_ONLY && reduceOnly)) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_TRADING", "instrument is under maintenance");
        }
        if (reduceOnly && !reduceOnlyEnabled()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_DISABLED", "reduce-only orders are disabled");
        }
    }

    /**
     * 从下单意图读取类型、有效期和标志，交给统一准入开关检查。
     * @param command 本次需要检查交易开关的下单意图
     */
    public void requireOrderEnabled(com.surprising.aeron.protocol.PlaceOrderCommand command) {
        requireOrderEnabled(command.orderType(), command.timeInForce(), command.postOnly(), command.reduceOnly());
    }

    /**
     * 先检查可交易条件，再按协议位号检查订单类型和有效期，最后检查市价与只挂单开关；不在这里检查价格、数量或余额。
     * @param orderType 委托的限价或市价类型
     * @param timeInForce 委托采用的有效期规则
     * @param postOnly 本次委托是否要求只挂单
     * @param reduceOnly 本次委托是否只减仓，用于判断状态、维护模式及功能开关
     */
    public void requireOrderEnabled(com.surprising.aeron.protocol.CoreOrderType orderType,
                                    com.surprising.aeron.protocol.CoreTimeInForce timeInForce,
                                    boolean postOnly, boolean reduceOnly) {
        requireTrading(reduceOnly);
        // 本次订单类型在允许位集中的单独一位
        int orderTypeBit = 1 << (orderType.wireCode() - 1);
        // 本次有效期在允许位集中的单独一位
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

    /**
     * 判断是否处于固定价格清仓模式，且任务 ID 与清仓价都和本命令相同；只做匹配，不检查或改变持仓及结算进度。
     * @param command 需要匹配维护任务 ID 和固定清仓价的结算命令
     */
    public boolean administrativeSettlement(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        // 本次固定的维护状态，用任务身份和价格与结算命令比较
        var maintenance = maintenance();
        return maintenance.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && maintenance.taskId() == command.settlementId()
                && maintenance.settlementPriceTicks() == command.settlementPriceTicks();
    }

    /**
     * 检查资产不同、正数单位及限额、合法位集、连续且覆盖上限的风险档位，再核对交割或期权到期字段。此方法只覆盖源码列出的条件，不等于协议构造器的全部参数校验。
     */
    private void validate() {
        // 本次构造完成待校验的配置版本
        Configuration configuration = this.configuration;
        if (configuration.baseAsset().equals(configuration.quoteAsset())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "base and quote assets must differ");
        }
        if (configuration.notionalMultiplierUnits() <= 0 || configuration.priceTickUnits() <= 0 || configuration.quantityStepUnits() <= 0
                || configuration.settleScaleUnits() <= 0 || configuration.initialMarginRatePpm() <= 0
                || configuration.maintenanceMarginRatePpm() <= 0
                || configuration.maxLeveragePpm() < 1_000_000L || configuration.maxPositionNotionalUnits() <= 0
                || configuration.userOpenInterestLimitRatePpm() < 0
                || configuration.userOpenInterestLimitFloorUnits() <= 0
                || configuration.riskLimitBrackets().isEmpty() || configuration.instrumentStatus() == null
                || configuration.supportedOrderTypeMask() <= 0
                || (configuration.supportedOrderTypeMask() & ~0b11) != 0
                || configuration.supportedTimeInForceMask() <= 0
                || (configuration.supportedTimeInForceMask() & ~0b1111) != 0) {
            throw new IllegalArgumentException("invalid instrument");
        }
        // 上一档上界，下一档下界必须与它衔接，循环后作为覆盖总上限
        long previousCap = 0;
        // 下一档必须采用的连续档号，从一开始
        int expectedBracketNo = 1;
        for (CoreRiskLimitBracket /* 当前检查的风险档位 */ bracket : configuration.riskLimitBrackets()) {
            if (bracket.bracketNo() != expectedBracketNo++
                    || bracket.notionalFloorUnits() != previousCap
                    || bracket.maxLeveragePpm() > configuration.maxLeveragePpm()) {
                throw new IllegalArgumentException("risk limit brackets must be contiguous and bounded");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < configuration.maxPositionNotionalUnits()) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
        }
        if (configuration.contractType().isDelivery() && configuration.expiryEpochMillis() <= 0) {
            throw new IllegalArgumentException("delivery instrument requires expiry time");
        }
        if (configuration.contractType().isOption()) {
            if (configuration.expiryEpochMillis() <= 0 || configuration.optionType() == null
                    || configuration.strikePriceTicks() <= 0) {
                throw new IllegalArgumentException("option instrument requires expiry, type, and strike");
            }
        } else if (configuration.optionType() != null || configuration.strikePriceTicks() != 0) {
            throw new IllegalArgumentException("non-option instrument contains option parameters");
        }
    }

    /**
     * 比较标的文本、完整配置和维护状态，三者都相同才按值相等；对象内容可更新，持有者应遵守访问边界。
     * @param other 参与值比较的任意对象
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CoreInstrument /* 已确认同为标的对象的比较目标 */ that)) return false;
        return instrumentId.equals(that.instrumentId) && configuration.equals(that.configuration)
                && maintenance.equals(that.maintenance);
    }

    /**
     * 按标的文本和配置计算哈希，当前实现不纳入维护状态；配置变化后哈希也会变化。
     */
    @Override
    public int hashCode() {
        return Objects.hash(instrumentId, configuration);
    }

    /**
     * 读取一个配置版本，输出标的、合约类型、资产和维护状态，供诊断使用。
     */
    @Override
    public String toString() {
        // 本次读取固定下来的配置版本，防止同次计算混用前后配置
        Configuration current = configuration;
        return "CoreInstrument[instrumentId=" + instrumentId + ", contractType=" + current.contractType()
                + ", baseAsset=" + current.baseAsset() + ", quoteAsset=" + current.quoteAsset()
                + ", settleAsset=" + current.settleAsset() + ", maintenance=" + maintenance + ']';
    }

    /**
     * 把已构造的注册命令转换成标的，确认合约类型属于当前产品线并解释期权类型；初始维护为正常交易，实际注册时保留旧维护状态由外层负责。
     * @param productLine 该标的必须归属的运行时产品线
     * @param command 已由协议构造器检查基础边界的注册或配置更新命令
     */
    public static CoreInstrument from(ProductLine productLine, RegisterInstrumentCommand command) {
        // 合约类型枚举数组，注册命令的类型码按下标解释
        ContractType[] values = ContractType.values();
        if (command.contractTypeCode() >= values.length) {
            throw new CoreStateRejectedException("INVALID_CONTRACT_TYPE", "contract type code is unknown");
        }
        // 类型码选出的具体合约类型，必须属于本运行时产品线
        ContractType contractType = values[command.contractTypeCode()];
        if (contractType.productLine() != productLine) {
            throw new CoreStateRejectedException("PRODUCT_LINE_MISMATCH", "instrument belongs to another product line");
        }
        // 解释出的看涨或看跌类型，非期权时保持为空
        OptionType optionType = null;
        if (contractType.isOption()) {
            if (command.optionTypeCode() < 0 || command.optionTypeCode() >= OptionType.values().length) {
                throw new CoreStateRejectedException("INVALID_OPTION_TYPE", "option type code is unknown");
            }
            optionType = OptionType.values()[command.optionTypeCode()];
        } else if (command.optionTypeCode() != -1) {
            throw new CoreStateRejectedException("INVALID_OPTION_TYPE", "non-option must not set option type");
        }
        return new CoreInstrument(command.instrumentId(), contractType,
                command.baseAsset(), command.quoteAsset(), command.settleAsset(),
                command.notionalMultiplierUnits(), command.priceTickUnits(), command.settleScaleUnits(),
                command.initialMarginRatePpm(), command.maintenanceMarginRatePpm(),
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), command.expiryEpochMillis(),
                optionType, command.strikePriceTicks(), command.maxLeveragePpm(),
                command.maxPositionNotionalUnits(), command.userOpenInterestLimitRatePpm(),
                command.userOpenInterestLimitFloorUnits(), command.riskLimitBrackets(),
                CoreInstrumentMaintenance.TRADING, InstrumentStatus.values()[command.instrumentStatusCode()],
                command.marketOrderEnabled(), command.postOnlyEnabled(), command.reduceOnlyEnabled(),
                command.supportedOrderTypeMask(), command.supportedTimeInForceMask(), command.quantityStepUnits(), command.orderProtection());
    }

    /**
     * 一整版不可变的合约、费率、风险及订单开关配置；风险档位列表在构造时复制，维护任务单独存放在外层。
     * @param contractType 现货、正反向永续或交割、期权中的具体合约类型
     * @param baseAsset 基础资产代码；由 CoreInstrument 完整构造路径规范化
     * @param quoteAsset 报价资产代码；由 CoreInstrument 完整构造路径规范化并检查不同于基础资产
     * @param settleAsset 结算资产代码；由 CoreInstrument 完整构造路径规范化
     * @param notionalMultiplierUnits 每份数量对应的名义金额乘数，使用合约计算约定的整数单位
     * @param priceTickUnits 每个价格 tick 对应的整数价格单位，供合约金额换算使用
     * @param settleScaleUnits 合约金额换算到结算资产整数单位时使用的精度比例
     * @param initialMarginRatePpm 初始保证金率，按百万分之一保存
     * @param maintenanceMarginRatePpm 维持保证金率，按百万分之一保存
     * @param makerFeeRatePpm 挂单成交费率，按百万分之一保存，负数表示返佣
     * @param takerFeeRatePpm 吃单成交费率，按百万分之一保存，负数表示返佣
     * @param expiryEpochMillis 到期毫秒时间戳，交割和期权必须为正
     * @param optionType 期权看涨或看跌类型，非期权必须为空
     * @param strikePriceTicks 期权行权价格，按价格 tick 保存，非期权必须为零
     * @param maxLeveragePpm 最大杠杆，按一百万倍缩放，一百万表示一倍
     * @param maxPositionNotionalUnits 该标的允许的用户持仓名义金额固定上限
     * @param userOpenInterestLimitRatePpm 按市场未平仓规模计算用户上限的比例，按百万分之一保存，零表示不使用该比例
     * @param userOpenInterestLimitFloorUnits 按未平仓规模计算用户上限时采用的最低名义金额
     * @param riskLimitBrackets 需要连续覆盖限额的风险档位；本 record 复制列表，完整覆盖校验由外层执行
     * @param instrumentStatus 标的业务状态，与维护任务模式分别判断
     * @param marketOrderEnabled 是否额外允许市价单，仍需通过订单类型掩码检查
     * @param postOnlyEnabled 是否允许只挂单委托
     * @param reduceOnlyEnabled 是否允许只减仓委托，仍需满足产品线、标的状态和维护模式
     * @param supportedOrderTypeMask 允许的订单类型位集，第 wireCode 减一位表示该类型
     * @param supportedTimeInForceMask 允许的有效期位集，第 wireCode 减一位表示该有效期
     * @param quantityStepUnits 每个数量步对应的资产整数数量，必须为正
     * @param orderProtection 订单价格和数量保护规则，必须非空
     */
    public record Configuration(ContractType contractType, String baseAsset, String quoteAsset, String settleAsset,
                                long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
                                long initialMarginRatePpm, long maintenanceMarginRatePpm,
                                long makerFeeRatePpm, long takerFeeRatePpm, long expiryEpochMillis,
                                OptionType optionType, long strikePriceTicks, long maxLeveragePpm,
                                long maxPositionNotionalUnits, long userOpenInterestLimitRatePpm,
                                long userOpenInterestLimitFloorUnits, List<CoreRiskLimitBracket> riskLimitBrackets,
                                InstrumentStatus instrumentStatus, boolean marketOrderEnabled,
                                boolean postOnlyEnabled, boolean reduceOnlyEnabled, int supportedOrderTypeMask,
                                int supportedTimeInForceMask, long quantityStepUnits, CoreOrderProtection orderProtection) {
        /**
         * 要求保护规则和业务状态非空，并复制风险档位列表；完整业务参数约束由协议及 CoreInstrument 的构造校验负责。
         */
        public Configuration {
            Objects.requireNonNull(orderProtection, "orderProtection");
            riskLimitBrackets = List.copyOf(riskLimitBrackets);
            Objects.requireNonNull(instrumentStatus, "instrumentStatus");
        }
    }
}
