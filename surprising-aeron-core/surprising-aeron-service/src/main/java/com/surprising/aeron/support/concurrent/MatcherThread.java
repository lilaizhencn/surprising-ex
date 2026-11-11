package com.surprising.aeron.support.concurrent;

import lombok.Getter;
import lombok.experimental.Accessors;
/** Matcher 专用工作线程，在线程本身保存负责的订单簿分片编号。 */
public final class MatcherThread extends Thread {
    /** 当前线程负责的 Matcher 分片编号。不能拿它当账户 Lane 编号。返回当前线程负责的 Matcher 分片，用于校验订单簿状态的线程所有权。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int shardId;

    /** 创建一个带固定分片身份的守护线程，具体撮合循环由调用方提供。 */
    public MatcherThread(Runnable target, String name, int shardId) {
        super(null, target, name, 0);
        this.shardId = shardId;
        setDaemon(true);
    }

}
