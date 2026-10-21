package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.TransferFundsCommand;
import java.util.Objects;

/**
 * 源账户 Lane 保存的待完成跨产品划转记录，携带源用户和完整划转命令，供幂等、恢复及完成确认使用。
 * @param userId 发起转出的源用户编号，必须与命令来源一致
 * @param command 保留完整源目标产品、用户、资产、金额及划转编号的命令
 */
public record TransferRuntime(long userId, TransferFundsCommand command) {

    /**
     * 要求正数源用户、非空命令且用户与命令来源相同，防止把其他用户的划转登记到本账户。
     */
    public TransferRuntime {
        if (userId <= 0) throw new IllegalArgumentException("transfer userId must be positive");
        Objects.requireNonNull(command, "command");
        if (userId != command.sourceUserId()) throw new IllegalArgumentException("pending transfer sender mismatch");
    }

    /**
     * 从完整划转命令取得待完成记录的业务编号，不另存第二份编号字段。
     */
    public long transferId() {
        return command.transferId();
    }
}
