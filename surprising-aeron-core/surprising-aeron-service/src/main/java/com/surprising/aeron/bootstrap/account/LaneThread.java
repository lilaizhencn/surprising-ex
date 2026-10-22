package com.surprising.aeron.bootstrap.account;

import com.surprising.aeron.service.account.AccountLaneState;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 账户 Lane 专用的线程，在线程对象里直接记住它负责的账户分区。
 * 业务代码检查当前线程就能找到所属 Lane，不需要逐个扫描其他工作线程。
 */
public final class LaneThread extends Thread {
    /**  这个线程唯一负责的账户分区，包含该分区用户的余额、订单和持仓。  返回当前线程直接持有的账户分区，供线程归属检查使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final AccountLaneState lane;

    /**
     * 创建账户工作线程，并关闭可继承线程局部变量，避免带入启动线程的业务上下文。
     * @param name 用于日志和线程转储的线程名称
     * @param target 启动后要执行的账户任务循环
     * @param lane 当前线程负责的账户分区，不能为 null
     */
    public LaneThread(String name, Runnable target, AccountLaneState lane) {
        super(null, target, Objects.requireNonNull(name, "name"), 0, false);
        this.lane = Objects.requireNonNull(lane, "lane");
        setDaemon(true);
    }

}
