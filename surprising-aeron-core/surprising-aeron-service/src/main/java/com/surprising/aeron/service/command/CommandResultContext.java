package com.surprising.aeron.service.command;

import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Owner capabilities shared by direct business command handlers.
 *
 * <p>The command packages can update the current command result and schedule an owner
 * continuation without retaining the complete orchestration runtime.</p>
 */
public interface CommandResultContext extends CommandOwnerContext {
    boolean asynchronousCommands();

    void deferControl(BooleanSupplier continuation);

    void setSingleChangedUser(long userId);

    void markUserChanged(long userId);

    void markOrderChanged(long orderId);

    void beginChangedUsers();

    void addChangedUser(long userId);

    void addChangedUsersFromOrders(Iterable<? extends CoreOrderState> orders);

    void addChangedUsersFromFundingPayments(
            Iterable<? extends CoreFundingPaymentView> payments);

    void setCommandChangedOrderIds(List<Long> orderIds);

    void setCommandFundingProgress(CoreFundingProgressView progress);

    void setCommandSettlementProgress(CoreSettlementProgressView progress);

    void setCommandRiskScanControl(CoreRiskScanControlView control);

    void setCommandTriggerOrderView(
            com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger);
}
