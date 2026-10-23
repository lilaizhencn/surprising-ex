package com.surprising.aeron.service.retention;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证终态 FIFO 与长期资金身份的不同保留范围、独立编码恢复和客户号字节边界。测试直接操作保留器，不以这些用例替代真实交易、Ledger 线程或集群恢复验证。
 */
class TerminalStateRetentionTest {
    /**
     * 写入十四万条资金身份后编码恢复，最早与最新项仍可查；恢复后还能新增，相同 UUID 同指纹幂等，不同指纹明确冲突。
     */
    @Test
    void fundsHistoryExceedsOldLimitAndPreservesIdentitiesAcrossRecovery() {
        try (/* 本场景实际终态与资金身份保留器 */ var retention = new TerminalStateRetention()) {
            // 本测试保存的稳定资金命令指纹
            var fingerprint = CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]);
            for (/* 当前建立的长期资金身份序号 */ int i = 0; i < 140_000; i++) retention.retainFundsCommand(new UUID(17, i), fingerprint);
            try (/* 从完整保留段字节恢复的独立保留器 */ var restored = TerminalStateRetention.decode(retention.encode())) {
                assertThat(restored.fundsCommand(new UUID(17, 0))).isEqualTo(fingerprint);
                assertThat(restored.fundsCommand(new UUID(17, 139_999))).isEqualTo(fingerprint);
                restored.retainFundsCommand(new UUID(17, 140_000), fingerprint);
                restored.retainFundsCommand(new UUID(17, 0), fingerprint);
                // 故意改变首字节的冲突指纹输入
                byte[] conflicting = new byte[CommandFingerprint.LENGTH];
                conflicting[0] = 1;
                assertThatThrownBy(() -> restored.retainFundsCommand(new UUID(17, 0),
                        CommandFingerprint.fromBytes(conflicting)))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("fingerprint conflict");
            }
        }
    }

    /**
     * 写一条订单墓碑后反复查不存在 ID、客户号及已存订单，要求编码字节保持一致，恢复后原墓碑仍存在；没有单独调用候选查询键复用入口。
     */
    @Test
    void reusingLookupKeysCannotMutateStoredKeysOrSnapshot() {
        // 本场景实际终态与资金身份保留器
        var retention = new TerminalStateRetention();
        retention.accept(1, 7, "");
        // 修改前取得的独立保留段编码，用来核对无变化或恢复
        byte[] snapshot = retention.encode();
        for (/* 当前循环的终态订单 ID */ int id = 2; id < 1000; id++) {
            assertThat(retention.containsOrder(id, 8, "client-" + id)).isFalse();
            assertThat(retention.containsOrder(1, 7, "")).isTrue();
        }
        assertThat(retention.encode()).isEqualTo(snapshot);
        assertThat(TerminalStateRetention.decode(snapshot).containsOrder(1, 7, "")).isTrue();
    }

    /**
     * 核对 ASCII、emoji、中文和孤立 UTF-16 代理单元在二百五十六字节边界内可查、超界拒绝；本用例没有内存分配计数断言。
     */
    @Test
    void utf8ClientLimitIsUnchangedWithoutEncodingATemporaryArray() {
        // 本场景实际终态与资金身份保留器
        var retention = new TerminalStateRetention();
        for (String /* 当前验证 UTF-8 字节边界的客户号文本 */ client : new String[]{"x".repeat(256), "😀".repeat(64), "中".repeat(85), "\ud800".repeat(256)}) {
            assertThat(retention.containsOrder(1, 7, client)).isFalse();
        }
        for (String /* 当前验证 UTF-8 字节边界的客户号文本 */ client : new String[]{"x".repeat(257), "😀".repeat(65), "中".repeat(86)}) {
            assertThatThrownBy(() -> retention.containsOrder(1, 7, client)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 同一序号先写超过总上限四十项，序列完成时一次淘汰全部最旧超额项；原实例和编码恢复实例都保留相同最新区间。
     */
    @Test
    void oneSequenceEvictsEveryExcessTombstoneAndKeepsTheNewestAfterRestore() {
        // 本场景实际终态与资金身份保留器
        var retention = new TerminalStateRetention();
        for (/* 当前循环的终态订单 ID */ long id = 1; id <= TerminalStateRetention.MAX_TOMBSTONES + 40; id++)
            retention.accept(id, 7, "");
        retention.completeSequence();
        assertThat(retention.tombstoneCount()).isEqualTo(TerminalStateRetention.MAX_TOMBSTONES);
        for (var /* 本次检查的原保留器或编码恢复副本 */ state : new TerminalStateRetention[]{retention, TerminalStateRetention.decode(retention.encode())}) {
            assertThat(state.containsOrder(40, 7, "")).isFalse();
            assertThat(state.containsOrder(41, 7, "")).isTrue();
            assertThat(state.containsOrder(TerminalStateRetention.MAX_TOMBSTONES + 40, 7, "")).isTrue();
        }
    }
    /**
     * 重复登记相同资金身份不改变编码，恢复后内容冲突也不能改原字节，最后关闭实例；本用例不直接检查已删除审计字段。
     */
    @Test
    void removingAuditDigestsPreservesFundsIdempotencyAndSnapshotBytes() {
        // 本场景实际终态与资金身份保留器
        TerminalStateRetention retention = new TerminalStateRetention();
        // 需要反复核对的资金命令 UUID
        UUID command = UUID.randomUUID();
        // 用于创建及改动指纹输入的原字节数组，稳定指纹值不随它改写
        byte[] bytes = new byte[CommandFingerprint.LENGTH];
        // 本测试保存的稳定资金命令指纹
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(bytes);
        retention.retainFundsCommand(command, fingerprint);
        // 修改前取得的独立保留段编码，用来核对无变化或恢复
        byte[] snapshot = retention.encode();
        retention.retainFundsCommand(command, fingerprint);
        assertThat(retention.encode()).isEqualTo(snapshot);
        // 从完整保留段字节恢复的独立保留器
        var restored = TerminalStateRetention.decode(snapshot);
        assertThat(restored.fundsCommand(command)).isEqualTo(fingerprint);
        bytes[0] = 1;
        assertThatThrownBy(() -> restored.retainFundsCommand(command, CommandFingerprint.fromBytes(bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fingerprint conflict");
        assertThat(restored.encode()).isEqualTo(snapshot);
        restored.close();
        retention.close();
    }
}
