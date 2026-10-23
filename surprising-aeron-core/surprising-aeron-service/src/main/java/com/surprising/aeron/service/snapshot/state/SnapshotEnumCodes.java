package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 快照和确定性业务表示使用的固定枚举编号表。每种业务枚举单独双向映射，已有数字保持不变，新增 Java 枚举不能靠改变 ordinal 混入历史快照；未知持久化编号一律拒绝。 构造约定：工具类不创建实例，编码由固定映射确定。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotEnumCodes {
    /**
     * 把预留用途写成固定持久化编号：SPOT_ASSET=0、DERIVATIVE_MARGIN=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的预留用途枚举值
     */
    public static int encode(ReservationKind value) {
        return switch (value) {
            case SPOT_ASSET -> 0;
            case DERIVATIVE_MARGIN -> 1;
        };
    }
    /**
     * 把固定编号恢复为预留用途：SPOT_ASSET=0、DERIVATIVE_MARGIN=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的预留用途固定编号
     */
    static ReservationKind readReservationKind(int value) {
        return switch (value) {
            case 0 -> ReservationKind.SPOT_ASSET;
            case 1 -> ReservationKind.DERIVATIVE_MARGIN;
            default -> throw new ProtocolException("unknown persisted ReservationKind code: " + value);
        };
    }
    /**
     * 把保证金模式写成固定持久化编号：CROSS=0、ISOLATED=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的保证金模式枚举值
     */
    public static int encode(CoreMarginMode value) {
        return switch (value) {
            case CROSS -> 0;
            case ISOLATED -> 1;
        };
    }
    /**
     * 把固定编号恢复为保证金模式：CROSS=0、ISOLATED=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的保证金模式固定编号
     */
    static CoreMarginMode readCoreMarginMode(int value) {
        return switch (value) {
            case 0 -> CoreMarginMode.CROSS;
            case 1 -> CoreMarginMode.ISOLATED;
            default -> throw new ProtocolException("unknown persisted CoreMarginMode code: " + value);
        };
    }
    /**
     * 把持仓方向写成固定持久化编号：NET=0、LONG=1、SHORT=2。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的持仓方向枚举值
     */
    public static int encode(CorePositionSide value) {
        return switch (value) {
            case NET -> 0;
            case LONG -> 1;
            case SHORT -> 2;
        };
    }
    /**
     * 把固定编号恢复为持仓方向：NET=0、LONG=1、SHORT=2；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的持仓方向固定编号
     */
    static CorePositionSide readCorePositionSide(int value) {
        return switch (value) {
            case 0 -> CorePositionSide.NET;
            case 1 -> CorePositionSide.LONG;
            case 2 -> CorePositionSide.SHORT;
            default -> throw new ProtocolException("unknown persisted CorePositionSide code: " + value);
        };
    }
    /**
     * 把产品线写成固定持久化编号：现货=0、U 本位永续=1、币本位永续=2、U 本位交割=3、币本位交割=4、期权=5。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的产品线枚举值
     */
    public static int encode(ProductLine value) {
        return switch (value) {
            case SPOT -> 0;
            case LINEAR_PERPETUAL -> 1;
            case INVERSE_PERPETUAL -> 2;
            case LINEAR_DELIVERY -> 3;
            case INVERSE_DELIVERY -> 4;
            case OPTION -> 5;
        };
    }
    /**
     * 把固定编号恢复为产品线：现货=0、U 本位永续=1、币本位永续=2、U 本位交割=3、币本位交割=4、期权=5；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的产品线固定编号
     */
    static ProductLine readProductLine(int value) {
        return switch (value) {
            case 0 -> ProductLine.SPOT;
            case 1 -> ProductLine.LINEAR_PERPETUAL;
            case 2 -> ProductLine.INVERSE_PERPETUAL;
            case 3 -> ProductLine.LINEAR_DELIVERY;
            case 4 -> ProductLine.INVERSE_DELIVERY;
            case 5 -> ProductLine.OPTION;
            default -> throw new ProtocolException("unknown persisted ProductLine code: " + value);
        };
    }
    /**
     * 把买卖方向写成固定持久化编号：BUY=0、SELL=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的买卖方向枚举值
     */
    public static int encode(CoreOrderSide value) {
        return switch (value) {
            case BUY -> 0;
            case SELL -> 1;
        };
    }
    /**
     * 把固定编号恢复为买卖方向：BUY=0、SELL=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的买卖方向固定编号
     */
    static CoreOrderSide readCoreOrderSide(int value) {
        return switch (value) {
            case 0 -> CoreOrderSide.BUY;
            case 1 -> CoreOrderSide.SELL;
            default -> throw new ProtocolException("unknown persisted CoreOrderSide code: " + value);
        };
    }
    /**
     * 把订单类型写成固定持久化编号：LIMIT=0、MARKET=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的订单类型枚举值
     */
    public static int encode(CoreOrderType value) {
        return switch (value) {
            case LIMIT -> 0;
            case MARKET -> 1;
        };
    }
    /**
     * 把固定编号恢复为订单类型：LIMIT=0、MARKET=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的订单类型固定编号
     */
    static CoreOrderType readCoreOrderType(int value) {
        return switch (value) {
            case 0 -> CoreOrderType.LIMIT;
            case 1 -> CoreOrderType.MARKET;
            default -> throw new ProtocolException("unknown persisted CoreOrderType code: " + value);
        };
    }
    /**
     * 把订单有效方式写成固定持久化编号：GTC=0、IOC=1、FOK=2、GTX=3。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的订单有效方式枚举值
     */
    public static int encode(CoreTimeInForce value) {
        return switch (value) {
            case GTC -> 0;
            case IOC -> 1;
            case FOK -> 2;
            case GTX -> 3;
        };
    }
    /**
     * 把固定编号恢复为订单有效方式：GTC=0、IOC=1、FOK=2、GTX=3；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的订单有效方式固定编号
     */
    static CoreTimeInForce readCoreTimeInForce(int value) {
        return switch (value) {
            case 0 -> CoreTimeInForce.GTC;
            case 1 -> CoreTimeInForce.IOC;
            case 2 -> CoreTimeInForce.FOK;
            case 3 -> CoreTimeInForce.GTX;
            default -> throw new ProtocolException("unknown persisted CoreTimeInForce code: " + value);
        };
    }
    /**
     * 把普通订单状态写成固定持久化编号：OPEN=0、CANCELED=1、FILLED=2、REJECTED=3。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的普通订单状态枚举值
     */
    public static int encode(CoreOrderStatus value) {
        return switch (value) {
            case OPEN -> 0;
            case CANCELED -> 1;
            case FILLED -> 2;
            case REJECTED -> 3;
        };
    }
    /**
     * 把固定编号恢复为普通订单状态：OPEN=0、CANCELED=1、FILLED=2、REJECTED=3；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的普通订单状态固定编号
     */
    static CoreOrderStatus readCoreOrderStatus(int value) {
        return switch (value) {
            case 0 -> CoreOrderStatus.OPEN;
            case 1 -> CoreOrderStatus.CANCELED;
            case 2 -> CoreOrderStatus.FILLED;
            case 3 -> CoreOrderStatus.REJECTED;
            default -> throw new ProtocolException("unknown persisted CoreOrderStatus code: " + value);
        };
    }
    /**
     * 把账户风险状态写成固定持久化编号：NORMAL=0、WARNING=1、LIQUIDATION=2。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的账户风险状态枚举值
     */
    public static int encode(CoreRiskStatus value) {
        return switch (value) {
            case NORMAL -> 0;
            case WARNING -> 1;
            case LIQUIDATION -> 2;
        };
    }
    /**
     * 把强平业务状态写成固定持久化编号：PLANNED=0、ORDERED=1、COMPLETED=2、INSURANCE_REQUIRED=3、ADL_REQUIRED=4、CANCELED=5。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的强平业务状态枚举值
     */
    public static int encode(CoreLiquidationState.Status value) {
        return switch (value) {
            case PLANNED -> 0;
            case ORDERED -> 1;
            case COMPLETED -> 2;
            case INSURANCE_REQUIRED -> 3;
            case ADL_REQUIRED -> 4;
            case CANCELED -> 5;
        };
    }
    /**
     * 把固定编号恢复为强平业务状态：PLANNED=0、ORDERED=1、COMPLETED=2、INSURANCE_REQUIRED=3、ADL_REQUIRED=4、CANCELED=5；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的强平业务状态固定编号
     */
    static CoreLiquidationState.Status readCoreLiquidationStateStatus(int value) {
        return switch (value) {
            case 0 -> CoreLiquidationState.Status.PLANNED;
            case 1 -> CoreLiquidationState.Status.ORDERED;
            case 2 -> CoreLiquidationState.Status.COMPLETED;
            case 3 -> CoreLiquidationState.Status.INSURANCE_REQUIRED;
            case 4 -> CoreLiquidationState.Status.ADL_REQUIRED;
            case 5 -> CoreLiquidationState.Status.CANCELED;
            default -> throw new ProtocolException("unknown persisted CoreLiquidationState.Status code: " + value);
        };
    }
    /**
     * 把币对维护模式写成固定持久化编号：TRADING=0、REDUCE_ONLY=1、HALTED=2、SETTLEMENT=3、CLOSED=4。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的币对维护模式枚举值
     */
    public static int encode(CoreInstrumentMaintenance.Mode value) {
        return switch (value) {
            case TRADING -> 0;
            case REDUCE_ONLY -> 1;
            case HALTED -> 2;
            case SETTLEMENT -> 3;
            case CLOSED -> 4;
        };
    }
    /**
     * 把固定编号恢复为币对维护模式：TRADING=0、REDUCE_ONLY=1、HALTED=2、SETTLEMENT=3、CLOSED=4；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的币对维护模式固定编号
     */
    static CoreInstrumentMaintenance.Mode readCoreInstrumentMaintenanceMode(int value) {
        return switch (value) {
            case 0 -> CoreInstrumentMaintenance.Mode.TRADING;
            case 1 -> CoreInstrumentMaintenance.Mode.REDUCE_ONLY;
            case 2 -> CoreInstrumentMaintenance.Mode.HALTED;
            case 3 -> CoreInstrumentMaintenance.Mode.SETTLEMENT;
            case 4 -> CoreInstrumentMaintenance.Mode.CLOSED;
            default -> throw new ProtocolException("unknown persisted CoreInstrumentMaintenance.Mode code: " + value);
        };
    }
    /**
     * 把倒计时撤单状态写成固定持久化编号：DISABLED=0、ACTIVE=1、TRIGGERING=2、TRIGGERED=3。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的倒计时撤单状态枚举值
     */
    public static int encode(CoreCancelAllAfterStatus value) {
        return switch (value) {
            case DISABLED -> 0;
            case ACTIVE -> 1;
            case TRIGGERING -> 2;
            case TRIGGERED -> 3;
        };
    }
    /**
     * 把固定编号恢复为倒计时撤单状态：DISABLED=0、ACTIVE=1、TRIGGERING=2、TRIGGERED=3；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的倒计时撤单状态固定编号
     */
    static CoreCancelAllAfterStatus readCoreCancelAllAfterStatus(int value) {
        return switch (value) {
            case 0 -> CoreCancelAllAfterStatus.DISABLED;
            case 1 -> CoreCancelAllAfterStatus.ACTIVE;
            case 2 -> CoreCancelAllAfterStatus.TRIGGERING;
            case 3 -> CoreCancelAllAfterStatus.TRIGGERED;
            default -> throw new ProtocolException("unknown persisted CoreCancelAllAfterStatus code: " + value);
        };
    }
    /**
     * 把触发单类型写成固定持久化编号：TAKE_PROFIT=0、STOP_LOSS=1、TRAILING_STOP=2。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的触发单类型枚举值
     */
    public static int encode(CoreTriggerOrderType value) {
        return switch (value) {
            case TAKE_PROFIT -> 0;
            case STOP_LOSS -> 1;
            case TRAILING_STOP -> 2;
        };
    }
    /**
     * 把固定编号恢复为触发单类型：TAKE_PROFIT=0、STOP_LOSS=1、TRAILING_STOP=2；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的触发单类型固定编号
     */
    static CoreTriggerOrderType readCoreTriggerOrderType(int value) {
        return switch (value) {
            case 0 -> CoreTriggerOrderType.TAKE_PROFIT;
            case 1 -> CoreTriggerOrderType.STOP_LOSS;
            case 2 -> CoreTriggerOrderType.TRAILING_STOP;
            default -> throw new ProtocolException("unknown persisted CoreTriggerOrderType code: " + value);
        };
    }
    /**
     * 把触发比较条件写成固定持久化编号：GREATER_OR_EQUAL=0、LESS_OR_EQUAL=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的触发比较条件枚举值
     */
    public static int encode(CoreTriggerCondition value) {
        return switch (value) {
            case GREATER_OR_EQUAL -> 0;
            case LESS_OR_EQUAL -> 1;
        };
    }
    /**
     * 把固定编号恢复为触发比较条件：GREATER_OR_EQUAL=0、LESS_OR_EQUAL=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的触发比较条件固定编号
     */
    static CoreTriggerCondition readCoreTriggerCondition(int value) {
        return switch (value) {
            case 0 -> CoreTriggerCondition.GREATER_OR_EQUAL;
            case 1 -> CoreTriggerCondition.LESS_OR_EQUAL;
            default -> throw new ProtocolException("unknown persisted CoreTriggerCondition code: " + value);
        };
    }
    /**
     * 把触发单状态写成固定持久化编号：PENDING=0、TRIGGERING=1、TRIGGERED=2、TRIGGER_FAILED=3、CANCELED=4、EXPIRED=5。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的触发单状态枚举值
     */
    public static int encode(CoreTriggerOrderStatus value) {
        return switch (value) {
            case PENDING -> 0;
            case TRIGGERING -> 1;
            case TRIGGERED -> 2;
            case TRIGGER_FAILED -> 3;
            case CANCELED -> 4;
            case EXPIRED -> 5;
        };
    }
    /**
     * 把固定编号恢复为触发单状态：PENDING=0、TRIGGERING=1、TRIGGERED=2、TRIGGER_FAILED=3、CANCELED=4、EXPIRED=5；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的触发单状态固定编号
     */
    static CoreTriggerOrderStatus readCoreTriggerOrderStatus(int value) {
        return switch (value) {
            case 0 -> CoreTriggerOrderStatus.PENDING;
            case 1 -> CoreTriggerOrderStatus.TRIGGERING;
            case 2 -> CoreTriggerOrderStatus.TRIGGERED;
            case 3 -> CoreTriggerOrderStatus.TRIGGER_FAILED;
            case 4 -> CoreTriggerOrderStatus.CANCELED;
            case 5 -> CoreTriggerOrderStatus.EXPIRED;
            default -> throw new ProtocolException("unknown persisted CoreTriggerOrderStatus code: " + value);
        };
    }
    /**
     * 把触发价格来源写成固定持久化编号：MARK=0、LAST=1、INDEX=2。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的触发价格来源枚举值
     */
    public static int encode(CoreTriggerPriceSource value) {
        return switch (value) {
            case MARK -> 0;
            case LAST -> 1;
            case INDEX -> 2;
        };
    }
    /**
     * 把固定编号恢复为触发价格来源：MARK=0、LAST=1、INDEX=2；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的触发价格来源固定编号
     */
    static CoreTriggerPriceSource readCoreTriggerPriceSource(int value) {
        return switch (value) {
            case 0 -> CoreTriggerPriceSource.MARK;
            case 1 -> CoreTriggerPriceSource.LAST;
            case 2 -> CoreTriggerPriceSource.INDEX;
            default -> throw new ProtocolException("unknown persisted CoreTriggerPriceSource code: " + value);
        };
    }
    /**
     * 把账户持仓模式写成固定持久化编号：ONE_WAY=0、HEDGE=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的账户持仓模式枚举值
     */
    public static int encode(CorePositionMode value) {
        return switch (value) {
            case ONE_WAY -> 0;
            case HEDGE -> 1;
        };
    }
    /**
     * 把固定编号恢复为账户持仓模式：ONE_WAY=0、HEDGE=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的账户持仓模式固定编号
     */
    static CorePositionMode readCorePositionMode(int value) {
        return switch (value) {
            case 0 -> CorePositionMode.ONE_WAY;
            case 1 -> CorePositionMode.HEDGE;
            default -> throw new ProtocolException("unknown persisted CorePositionMode code: " + value);
        };
    }
    /**
     * 把期权方向写成固定持久化编号：CALL=0、PUT=1。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的期权方向枚举值
     */
    public static int encode(OptionType value) {
        return switch (value) {
            case CALL -> 0;
            case PUT -> 1;
        };
    }
    /**
     * 把固定编号恢复为期权方向：CALL=0、PUT=1；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的期权方向固定编号
     */
    static OptionType readOptionType(int value) {
        return switch (value) {
            case 0 -> OptionType.CALL;
            case 1 -> OptionType.PUT;
            default -> throw new ProtocolException("unknown persisted OptionType code: " + value);
        };
    }
    /**
     * 把币对业务状态写成固定持久化编号：PRE_TRADING=0、TRADING=1、HALT=2、SETTLING=3、CLOSED=4、DRAFT=5。不使用 Java 枚举声明顺序。
     * @param value 本次要持久化的币对业务状态枚举值
     */
    public static int encode(InstrumentStatus value) {
        return switch (value) {
            case PRE_TRADING -> 0;
            case TRADING -> 1;
            case HALT -> 2;
            case SETTLING -> 3;
            case CLOSED -> 4;
            case DRAFT -> 5;
        };
    }
    /**
     * 把固定编号恢复为币对业务状态：PRE_TRADING=0、TRADING=1、HALT=2、SETTLING=3、CLOSED=4、DRAFT=5；未定义编号抛出 ProtocolException，不猜测默认业务值。
     * @param value 快照读取的币对业务状态固定编号
     */
    static InstrumentStatus readInstrumentStatus(int value) {
        return switch (value) {
            case 0 -> InstrumentStatus.PRE_TRADING;
            case 1 -> InstrumentStatus.TRADING;
            case 2 -> InstrumentStatus.HALT;
            case 3 -> InstrumentStatus.SETTLING;
            case 4 -> InstrumentStatus.CLOSED;
            case 5 -> InstrumentStatus.DRAFT;
            default -> throw new ProtocolException("unknown persisted InstrumentStatus code: " + value);
        };
    }
}
