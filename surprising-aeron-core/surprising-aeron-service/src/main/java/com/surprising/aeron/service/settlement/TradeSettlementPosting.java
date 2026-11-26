package com.surprising.aeron.service.settlement;

import com.surprising.aeron.service.order.ReplaceOrderCheck;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.service.treasury.SystemFundsChange;
import com.surprising.aeron.service.treasury.SystemFunds;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 下单步骤 4.2–4.6 的可复用结算事件；ready 还等待发布方交出租约，清理前须交出 changes。 */

/** 资金分录的所有者与子账类别。 */
public final class TradeSettlementPosting {
    private TradeSettlementPosting() {
    }

    /** 本条资金变化归谁：普通用户、做市账户、系统财库或系统外部。 */
    public enum OwnerKind {
        USER,
        MAKER,
        /** 系统财库的资金归属，所有者编号为零。 */
        TREASURY,
        EXTERNAL;

    }

    /** 按用途区分资金变化的子账类别。 */
    public enum Subledger {
        AVAILABLE,
        LOCKED,
        RESERVATION,
        POSITION_MARGIN,
        FEE,
        INSURANCE,
        LIQUIDATION_FEE,
        FUNDING_RESIDUAL,
        /** 金额舍入剩余差额的变动。 */
        ROUNDING_RESIDUAL,
        CLEARING_PNL,
        DEFICIT,
        /** 外部调入或调出系统的资金变化，只配外部所有者。 */
        EXTERNAL_ADJUSTMENT;

    }
}
