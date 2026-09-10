package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.TransferFundsCommand;

/** 异步转出：所属账户 Lane 扣款，Owner 收集完成后登记跨产品划转记录。 */
public final class AccountTransferOut {
    /** 唯一产品控制上下文。 */
    private final TradingRuntimeState runtime;
    /** 已验证的划转身份；null 表示已存在同一笔划转，不重复扣款。 */
    private final TransferRuntime transfer;
    /** 预解析资产，账户线程不修改身份注册表。 */
    private final int assetId;
    /** 扣款完成后提交的产品修订号。 */
    private final long nextRevision;
    /** 完成收集后防止重复登记或修改修订号。 */
    private boolean completed;

    public AccountTransferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                              long userId, TransferFundsCommand command) {
        if (identities == null) throw new IllegalArgumentException("transfer identities are required");
        this.runtime = runtime;
        transfer = RuntimeCommandProcessor.prepareTransferOut(runtime, userId, command);
        if (transfer == null) {
            assetId = 0;
            nextRevision = runtime.revision();
            completed = true;
            return;
        }
        assetId = identities.assetId(command.asset());
        nextRevision = Math.incrementExact(runtime.revision());
        runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), lane -> {
            RuntimeCommandProcessor.debitTransferAccount(runtime, userId, assetId, command.amountUnits());
            return null;
        });
    }

    public boolean poll() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        runtime.markBalanceChanged(transfer.userId(), assetId);
        runtime.putPendingTransfer(transfer);
        runtime.setMetadata(runtime.productLine(), nextRevision);
        completed = true;
        return true;
    }
}
