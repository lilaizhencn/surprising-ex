package com.surprising.aeron.service.matcher;

/**
 * 保存一份 Matcher 分片的执行进度，把分片身份和已经到达的撮合序号放在一起。
 * @param matcherShardId 本进度对应的分片编号，校验允许 -1 或非负数
 * @param matcherSequence 该分片的撮合序号，0 表示还没有推进到正数序号
 */
public record MatcherShardProgress(int matcherShardId, long matcherSequence) {

    /** 拒绝超出编号下界的分片和负撮合序号，避免携带无效游标参与提交或恢复检查。 */
    public MatcherShardProgress {
        if (matcherShardId < -1 || matcherSequence < 0) {
            throw new IllegalArgumentException("invalid matcher shard progress");
        }
    }
}
