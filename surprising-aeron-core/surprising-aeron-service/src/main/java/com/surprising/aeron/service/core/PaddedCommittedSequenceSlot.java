package com.surprising.aeron.service.core;

/**
 * 所属 Lane 已提交到的 Core 序号槽位，以 volatile 发布给 Owner。
 */
final class PaddedCommittedSequenceSlot {
    /** 所属 Lane 已提交到的 Core 序号，以 volatile 发布给 Owner。 */
    protected volatile long sequence;
}
