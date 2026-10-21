package com.surprising.aeron.service.matcher;

/**
 * Matcher 专用工作线程，在线程本身保存负责的订单簿分片编号。
 * 撮合适配器可据此直接核对当前分片，不需要扫描所有 Matcher。
 */
public final class MatcherThread extends Thread {
    /** 当前线程负责的 Matcher 分片编号；不能拿它当账户 Lane 编号。 */
    private final int shardId;

    /**
     * 创建一个带固定分片身份的守护线程，具体撮合循环由调用方提供。
     * @param target 启动后执行的 Matcher 任务循环
     * @param name 用于日志和线程转储的线程名称
     * @param shardId 当前线程负责的 Matcher 分片编号
     */
    public MatcherThread(Runnable target, String name, int shardId) {
        super(null, target, name, 0);
        this.shardId = shardId;
        setDaemon(true);
    }

    /** 返回当前线程负责的 Matcher 分片，用于校验订单簿状态的线程所有权。 */
    public int shardId() {
        return shardId;
    }
}
