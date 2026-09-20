/**
 * 交易核心快照的公开格式边界。
 *
 * <p>{@code CoreSnapshotManifest} 和 {@code SectionedCoreSnapshotCodec}
 * 定义快照版本、大小限制和对外编解码入口。直接访问 Owner 线程私有运行时状态的写入、解析、恢复和校验实现
 * 暂留在 {@code orchestration} 包，避免为了包移动扩大订单、账户和恢复状态的可见性。</p>
 */
package com.surprising.aeron.service.orchestration.snapshot;
