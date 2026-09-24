/**
 * 交易核心快照的格式与 Aeron 传输边界。
 *
 * <p>{@code CoreSnapshotManifest} 和 {@code SectionedCoreSnapshotCodec}
 * 定义快照版本、大小限制和编解码入口；{@code ClusterCoreSnapshotTransfer} 负责从 Aeron Image 接收快照片段，
 * 并将 Owner 已捕获的 sections 分块写入 Cluster Publication。直接访问 Owner 线程私有运行时状态的捕获、解析、
 * 恢复和校验实现暂留在 {@code orchestration} 包，避免为了包移动扩大订单、账户和恢复状态的可见性。</p>
 */
package com.surprising.aeron.service.orchestration.snapshot;
