package com.surprising.aeron.service.orchestration.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证资金身份磁盘索引的失败与清理边界。使用 JUnit 系统属性锁串行修改目录配置，测试结束恢复配置；只检查本索引，不启动交易集群或钱包。
 */
@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class FundsCommandIndexTest {
    /** JUnit 提供并管理的临时根目录，仅供本测试构造索引与无关文件。 */
    @TempDir Path directory;

    /**
     * 把目录配置指向普通文件，制造首次建库失败；随后查另一个 UUID 及健康检查都必须继续报已记录故障，不能当作没有历史。
     */
    @Test
    void storageFailurePoisonsFurtherReadsRatherThanTreatingHistoryAsAbsent() throws Exception {
        // 当前测试要临时修改的资金索引根目录 JVM 属性名
        String property = "surprising.aeron.funds-index-directory";
        // 进入测试前的可空原属性，finally 按原值恢复
        String previous = System.getProperty(property);
        // 故意伪装成目录配置的普通文件
        Path file = Files.createFile(directory.resolve("not-a-directory"));
        System.setProperty(property, file.toString());
        try (/* 真实资金命令磁盘索引，try 结束时关闭本实例资源 */ var index = new FundsCommandIndex()) {
            assertThatThrownBy(() -> index.put(new UUID(1, 1),
                    CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH])))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stop processing");
            assertThatThrownBy(() -> index.get(new UUID(2, 2)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stop processing");
            assertThatThrownBy(index::assertHealthy).isInstanceOf(IllegalStateException.class);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    /**
     * 在配置根下先建无关文件，真实写一条资金身份并关闭索引；要求只删本实例子目录，根与无关文件仍保留，最后恢复原属性。
     */
    @Test
    void closeRemovesOnlyThisRuntimeIndexAndLeavesConfiguredRoot() throws Exception {
        // 当前测试要临时修改的资金索引根目录 JVM 属性名
        String property = "surprising.aeron.funds-index-directory";
        // 进入测试前的可空原属性，finally 按原值恢复
        String previous = System.getProperty(property);
        System.setProperty(property, directory.toString());
        // 配置根下预先存在的无关文件，索引关闭不得删除
        Path unrelated = Files.createFile(directory.resolve("unrelated"));
        try {
            try (/* 真实资金命令磁盘索引，try 结束时关闭本实例资源 */ var index = new FundsCommandIndex()) {
                index.put(new UUID(1, 1), CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]));
            }
            assertThat(Files.exists(unrelated)).isTrue();
            try (/* 关闭索引后检查根目录内容的可关闭目录流 */ var children = Files.list(directory)) {
                assertThat(children.toList()).containsExactly(unrelated);
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
