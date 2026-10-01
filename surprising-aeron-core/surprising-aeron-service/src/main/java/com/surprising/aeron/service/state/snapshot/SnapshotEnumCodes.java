package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreRiskState.RiskScan;
import com.surprising.aeron.service.state.CoreTreasuryState.FundingProgress;
import com.surprising.aeron.service.state.CoreTreasuryState.LifecycleProgress;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;

/** 快照枚举编号，独立于 Java 声明顺序；已有编号永久保留。 */
public final class SnapshotEnumCodes {
    private SnapshotEnumCodes() { }
    public static int encode(ReservationKind value) {
        return switch (value) {
            case SPOT_ASSET -> 0;
            case DERIVATIVE_MARGIN -> 1;
        };
    }
    static ReservationKind readReservationKind(int value) {
        return switch (value) {
            case 0 -> ReservationKind.SPOT_ASSET;
            case 1 -> ReservationKind.DERIVATIVE_MARGIN;
            default -> throw new ProtocolException("unknown persisted ReservationKind code: " + value);
        };
    }
    public static int encode(CoreMarginMode value) {
        return switch (value) {
            case CROSS -> 0;
            case ISOLATED -> 1;
        };
    }
    static CoreMarginMode readCoreMarginMode(int value) {
        return switch (value) {
            case 0 -> CoreMarginMode.CROSS;
            case 1 -> CoreMarginMode.ISOLATED;
            default -> throw new ProtocolException("unknown persisted CoreMarginMode code: " + value);
        };
    }
    public static int encode(CorePositionSide value) {
        return switch (value) {
            case NET -> 0;
            case LONG -> 1;
            case SHORT -> 2;
        };
    }
    static CorePositionSide readCorePositionSide(int value) {
        return switch (value) {
            case 0 -> CorePositionSide.NET;
            case 1 -> CorePositionSide.LONG;
            case 2 -> CorePositionSide.SHORT;
            default -> throw new ProtocolException("unknown persisted CorePositionSide code: " + value);
        };
    }
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
    public static int encode(CoreOrderSide value) {
        return switch (value) {
            case BUY -> 0;
            case SELL -> 1;
        };
    }
    static CoreOrderSide readCoreOrderSide(int value) {
        return switch (value) {
            case 0 -> CoreOrderSide.BUY;
            case 1 -> CoreOrderSide.SELL;
            default -> throw new ProtocolException("unknown persisted CoreOrderSide code: " + value);
        };
    }
    public static int encode(CoreOrderType value) {
        return switch (value) {
            case LIMIT -> 0;
            case MARKET -> 1;
        };
    }
    static CoreOrderType readCoreOrderType(int value) {
        return switch (value) {
            case 0 -> CoreOrderType.LIMIT;
            case 1 -> CoreOrderType.MARKET;
            default -> throw new ProtocolException("unknown persisted CoreOrderType code: " + value);
        };
    }
    public static int encode(CoreTimeInForce value) {
        return switch (value) {
            case GTC -> 0;
            case IOC -> 1;
            case FOK -> 2;
            case GTX -> 3;
        };
    }
    static CoreTimeInForce readCoreTimeInForce(int value) {
        return switch (value) {
            case 0 -> CoreTimeInForce.GTC;
            case 1 -> CoreTimeInForce.IOC;
            case 2 -> CoreTimeInForce.FOK;
            case 3 -> CoreTimeInForce.GTX;
            default -> throw new ProtocolException("unknown persisted CoreTimeInForce code: " + value);
        };
    }
    public static int encode(CoreOrderStatus value) {
        return switch (value) {
            case OPEN -> 0;
            case CANCELED -> 1;
            case FILLED -> 2;
            case REJECTED -> 3;
        };
    }
    static CoreOrderStatus readCoreOrderStatus(int value) {
        return switch (value) {
            case 0 -> CoreOrderStatus.OPEN;
            case 1 -> CoreOrderStatus.CANCELED;
            case 2 -> CoreOrderStatus.FILLED;
            case 3 -> CoreOrderStatus.REJECTED;
            default -> throw new ProtocolException("unknown persisted CoreOrderStatus code: " + value);
        };
    }
    public static int encode(CoreRiskStatus value) {
        return switch (value) {
            case NORMAL -> 0;
            case WARNING -> 1;
            case LIQUIDATION -> 2;
        };
    }
    static CoreRiskStatus readCoreRiskStatus(int value) {
        return switch (value) {
            case 0 -> CoreRiskStatus.NORMAL;
            case 1 -> CoreRiskStatus.WARNING;
            case 2 -> CoreRiskStatus.LIQUIDATION;
            default -> throw new ProtocolException("unknown persisted CoreRiskStatus code: " + value);
        };
    }
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
    public static int encode(CoreInstrumentMaintenance.Mode value) {
        return switch (value) {
            case TRADING -> 0;
            case REDUCE_ONLY -> 1;
            case HALTED -> 2;
            case SETTLEMENT -> 3;
            case CLOSED -> 4;
        };
    }
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
    public static int encode(CoreCancelAllAfterStatus value) {
        return switch (value) {
            case DISABLED -> 0;
            case ACTIVE -> 1;
            case TRIGGERING -> 2;
            case TRIGGERED -> 3;
        };
    }
    static CoreCancelAllAfterStatus readCoreCancelAllAfterStatus(int value) {
        return switch (value) {
            case 0 -> CoreCancelAllAfterStatus.DISABLED;
            case 1 -> CoreCancelAllAfterStatus.ACTIVE;
            case 2 -> CoreCancelAllAfterStatus.TRIGGERING;
            case 3 -> CoreCancelAllAfterStatus.TRIGGERED;
            default -> throw new ProtocolException("unknown persisted CoreCancelAllAfterStatus code: " + value);
        };
    }
    public static int encode(CoreTriggerOrderType value) {
        return switch (value) {
            case TAKE_PROFIT -> 0;
            case STOP_LOSS -> 1;
            case TRAILING_STOP -> 2;
        };
    }
    static CoreTriggerOrderType readCoreTriggerOrderType(int value) {
        return switch (value) {
            case 0 -> CoreTriggerOrderType.TAKE_PROFIT;
            case 1 -> CoreTriggerOrderType.STOP_LOSS;
            case 2 -> CoreTriggerOrderType.TRAILING_STOP;
            default -> throw new ProtocolException("unknown persisted CoreTriggerOrderType code: " + value);
        };
    }
    public static int encode(CoreTriggerCondition value) {
        return switch (value) {
            case GREATER_OR_EQUAL -> 0;
            case LESS_OR_EQUAL -> 1;
        };
    }
    static CoreTriggerCondition readCoreTriggerCondition(int value) {
        return switch (value) {
            case 0 -> CoreTriggerCondition.GREATER_OR_EQUAL;
            case 1 -> CoreTriggerCondition.LESS_OR_EQUAL;
            default -> throw new ProtocolException("unknown persisted CoreTriggerCondition code: " + value);
        };
    }
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
    public static int encode(CoreTriggerPriceSource value) {
        return switch (value) {
            case MARK -> 0;
            case LAST -> 1;
            case INDEX -> 2;
        };
    }
    static CoreTriggerPriceSource readCoreTriggerPriceSource(int value) {
        return switch (value) {
            case 0 -> CoreTriggerPriceSource.MARK;
            case 1 -> CoreTriggerPriceSource.LAST;
            case 2 -> CoreTriggerPriceSource.INDEX;
            default -> throw new ProtocolException("unknown persisted CoreTriggerPriceSource code: " + value);
        };
    }
    public static int encode(CorePositionMode value) {
        return switch (value) {
            case ONE_WAY -> 0;
            case HEDGE -> 1;
        };
    }
    static CorePositionMode readCorePositionMode(int value) {
        return switch (value) {
            case 0 -> CorePositionMode.ONE_WAY;
            case 1 -> CorePositionMode.HEDGE;
            default -> throw new ProtocolException("unknown persisted CorePositionMode code: " + value);
        };
    }
    public static int encode(ContractType value) {
        return switch (value) {
            case SPOT -> 0;
            case LINEAR_PERPETUAL -> 1;
            case INVERSE_PERPETUAL -> 2;
            case LINEAR_DELIVERY -> 3;
            case INVERSE_DELIVERY -> 4;
            case VANILLA_OPTION -> 5;
        };
    }
    static ContractType readContractType(int value) {
        return switch (value) {
            case 0 -> ContractType.SPOT;
            case 1 -> ContractType.LINEAR_PERPETUAL;
            case 2 -> ContractType.INVERSE_PERPETUAL;
            case 3 -> ContractType.LINEAR_DELIVERY;
            case 4 -> ContractType.INVERSE_DELIVERY;
            case 5 -> ContractType.VANILLA_OPTION;
            default -> throw new ProtocolException("unknown persisted ContractType code: " + value);
        };
    }
    public static int encode(OptionType value) {
        return switch (value) {
            case CALL -> 0;
            case PUT -> 1;
        };
    }
    static OptionType readOptionType(int value) {
        return switch (value) {
            case 0 -> OptionType.CALL;
            case 1 -> OptionType.PUT;
            default -> throw new ProtocolException("unknown persisted OptionType code: " + value);
        };
    }
    public static int encode(InstrumentStatus value) {
        return switch (value) {
            case PRE_TRADING -> 0;
            case TRADING -> 1;
            case HALT -> 2;
            case SETTLING -> 3;
            case CLOSED -> 4;
        };
    }
    static InstrumentStatus readInstrumentStatus(int value) {
        return switch (value) {
            case 0 -> InstrumentStatus.PRE_TRADING;
            case 1 -> InstrumentStatus.TRADING;
            case 2 -> InstrumentStatus.HALT;
            case 3 -> InstrumentStatus.SETTLING;
            case 4 -> InstrumentStatus.CLOSED;
            default -> throw new ProtocolException("unknown persisted InstrumentStatus code: " + value);
        };
    }
}
