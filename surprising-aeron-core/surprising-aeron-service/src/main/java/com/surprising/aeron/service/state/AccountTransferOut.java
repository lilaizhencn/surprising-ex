package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.protocol.TransferFundsCommand;

/** 所属账户 Lane 校验划转身份、扣款和保存记录；Owner 只登记完成后的路由。 */
public final class AccountTransferOut implements java.util.function.IntFunction<Object> {
    /** 唯一产品控制上下文。 */
    private TradingRuntimeState runtime;
    private TransferFundsCommand command;
    private long userId;
    /** 预解析资产，账户线程不修改身份注册表。 */
    private int assetId;
    private boolean created;
    /** 完成收集后防止重复登记或修改修订号。 */
    private boolean completed;

    public AccountTransferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                              long userId, TransferFundsCommand command) {
        reset(runtime, identities, userId, command);
    }

    public static AccountTransferOut prepare(AccountTransferOut reuse, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, long userId, TransferFundsCommand command) {
        if (reuse == null) return new AccountTransferOut(runtime, identities, userId, command);
        reuse.reset(runtime, identities, userId, command);
        return reuse;
    }

    private void reset(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId,
                       TransferFundsCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0)
            throw new IllegalArgumentException("transfer inputs are required");
        runtime.assertOwner();
        RuntimeAccountStateTransitions.validateTransferSource(runtime, userId, command);
        long routedUser = runtime.pendingTransferUser(command.transferId());
        if (routedUser != 0 && routedUser != userId)
            throw new com.surprising.aeron.service.exception.CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        if (routedUser == 0 && !runtime.hasPendingTransferCapacity())
            throw new com.surprising.aeron.service.exception.CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
        this.runtime = runtime;
        this.userId = userId;
        this.command = command;
        assetId = identities.assetId(command.asset());
        created = false;
        completed = false;
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
    }

    @Override public Object apply(int ignoredLaneId) {
        TransferRuntime transfer = RuntimeAccountStateTransitions.prepareTransferOut(runtime, userId, command);
        if (transfer == null) return null;
        runtime.requireRevisionCapacity();
        RuntimeAccountStateTransitions.debitTransferAccount(runtime, userId, assetId, command.amountUnits());
        runtime.putPendingTransfer(transfer);
        runtime.incrementCommandRevision();
        created = true;
        return null;
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        if (created) runtime.registerPendingTransferRoute(command.transferId(), userId);
        command = null;
        completed = true;
        return true;
    }
}
