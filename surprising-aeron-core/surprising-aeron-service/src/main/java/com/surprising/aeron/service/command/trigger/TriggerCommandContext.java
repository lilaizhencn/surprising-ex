package com.surprising.aeron.service.command.trigger;

import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/**
 * Trigger/algo command capabilities. The trigger command package owns trigger lifecycle logic;
 * the core runtime only supplies these state, result and matching boundaries.
 */
public interface TriggerCommandContext extends CommandResultContext {
    enum Mutation { CANCEL, CLAIM, COMPLETE, TRAILING, EXPIRE, RETRY }
    TradingRuntimeState runtimeState();

    RuntimeIdentityRegistry identities();

    ProductLine productLine();

    TriggerOrderIndex triggerOrderIndex();

    OpenInterestIndex openInterestIndex();

    long appliedCommandCount();

    int queuedMatchingCount();

    int defaultTriggerScanBatchSize();

    long currentClusterTimestamp();

    boolean terminalAlgoRetained(long algoOrderId, long userId, String clientAlgoOrderId);

    boolean terminalTriggerRetained(long triggerOrderId, long userId, String clientTriggerOrderId);

    void cancelAllOcoSiblings(CoreTriggerOrderState trigger);

    void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command);

    void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                  long pendingCoreSequence);

    void queueTriggerMatching(CoreTriggerOrderState trigger, long triggerSequence,
                              long triggeredPriceTicks, long triggeredAtEpochMillis,
                              UUID parentCommandId, long childOrderId);

    void seedChangeAccumulators();

    void setCommandTriggerOrderView(CoreTriggerOrderStateView trigger);

    void deferTriggerMutation(long userId, Mutation mutation, long triggerOrderId,
                              long arg1, long arg2, long arg3, boolean flag, String text);

    void deferTriggerUpsert(long userId, CoreTriggerOrderStateView trigger, int symbolId,
                            long positionKey, boolean instrumentSettled);

    void deferAlgoUpsert(long userId, CoreAlgoOrderView algo, int symbolId);

    record OcoCancellationPage(boolean complete, long nextCursor, int workUnits) { }
}
